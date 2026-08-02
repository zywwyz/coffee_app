package com.niumi.coffeejournal.importer

import java.io.ByteArrayInputStream
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialImageImporterTest {
    @Test
    fun `official image policy rejects non https cross brand and deceptive hosts`() {
        assertTrue(OfficialImagePolicy.accepts("seed-chain-luckin", "https://img.luckincoffee.com/menu/a.webp"))
        assertFalse(OfficialImagePolicy.accepts("seed-chain-luckin", "http://img.luckincoffee.com/menu/a.webp"))
        assertFalse(OfficialImagePolicy.accepts("seed-chain-luckin", "https://img.luckincoffee.com.evil.example/a.webp"))
        assertFalse(OfficialImagePolicy.accepts("seed-chain-luckin", "https://mstand.cn/a.webp"))
        assertTrue(OfficialImagePolicy.accepts("seed-chain-mstand", "https://mstand.cn/userfiles/a.jpg"))
        assertFalse(OfficialImagePolicy.accepts("custom", "https://example.com/a.jpg"))
        assertFalse(OfficialImagePolicy.accepts("seed-chain-luckin", "https://img.luckincoffee.com:8443/a.jpg"))
    }

    @Test
    fun `safe image downloader validates dns pins address and enforces response`() = runBlocking {
        val pinned = mutableListOf<List<InetAddress>>()
        val transport = PinnedHttpTransport { _, addresses, _ ->
            pinned += addresses
            SafeHttpResponse(200, emptyMap(), byteArrayOf(1, 2), "image/png")
        }
        val public = InetAddress.getByName("93.184.216.34")
        val downloader = SafeOfficialImageDownloader(
            resolver = NetworkResolver { listOf(public) }, transport = transport, maxBytes = 2,
        )
        assertArrayEquals(byteArrayOf(1, 2), downloader.download("https://img.luckincoffee.com/a.png").bytes)
        assertEquals(listOf(listOf(public)), pinned)

        assertThrows(OfficialImageException::class.java) {
            runBlocking {
                SafeOfficialImageDownloader(
                    resolver = NetworkResolver { listOf(InetAddress.getByName("10.0.0.1")) }, transport = transport,
                ).download("https://img.luckincoffee.com/a.png")
            }
        }
        assertThrows(OfficialImageException::class.java) {
            runBlocking {
                SafeOfficialImageDownloader(
                    resolver = NetworkResolver { listOf(public) },
                    transport = PinnedHttpTransport { _, _, _ -> SafeHttpResponse(200, emptyMap(), ByteArray(3), "image/png") },
                    maxBytes = 2,
                ).download("https://img.luckincoffee.com/a.png")
            }
        }
        Unit
    }

    @Test
    fun `bounded reader rejects response larger than configured maximum`() {
        assertThrows(PublicPageException.TooLarge::class.java) {
            ByteArrayInputStream(ByteArray(11)).readBounded(10)
        }
        assertArrayEquals(ByteArray(10), ByteArrayInputStream(ByteArray(10)).readBounded(10))
    }

    @Test
    fun `valid official image is checked then imported into private asset store`() = runBlocking {
        val store = FakeOfficialAssetStore()
        val importer = ValidatingOfficialImageImporter(
            downloader = FakeOfficialImageDownloader(DownloadedOfficialImage(byteArrayOf(1, 2, 3), "image/webp")),
            assetStore = store,
            decodeBounds = { ImageBounds(1200, 800) },
        )

        val id = importer.importOfficialImage("seed-chain-luckin", "https://img.luckincoffee.com/menu/a.webp")

        assertEquals("private-asset", id)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.imported)
    }

    @Test
    fun `invalid content type size or pixel bounds never reaches private store`() {
        fun attempt(payload: DownloadedOfficialImage, bounds: ImageBounds = ImageBounds(10, 10)): FakeOfficialAssetStore {
            val store = FakeOfficialAssetStore()
            val importer = ValidatingOfficialImageImporter(
                FakeOfficialImageDownloader(payload), store, maxBytes = 4, maxPixels = 100, decodeBounds = { bounds },
            )
            assertThrows(OfficialImageException::class.java) {
                runBlocking { importer.importOfficialImage("seed-chain-luckin", "https://img.luckincoffee.com/a.webp") }
            }
            return store
        }

        assertEquals(0, attempt(DownloadedOfficialImage(byteArrayOf(1), "text/html")).importCalls)
        assertEquals(0, attempt(DownloadedOfficialImage(ByteArray(5), "image/jpeg")).importCalls)
        assertEquals(0, attempt(DownloadedOfficialImage(byteArrayOf(1), "image/png"), ImageBounds(11, 10)).importCalls)
        assertEquals(0, attempt(DownloadedOfficialImage(byteArrayOf(1), "image/png"), ImageBounds(0, 0)).importCalls)
    }

    @Test
    fun `cleanup delegates only after private import`() = runBlocking {
        val store = FakeOfficialAssetStore()
        val importer = ValidatingOfficialImageImporter(
            FakeOfficialImageDownloader(DownloadedOfficialImage(byteArrayOf(1), "image/png")),
            store, decodeBounds = { ImageBounds(1, 1) },
        )
        importer.cleanup("private-asset")
        assertEquals(listOf("private-asset"), store.cleaned)
    }
}

private class FakeOfficialImageDownloader(private val result: DownloadedOfficialImage) : OfficialImageDownloader {
    override suspend fun download(url: String): DownloadedOfficialImage = result
}

private class FakeOfficialAssetStore : OfficialImageAssetStore {
    var imported: ByteArray? = null
    var importCalls = 0
    val cleaned = mutableListOf<String>()
    override suspend fun import(bytes: ByteArray, mimeType: String): String {
        importCalls++
        imported = bytes
        return "private-asset"
    }
    override suspend fun cleanup(assetId: String) { cleaned += assetId }
}
