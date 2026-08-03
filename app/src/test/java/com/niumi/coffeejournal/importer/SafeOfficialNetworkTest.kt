package com.niumi.coffeejournal.importer

import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeOfficialNetworkTest {
    @Test
    fun `shared okhttp factory reuses resources without sharing request dns`() {
        var baseConstructions = 0
        val factory = SharedOkHttpClientFactory {
            baseConstructions++
            OkHttpClient.Builder().build()
        }
        val luckinAddress = InetAddress.getByName("93.184.216.34")
        val mstandAddress = InetAddress.getByName("93.184.216.35")
        val luckinDns = dns { host ->
            assertEquals("www.luckincoffee.com", host)
            listOf(luckinAddress)
        }
        val mstandDns = dns { host ->
            assertEquals("www.mstand.cn", host)
            listOf(mstandAddress)
        }

        val clients = (0 until 100).map { index ->
            factory.create(if (index % 2 == 0) luckinDns else mstandDns, 1_000, 2_000, 3_000) as OkHttpClient
        }

        assertEquals(1, baseConstructions)
        assertTrue(clients.all { it.dispatcher === clients.first().dispatcher })
        assertTrue(clients.all { it.connectionPool === clients.first().connectionPool })
        assertTrue(clients[0].dns !== clients[1].dns)
        assertEquals(listOf(luckinAddress), clients[0].dns.lookup("www.luckincoffee.com"))
        assertEquals(listOf(mstandAddress), clients[1].dns.lookup("www.mstand.cn"))
    }

    @Test
    fun `page client rejects ip literals non443 and any private or mixed dns answer`() = runBlocking {
        val transport = RecordingPinnedTransport()
        suspend fun attempt(url: String, answers: List<String>) = runCatching {
            SafeOfficialHttpClient(
                resolver = NetworkResolver { answers.map(InetAddress::getByName) }, transport = transport,
            ).getText(PublicPageRequest(url, OfficialPagePolicy.CUSTOM))
        }.exceptionOrNull()

        listOf(
            "https://127.0.0.1/menu" to listOf("93.184.216.34"),
            "https://[::1]/menu" to listOf("2606:4700:4700::1111"),
            "https://example.com:8443/menu" to listOf("93.184.216.34"),
            "https://example.com/menu" to listOf("10.0.0.1"),
            "https://example.com/menu" to listOf("2606:4700:4700::1111", "fd00::1"),
        ).forEach { (url, answers) -> assertTrue(attempt(url, answers) is PublicPageException.UnsafeUrl) }
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `validated dns addresses are pinned to transport and redirect is revalidated`() = runBlocking {
        var resolves = 0
        val first = InetAddress.getByName("93.184.216.34")
        val second = InetAddress.getByName("93.184.216.35")
        val transport = RecordingPinnedTransport(
            responses = ArrayDeque(
                listOf(
                    SafeHttpResponse(302, mapOf("location" to "/next"), ByteArray(0), "text/html"),
                    SafeHttpResponse(200, emptyMap(), "ok".toByteArray(), "text/html"),
                ),
            ),
        )
        val client = SafeOfficialHttpClient(
            resolver = NetworkResolver { if (resolves++ == 0) listOf(first) else listOf(second) },
            transport = transport,
        )

        assertEquals("ok", client.getText(PublicPageRequest("https://example.com/start", OfficialPagePolicy.CUSTOM)).body)
        assertEquals(listOf(listOf(first), listOf(second)), transport.requests.map { it.addresses })
        assertEquals(listOf("example.com", "example.com"), transport.requests.map { it.request.host })
    }

    @Test
    fun `redirect cannot cross host and mixed rebinding answer is blocked before second transport`() = runBlocking {
        val transport = RecordingPinnedTransport(
            responses = ArrayDeque(listOf(SafeHttpResponse(302, mapOf("location" to "https://evil.example/x"), ByteArray(0), "text/html"))),
        )
        val client = SafeOfficialHttpClient(
            resolver = NetworkResolver { listOf(InetAddress.getByName("93.184.216.34")) }, transport = transport,
        )
        assertThrows(PublicPageException.UnsafeUrl::class.java) {
            runBlocking { client.getText(PublicPageRequest("https://example.com/start", OfficialPagePolicy.CUSTOM)) }
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `safe client enforces actual bytes content type and propagates cancellation to transport`() = runBlocking {
        val tooLarge = SafeOfficialHttpClient(
            resolver = publicResolver,
            transport = RecordingPinnedTransport(ArrayDeque(listOf(SafeHttpResponse(200, emptyMap(), ByteArray(11), "text/html")))),
        )
        assertThrows(PublicPageException.TooLarge::class.java) {
            runBlocking { tooLarge.getText(PublicPageRequest("https://example.com/x", OfficialPagePolicy.CUSTOM, 10)) }
        }
        val wrongType = SafeOfficialHttpClient(
            resolver = publicResolver,
            transport = RecordingPinnedTransport(ArrayDeque(listOf(SafeHttpResponse(200, emptyMap(), byteArrayOf(1), "application/json")))),
        )
        assertThrows(PublicPageException.Http::class.java) {
            runBlocking { wrongType.getText(PublicPageRequest("https://example.com/x", OfficialPagePolicy.CUSTOM)) }
        }

        var cancelled = false
        val blocking = PinnedHttpTransport { _, _, _ ->
            try { awaitCancellation() } catch (error: CancellationException) { cancelled = true; throw error }
        }
        val job = async { SafeOfficialHttpClient(resolver = publicResolver, transport = blocking)
            .getText(PublicPageRequest("https://example.com/x", OfficialPagePolicy.CUSTOM)) }
        yield(); job.cancel(); runCatching { job.await() }
        assertTrue(cancelled)
    }

    @Test
    fun `real okhttp transport uses validated custom dns keeps hostname closes and cancels call`() = runBlocking {
        val testThread = Thread.currentThread()
        val public = InetAddress.getByName("93.184.216.34")
        var lookupThread: Thread? = null
        var systemLookups = 0
        var factory: FakeOkHttpCallFactory? = null
        val transport = OkHttpPinnedTransport(
            systemDns = dns { host ->
                assertEquals("example.com", host)
                lookupThread = Thread.currentThread()
                systemLookups++
                listOf(public)
            },
            clientFactory = OkHttpClientFactory { dns, _, _, _ ->
                FakeOkHttpCallFactory(dns, completeResponse = true).also { factory = it }
            },
        )

        assertEquals(
            "ok",
            transport.execute(SafeHttpRequest("GET", "https://example.com/x"), emptyList(), 10)
                .body.toString(Charsets.UTF_8),
        )
        assertEquals("example.com", factory?.lastRequest?.url?.host)
        assertEquals(listOf(public), factory?.dnsResult)
        assertEquals(1, systemLookups)
        assertTrue(factory?.dnsResultWasStable == true)
        assertTrue(lookupThread != testThread)
        assertTrue(factory?.body?.closed == true)

        val luckinAddress = InetAddress.getByName("93.184.216.35")
        val mstandAddress = InetAddress.getByName("93.184.216.36")
        val hostFactories = mutableListOf<FakeOkHttpCallFactory>()
        val multiHost = OkHttpPinnedTransport(
            systemDns = dns { host ->
                when (host) {
                    "www.luckincoffee.com" -> listOf(luckinAddress)
                    "www.mstand.cn" -> listOf(mstandAddress)
                    else -> error("unexpected host $host")
                }
            },
            clientFactory = OkHttpClientFactory { dns, _, _, _ ->
                FakeOkHttpCallFactory(dns, completeResponse = true).also(hostFactories::add)
            },
        )
        multiHost.execute(SafeHttpRequest("GET", "https://www.luckincoffee.com/x"), emptyList(), 10)
        multiHost.execute(SafeHttpRequest("GET", "https://www.mstand.cn/x"), emptyList(), 10)
        assertTrue(hostFactories[0].requestDns !== hostFactories[1].requestDns)
        assertEquals(listOf(luckinAddress), hostFactories[0].dnsResult)
        assertEquals(listOf(mstandAddress), hostFactories[1].dnsResult)

        val mixedDns = OkHttpPinnedTransport(
            systemDns = dns { listOf(public, InetAddress.getByName("10.0.0.1")) },
            clientFactory = OkHttpClientFactory { dns, _, _, _ ->
                FakeOkHttpCallFactory(dns, completeResponse = true)
            },
        )
        assertThrows(PublicPageException.UnsafeUrl::class.java) {
            runBlocking { mixedDns.execute(SafeHttpRequest("GET", "https://example.com/x"), emptyList(), 10) }
        }

        var blockingFactory: FakeOkHttpCallFactory? = null
        val blocking = OkHttpPinnedTransport(
            systemDns = dns { listOf(public) },
            clientFactory = OkHttpClientFactory { dns, _, _, _ ->
                FakeOkHttpCallFactory(dns, completeResponse = false).also { blockingFactory = it }
            },
        )
        val job = async { blocking.execute(SafeHttpRequest("GET", "https://example.com/x"), emptyList(), 10) }
        yield()
        requireNotNull(blockingFactory).enqueued.await()
        job.cancel(); runCatching { job.await() }
        assertTrue(blockingFactory?.lastCall?.isCanceled() == true)
    }

    private val publicResolver = NetworkResolver { listOf(InetAddress.getByName("93.184.216.34")) }
}

private class TrackingBody : ResponseBody() {
    var closed = false
    private val trackedSource = object : ForwardingSource(Buffer().writeUtf8("ok")) {
        override fun close() {
            closed = true
            super.close()
        }
    }.buffer()
    override fun contentType() = "text/html".toMediaType()
    override fun contentLength() = 2L
    override fun source(): BufferedSource = trackedSource
}

private fun dns(lookup: (String) -> List<InetAddress>): Dns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> = lookup(hostname)
}

private class FakeOkHttpCallFactory(
    val requestDns: Dns,
    private val completeResponse: Boolean,
) : Call.Factory {
    val enqueued = CompletableDeferred<Unit>()
    val body = TrackingBody()
    var lastRequest: Request? = null
    var lastCall: Call? = null
    var dnsResult: List<InetAddress>? = null
    var dnsResultWasStable = false

    override fun newCall(request: Request): Call = object : Call {
        @Volatile private var cancelled = false
        @Volatile private var executed = false
        override fun request() = request
        override fun execute(): Response = error("async only")
        override fun enqueue(responseCallback: Callback) {
            executed = true
            lastRequest = request
            lastCall = this
            enqueued.complete(Unit)
            Thread {
                try {
                    val firstResult = requestDns.lookup(request.url.host)
                    dnsResult = firstResult
                    dnsResultWasStable = firstResult === requestDns.lookup(request.url.host)
                } catch (error: IOException) {
                    responseCallback.onFailure(this, error)
                    return@Thread
                }
                if (completeResponse && !cancelled) {
                    responseCallback.onResponse(
                        this,
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(200).message("OK").header("Content-Type", "text/html").body(body).build(),
                    )
                }
            }.start()
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = okio.Timeout.NONE
        override fun clone(): Call = this
    }
}

private data class RecordedPinnedRequest(val request: SafeHttpRequest, val addresses: List<InetAddress>)
private class RecordingPinnedTransport(
    private val responses: ArrayDeque<SafeHttpResponse> = ArrayDeque(
        listOf(SafeHttpResponse(200, emptyMap(), "ok".toByteArray(), "text/html")),
    ),
) : PinnedHttpTransport {
    val requests = mutableListOf<RecordedPinnedRequest>()
    override suspend fun execute(request: SafeHttpRequest, addresses: List<InetAddress>, maxBytes: Int): SafeHttpResponse {
        requests += RecordedPinnedRequest(request, addresses)
        return responses.removeFirst()
    }
}
