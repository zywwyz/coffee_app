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
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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
    fun `whole imports preserve png jpeg and webp source bytes with magic based extensions`() = runBlocking {
        val fixtures = listOf(
            bitmapFile("original-png", 24, 16, Bitmap.CompressFormat.PNG) to "png",
            bitmapFile("original-jpeg", 24, 16, Bitmap.CompressFormat.JPEG) to "jpg",
            webpFile("original-webp") to "webp",
        )

        fixtures.forEach { (source, extension) ->
            val asset = store.importWhole(Uri.fromFile(source), ImageKind.PRODUCT)

            assertArrayEquals(source.readBytes(), File(asset.localPath).readBytes())
            assertEquals(sha256(source), asset.sha256)
            assertTrue(asset.localPath.endsWith(".${extension}"))
        }
    }

    @Test
    fun `whole imports deduplicate identical original bytes`() = runBlocking {
        val source = bitmapFile("whole-dedup", 32, 32, Bitmap.CompressFormat.JPEG)

        val first = store.importWhole(Uri.fromFile(source), ImageKind.PRODUCT)
        val second = store.importWhole(Uri.fromFile(source), ImageKind.BRAND_LOGO)

        assertEquals(first.id, second.id)
        assertEquals(1, File(context.filesDir, "images").listFiles()?.size)
    }

    @Test
    fun `whole import rejects invalid and over limit files without rows or files`() = runBlocking {
        val invalid = File(context.cacheDir, "invalid-image.png").apply { writeText("not an image") }
        val tooLarge = bitmapFile("too-large", 2, 2).apply {
            appendBytes(ByteArray(20 * 1024 * 1024 + 1))
        }

        listOf(invalid, tooLarge).forEach { source ->
            try {
                store.importWhole(Uri.fromFile(source), ImageKind.PRODUCT)
                fail("expected invalid image")
            } catch (_: ImageDecodeException) {
            }
        }

        assertTrue(File(context.filesDir, "images").listFiles().isNullOrEmpty())
        assertEquals(null, database.imageAssetDao().getBySha256(sha256(tooLarge)))
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
    fun `cancellation after row and file creation cleans asset before id delivery`() = runBlocking {
        val source = Uri.fromFile(bitmapFile("return-boundary", 20, 20))
        val cancellingStore = LocalImageStore(
            context = context,
            imageAssetDao = database.imageAssetDao(),
            newAssetId = { "boundary-asset" },
            beforeAssetDelivery = {
                currentCoroutineContext().cancel()
                yield()
            },
        )

        try {
            cancellingStore.importWhole(source, ImageKind.PRODUCT)
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }

        assertEquals(null, database.imageAssetDao().get("boundary-asset"))
        assertTrue(File(context.filesDir, "images").listFiles().isNullOrEmpty())
    }

    @Test
    fun `cancellation before caller resumes imported asset rolls back before same hash import can observe it`() = runBlocking {
        val source = Uri.fromFile(bitmapFile("caller-return-cancel", 20, 20))
        val caller = GateDispatcher(Dispatchers.Default)
        val hooks = object : WholeImportHooks {
            override suspend fun afterPrepared() = Unit
            override suspend fun beforeMutationLock() = Unit
            override suspend fun afterMutationCommitted() {
                caller.arm()
            }
            override suspend fun afterMutationReturned() = Unit
            override suspend fun beforeRollback() = Unit
        }
        val cancellingStore = LocalImageStore(
            context = context,
            imageAssetDao = database.imageAssetDao(),
            newAssetId = { "return-dispatch-asset" },
            wholeImportHooks = hooks,
        )
        val scope = CoroutineScope(SupervisorJob() + caller)
        val cancelledImport = scope.async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { cancellingStore.importWhole(source, ImageKind.PRODUCT) }
        }

        val blockedReturn = caller.awaitBlockedDispatch()
        cancelledImport.cancel()
        blockedReturn.run()
        try {
            cancelledImport.await()
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }

        assertEquals(null, database.imageAssetDao().get("return-dispatch-asset"))
        assertTrue(File(context.filesDir, "images").listFiles().isNullOrEmpty())
        scope.cancel()
    }

    @Test
    fun `cancellation before caller resumes prepared image removes temporary file`() = runBlocking {
        val source = Uri.fromFile(bitmapFile("prepare-return-cancel", 20, 20))
        val caller = GateDispatcher(Dispatchers.Default)
        val hooks = object : WholeImportHooks {
            override suspend fun afterPrepared() { caller.arm() }
            override suspend fun beforeMutationLock() = Unit
            override suspend fun afterMutationCommitted() = Unit
            override suspend fun afterMutationReturned() = Unit
            override suspend fun beforeRollback() = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + caller)
        val cancelledImport = scope.async(start = CoroutineStart.UNDISPATCHED) {
            LocalImageStore(context, database.imageAssetDao(), wholeImportHooks = hooks)
                .importWhole(source, ImageKind.PRODUCT)
        }

        caller.awaitBlockedDispatch().also { blockedReturn ->
            cancelledImport.cancel()
            blockedReturn.run()
        }
        try {
            cancelledImport.await()
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }

        assertTrue(File(context.filesDir, "images").listFiles().isNullOrEmpty())
        scope.cancel()
    }

    @Test
    fun `successful mutation return has no further dispatcher boundary before delivery`() = runBlocking {
        val source = Uri.fromFile(bitmapFile("mutation-return-success", 20, 20))
        val caller = GateDispatcher(Dispatchers.Default)
        val hooks = object : WholeImportHooks {
            override suspend fun afterPrepared() = Unit
            override suspend fun beforeMutationLock() = Unit
            override suspend fun afterMutationCommitted() = Unit
            override suspend fun afterMutationReturned() { caller.arm() }
            override suspend fun beforeRollback() = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + caller)
        val imported = scope.async(start = CoroutineStart.UNDISPATCHED) {
            LocalImageStore(context, database.imageAssetDao(), wholeImportHooks = hooks)
                .importWhole(source, ImageKind.PRODUCT)
        }.await()

        assertFalse("success must not dispatch after mutation return", caller.hasBlockedDispatch())
        assertEquals(imported.id, database.imageAssetDao().get(imported.id)?.id)
        assertTrue(File(imported.localPath).isFile)
        assertTrue(File(context.filesDir, "images").listFiles()?.none { it.name.startsWith("whole-") } == true)
        scope.cancel()
    }

    @Test
    fun `waiting same hash import does not receive asset being rolled back`() = runBlocking {
        val reachedDelivery = CompletableDeferred<Unit>()
        val rollbackStarted = CompletableDeferred<Unit>()
        val releaseRollback = CompletableDeferred<Unit>()
        val secondReachedMutationLock = CompletableDeferred<Unit>()
        val lockAttempts = AtomicInteger()
        val hooks = object : WholeImportHooks {
            override suspend fun afterPrepared() = Unit
            override suspend fun beforeMutationLock() {
                if (lockAttempts.incrementAndGet() == 2) secondReachedMutationLock.complete(Unit)
            }
            override suspend fun afterMutationCommitted() = Unit
            override suspend fun afterMutationReturned() = Unit
            override suspend fun beforeRollback() {
                val acquired = ImageMutationCoordinator.mutex.tryLock()
                if (acquired) ImageMutationCoordinator.mutex.unlock()
                assertFalse("rollback must retain the mutation mutex", acquired)
                rollbackStarted.complete(Unit)
                releaseRollback.await()
            }
        }
        val source = Uri.fromFile(bitmapFile("rollback-race", 20, 20))
        val cancellingStore = LocalImageStore(
            context = context,
            imageAssetDao = database.imageAssetDao(),
            newAssetId = { "rolling-back" },
            beforeAssetDelivery = {
                reachedDelivery.complete(Unit)
                currentCoroutineContext().cancel()
                yield()
            },
            wholeImportHooks = hooks,
        )
        val waitingStore = LocalImageStore(context, database.imageAssetDao(), wholeImportHooks = hooks)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = scope.async { runCatching { cancellingStore.importWhole(source, ImageKind.PRODUCT) } }
        reachedDelivery.await()
        rollbackStarted.await()
        val second = scope.async { waitingStore.importWhole(source, ImageKind.PRODUCT) }
        secondReachedMutationLock.await()
        assertFalse("B must wait until A finishes rollback", second.isCompleted)

        releaseRollback.complete(Unit)
        assertTrue(first.await().isFailure)
        val imported = second.await()
        assertEquals(imported.id, database.imageAssetDao().getBySha256(imported.sha256)?.id)
        assertTrue(File(imported.localPath).isFile)
        assertEquals(1, File(context.filesDir, "images").listFiles()?.size)
        scope.cancel()
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

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun webpFile(name: String): File = File(context.cacheDir, "$name.webp").apply {
        writeBytes(Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAACQAQCdASoBAAEAAUAmJaQAA3AA/vuUAAA="))
        assertNotNull(BitmapFactory.decodeFile(absolutePath))
    }

    private class GateDispatcher(private val delegate: kotlinx.coroutines.CoroutineDispatcher) : kotlinx.coroutines.CoroutineDispatcher() {
        private val armed = java.util.concurrent.atomic.AtomicBoolean()
        private val blocked = CompletableDeferred<Runnable>()

        fun arm() {
            check(armed.compareAndSet(false, true))
        }

        suspend fun awaitBlockedDispatch(): Runnable = blocked.await()

        fun hasBlockedDispatch(): Boolean = blocked.isCompleted

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (armed.compareAndSet(true, false)) {
                check(blocked.complete(block))
            } else {
                delegate.dispatch(context, block)
            }
        }
    }
}
