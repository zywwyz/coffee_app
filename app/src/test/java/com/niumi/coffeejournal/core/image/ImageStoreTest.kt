package com.niumi.coffeejournal.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.Room
import androidx.exifinterface.media.ExifInterface
import com.niumi.coffeejournal.core.database.BrandEntity
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.importer.ImportedAssetSelection
import com.niumi.coffeejournal.importer.associateImportedAsset
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageStoreTest {
    private lateinit var context: Context
    private lateinit var database: CoffeeDatabase
    private lateinit var store: LocalImageStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "images").deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, CoffeeDatabase::class.java)
            .allowMainThreadQueries().build()
        store = LocalImageStore(context, database.imageAssetDao())
    }

    @After
    fun tearDown() {
        database.close()
        File(context.filesDir, "images").deleteRecursively()
    }

    @Test
    fun `rejects crop outside oriented image bounds without writing file or row`() = runBlocking {
        val source = bitmapFile("bounds", 80, 40)

        try {
            store.importCropped(Uri.fromFile(source), CropRect(0, 0, 81, 40), ImageKind.PRODUCT)
            fail("expected invalid crop")
        } catch (_: InvalidCropException) {
        }

        assertTrue(File(context.filesDir, "images").listFiles().isNullOrEmpty())
    }

    @Test
    fun `confirmed crop stores only crop dimensions and deduplicates by sha256`() = runBlocking {
        val source = bitmapFile("crop", 80, 40)

        val first = store.importCropped(Uri.fromFile(source), CropRect(10, 5, 50, 25), ImageKind.PRODUCT)
        val second = store.importCropped(Uri.fromFile(source), CropRect(10, 5, 50, 25), ImageKind.PRODUCT)

        assertEquals(first.id, second.id)
        assertEquals(1, File(context.filesDir, "images").listFiles()?.size)
        val decoded = BitmapFactory.decodeFile(first.localPath)
        assertEquals(40, decoded.width)
        assertEquals(20, decoded.height)
    }

    @Test
    fun `delete refuses referenced assets and only deletes managed paths`() = runBlocking {
        val asset = store.importWhole(Uri.fromFile(bitmapFile("logo", 32, 32)), ImageKind.BRAND_LOGO)
        database.brandDao().upsert(
            BrandEntity("brand", "CHAIN", "品牌", "品牌", asset.id, "MANUAL_ONLY", null),
        )

        assertFalse(store.deleteIfUnreferenced(asset.id))
        assertTrue(File(asset.localPath).exists())

        val outside = File(context.cacheDir, "outside.webp").apply { writeText("do not delete") }
        database.imageAssetDao().upsert(
            com.niumi.coffeejournal.core.database.ImageAssetEntity(
                "outside", outside.absolutePath, "outside-sha", "PRODUCT", 1,
            ),
        )
        assertFalse(store.deleteIfUnreferenced("outside"))
        assertTrue(outside.exists())
    }

    @Test
    fun `successful replacement cleanup retains old asset while a database snapshot or catalog reference exists`() = runBlocking {
        val old = store.importWhole(Uri.fromFile(bitmapFile("referenced-old", 32, 32)), ImageKind.BRAND_LOGO)
        val replacement = store.importWhole(Uri.fromFile(bitmapFile("replacement", 24, 24)), ImageKind.BRAND_LOGO)
        database.brandDao().upsert(
            BrandEntity("brand-replacement", "CHAIN", "品牌", "品牌", old.id, "MANUAL_ONLY", null),
        )

        val accepted = associateImportedAsset(
            imageStore = store,
            selection = ImportedAssetSelection(replacement.id),
            previousAssetId = old.id,
            association = { true },
        )

        assertTrue(accepted)
        assertTrue(File(old.localPath).isFile)
        assertNotNull(database.imageAssetDao().get(old.id))
    }

    @Test
    fun `failed database write cleans newly encoded file`() = runBlocking {
        val source = Uri.fromFile(bitmapFile("failure", 20, 20))
        val failingStore = LocalImageStore(
            context = context,
            imageAssetDao = database.imageAssetDao(),
            persistAsset = { throw IllegalStateException("database unavailable") },
        )

        try {
            failingStore.importWhole(source, ImageKind.PRODUCT)
            fail("expected failure")
        } catch (_: IllegalStateException) {
        }

        assertTrue(File(context.filesDir, "images").listFiles().isNullOrEmpty())
        assertFalse(File(context.filesDir, "images").walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `crop coordinates use exif oriented dimensions`() = runBlocking {
        val source = bitmapFile("rotated", 80, 40, Bitmap.CompressFormat.JPEG)
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val asset = store.importCropped(Uri.fromFile(source), CropRect(0, 0, 40, 80), ImageKind.PRODUCT)

        val decoded = BitmapFactory.decodeFile(asset.localPath)
        assertEquals(40, decoded.width)
        assertEquals(80, decoded.height)
    }

    @Test
    fun `cancellation during persistence removes temporary and unowned target files`() = runBlocking {
        val source = Uri.fromFile(bitmapFile("cancelled", 20, 20))
        val cancellingStore = LocalImageStore(
            context = context,
            imageAssetDao = database.imageAssetDao(),
            persistAsset = {
                currentCoroutineContext().cancel()
                yield()
                error("unreachable")
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val job = scope.launch {
            try {
                cancellingStore.importWhole(source, ImageKind.PRODUCT)
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }
        job.join()

        assertTrue(File(context.filesDir, "images").listFiles().isNullOrEmpty())
    }

    @Test
    fun `delete waits for same store import finalization then removes a consistent row and file`() = runBlocking {
        val persistStarted = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        val dao = database.imageAssetDao()
        val serializedStore = LocalImageStore(
            context = context,
            imageAssetDao = dao,
            newAssetId = { "serialized-asset" },
            persistAsset = { candidate ->
                persistStarted.complete(Unit)
                releasePersist.await()
                dao.insertIgnoringExisting(candidate)
                checkNotNull(dao.getBySha256(candidate.sha256))
            },
        )
        val source = Uri.fromFile(bitmapFile("serialized", 30, 30))

        val importing = async(Dispatchers.Default) { serializedStore.importWhole(source, ImageKind.PRODUCT) }
        persistStarted.await()
        val deleting = async(Dispatchers.Default) { serializedStore.deleteIfUnreferenced("serialized-asset") }
        repeat(20) { yield() }
        assertFalse("delete must wait for import mutation", deleting.isCompleted)

        releasePersist.complete(Unit)
        val imported = importing.await()
        assertTrue(deleting.await())
        assertFalse(File(imported.localPath).exists())
        assertEquals(null, dao.get(imported.id))
    }

    @Test
    fun `global mutation lock keeps failing and succeeding same hash imports consistent`() = runBlocking {
        val persistStarted = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        val source = Uri.fromFile(bitmapFile("same-hash-race", 28, 28))
        val failing = LocalImageStore(
            context = context,
            imageAssetDao = database.imageAssetDao(),
            newAssetId = { "failing-asset" },
            persistAsset = {
                persistStarted.complete(Unit)
                releaseFailure.await()
                throw IllegalStateException("database unavailable")
            },
        )
        val succeeding = LocalImageStore(context, database.imageAssetDao(), newAssetId = { "winning-asset" })

        val failedImport = async(Dispatchers.Default) { runCatching { failing.importWhole(source, ImageKind.PRODUCT) } }
        persistStarted.await()
        val successfulImport = async(Dispatchers.Default) { succeeding.importWhole(source, ImageKind.PRODUCT) }
        repeat(20) { yield() }
        assertFalse("second store must share the mutation lock", successfulImport.isCompleted)

        releaseFailure.complete(Unit)
        assertTrue(failedImport.await().isFailure)
        val asset = successfulImport.await()
        assertTrue(File(asset.localPath).isFile)
        assertEquals(asset.id, database.imageAssetDao().getBySha256(asset.sha256)?.id)
    }

    private fun bitmapFile(
        name: String,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    ): File =
        File(context.cacheDir, "$name.${if (format == Bitmap.CompressFormat.JPEG) "jpg" else "png"}").also { file ->
            FileOutputStream(file).use { output ->
                assertTrue(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    .compress(format, 100, output))
            }
            assertNotNull(BitmapFactory.decodeFile(file.absolutePath))
        }
}
