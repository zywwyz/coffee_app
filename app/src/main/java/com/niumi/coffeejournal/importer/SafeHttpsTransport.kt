package com.niumi.coffeejournal.importer

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

fun interface NetworkResolver {
    suspend fun resolve(host: String): List<InetAddress>
}

data class SafeHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    val host: String get() = URI(url).host.lowercase()
}

data class SafeHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val contentType: String,
)

fun interface PinnedHttpTransport {
    suspend fun execute(request: SafeHttpRequest, addresses: List<InetAddress>, maxBytes: Int): SafeHttpResponse
}

internal interface InternallyResolvingPinnedTransport : PinnedHttpTransport

fun interface OkHttpClientFactory {
    fun create(
        dns: Dns,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        callTimeoutMillis: Int,
    ): Call.Factory
}

internal class SharedOkHttpClientFactory(
    private val baseClientProvider: () -> OkHttpClient = { OkHttpClient.Builder().build() },
) : OkHttpClientFactory {
    private val baseClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED, baseClientProvider)

    override fun create(
        dns: Dns,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        callTimeoutMillis: Int,
    ): Call.Factory = baseClient.newBuilder()
        .dns(dns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        .build()
}

internal val SystemNetworkResolver = NetworkResolver { host -> InetAddress.getAllByName(host).toList() }

internal suspend fun resolveGlobalAddresses(
    uri: URI,
    resolver: NetworkResolver,
): List<InetAddress> {
    val host = uri.host?.lowercase() ?: throw PublicPageException.UnsafeUrl()
    if (uri.port !in setOf(-1, 443) || isIpLiteral(host)) throw PublicPageException.UnsafeUrl()
    val addresses = try { resolver.resolve(host) } catch (error: Exception) { throw error.toPublicNetworkException() }
    return validateGlobalAddresses(addresses)
}

internal fun validateGlobalAddresses(addresses: List<InetAddress>): List<InetAddress> {
    if (addresses.isEmpty() || addresses.any { !it.isGlobalPublic() }) throw PublicPageException.UnsafeUrl()
    return Collections.unmodifiableList(addresses.distinctBy { it.hostAddress })
}

internal fun URI.isSafeHttpsAuthority(): Boolean =
    scheme.equals("https", true) && userInfo == null && fragment == null &&
        !host.isNullOrBlank() && port in setOf(-1, 443) && !isIpLiteral(host)

private fun isIpLiteral(host: String): Boolean {
    val plain = host.removePrefix("[").removeSuffix("]")
    return ':' in plain || plain.matches(Regex("[0-9.]+"))
}

private fun InetAddress.isGlobalPublic(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) return false
    val b = address.map { it.toInt() and 0xff }
    return when (this) {
        is Inet4Address -> when {
            b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 -> false
            b[0] == 100 && b[1] in 64..127 -> false
            b[0] == 169 && b[1] == 254 -> false
            b[0] == 172 && b[1] in 16..31 -> false
            b[0] == 192 && b[1] == 168 -> false
            b[0] == 192 && b[1] == 0 -> false
            b[0] == 198 && b[1] in 18..19 -> false
            b[0] == 198 && b[1] == 51 && b[2] == 100 -> false
            b[0] == 203 && b[1] == 0 && b[2] == 113 -> false
            else -> true
        }
        is Inet6Address -> {
            val ula = b[0] and 0xfe == 0xfc
            val documentation = b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8
            !ula && !documentation
        }
        else -> false
    }
}

class OkHttpPinnedTransport(
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 12_000,
    private val callTimeoutMillis: Int = 20_000,
    private val systemDns: Dns = Dns.SYSTEM,
    private val clientFactory: OkHttpClientFactory = SharedOkHttpClientFactory(),
) : InternallyResolvingPinnedTransport {
    override suspend fun execute(
        request: SafeHttpRequest,
        addresses: List<InetAddress>,
        maxBytes: Int,
    ): SafeHttpResponse {
        require(maxBytes > 0)
        val expectedHost = request.host
        val validatedAddresses by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            try {
                validateGlobalAddresses(systemDns.lookup(expectedHost))
            } catch (error: PublicPageException.UnsafeUrl) {
                throw UnsafeDnsException(error.message ?: "来源地址不安全")
            }
        }
        val requestDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!hostname.equals(expectedHost, true)) throw UnsafeDnsException("DNS 目标已改变")
                return validatedAddresses
            }
        }
        val contentType = request.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.toMediaTypeOrNull()
        val body = request.body?.toRequestBody(contentType)
        val okRequest = Request.Builder().url(request.url).apply {
            for ((name, value) in request.headers) header(name, value)
            method(request.method, body)
        }.build()
        val call = clientFactory.create(
            requestDns, connectTimeoutMillis, readTimeoutMillis, callTimeoutMillis,
        ).newCall(okRequest)

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e.toPublicNetworkException())
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { received ->
                            val responseBody = received.body
                            val bytes = responseBody?.byteStream()?.use { it.readBounded(maxBytes) } ?: ByteArray(0)
                            val headers = received.headers.names().associate { name ->
                                name.lowercase() to received.header(name).orEmpty()
                            }
                            SafeHttpResponse(
                                status = received.code,
                                headers = headers,
                                body = bytes,
                                contentType = responseBody?.contentType()?.toString()
                                    ?: received.header("Content-Type").orEmpty(),
                            )
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error.toPublicNetworkException())
                    }
                }
            })
        }
    }
}

private class UnsafeDnsException(message: String) : UnknownHostException(message)

internal fun Exception.toPublicNetworkException(): PublicPageException = when {
    this is PublicPageException -> this
    this is UnsafeDnsException || generateSequence(cause) { it.cause }.any { it is UnsafeDnsException } ->
        PublicPageException.UnsafeUrl(message ?: "来源地址不安全")
    this is UnknownHostException || this is java.net.ConnectException || this is java.net.SocketTimeoutException ->
        PublicPageException.Offline(message ?: "网络不可用")
    else -> PublicPageException.Http(message = message ?: "请求失败")
}
