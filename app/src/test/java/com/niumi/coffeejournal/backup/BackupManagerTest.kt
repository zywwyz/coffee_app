package com.niumi.coffeejournal.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import kotlinx.coroutines.delay
import com.niumi.coffeejournal.core.image.LocalImageStore
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
        val item = CatalogItemEntity("item", "brand", "CHAIN_PRODUCT", "拿铁", "拿铁", "logo", status = "ACTIVE", chainProductKind = "MILK")
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

    @Test fun `schema validation rejects every untrusted schema variant before writing active data`() = runBlocking {
        val variants = listOf<Pair<String, SQLiteDatabase.() -> Unit>>(
            "extra table" to { execSQL("CREATE TABLE attacker (id TEXT)") },
            "extra column" to { execSQL("ALTER TABLE brands ADD COLUMN attacker TEXT") },
            "missing column" to { execSQL("ALTER TABLE drink_records DROP COLUMN revision") },
            "altered type" to { rebuildUpdates("id INTEGER NOT NULL PRIMARY KEY, brandId TEXT NOT NULL, fetchedAtEpochMillis INTEGER NOT NULL, status TEXT NOT NULL, sourceUrl TEXT, errorMessage TEXT, FOREIGN KEY(brandId) REFERENCES brands(id) ON UPDATE CASCADE ON DELETE RESTRICT") },
            "altered not null default" to { rebuildUpdates("id TEXT NOT NULL PRIMARY KEY, brandId TEXT NOT NULL DEFAULT 'x', fetchedAtEpochMillis INTEGER NOT NULL, status TEXT NOT NULL, sourceUrl TEXT, errorMessage TEXT, FOREIGN KEY(brandId) REFERENCES brands(id) ON UPDATE CASCADE ON DELETE RESTRICT") },
            "primary key" to { rebuildUpdates("id TEXT NOT NULL, brandId TEXT NOT NULL, fetchedAtEpochMillis INTEGER NOT NULL, status TEXT NOT NULL, sourceUrl TEXT, errorMessage TEXT, FOREIGN KEY(brandId) REFERENCES brands(id) ON UPDATE CASCADE ON DELETE RESTRICT") },
            "foreign key" to { rebuildUpdates("id TEXT NOT NULL PRIMARY KEY, brandId TEXT NOT NULL, fetchedAtEpochMillis INTEGER NOT NULL, status TEXT NOT NULL, sourceUrl TEXT, errorMessage TEXT") },
            "index" to { execSQL("DROP INDEX index_brands_type_normalizedName") },
            "room identity" to { execSQL("UPDATE room_master_table SET identity_hash='attacker' WHERE id=42") },
        )
        variants.forEach { (name, mutate) -> assertRejectedWithoutWrites(name, mutate = mutate) }
    }

    @Test fun `schema validation rejects manifest user version mismatch before writing active data`() = runBlocking {
        assertRejectedWithoutWrites("manifest version", manifestVersion = 1, mutate = { })
    }

    @Test fun `v4 domain-invalid catalog kinds are rejected before active writes`() = runBlocking {
        listOf(
            "other" to "INSERT INTO catalog_items (id,brandId,type,name,normalizedName,status,chainProductKind) VALUES ('item-other','brand-other','CHAIN_PRODUCT','产品','产品','ACTIVE','OTHER')",
            "chain-null" to "INSERT INTO catalog_items (id,brandId,type,name,normalizedName,status,chainProductKind) VALUES ('item-null','brand-chain-null','CHAIN_PRODUCT','产品','产品','ACTIVE',NULL)",
            "bean-kind" to "INSERT INTO catalog_items (id,brandId,type,name,normalizedName,status,chainProductKind) VALUES ('item-bean','brand-bean-kind','PERSONAL_BEAN','豆子','豆子','ACTIVE','MILK')",
        ).forEach { (name, insert) ->
            val guard = File(context.filesDir, "images/domain-guard-$name.bin").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, name.length.toByte())) }
            val guardBytes = guard.readBytes()
            assertRejectedWithoutWrites(
                name = name,
                manifestCounts = BackupCounts(1, 1, 0, 0, 0, 0),
                expectedMessage = "产品分类字段无效",
            ) {
                val brandId = when (name) { "other" -> "brand-other"; "chain-null" -> "brand-chain-null"; else -> "brand-bean-kind" }
                execSQL("INSERT INTO brands (id,type,name,normalizedName,maintenanceMode) VALUES ('$brandId','CHAIN','$brandId','$brandId','MANUAL_ONLY')")
                execSQL(insert)
            }
            assertArrayEquals(guardBytes, guard.readBytes())
        }
    }

    @Test fun `v3 catalog column cannot be smuggled through a declared v2 backup`() = runBlocking {
        listOf("OTHER", null, "BLACK").forEachIndexed { index, kind ->
            val name = "v2-smuggle-$index"
            val guard = File(context.filesDir, "images/$name.bin").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(index.toByte(), 9, 8)) }
            val bytes = guard.readBytes()
            assertRejectedWithoutWrites(
                name = name,
                manifestVersion = 2,
                manifestCounts = BackupCounts(1, 1, 0, 0, 0, 0),
                expectedMessage = "数据库 catalog_items columns 不匹配",
            ) {
                execSQL("PRAGMA user_version=2")
                execSQL("UPDATE room_master_table SET identity_hash='e34586f75354c95386a2ba92f7121b27' WHERE id=42")
                execSQL("INSERT INTO brands (id,type,name,normalizedName,maintenanceMode) VALUES ('brand-$index','CHAIN','brand-$index','brand-$index','MANUAL_ONLY')")
                execSQL("INSERT INTO catalog_items (id,brandId,type,name,normalizedName,status,chainProductKind) VALUES ('item-$index','brand-$index','CHAIN_PRODUCT','产品','产品','ACTIVE',?)", arrayOf(kind))
            }
            assertArrayEquals(bytes, guard.readBytes())
        }
    }

    @Test fun `valid schema v1 archive validates and restores into the current database`() = runBlocking {
        val imageDir = File(context.filesDir, "images").apply { mkdirs() }
        val logoFile = writeTestPng(File(imageDir, "v1-logo.png"), 0xFF673AB7.toInt())
        val snapshotFile = writeTestPng(File(imageDir, "v1-snapshot.png"), 0xFF009688.toInt())
        val sourceName = "v1-source-${System.nanoTime()}.db"
        val source = Room.databaseBuilder(context, CoffeeDatabase::class.java, sourceName).allowMainThreadQueries().build()
        val occurredAt = 1_700_000_000_000L
        val draftUpdatedAt = occurredAt + 500L
        try {
            source.imageAssetDao().upsert(ImageAssetEntity("v1-logo", logoFile.absolutePath, sha256(logoFile), "BRAND_LOGO", occurredAt - 2))
            source.imageAssetDao().upsert(ImageAssetEntity("v1-snapshot", snapshotFile.absolutePath, sha256(snapshotFile), "RECORD_SNAPSHOT", occurredAt - 1))
            source.brandDao().upsert(BrandEntity("v1-brand", "CHAIN", "旧版品牌", "旧版品牌", "v1-logo", "MANUAL_ONLY", "https://example.test/brand"))
            source.catalogItemDao().upsert(CatalogItemEntity("v1-item", "v1-brand", "CHAIN_PRODUCT", "旧版拿铁", "旧版拿铁", "v1-snapshot", status = "ACTIVE", chainProductKind = "MILK"))
            source.drinkDao().insert(DrinkRecordEntity(
                id = "v1-record", occurredAtEpochMillis = occurredAt, localDate = "2023-11-14", itemType = "CHAIN_PRODUCT", sourceItemId = "v1-item",
                note = "来自 v1", snapshotBrandName = "旧版品牌", snapshotItemName = "旧版拿铁",
                snapshotImageAssetId = "v1-snapshot", snapshotBrandLogoAssetId = "v1-logo",
                createdAtEpochMillis = occurredAt - 99, updatedAtEpochMillis = occurredAt - 1, revision = 8,
            ))
            source.catalogUpdateDao().insert(CatalogUpdateEntity("v1-update", "v1-brand", occurredAt + 1, "CONFIRMED", "https://example.test/feed", null))
            source.draftDao().upsert(DraftRecordEntity(
                "v1-draft", "v1-revision", "CHAIN_PRODUCT", "v1-item", null, 8, 1999, "未完成草稿", draftUpdatedAt,
                consumedAtEpochMillis = draftUpdatedAt + 99, editingRecordId = "v1-record", expectedRecordRevision = 8,
            ))
            source.close()

            val sourceFile = context.getDatabasePath(sourceName)
            SQLiteDatabase.openDatabase(sourceFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { v1 ->
                v1.rebuildAsSchemaV1()
            }
            val archive = File(context.cacheDir, "schema-v1-${System.nanoTime()}.zip")
            SafeBackupArchiveCodec().encode(
                target = archive,
                database = sourceFile,
                images = listOf(
                    BackupImage("v1-logo", "images/${sha256(logoFile)}.png", logoFile, "BRAND_LOGO"),
                    BackupImage("v1-snapshot", "images/${sha256(snapshotFile)}.png", snapshotFile, "RECORD_SNAPSHOT"),
                ),
                counts = BackupCounts(1, 1, 1, 2, 1, 1),
                schemaVersion = 1,
                exportedAtEpochMillis = occurredAt,
            )

            val validated = manager.validate(Uri.fromFile(archive))
            assertEquals(1, validated.manifest.schemaVersion)
            manager.restore(validated)

            assertEquals(1, count("brands"))
            assertEquals(1, count("catalog_items"))
            assertEquals(1, count("drink_records"))
            assertEquals(2, count("image_assets"))
            assertEquals(1, count("catalog_updates"))
            assertEquals(1, count("draft_records"))
            assertEquals("旧版品牌", database.brandDao().get("v1-brand")!!.name)
            assertEquals("v1-snapshot", database.catalogItemDao().get("v1-item")!!.imageAssetId)
            assertEquals("MILK", database.catalogItemDao().get("v1-item")!!.chainProductKind)
            database.drinkDao().get("v1-record")!!.also { record ->
                assertEquals(occurredAt, record.createdAtEpochMillis)
                assertEquals(occurredAt, record.updatedAtEpochMillis)
                assertEquals(0, record.revision)
                assertEquals("v1-snapshot", record.snapshotImageAssetId)
                assertEquals("v1-logo", record.snapshotBrandLogoAssetId)
            }
            database.draftDao().get("v1-draft")!!.also { draft ->
                assertEquals(draftUpdatedAt, draft.consumedAtEpochMillis)
                assertNull(draft.editingRecordId)
                assertNull(draft.expectedRecordRevision)
                assertEquals("未完成草稿", draft.note)
            }
            assertEquals("CONFIRMED", database.openHelper.writableDatabase.query("SELECT status FROM catalog_updates WHERE id='v1-update'").use { it.moveToFirst(); it.getString(0) })
            listOf("v1-logo", "v1-snapshot").forEach { assetId ->
                val asset = database.imageAssetDao().get(assetId)!!
                assertTrue(File(asset.localPath).isFile)
                assertEquals(asset.sha256, sha256(File(asset.localPath)))
            }
        } finally {
            if (source.isOpen) source.close()
            context.deleteDatabase(sourceName)
        }
    }

    @Test fun `valid v2 archive restores every legacy chain category into v3 kinds`() = runBlocking {
        val sourceName = "v2-source-${System.nanoTime()}.db"
        val source = Room.databaseBuilder(context, CoffeeDatabase::class.java, sourceName).allowMainThreadQueries().build()
        try {
            source.brandDao().upsert(BrandEntity("v2-brand", "CHAIN", "旧品牌", "旧品牌", null, "MANUAL_ONLY", null))
            listOf("fruit" to "柠檬气泡美式", "milk" to "生椰拿铁", "black" to "冰美式", "pending" to "季节限定").forEach { (id, name) ->
                source.catalogItemDao().upsert(CatalogItemEntity(id, "v2-brand", "CHAIN_PRODUCT", name, name, status = "ACTIVE", chainProductKind = "PENDING"))
            }
            source.close()
            val sourceFile = context.getDatabasePath(sourceName)
            SQLiteDatabase.openDatabase(sourceFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { it.rebuildAsSchemaV2() }
            val archive = File(context.cacheDir, "schema-v2-${System.nanoTime()}.zip")
            SafeBackupArchiveCodec().encode(archive, sourceFile, emptyList(), BackupCounts(1, 4, 0, 0, 0, 0), 2, 1)

            manager.restore(manager.validate(Uri.fromFile(archive)))

            assertEquals("FRUIT", database.catalogItemDao().get("fruit")!!.chainProductKind)
            assertEquals("MILK", database.catalogItemDao().get("milk")!!.chainProductKind)
            assertEquals("BLACK", database.catalogItemDao().get("black")!!.chainProductKind)
            assertEquals("PENDING", database.catalogItemDao().get("pending")!!.chainProductKind)
        } finally {
            if (source.isOpen) source.close()
            context.deleteDatabase(sourceName)
        }
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

    @Test fun `restore serializes its image and database mutation with ordinary image deletion`() = runBlocking {
        val imageDir = File(context.filesDir, "images").apply { mkdirs() }
        val file = File(imageDir, "shared.webp")
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP, 90, it) }; bitmap.recycle() }
        database.imageAssetDao().upsert(ImageAssetEntity("shared", file.absolutePath, sha256(file), "PRODUCT", 1))
        val archive = File(context.cacheDir, "shared-lock-${System.nanoTime()}.zip")
        manager.export(Uri.fromFile(archive))
        val validated = manager.validate(Uri.fromFile(archive))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val restoring = LocalBackupManager(context, database, afterRestoreImageLockAcquired = { entered.countDown(); release.await() })
        val restoreJob = launch(Dispatchers.IO) { restoring.restore(validated) }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val deleteJob = launch(Dispatchers.IO) { LocalImageStore(context, database.imageAssetDao()).deleteIfUnreferenced("shared") }

        delay(100)
        assertFalse(deleteJob.isCompleted)
        release.countDown()
        restoreJob.join()
        deleteJob.join()

        assertFalse(restoreJob.isCancelled)
    }

    private fun count(table:String):Int=database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use{it.moveToFirst();it.getInt(0)}
    private suspend fun assertRejectedWithoutWrites(
        name: String,
        manifestVersion: Int = 4,
        manifestCounts: BackupCounts? = null,
        expectedMessage: String? = null,
        mutate: SQLiteDatabase.() -> Unit,
    ) {
        database.brandDao().upsert(BrandEntity("keep-$name", "CHAIN", "保留", "保留-$name", null, "MANUAL_ONLY", null))
        val activeCounts = listOf("brands", "catalog_items", "drink_records", "image_assets", "catalog_updates", "draft_records").associateWith(::count)
        val imageBytes = File(context.filesDir, "images").listFiles().orEmpty().associate { it.name to it.readBytes() }
        val source = Room.databaseBuilder(context, CoffeeDatabase::class.java, "schema-source-${System.nanoTime()}.db").allowMainThreadQueries().build()
        try {
            val archive = File(context.cacheDir, "schema-$name-${System.nanoTime()}.zip")
            LocalBackupManager(context, source).export(Uri.fromFile(archive))
            val decoded = SafeBackupArchiveCodec().decode(archive, File(context.cacheDir, "schema-unpack-${System.nanoTime()}"))
            SQLiteDatabase.openDatabase(decoded.databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(mutate)
            val rewritten = File(context.cacheDir, "schema-rewritten-${System.nanoTime()}.zip")
            SafeBackupArchiveCodec().encode(rewritten, decoded.databaseFile, emptyList(), manifestCounts ?: decoded.manifest.counts, manifestVersion, 1)
            val error = assertThrows(BackupValidationException::class.java) { runBlocking { manager.validate(Uri.fromFile(rewritten)) } }
            expectedMessage?.let { assertEquals(it, error.message) }
            assertNotNull(database.brandDao().get("keep-$name"))
            assertEquals(activeCounts, listOf("brands", "catalog_items", "drink_records", "image_assets", "catalog_updates", "draft_records").associateWith(::count))
            assertEquals(imageBytes.keys, File(context.filesDir, "images").listFiles().orEmpty().map { it.name }.toSet())
            imageBytes.forEach { (file, bytes) -> assertArrayEquals(bytes, File(context.filesDir, "images/$file").readBytes()) }
        } finally { source.close() }
    }
    private fun SQLiteDatabase.rebuildUpdates(columns: String) {
        execSQL("ALTER TABLE catalog_updates RENAME TO updates_old")
        execSQL("CREATE TABLE catalog_updates ($columns)")
        execSQL("DROP TABLE updates_old")
    }
    private fun SQLiteDatabase.rebuildAsSchemaV1() {
        execSQL("PRAGMA foreign_keys=OFF")
        execSQL("ALTER TABLE drink_records RENAME TO drink_records_v2")
        // Copied from app/schemas/.../CoffeeDatabase/1.json to make this a real v1 schema.
        execSQL("CREATE TABLE drink_records (id TEXT NOT NULL, occurredAtEpochMillis INTEGER NOT NULL, localDate TEXT NOT NULL, itemType TEXT NOT NULL, sourceItemId TEXT NOT NULL, brewMethod TEXT, ratingHalfStars INTEGER, actualPriceFen INTEGER, note TEXT, snapshotBrandName TEXT NOT NULL, snapshotItemName TEXT NOT NULL, snapshotOrigin TEXT, snapshotProcessing TEXT, snapshotImageAssetId TEXT, snapshotBrandLogoAssetId TEXT, snapshotRoastLevel TEXT, snapshotFlavorNotes TEXT, PRIMARY KEY(id), FOREIGN KEY(snapshotImageAssetId) REFERENCES image_assets(id) ON UPDATE CASCADE ON DELETE RESTRICT, FOREIGN KEY(snapshotBrandLogoAssetId) REFERENCES image_assets(id) ON UPDATE CASCADE ON DELETE RESTRICT)")
        execSQL("INSERT INTO drink_records (id,occurredAtEpochMillis,localDate,itemType,sourceItemId,brewMethod,ratingHalfStars,actualPriceFen,note,snapshotBrandName,snapshotItemName,snapshotOrigin,snapshotProcessing,snapshotImageAssetId,snapshotBrandLogoAssetId,snapshotRoastLevel,snapshotFlavorNotes) SELECT id,occurredAtEpochMillis,localDate,itemType,sourceItemId,brewMethod,ratingHalfStars,actualPriceFen,note,snapshotBrandName,snapshotItemName,snapshotOrigin,snapshotProcessing,snapshotImageAssetId,snapshotBrandLogoAssetId,snapshotRoastLevel,snapshotFlavorNotes FROM drink_records_v2")
        execSQL("DROP TABLE drink_records_v2")
        execSQL("CREATE INDEX index_drink_records_localDate_occurredAtEpochMillis ON drink_records(localDate, occurredAtEpochMillis)")
        execSQL("CREATE INDEX index_drink_records_snapshotImageAssetId ON drink_records(snapshotImageAssetId)")
        execSQL("CREATE INDEX index_drink_records_snapshotBrandLogoAssetId ON drink_records(snapshotBrandLogoAssetId)")
        execSQL("ALTER TABLE draft_records RENAME TO draft_records_v2")
        execSQL("CREATE TABLE draft_records (id TEXT NOT NULL, revisionId TEXT NOT NULL, itemType TEXT, sourceItemId TEXT, brewMethod TEXT, ratingHalfStars INTEGER, actualPriceFen INTEGER, note TEXT NOT NULL, updatedAtEpochMillis INTEGER NOT NULL, PRIMARY KEY(id))")
        execSQL("INSERT INTO draft_records (id,revisionId,itemType,sourceItemId,brewMethod,ratingHalfStars,actualPriceFen,note,updatedAtEpochMillis) SELECT id,revisionId,itemType,sourceItemId,brewMethod,ratingHalfStars,actualPriceFen,note,updatedAtEpochMillis FROM draft_records_v2")
        execSQL("DROP TABLE draft_records_v2")
        execSQL("ALTER TABLE catalog_items DROP COLUMN chainProductKind")
        execSQL("UPDATE room_master_table SET identity_hash='630300b58f2f33802ecc0d756158b804' WHERE id=42")
        execSQL("PRAGMA user_version=1")
        execSQL("PRAGMA foreign_keys=ON")
    }
    private fun SQLiteDatabase.rebuildAsSchemaV2() {
        execSQL("ALTER TABLE catalog_items DROP COLUMN chainProductKind")
        execSQL("ALTER TABLE drink_records DROP COLUMN snapshotCoffeeType")
        execSQL("UPDATE room_master_table SET identity_hash='e34586f75354c95386a2ba92f7121b27' WHERE id=42")
        execSQL("PRAGMA user_version=2")
    }
    private fun writeTestPng(file: File, color: Int): File {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return file
    }
    private fun sha256(file:File):String{val d=MessageDigest.getInstance("SHA-256");file.inputStream().use{i->val b=ByteArray(1024);while(true){val n=i.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
}
