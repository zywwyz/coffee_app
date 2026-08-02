package com.niumi.coffeejournal.catalog

import android.net.Uri
import com.niumi.coffeejournal.core.image.CropRect
import com.niumi.coffeejournal.core.image.ImageAsset
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import com.niumi.coffeejournal.importer.ImportedAssetSelection
import com.niumi.coffeejournal.importer.associateImportedAsset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogScreenshotImportSessionTest {
    @Test
    fun `start requests screenshot once and accepted selection stages before prefilling editor`() = runBlocking {
        var requestCalls = 0
        var callback: (suspend (ImportedAssetSelection) -> Boolean)? = null
        val events = mutableListOf<String>()
        var editorName = ""
        var editorImage: String? = null
        val session = CatalogScreenshotImportSession(
            leaseId = "lease", previousAssetId = null,
            retain = { _, _ -> events += "retain"; true },
            stage = { _, _, asset -> events += "stage:$asset"; true },
            discard = {},
            applyToEditor = { selection ->
                events += "apply:${selection.assetId}"
                editorImage = selection.assetId
                if (editorName.isBlank()) editorName = selection.suggestedName.orEmpty()
            },
        )
        val requester: CatalogAssetPicker = { _, kind, accepted ->
            requestCalls++
            assertEquals(CatalogAssetKind.CHAIN_PRODUCT_IMAGE, kind)
            callback = accepted
        }

        session.startScreenshot(requester)
        session.startScreenshot(requester)
        assertTrue(requireNotNull(callback)(ImportedAssetSelection("asset", "候选拿铁", 990)))

        assertEquals(1, requestCalls)
        assertEquals(listOf("retain", "stage:asset", "apply:asset"), events)
        assertEquals("候选拿铁", editorName)
        assertEquals("asset", editorImage)
    }

    @Test
    fun `closing while picker is open rejects selection and host removes orphan`() = runBlocking {
        var callback: (suspend (ImportedAssetSelection) -> Boolean)? = null
        var stages = 0
        var discards = 0
        val store = DeletingStore()
        val session = CatalogScreenshotImportSession(
            leaseId = "lease", previousAssetId = null,
            retain = { _, _ -> true },
            stage = { _, _, _ -> stages++; true },
            discard = { discards++ },
            applyToEditor = {},
        )
        session.startScreenshot { _, _, accepted -> callback = accepted }
        session.close()

        val associated = associateImportedAsset(
            store, ImportedAssetSelection("orphan"), association = requireNotNull(callback),
        )

        assertFalse(associated)
        assertEquals(0, stages)
        assertEquals(1, discards)
        assertEquals(listOf("orphan"), store.deleted)
    }

    private class DeletingStore : ImageStore {
        val deleted = mutableListOf<String>()
        override suspend fun importCropped(source: Uri, crop: CropRect, kind: ImageKind): ImageAsset = error("unused")
        override suspend fun importWhole(source: Uri, kind: ImageKind): ImageAsset = error("unused")
        override suspend fun deleteIfUnreferenced(assetId: String): Boolean { deleted += assetId; return true }
    }
}
