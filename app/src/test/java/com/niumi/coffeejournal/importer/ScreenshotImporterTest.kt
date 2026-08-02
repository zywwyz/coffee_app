package com.niumi.coffeejournal.importer

import android.graphics.Bitmap
import android.net.Uri
import com.niumi.coffeejournal.core.image.CropRect
import com.niumi.coffeejournal.core.image.ImageAsset
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenshotImporterTest {
    @Test
    fun `actual payment label wins over original and crossed out prices`() {
        val candidate = normalizeScreenshot(
            listOf(
                TextBlock("原价 ¥32", CropRect(0, 0, 120, 30)),
                TextBlock("划线价 29.00", CropRect(0, 40, 120, 70)),
                TextBlock("实付 ¥9.90", CropRect(0, 80, 120, 110)),
            ),
        )

        assertEquals(990L, candidate.actualPriceFen)
        assertFalse("actualPriceFen" in candidate.lowConfidenceFields)
    }

    @Test
    fun `yuan price converts exactly to fen without floating point`() {
        assertEquals(1L, parseYuanAmountToFen("0.01"))
        assertEquals(1_234_567L, parseYuanAmountToFen("12,345.67"))
        assertEquals(900L, parseYuanAmountToFen("9"))
        assertNull(parseYuanAmountToFen("9.999"))
    }

    @Test
    fun `unlabelled price stays low confidence and product name is not invented`() {
        val candidate = normalizeScreenshot(
            listOf(TextBlock("¥18", CropRect(10, 20, 80, 50))),
        )

        assertEquals(1_800L, candidate.actualPriceFen)
        assertNull(candidate.productName)
        assertTrue("actualPriceFen" in candidate.lowConfidenceFields)
        assertTrue("productName" in candidate.lowConfidenceFields)
    }

    @Test
    fun `payment time order number and bare date integers are never prices`() {
        val candidate = normalizeScreenshot(
            listOf(
                TextBlock("支付时间 2026-08-01 12:30", CropRect(0, 0, 300, 40)),
                TextBlock("订单号 123456789", CropRect(0, 50, 300, 90)),
                TextBlock("取餐日期 2026", CropRect(0, 100, 300, 140)),
            ),
        )

        assertNull(candidate.actualPriceFen)
    }

    @Test
    fun `broad payment label needs currency mark for high confidence`() {
        val withoutCurrency = normalizeScreenshot(
            listOf(TextBlock("支付 9.90", CropRect(0, 0, 120, 40))),
        )
        val withCurrency = normalizeScreenshot(
            listOf(TextBlock("支付 ¥9.90", CropRect(0, 0, 120, 40))),
        )

        assertTrue("actualPriceFen" in withoutCurrency.lowConfidenceFields)
        assertEquals(990L, withCurrency.actualPriceFen)
        assertFalse("actualPriceFen" in withCurrency.lowConfidenceFields)
    }

    @Test
    fun `multiple product text regions remain separate selectable candidates`() {
        val candidates = normalizeScreenshotCandidates(
            listOf(
                TextBlock("生椰拿铁", CropRect(20, 100, 180, 140)),
                TextBlock("实付 9.90", CropRect(20, 150, 140, 180)),
                TextBlock("燕麦澳白", CropRect(20, 500, 180, 540)),
                TextBlock("到手 12.80", CropRect(20, 550, 160, 580)),
            ), imageWidth = 1080, imageHeight = 2400,
        )

        assertEquals(listOf("生椰拿铁", "燕麦澳白"), candidates.map { it.productName })
        assertEquals(listOf(990L, 1280L), candidates.map { it.actualPriceFen })
        assertTrue(candidates.all { it.proposedCrop != null })
    }

    @Test
    fun `vertical cards include image region and stay within neighboring rows`() {
        val candidates = normalizeScreenshotCandidates(
            listOf(
                TextBlock("生椰拿铁", CropRect(120, 720, 420, 770)),
                TextBlock("实付 9.90", CropRect(120, 790, 300, 830)),
                TextBlock("燕麦澳白", CropRect(120, 1640, 420, 1690)),
                TextBlock("到手 12.80", CropRect(120, 1710, 330, 1750)),
            ),
            imageWidth = 1080,
            imageHeight = 2400,
        )

        val first = candidates[0].proposedCrop!!
        val second = candidates[1].proposedCrop!!
        assertTrue(first.top < 500)
        assertTrue(first.bottom < second.top)
        assertTrue(first.right <= 1080 && second.bottom <= 2400)
    }

    @Test
    fun `two column products get separate card crops with image area above labels`() {
        val candidates = normalizeScreenshotCandidates(
            listOf(
                TextBlock("左侧拿铁", CropRect(90, 720, 390, 770)),
                TextBlock("实付 9.90", CropRect(90, 790, 280, 830)),
                TextBlock("右侧澳白", CropRect(650, 720, 950, 770)),
                TextBlock("到手 12.80", CropRect(650, 790, 860, 830)),
            ),
            imageWidth = 1080,
            imageHeight = 1600,
        )

        val left = candidates[0].proposedCrop!!
        val right = candidates[1].proposedCrop!!
        assertTrue(left.top < 500 && right.top < 500)
        assertTrue(left.right <= right.left)
        assertTrue(left.contains(CropRect(90, 720, 390, 770)))
        assertTrue(right.contains(CropRect(650, 720, 950, 770)))
    }

    @Test
    fun `session threads image dimensions into distinct candidate crops`() = runBlocking {
        val session = ScreenshotImportSession(
            recognizer = ScreenshotTextRecognizer {
                listOf(
                    TextBlock("左侧拿铁", CropRect(90, 720, 390, 770)),
                    TextBlock("实付 9.90", CropRect(90, 790, 280, 830)),
                    TextBlock("右侧澳白", CropRect(650, 720, 950, 770)),
                    TextBlock("到手 12.80", CropRect(650, 790, 860, 830)),
                )
            },
            imageStore = RecordingImageStore(),
        )

        val preparation = session.prepare(Uri.parse("content://private/two-column"), 1080, 1600)

        assertEquals(2, preparation.candidates.size)
        assertTrue(preparation.candidates[0].proposedCrop != preparation.candidates[1].proposedCrop)
    }

    @Test
    fun `ocr sample size enforces both long edge and pixel budget`() {
        val sample = ocrSampleSize(width = 1440, height = 20_000, maxDimension = 2048, maxPixels = 4_000_000)

        assertTrue(1440 / sample <= 2048)
        assertTrue(20_000 / sample <= 2048)
        assertTrue((1440L / sample) * (20_000L / sample) <= 4_000_000L)
    }

    @Test
    fun `sampled recognizer scales blocks to oriented original coordinates and recycles`() = runBlocking {
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        var closed = false
        val recognizer = SampledBitmapScreenshotTextRecognizer(
            decoder = ScreenshotBitmapDecoder {
                DecodedScreenshotBitmap(bitmap, orientedWidth = 1000, orientedHeight = 2000)
            },
            sessionFactory = BitmapTextRecognitionSessionFactory {
                object : BitmapTextRecognitionSession {
                    override suspend fun recognize(bitmap: Bitmap) =
                        listOf(TextBlock("拿铁", CropRect(20, 40, 120, 160)))
                    override fun close() { closed = true }
                }
            },
        )

        val blocks = recognizer.recognize(Uri.parse("content://large/screenshot"))

        assertEquals(listOf(TextBlock("拿铁", CropRect(100, 200, 600, 800))), blocks)
        assertTrue(bitmap.isRecycled)
        assertTrue(closed)
    }

    @Test
    fun `sampled recognizer closes engine and recycles bitmap on cancellation`() = runBlocking {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        var closed = false
        val recognizer = SampledBitmapScreenshotTextRecognizer(
            decoder = ScreenshotBitmapDecoder { DecodedScreenshotBitmap(bitmap, 1000, 1000) },
            sessionFactory = BitmapTextRecognitionSessionFactory {
                object : BitmapTextRecognitionSession {
                    override suspend fun recognize(bitmap: Bitmap): List<TextBlock> =
                        throw CancellationException("cancelled")
                    override fun close() { closed = true }
                }
            },
        )

        try {
            recognizer.recognize(Uri.parse("content://large/cancelled"))
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(bitmap.isRecycled)
        assertTrue(closed)
    }

    @Test
    fun `cancellation waits for underlying recognition terminal before cleanup`() = runBlocking {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val started = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<List<TextBlock>>()
        var closeCount = 0
        var cancellationObserved = false
        var propagatedCancellation: CancellationException? = null
        val recognizer = SampledBitmapScreenshotTextRecognizer(
            decoder = ScreenshotBitmapDecoder { DecodedScreenshotBitmap(bitmap, 100, 100) },
            sessionFactory = BitmapTextRecognitionSessionFactory {
                object : BitmapTextRecognitionSession {
                    override suspend fun recognize(bitmap: Bitmap): List<TextBlock> {
                        started.complete(Unit)
                        return terminal.await()
                    }
                    override fun close() {
                        closeCount++
                        error("close failed")
                    }
                }
            },
        )
        val job = launch(Dispatchers.Default) {
            try {
                recognizer.recognize(Uri.parse("content://large/pending-task"))
            } catch (error: CancellationException) {
                cancellationObserved = true
                propagatedCancellation = error
                throw error
            }
        }

        started.await()
        job.cancel()
        repeat(20) { yield() }
        assertFalse(bitmap.isRecycled)
        assertEquals(0, closeCount)
        assertFalse(job.isCompleted)

        terminal.complete(emptyList())
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(cancellationObserved)
        assertTrue(bitmap.isRecycled)
        assertEquals(1, closeCount)
        assertEquals("close failed", propagatedCancellation?.suppressed?.single()?.message)
    }

    @Test
    fun `failed catalog association deletes newly stored unreferenced asset`() = runBlocking {
        val store = RecordingImageStore()

        val associated = associateImportedAsset(
            imageStore = store,
            selection = ImportedAssetSelection("asset"),
            association = { throw IllegalStateException("catalog failed") },
        )

        assertFalse(associated)
        assertEquals(listOf("asset"), store.deletedAssets)
    }

    @Test
    fun `accepted staged association leaves the new asset owned by the editor`() = runBlocking {
        val store = RecordingImageStore()

        val associated = associateImportedAsset(
            imageStore = store,
            selection = ImportedAssetSelection("asset"),
            previousAssetId = null,
            association = { true },
        )

        assertTrue(associated)
        assertTrue(store.deletedAssets.isEmpty())
    }

    @Test
    fun `cancellation remains cancellation even when cleanup fails`() = runBlocking {
        val store = RecordingImageStore(deleteFailure = IllegalStateException("disk failed"))

        try {
            associateImportedAsset(
                imageStore = store,
                selection = ImportedAssetSelection("asset"),
                association = { throw CancellationException("screen closed") },
            )
            throw AssertionError("Expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("screen closed", error.message)
        }
        assertEquals(listOf("asset"), store.deletedAssets)
    }

    @Test
    fun `association failure remains a safe false result when cleanup itself fails`() = runBlocking {
        val store = RecordingImageStore(deleteFailure = IllegalStateException("disk failed"))

        val associated = associateImportedAsset(
            imageStore = store,
            selection = ImportedAssetSelection("asset"),
            association = { false },
        )

        assertFalse(associated)
        assertEquals(listOf("asset"), store.deletedAssets)
    }

    @Test
    fun `confirmed result has no source uri and privacy safe toString`() {
        val result = ConfirmedScreenshotImport(
            productName = "生椰拿铁",
            actualPriceFen = 990,
            imageAssetId = "asset-1",
        )

        assertFalse(result.toString().contains("content://"))
        assertFalse(result.toString().contains("Uri"))
    }

    @Test
    fun `cancelling review writes no image and confirmed crop remains retryable until review closes`() = runBlocking {
        val store = RecordingImageStore()
        val session = ScreenshotImportSession(
            recognizer = ScreenshotTextRecognizer { listOf(TextBlock("实付 9.90", CropRect(0, 0, 40, 20))) },
            imageStore = store,
        )
        val uri = Uri.parse("content://private/source-screen")

        session.prepare(uri, 100, 100)
        session.cancel()
        assertEquals(0, store.cropImports)
        assertFalse(session.toString().contains(uri.toString()))

        session.prepare(uri, 100, 100)
        val result = session.confirm("生椰拿铁", "9.90", CropRect(1, 2, 20, 30), ImageKind.PRODUCT)
        assertEquals(1, store.cropImports)
        assertEquals("asset", result.imageAssetId)
        session.confirm("生椰拿铁", "9.90", CropRect(1, 2, 20, 30), ImageKind.PRODUCT)
        assertEquals(2, store.cropImports)
        session.cancel()
        assertFalse(session.toString().contains(uri.toString()))
    }

    private class RecordingImageStore(
        private val deleteFailure: Exception? = null,
    ) : ImageStore {
        var cropImports = 0
        val deletedAssets = mutableListOf<String>()
        override suspend fun importCropped(source: Uri, crop: CropRect, kind: ImageKind): ImageAsset {
            cropImports++
            return ImageAsset("asset", "/private/images/asset.webp", "sha", kind)
        }
        override suspend fun importWhole(source: Uri, kind: ImageKind): ImageAsset = error("unexpected")
        override suspend fun deleteIfUnreferenced(assetId: String): Boolean {
            deletedAssets += assetId
            deleteFailure?.let { throw it }
            return true
        }
    }
}

private fun CropRect.contains(other: CropRect): Boolean =
    left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
