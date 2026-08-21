package com.niumi.coffeejournal.core.image

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WholeImageImportCoordinatorTest {
    @Test
    fun `pending request survives new consumer and result is consumed once`() = runBlocking {
        val store = RecordingStore()
        val coordinator = coordinator(store)
        var associated = 0

        assertTrue(coordinator.request(ImageKind.PRODUCT, null) { associated++; true })
        assertTrue(coordinator.hasPendingRequest)
        assertTrue(coordinator.consumePickerResult(Uri.parse("content://image")))
        assertFalse(coordinator.consumePickerResult(Uri.parse("content://duplicate")))

        assertEquals(1, store.imported.size)
        assertEquals(1, associated)
        assertFalse(coordinator.hasPendingRequest)
        assertNull(coordinator.error)
    }

    @Test
    fun `cancelled picker clears pending request without an error`() {
        val coordinator = coordinator(RecordingStore())

        assertTrue(coordinator.request(ImageKind.PRODUCT, null) { true })
        assertTrue(coordinator.consumePickerResult(null))

        assertFalse(coordinator.hasPendingRequest)
        assertNull(coordinator.error)
    }

    @Test
    fun `failed association cleans new asset and retains retryable error`() = runBlocking {
        val store = RecordingStore(associationResult = false)
        val coordinator = coordinator(store)

        coordinator.request(ImageKind.PRODUCT, "old") { store.associationResult }
        coordinator.consumePickerResult(Uri.parse("content://image"))

        assertEquals(listOf("new"), store.deleted)
        assertTrue(coordinator.hasPendingRequest)
        assertTrue(coordinator.error != null)
        assertTrue(coordinator.retry())
        assertTrue(coordinator.isAwaitingPicker)
    }

    @Test
    fun `import exception retains retryable error without a stale asset`() {
        val store = RecordingStore(importFailure = IllegalStateException("read failed"))
        val coordinator = coordinator(store)

        coordinator.request(ImageKind.PRODUCT, null) { true }
        coordinator.consumePickerResult(Uri.parse("content://image"))

        assertTrue(coordinator.hasPendingRequest)
        assertTrue(coordinator.error != null)
        assertTrue(store.deleted.isEmpty())
    }

    @Test
    fun `association exception cleans new asset and dismiss releases request`() = runBlocking {
        val store = RecordingStore(associationFailure = IllegalStateException("write failed"))
        val coordinator = coordinator(store)

        coordinator.request(ImageKind.PRODUCT, null) { throw store.associationFailure!! }
        coordinator.consumePickerResult(Uri.parse("content://image"))

        assertEquals(listOf("new"), store.deleted)
        assertTrue(coordinator.error != null)
        coordinator.dismissError()
        assertFalse(coordinator.hasPendingRequest)
        assertNull(coordinator.error)
    }

    @Test
    fun `concurrent request is rejected and retry only prepares one new picker launch`() {
        val coordinator = coordinator(RecordingStore())

        assertTrue(coordinator.request(ImageKind.PRODUCT, null) { false })
        assertFalse(coordinator.request(ImageKind.BRAND_LOGO, null) { true })
        coordinator.consumePickerResult(Uri.parse("content://image"))
        assertTrue(coordinator.retry())
        assertFalse(coordinator.retry())
    }

    private fun coordinator(store: RecordingStore) = WholeImageImportCoordinatorViewModel(
        store,
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private class RecordingStore(
        var associationResult: Boolean = true,
        var associationFailure: Throwable? = null,
        var importFailure: Throwable? = null,
    ) : ImageStore {
        val imported = mutableListOf<Uri>()
        val deleted = mutableListOf<String>()

        override suspend fun importWhole(source: Uri, kind: ImageKind): ImageAsset {
            imported += source
            importFailure?.let { throw it }
            return ImageAsset("new", "path", "hash", kind)
        }

        override suspend fun deleteIfUnreferenced(assetId: String): Boolean {
            deleted += assetId
            return true
        }
    }
}
