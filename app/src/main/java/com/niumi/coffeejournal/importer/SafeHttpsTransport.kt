package com.niumi.coffeejournal.importer

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

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

fun interface PinnedHttpsConnectionFactory {
    fun open(request: SafeHttpRequest, addresses: List<InetAddress>, connectTimeoutMillis: Int): HttpsURLConnection
}

internal val SystemNetworkResolver = NetworkResolver { host -> InetAddress.getAllByName(host).toList() }

internal suspend fun resolveGlobalAddresses(
    uri: URI,
    resolver: NetworkResolver,
): List<InetAddress> {
    val host = uri.host?.lowercase() ?: throw PublicPageException.UnsafeUrl()
    if (uri.port !in setOf(-1, 443) || isIpLiteral(host)) throw PublicPageException.UnsafeUrl()
    val addresses = try { resolver.resolve(host) } catch (error: Exception) { throw error.toPublicNetworkException() }
    if (addresses.isEmpty() || addresses.any { !it.isGlobalPublic() }) throw PublicPageException.UnsafeUrl()
    return addresses.distinctBy { it.hostAddress }
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

class HttpsUrlPinnedTransport(
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 12_000,
    private val connectionFactory: PinnedHttpsConnectionFactory = PinnedHttpsConnectionFactory { request, addresses, timeout ->
        val uri = URI(request.url)
        (URL(request.url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = PinnedSslSocketFactory(
                SSLSocketFactory.getDefault() as SSLSocketFactory,
                checkNotNull(uri.host), addresses, timeout,
            )
        }
    },
) : PinnedHttpTransport {
    override suspend fun execute(
        request: SafeHttpRequest,
        addresses: List<InetAddress>,
        maxBytes: Int,
    ): SafeHttpResponse = runInterruptible(Dispatchers.IO) {
        val connection = connectionFactory.open(request, addresses, connectTimeoutMillis).apply {
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            useCaches = false
            doOutput = request.body != null
            requestMethod = request.method
            for ((name, value) in request.headers) setRequestProperty(name, value)
            setRequestProperty("Cookie", "")
        }
        try {
            request.body?.let { body ->
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val headers = connection.headerFields.orEmpty().filterKeys { it != null }
                .mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull().orEmpty() }
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            val body = input?.use { it.readBounded(maxBytes) } ?: ByteArray(0)
            SafeHttpResponse(status, headers, body, connection.contentType.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

private class PinnedSslSocketFactory(
    private val delegate: SSLSocketFactory,
    private val expectedHost: String,
    private val addresses: List<InetAddress>,
    private val connectTimeoutMillis: Int,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(host: String, port: Int): Socket = layered(host, port, null, 0)
    override fun createSocket(host: String, port: Int, local: InetAddress?, localPort: Int): Socket =
        layered(host, port, local, localPort)
    override fun createSocket(address: InetAddress, port: Int): Socket = verified(address, port, null, 0)
    override fun createSocket(address: InetAddress, port: Int, local: InetAddress?, localPort: Int): Socket =
        verified(address, port, local, localPort)
    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        if (autoClose) runCatching { socket.close() }
        return layered(host, port, null, 0)
    }
    override fun createSocket(): Socket = throw IOException("Unpinned TLS socket creation is forbidden")

    private fun layered(host: String, port: Int, local: InetAddress?, localPort: Int): Socket {
        if (!host.equals(expectedHost, true) || port != 443) throw IOException("TLS target changed")
        var failure: IOException? = null
        for (address in addresses) {
            try {
                val raw = Socket()
                if (local != null) raw.bind(InetSocketAddress(local, localPort))
                raw.connect(InetSocketAddress(address, port), connectTimeoutMillis)
                return delegate.createSocket(raw, expectedHost, port, true)
            } catch (error: IOException) { failure = error }
        }
        throw failure ?: IOException("No validated address")
    }

    private fun verified(address: InetAddress, port: Int, local: InetAddress?, localPort: Int): Socket {
        if (addresses.none { it == address } || port != 443) throw IOException("Unvalidated address")
        return if (local == null) delegate.createSocket(address, port)
        else delegate.createSocket(address, port, local, localPort)
    }
}

internal fun Exception.toPublicNetworkException(): PublicPageException = when (this) {
    is PublicPageException -> this
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
        PublicPageException.Offline(message ?: "网络不可用")
    else -> PublicPageException.Http(message = message ?: "请求失败")
}
