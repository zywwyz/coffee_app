package com.niumi.coffeejournal.importer

import android.net.Uri
import com.niumi.coffeejournal.core.image.CropRect
import com.niumi.coffeejournal.core.image.ImageAsset
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalOfficialImageAssetStoreTest {
    @Test
    fun `cancellation exactly after temp write return removes cache file before image import`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "official-image-import").apply { deleteRecursively() }
        val images = BoundaryImageStore()
        val store = LocalOfficialImageAssetStore(
            context, images,
            afterTempWritten = {
                currentCoroutineContext().cancel()
                yield()
            },
        )

        val importing = async(Dispatchers.Default) { store.import(byteArrayOf(1, 2, 3), "image/png") }
        try { importing.await(); fail("expected cancellation") } catch (_: CancellationException) { }

        assertEquals(0, images.importCalls)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `cancellation at asset return boundary deletes asset and temporary file without masking cancellation`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "official-image-import").apply { deleteRecursively() }
        val images = BoundaryImageStore()
        val store = LocalOfficialImageAssetStore(
            context, images,
            beforeAssetDelivery = {
                currentCoroutineContext().cancel()
                yield()
            },
        )

        val importing = async(Dispatchers.Default) { store.import(byteArrayOf(1, 2, 3), "image/png") }
        try { importing.await(); fail("expected cancellation") } catch (_: CancellationException) { }

        assertEquals(listOf("created-asset"), images.deleted)
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    private class BoundaryImageStore : ImageStore {
        val deleted = mutableListOf<String>()
        var importCalls = 0
        override suspend fun importCropped(source: Uri, crop: CropRect, kind: ImageKind): ImageAsset = error("unused")
        override suspend fun importWhole(source: Uri, kind: ImageKind): ImageAsset {
            importCalls++
            return ImageAsset("created-asset", "/private/a", "sha", kind)
        }
        override suspend fun deleteIfUnreferenced(assetId: String): Boolean { deleted += assetId; return true }
    }
}
