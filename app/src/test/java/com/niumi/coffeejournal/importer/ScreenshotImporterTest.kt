package com.niumi.coffeejournal.importer

import com.niumi.coffeejournal.core.image.CropRect
import com.niumi.coffeejournal.core.image.ImageAsset
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import android.net.Uri
import kotlinx.coroutines.runBlocking
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
    fun `multiple product text regions remain separate selectable candidates`() {
        val candidates = normalizeScreenshotCandidates(
            listOf(
                TextBlock("生椰拿铁", CropRect(20, 100, 180, 140)),
                TextBlock("实付 9.90", CropRect(20, 150, 140, 180)),
                TextBlock("燕麦澳白", CropRect(20, 500, 180, 540)),
                TextBlock("到手 12.80", CropRect(20, 550, 160, 580)),
            ),
        )

        assertEquals(listOf("生椰拿铁", "燕麦澳白"), candidates.map { it.productName })
        assertEquals(listOf(990L, 1280L), candidates.map { it.actualPriceFen })
        assertTrue(candidates.all { it.proposedCrop != null })
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
    fun `cancelling review writes no image and confirmation writes crop once`() = runBlocking {
        val store = RecordingImageStore()
        val session = ScreenshotImportSession(
            recognizer = ScreenshotTextRecognizer { listOf(TextBlock("实付 9.90", CropRect(0, 0, 40, 20))) },
            imageStore = store,
        )
        val uri = Uri.parse("content://private/source-screen")

        session.prepare(uri)
        session.cancel()
        assertEquals(0, store.cropImports)
        assertFalse(session.toString().contains(uri.toString()))

        session.prepare(uri)
        val result = session.confirm("生椰拿铁", "9.90", CropRect(1, 2, 20, 30), ImageKind.PRODUCT)
        assertEquals(1, store.cropImports)
        assertEquals("asset", result.imageAssetId)
        assertFalse(session.toString().contains(uri.toString()))
    }

    private class RecordingImageStore : ImageStore {
        var cropImports = 0
        override suspend fun importCropped(source: Uri, crop: CropRect, kind: ImageKind): ImageAsset {
            cropImports++
            return ImageAsset("asset", "/private/images/asset.webp", "sha", kind)
        }
        override suspend fun importWhole(source: Uri, kind: ImageKind): ImageAsset = error("unexpected")
        override suspend fun deleteIfUnreferenced(assetId: String): Boolean = false
    }
}
