package com.niumi.coffeejournal.importer

import java.net.InetAddress
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeOfficialNetworkTest {
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
    fun `real pinned transport disconnects on success and cancellation`() = runBlocking {
        val normal = FakeHttpsConnection("ok".toByteArray())
        val transport = HttpsUrlPinnedTransport(
            connectionFactory = PinnedHttpsConnectionFactory { _, _, _ -> normal },
        )
        transport.execute(
            SafeHttpRequest("GET", "https://example.com/x"),
            listOf(InetAddress.getByName("93.184.216.34")), 10,
        )
        assertTrue(normal.disconnected)

        val blocking = FakeHttpsConnection(null)
        val cancellingTransport = HttpsUrlPinnedTransport(
            connectionFactory = PinnedHttpsConnectionFactory { _, _, _ -> blocking },
        )
        val job = async {
            cancellingTransport.execute(
                SafeHttpRequest("GET", "https://example.com/x"),
                listOf(InetAddress.getByName("93.184.216.34")), 10,
            )
        }
        blocking.readStarted.await()
        job.cancel(); runCatching { job.await() }
        assertTrue(blocking.disconnected)
    }

    private val publicResolver = NetworkResolver { listOf(InetAddress.getByName("93.184.216.34")) }
}

private class FakeHttpsConnection(private val payload: ByteArray?) : HttpsURLConnection(URL("https://example.com/x")) {
    var disconnected = false
    val readStarted = CompletableDeferred<Unit>()
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun connect() = Unit
    override fun getResponseCode() = 200
    override fun getContentType() = "text/html"
    override fun getInputStream(): InputStream = payload?.let(::ByteArrayInputStream) ?: object : InputStream() {
        override fun read(): Int {
            readStarted.complete(Unit)
            while (true) Thread.sleep(1_000)
        }
    }
    override fun getCipherSuite(): String = "TLS"
    override fun getLocalCertificates() = null
    override fun getServerCertificates() = null
    override fun getPeerPrincipal() = null
    override fun getLocalPrincipal() = null
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
