package com.niumi.coffeejournal.backup

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import com.niumi.coffeejournal.core.database.*
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupManagerTest {
    private lateinit var context: Context
    private lateinit var database: CoffeeDatabase
    private lateinit var manager: LocalBackupManager

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "images").deleteRecursively()
        database = Room.databaseBuilder(context, CoffeeDatabase::class.java, "backup-active-${System.nanoTime()}.db").allowMainThreadQueries().build()
        manager = LocalBackupManager(context, database, now = { 1234 })
    }

    @After fun tearDown() { database.close() }

    @Test fun `round trip preserves all six tables dual snapshot images and rewrites paths`() = runBlocking {
        val imageDir = File(context.filesDir, "images").apply { mkdirs() }
        val imageFile = File(imageDir, "source.webp")
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap -> imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP, 90, it) }; bitmap.recycle() }
        val sha = sha256(imageFile)
        val logo = ImageAssetEntity("logo", imageFile.absolutePath, sha, "BRAND_LOGO", 1)
        database.imageAssetDao().upsert(logo)
        val brand = BrandEntity("brand", "CHAIN", "瑞幸", "瑞幸", "logo", "MANUAL_ONLY", null)
        database.brandDao().upsert(brand)
        val item = CatalogItemEntity("item", "brand", "CHAIN_PRODUCT", "拿铁", "拿铁", "logo", status = "ACTIVE")
        database.catalogItemDao().upsert(item)
        database.drinkDao().insert(DrinkRecordEntity("record", 2, "2026-08-01", "CHAIN_PRODUCT", "item", snapshotBrandName="瑞幸", snapshotItemName="拿铁", snapshotImageAssetId="logo", snapshotBrandLogoAssetId="logo"))
        database.catalogUpdateDao().insert(CatalogUpdateEntity("update", "brand", 3, "CONFIRMED", null, null))
        database.draftDao().upsert(DraftRecordEntity("current", "rev", "CHAIN_PRODUCT", "item", null, null, null, "note", 4))
        val archive = File(context.cacheDir, "roundtrip-${System.nanoTime()}.zip")

        val exported = manager.export(Uri.fromFile(archive))
        assertEquals(BackupCounts(1,1,1,1,1,1), exported.manifest.counts)
        val validated = manager.validate(Uri.fromFile(archive))
        val active = database.openHelper.writableDatabase
        active.beginTransaction()
        try {
            listOf("draft_records","catalog_updates","drink_records","catalog_items","brands","image_assets").forEach { active.execSQL("DELETE FROM $it") }
            active.setTransactionSuccessful()
        } finally { active.endTransaction() }
        imageFile.delete()

        manager.restore(validated)

        assertNotNull(database.brandDao().get("brand"))
        assertNotNull(database.catalogItemDao().get("item"))
        assertNotNull(database.drinkDao().get("record"))
        assertNotNull(database.draftDao().get("current"))
        val restored = database.imageAssetDao().get("logo")!!
        assertTrue(File(restored.localPath).isFile)
        assertEquals(File(context.filesDir, "images").canonicalFile, File(restored.localPath).canonicalFile.parentFile)
        assertEquals(1, count("catalog_updates"))
    }

    @Test fun `corrupt checksum validation leaves active rows unchanged`() = runBlocking {
        database.brandDao().upsert(BrandEntity("keep", "CHAIN", "保留", "保留", null, "MANUAL_ONLY", null))
        val root = createTempDirectory("invalid-backup-").toFile()
        val db = File(root, "database.sqlite").apply { writeBytes("SQLite format 3\u0000bad".toByteArray()) }
        val archive = File(root, "bad.zip")
        SafeBackupArchiveCodec().encode(archive, db, emptyList(), BackupCounts(0,0,0,0,0,0), 1, 1)
        val bytes = archive.readBytes(); bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 1).toByte(); archive.writeBytes(bytes)
        assertThrows(Exception::class.java) { runBlocking { manager.validate(Uri.fromFile(archive)) } }
        assertNotNull(database.brandDao().get("keep"))
        root.deleteRecursively()
        Unit
    }

    @Test fun `invalid sqlite validation leaves active rows unchanged`() = runBlocking {
        database.brandDao().upsert(BrandEntity("keep", "CHAIN", "保留", "保留", null, "MANUAL_ONLY", null))
        val root = createTempDirectory("invalid-sqlite-").toFile()
        try {
            val fake = File(root, "database.sqlite").apply { writeBytes("SQLite format 3\u0000not-a-database".toByteArray()) }
            val archive = File(root, "invalid.zip")
            SafeBackupArchiveCodec().encode(archive, fake, emptyList(), BackupCounts(0,0,0,0,0,0), 1, 1)
            assertThrows(BackupValidationException::class.java) { runBlocking { manager.validate(Uri.fromFile(archive)) } }
            assertNotNull(database.brandDao().get("keep"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `restore failure rolls back every active row`() = runBlocking {
        database.brandDao().upsert(BrandEntity("keep", "CHAIN", "保留", "保留", null, "MANUAL_ONLY", null))
        val source = Room.databaseBuilder(context, CoffeeDatabase::class.java, "backup-source-${System.nanoTime()}.db").allowMainThreadQueries().build()
        try {
            source.brandDao().upsert(BrandEntity("incoming", "CHAIN", "导入", "导入", null, "MANUAL_ONLY", null))
            val archive = File(context.cacheDir, "rollback-${System.nanoTime()}.zip")
            LocalBackupManager(context, source).export(Uri.fromFile(archive))
            val validated = manager.validate(Uri.fromFile(archive))
            val failing = LocalBackupManager(context, database, beforeRestoreCommit = { error("injected") })

            assertThrows(IllegalStateException::class.java) { runBlocking { failing.restore(validated) } }

            assertNotNull(database.brandDao().get("keep"))
            assertNull(database.brandDao().get("incoming"))
        } finally { source.close() }
    }

    @Test fun `restore cancellation rolls back and removes validation temp`() = runBlocking {
        database.brandDao().upsert(BrandEntity("keep", "CHAIN", "保留", "保留", null, "MANUAL_ONLY", null))
        val source = Room.databaseBuilder(context, CoffeeDatabase::class.java, "cancel-source-${System.nanoTime()}.db").allowMainThreadQueries().build()
        try {
            source.brandDao().upsert(BrandEntity("incoming", "CHAIN", "导入", "导入", null, "MANUAL_ONLY", null))
            val archive = File(context.cacheDir, "cancel-${System.nanoTime()}.zip")
            LocalBackupManager(context, source).export(Uri.fromFile(archive))
            val validated = manager.validate(Uri.fromFile(archive))
            val root = validated.root
            val entered = CompletableDeferred<Unit>()
            val cancelling = LocalBackupManager(context, database, beforeRestoreCommit = { entered.complete(Unit); awaitCancellation() })
            val job = launch { cancelling.restore(validated) }
            entered.await()
            job.cancelAndJoin()

            assertNotNull(database.brandDao().get("keep"))
            assertNull(database.brandDao().get("incoming"))
            assertFalse(root.exists())
        } finally { source.close() }
    }

    @Test fun `restore refuses a corrupted existing content addressed image`() = runBlocking {
        val imageDir = File(context.filesDir, "images").apply { mkdirs() }
        val sourceFile = File(imageDir, "collision-source.webp")
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap -> sourceFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP, 90, it) }; bitmap.recycle() }
        val hash = sha256(sourceFile)
        database.imageAssetDao().upsert(ImageAssetEntity("asset", sourceFile.absolutePath, hash, "PRODUCT", 1))
        val archive = File(context.cacheDir, "collision-${System.nanoTime()}.zip")
        manager.export(Uri.fromFile(archive))
        val validated = manager.validate(Uri.fromFile(archive))
        val extension = validated.manifest.images.single().entry.substringAfterLast('.')
        val collision = File(imageDir, "$hash.$extension").apply { writeText("corrupt") }

        assertThrows(BackupValidationException::class.java) { runBlocking { manager.restore(validated) } }

        assertNotNull(database.imageAssetDao().get("asset"))
        assertTrue(sourceFile.isFile)
        collision.delete()
        Unit
    }

    @Test fun `operation sweeps stale staging but preserves an active validation lease`() = runBlocking {
        val archive = File(context.cacheDir, "lease-${System.nanoTime()}.zip")
        manager.export(Uri.fromFile(archive))
        val validated = manager.validate(Uri.fromFile(archive))
        val stale = File(context.cacheDir, "backup-staging/stale-abandoned").apply { mkdirs(); File(this, "x").writeText("x") }

        manager.export(Uri.fromFile(File(context.cacheDir, "lease-second-${System.nanoTime()}.zip")))

        assertFalse(stale.exists())
        assertTrue(validated.root.exists())
        manager.discard(validated)
    }

    @Test fun `restore rejects a database changed after validation before any write`() = runBlocking {
        database.brandDao().upsert(BrandEntity("keep", "CHAIN", "保留", "保留", null, "MANUAL_ONLY", null))
        val archive = File(context.cacheDir, "tamper-db-${System.nanoTime()}.zip")
        manager.export(Uri.fromFile(archive))
        val validated = manager.validate(Uri.fromFile(archive))
        validated.decoded.databaseFile.appendBytes(byteArrayOf(0))

        assertThrows(BackupValidationException::class.java) { runBlocking { manager.restore(validated) } }

        assertNotNull(database.brandDao().get("keep"))
    }

    @Test fun `restore rejects a valid image changed after validation before any write`() = runBlocking {
        val imageDir = File(context.filesDir, "images").apply { mkdirs() }
        val sourceFile = File(imageDir, "original.webp")
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap -> bitmap.eraseColor(0xFF000000.toInt()); sourceFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP, 90, it) }; bitmap.recycle() }
        database.imageAssetDao().upsert(ImageAssetEntity("asset", sourceFile.absolutePath, sha256(sourceFile), "PRODUCT", 1))
        val archive = File(context.cacheDir, "tamper-image-${System.nanoTime()}.zip")
        manager.export(Uri.fromFile(archive))
        val validated = manager.validate(Uri.fromFile(archive))
        val staged = validated.decoded.images.single().file
        Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).also { bitmap -> bitmap.eraseColor(0xFFFFFFFF.toInt()); staged.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP, 90, it) }; bitmap.recycle() }

        assertThrows(BackupValidationException::class.java) { runBlocking { manager.restore(validated) } }

        assertNotNull(database.imageAssetDao().get("asset"))
    }

    @Test fun `failed export deletes or truncates the SAF target`() = runBlocking {
        database.imageAssetDao().upsert(ImageAssetEntity("missing", File(context.filesDir, "images/missing.webp").absolutePath, "0".repeat(64), "PRODUCT", 1))
        val target = File(context.cacheDir, "failed-export-${System.nanoTime()}.zip").apply { writeText("private old content") }

        assertThrows(BackupValidationException::class.java) { runBlocking { manager.export(Uri.fromFile(target)) } }

        assertTrue(!target.exists() || target.length() == 0L)
    }

    @Test fun `cancelling during the production row copy loop rolls back`() = runBlocking {
        database.brandDao().upsert(BrandEntity("keep", "CHAIN", "保留", "保留", null, "MANUAL_ONLY", null))
        val source = Room.databaseBuilder(context, CoffeeDatabase::class.java, "loop-source-${System.nanoTime()}.db").allowMainThreadQueries().build()
        try {
            source.brandDao().upsert(BrandEntity("incoming", "CHAIN", "导入", "导入", null, "MANUAL_ONLY", null))
            repeat(300) { index -> source.catalogUpdateDao().insert(CatalogUpdateEntity("u$index", "incoming", index.toLong(), "CONFIRMED", null, null)) }
            val archive = File(context.cacheDir, "loop-${System.nanoTime()}.zip")
            LocalBackupManager(context, source).export(Uri.fromFile(archive))
            val validated = manager.validate(Uri.fromFile(archive))
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val rows = AtomicInteger()
            val cancelling = LocalBackupManager(context, database, onRestoreRowCopied = {
                if (rows.incrementAndGet() == 30) { entered.complete(Unit); release.await() }
            })
            val job = launch(Dispatchers.IO) { cancelling.restore(validated) }
            entered.await()
            job.cancel()
            release.countDown()
            job.join()

            assertTrue(job.isCancelled)
            assertNotNull(database.brandDao().get("keep"))
            assertNull(database.brandDao().get("incoming"))
            assertFalse(validated.root.exists())
        } finally { source.close() }
    }

    @Test fun `cancellation after commit still returns success to the caller`() = runBlocking {
        val archive = File(context.cacheDir, "commit-boundary-${System.nanoTime()}.zip")
        manager.export(Uri.fromFile(archive))
        val validated = manager.validate(Uri.fromFile(archive))
        val displayedSuccess = AtomicBoolean(false)

        val job = launch {
            val operationJob = coroutineContext.job
            LocalBackupManager(context, database, afterRestoreCommitted = { operationJob.cancel() }).restore(validated)
            displayedSuccess.set(true)
        }
        job.join()

        assertTrue(displayedSuccess.get())
    }

    @Test fun `discard completes when its composition cleanup scope is cancelled`() = runBlocking {
        val archive = File(context.cacheDir, "discard-scope-${System.nanoTime()}.zip")
        manager.export(Uri.fromFile(archive))
        val validated = manager.validate(Uri.fromFile(archive))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { manager.discard(validated) }
        scope.cancel()
        job.join()

        assertFalse(validated.root.exists())
    }

    private fun count(table:String):Int=database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use{it.moveToFirst();it.getInt(0)}
    private fun sha256(file:File):String{val d=MessageDigest.getInstance("SHA-256");file.inputStream().use{i->val b=ByteArray(1024);while(true){val n=i.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
}
