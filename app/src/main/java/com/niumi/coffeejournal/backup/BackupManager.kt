package com.niumi.coffeejournal.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.image.ImageMutationCoordinator
import com.niumi.coffeejournal.core.model.legacyChainProductKind
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

data class BackupSummary(val manifest: BackupManifest)
data class RestoreSummary(val counts: BackupCounts)

class ValidatedBackup internal constructor(
    internal val root: File,
    internal val decoded: DecodedBackup,
) {
    val manifest: BackupManifest get() = decoded.manifest
}

interface BackupManager {
    suspend fun export(target: Uri): BackupSummary
    suspend fun validate(source: Uri): ValidatedBackup
    suspend fun restore(backup: ValidatedBackup): RestoreSummary
    suspend fun discard(backup: ValidatedBackup)
}

class LocalBackupManager(
    context: Context,
    private val database: CoffeeDatabase,
    private val codec: SafeBackupArchiveCodec = SafeBackupArchiveCodec(),
    private val now: () -> Long = System::currentTimeMillis,
    private val beforeRestoreCommit: suspend () -> Unit = {},
    private val onRestoreRowCopied: () -> Unit = {},
    private val afterRestoreCommitted: () -> Unit = {},
    private val afterRestoreImageLockAcquired: () -> Unit = {},
) : BackupManager {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val tempRoot = File(appContext.cacheDir, "backup-staging")
    private val imageRoot = File(appContext.filesDir, "images")

    override suspend fun export(target: Uri): BackupSummary {
        try {
            return withContext(Dispatchers.IO) {
                val operationContext = coroutineContext
                val check = { operationContext.ensureActive() }
                MUTEX.withLock {
                    cleanupStaleStaging()
                    val root = newTemp("export")
                    try {
                        val snapshot = buildSnapshot(root, check)
                        val archive = File(root, "coffee-journal.zip")
                        val manifest = codec.encode(
                            target = archive, database = snapshot.database, images = snapshot.images,
                            counts = snapshot.counts, schemaVersion = CoffeeDatabaseSchema.CURRENT, exportedAtEpochMillis = now(),
                            checkCancelled = check,
                        )
                        val verificationRoot = File(root, "verification")
                        val verifiedArchive = codec.decode(archive, verificationRoot, check)
                        codec.verifyDecoded(verifiedArchive, check)
                        validateDatabase(verifiedArchive, check)
                        verificationRoot.deleteRecursively()
                        resolver.openOutputStream(target, "w")?.use { output -> FileInputStream(archive).use { copyBounded(it, output, MAX_ARCHIVE_BYTES, check) } }
                            ?: throw BackupValidationException("无法写入所选文件")
                        BackupSummary(manifest)
                    } finally { withContext(NonCancellable) { root.deleteRecursively() } }
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { cleanupFailedTarget(target, error) }
            throw error
        }
    }

    override suspend fun validate(source: Uri): ValidatedBackup {
      var leasedRoot: File? = null
      try {
       return withContext(Dispatchers.IO) {
        val operationContext = coroutineContext; val check = { operationContext.ensureActive() }
        MUTEX.withLock {
            cleanupStaleStaging()
            val root = newTemp("validate")
            try {
                val archive = File(root, "source.zip")
                resolver.openInputStream(source)?.use { input -> FileOutputStream(archive).use { output -> copyBounded(input, output, MAX_ARCHIVE_BYTES, check) } }
                    ?: throw BackupValidationException("无法读取所选文件")
                val decoded = codec.decode(archive, File(root, "unpacked"), check)
                validateDatabase(decoded, check)
                val validated = ValidatedBackup(root, decoded)
                ACTIVE_STAGING += root.canonicalPath
                leasedRoot = root
                validated
            } catch (error: Throwable) {
                withContext(NonCancellable) { root.deleteRecursively() }
                throw error
            }
        }
       }
      } catch (error: Throwable) {
        leasedRoot?.let { root -> withContext(NonCancellable + Dispatchers.IO) { MUTEX.withLock { ACTIVE_STAGING -= root.canonicalPath; root.deleteRecursively() } } }
        throw error
      }
    }

    override suspend fun restore(backup: ValidatedBackup): RestoreSummary {
        var committedResult: RestoreSummary? = null
        try {
            return withContext(Dispatchers.IO) {
              val operationContext = coroutineContext
              val check = { operationContext.ensureActive() }
              MUTEX.withLock {
                cleanupStaleStaging()
                ImageMutationCoordinator.mutex.withLock {
            afterRestoreImageLockAcquired()
            val stagingPrefix = tempRoot.canonicalPath + File.separator
            if (!backup.root.canonicalPath.startsWith(stagingPrefix) || backup.root.canonicalPath !in ACTIVE_STAGING) throw BackupValidationException("备份验证令牌无效")
            val created = mutableListOf<File>()
            var committed = false
            try {
                codec.verifyDecoded(backup.decoded, check)
                validateDatabase(backup.decoded, check)
                check()
                imageRoot.mkdirs()
                val localPaths = backup.decoded.images.associate { image ->
                    check()
                    val extension = image.entry.substringAfterLast('.')
                    val expectedHash = sha256(image.file, check)
                    val target = File(imageRoot, "$expectedHash.$extension")
                    if (target.exists()) {
                        if (!target.isFile || sha256(target, check) != expectedHash || !decodeable(target)) {
                            throw BackupValidationException("本地同名图片已损坏")
                        }
                    } else {
                        val staged = File(imageRoot, ".restore-${UUID.randomUUID()}")
                        image.file.inputStream().use { input -> staged.outputStream().use { output -> copyBounded(input, output, MAX_IMAGE_BYTES, check) } }
                        if (sha256(staged, check) != expectedHash || !decodeable(staged)) { staged.delete(); throw BackupValidationException("图片复制校验失败") }
                        if (!staged.renameTo(target)) { staged.delete(); throw BackupValidationException("无法保存恢复图片") }
                        created += target
                    }
                    image.assetId to target.absolutePath
                }
                val oldPaths = activeImagePaths()
                withContext(NonCancellable) { database.withTransaction {
                    check()
                    val active = database.openHelper.writableDatabase
                    clearAll(active)
                    openBackupDatabase(backup.decoded.databaseFile).use { copyAll(it, active, backup.manifest.schemaVersion, localPaths, check, onRestoreRowCopied) }
                    validateCatalogDomains(active)
                    validateRestoredDrinkCoffeeTypes(active)
                    checkForeignKeys(active)
                    withContext(operationContext) { beforeRestoreCommit() }
                    check()
                } }
                committed = true
                committedResult = RestoreSummary(backup.manifest.counts)
                afterRestoreCommitted()
                withContext(NonCancellable) {
                    val retained = activeImagePaths().mapTo(HashSet()) { File(it).canonicalPath }
                    oldPaths.forEach { path -> runCatching { File(path).canonicalFile }.getOrNull()?.takeIf { it.parentFile == imageRoot.canonicalFile && it.canonicalPath !in retained }?.delete() }
                }
                checkNotNull(committedResult)
            } catch (error: Throwable) {
                if (!committed) withContext(NonCancellable) { created.forEach { it.delete() } }
                if (error is CancellationException) throw error
                throw error
            } finally {
                withContext(NonCancellable) { ACTIVE_STAGING -= backup.root.canonicalPath; backup.root.deleteRecursively() }
            }
                }
              }
            }
        } catch (cancelled: CancellationException) {
            committedResult?.let { return it }
            withContext(NonCancellable + Dispatchers.IO) { MUTEX.withLock { ACTIVE_STAGING -= backup.root.canonicalPath; backup.root.deleteRecursively() } }
            throw cancelled
        }
    }

    override suspend fun discard(backup: ValidatedBackup) = withContext(NonCancellable + Dispatchers.IO) {
        MUTEX.withLock { ACTIVE_STAGING -= backup.root.canonicalPath; backup.root.deleteRecursively(); Unit }
    }

    private suspend fun buildSnapshot(root: File, check: () -> Unit): Snapshot {
        val tempName = "snapshot-${UUID.randomUUID()}.db"
        val tempDb = Room.databaseBuilder(appContext, CoffeeDatabase::class.java, tempName).build()
        val tempFile = appContext.getDatabasePath(tempName)
        try {
            val images = mutableListOf<BackupImage>()
            val counts = database.withTransaction {
                val source = database.openHelper.writableDatabase
                val destination = tempDb.openHelper.writableDatabase
                destination.beginTransaction()
                try {
                    TABLES.forEach { check(); copyTable(source, destination, it, check) }
                    destination.setTransactionSuccessful()
                } finally { destination.endTransaction() }
                imageRows(source, check).forEach { row ->
                    check()
                    val file = managedImage(row.localPath)
                    if (!file.isFile || file.length() > MAX_IMAGE_BYTES || sha256(file, check) != row.sha256 || !decodeable(file)) throw BackupValidationException("图片资产 ${row.id} 缺失或损坏")
                    val extension = extensionFor(file)
                    images += BackupImage(row.id, "images/${row.sha256}.$extension", file, row.kind)
                }
                counts(source)
            }
            tempDb.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            tempDb.close()
            val snapshot = File(root, "database.sqlite")
            tempFile.copyTo(snapshot)
            if (!hasSqliteMagic(snapshot)) throw BackupValidationException("快照数据库无效")
            return Snapshot(snapshot, images, counts)
        } finally {
            if (tempDb.isOpen) tempDb.close()
            appContext.deleteDatabase(tempName)
        }
    }

    private fun validateDatabase(decoded: DecodedBackup, check: () -> Unit = {}) {
        try {
            check()
            openBackupDatabase(decoded.databaseFile).use {
                if (databaseVersion(it) != decoded.manifest.schemaVersion) {
                    throw BackupValidationException("数据库版本与清单不一致")
                }
                validateSchema(it, decoded.manifest.schemaVersion)
                singleResult(it, "PRAGMA integrity_check").takeIf { value -> value == "ok" } ?: throw BackupValidationException("数据库完整性检查失败")
                checkForeignKeys(it)
                val actual = counts(it)
                if (actual != decoded.manifest.counts) throw BackupValidationException("数据库计数与清单不一致")
                validateDomains(it, check)
                val rows = imageRows(it, check)
                if (rows.map { row -> row.id }.toSet() != decoded.images.map { image -> image.assetId }.toSet()) throw BackupValidationException("图片数据库行与清单不一致")
                val rowById = rows.associateBy { row -> row.id }
                val manifestById = decoded.manifest.images.associateBy { image -> image.assetId }
                decoded.images.forEach { image ->
                    check()
                    val row = rowById.getValue(image.assetId)
                    val manifest = manifestById.getValue(image.assetId)
                    if (row.sha256 != manifest.sha256 || row.kind != manifest.kind) throw BackupValidationException("图片元数据不一致: ${image.assetId}")
                    if (!decodeable(image.file)) throw BackupValidationException("图片无法解码: ${image.assetId}")
                }
            }
        } catch (error: BackupValidationException) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw BackupValidationException("SQLite 数据库无效", error)
        }
    }

    private fun openBackupDatabase(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    private fun checkForeignKeys(db: SupportSQLiteDatabase) { db.query("PRAGMA foreign_key_check").use { if (it.moveToFirst()) throw BackupValidationException("数据库外键检查失败") } }
    private fun checkForeignKeys(db: SQLiteDatabase) { db.rawQuery("PRAGMA foreign_key_check", null).use { if (it.moveToFirst()) throw BackupValidationException("数据库外键检查失败") } }
    private fun validateDomains(db: SQLiteDatabase, check: () -> Unit) {
        db.rawQuery("SELECT ratingHalfStars,actualPriceFen FROM drink_records UNION ALL SELECT ratingHalfStars,actualPriceFen FROM draft_records", null).use { c -> while(c.moveToNext()){ check(); if(!c.isNull(0) && c.getInt(0) !in 1..10) throw BackupValidationException("评分字段无效"); if(!c.isNull(1) && c.getLong(1)<0) throw BackupValidationException("金额字段无效") } }
        db.rawQuery("SELECT informationCompleteness FROM catalog_items", null).use { c -> while(c.moveToNext()) { check(); if(c.getInt(0) !in 0..100) throw BackupValidationException("完整度字段无效") } }
        if (exists(db, "SELECT 1 FROM brands WHERE id='' OR name='' OR normalizedName='' OR type NOT IN ('CHAIN','ROASTER') OR maintenanceMode NOT IN ('PUBLIC_SOURCE','MANUAL_ONLY') LIMIT 1")) throw BackupValidationException("品牌字段无效")
        if (exists(db, "SELECT 1 FROM catalog_items WHERE id='' OR brandId='' OR name='' OR normalizedName='' OR type NOT IN ('CHAIN_PRODUCT','PERSONAL_BEAN') OR status NOT IN ('ACTIVE','NEEDS_IMAGE','DISCONTINUED','ARCHIVED') LIMIT 1")) throw BackupValidationException("产品字段无效")
        if (exists(db, "SELECT 1 FROM drink_records WHERE id='' OR sourceItemId='' OR snapshotBrandName='' OR snapshotItemName='' OR itemType NOT IN ('CHAIN_PRODUCT','PERSONAL_BEAN') LIMIT 1")) throw BackupValidationException("记录字段无效")
        if (exists(db, "SELECT 1 FROM image_assets WHERE id='' OR sha256 GLOB '*[^0-9a-f]*' OR length(sha256)!=64 OR kind NOT IN ('PRODUCT','BRAND_LOGO','BEAN_PACKAGE','RECORD_SNAPSHOT') LIMIT 1")) throw BackupValidationException("图片资产字段无效")
        if (exists(db, "SELECT 1 FROM draft_records WHERE id='' OR revisionId='' OR (itemType IS NOT NULL AND itemType NOT IN ('CHAIN_PRODUCT','PERSONAL_BEAN')) LIMIT 1")) throw BackupValidationException("草稿字段无效")
        if (databaseVersion(db) >= 2) {
            if (exists(db, "SELECT 1 FROM drink_records WHERE occurredAtEpochMillis<=0 OR createdAtEpochMillis<=0 OR updatedAtEpochMillis<=0 OR revision<0 LIMIT 1")) throw BackupValidationException("记录时间或修订号无效")
            if (exists(db, "SELECT 1 FROM draft_records WHERE consumedAtEpochMillis<=0 OR (editingRecordId IS NULL)!=(expectedRecordRevision IS NULL) OR expectedRecordRevision<0 LIMIT 1")) throw BackupValidationException("草稿时间或编辑版本无效")
        }
        if (databaseVersion(db) >= 3) validateCatalogDomains(db)
        if (databaseVersion(db) >= 4 && exists(db, "SELECT 1 FROM drink_records WHERE snapshotCoffeeType NOT IN ('BLACK','FRUIT','MILK','HAND_BREW') OR (itemType='PERSONAL_BEAN' AND snapshotCoffeeType!='HAND_BREW') OR (itemType='CHAIN_PRODUCT' AND snapshotCoffeeType='HAND_BREW') LIMIT 1")) throw BackupValidationException("记录咖啡类型字段无效")
    }

    private fun databaseVersion(db: SQLiteDatabase): Int =
        db.rawQuery("PRAGMA user_version", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun validateCatalogDomains(db: SQLiteDatabase) {
        if (exists(db, "SELECT 1 FROM catalog_items WHERE (type='CHAIN_PRODUCT' AND (chainProductKind IS NULL OR chainProductKind NOT IN ('BLACK','FRUIT','MILK','PENDING'))) OR (type='PERSONAL_BEAN' AND chainProductKind IS NOT NULL) LIMIT 1")) throw BackupValidationException("产品分类字段无效")
    }
    private fun validateCatalogDomains(db: SupportSQLiteDatabase) {
        db.query("SELECT 1 FROM catalog_items WHERE (type='CHAIN_PRODUCT' AND (chainProductKind IS NULL OR chainProductKind NOT IN ('BLACK','FRUIT','MILK','PENDING'))) OR (type='PERSONAL_BEAN' AND chainProductKind IS NOT NULL) LIMIT 1").use {
            if (it.moveToFirst()) throw BackupValidationException("产品分类字段无效")
        }
    }
    private fun validateRestoredDrinkCoffeeTypes(db: SupportSQLiteDatabase) {
        db.query("SELECT 1 FROM drink_records WHERE snapshotCoffeeType NOT IN ('BLACK','FRUIT','MILK','HAND_BREW') OR (itemType='PERSONAL_BEAN' AND snapshotCoffeeType!='HAND_BREW') OR (itemType='CHAIN_PRODUCT' AND snapshotCoffeeType='HAND_BREW') LIMIT 1").use {
            if (it.moveToFirst()) throw BackupValidationException("记录咖啡类型字段无效")
        }
    }

    /** Compare imported SQLite metadata to the app-owned Room schema before any restore mutation. */
    private fun validateSchema(input: SQLiteDatabase, version: Int) {
        if (version !in 1..CoffeeDatabaseSchema.CURRENT) throw BackupValidationException("不支持的数据库版本 $version")
        val expected = readSchema({ sql -> database.openHelper.writableDatabase.query(sql) }, version, filterFutureColumns = true)
        val actualTables = input.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null,
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        if (actualTables != TABLES.toSet() + ROOM_MASTER_TABLE + ANDROID_METADATA_TABLE) throw BackupValidationException("数据库表结构不匹配")
        val actual = readSchema({ sql -> input.rawQuery(sql, null) }, version, filterFutureColumns = false)
        TABLES.forEach { table ->
            val got = actual.getValue(table); val want = expected.getValue(table)
            if (got.columns.associateBy(ColumnSchema::name) != want.columns.associateBy(ColumnSchema::name)) throw BackupValidationException("数据库 $table columns 不匹配")
            if (got.foreignKeys != want.foreignKeys) throw BackupValidationException("数据库 $table foreign keys 不匹配")
            if (got.indices != want.indices) throw BackupValidationException("数据库 $table indices 不匹配")
        }
        val identity = input.rawQuery("SELECT identity_hash FROM $ROOM_MASTER_TABLE WHERE id=42", null).use {
            if (!it.moveToFirst()) null else it.getString(0)
        }
        if (identity != CoffeeDatabaseSchema.identityHash(version)) throw BackupValidationException("数据库 schema 标识不匹配")
    }

    private fun readSchema(query: (String) -> Cursor, version: Int, filterFutureColumns: Boolean): Map<String, TableSchema> =
        TABLES.associateWith { table ->
            val columns = query("PRAGMA table_info('$table')").use { cursor -> buildList {
                while (cursor.moveToNext()) add(ColumnSchema(cursor.getString(1), cursor.getString(2).uppercase(), cursor.getInt(3), cursor.getString(4), cursor.getInt(5)))
            } }.filterNot { column -> filterFutureColumns && ADDED_COLUMNS.filterKeys { it > version }.values.any { column.name in it[table].orEmpty() } }
            val foreignKeys = query("PRAGMA foreign_key_list('$table')").use { cursor -> buildList {
                while (cursor.moveToNext()) add(ForeignKeySchema(cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6)))
            } }
            val indices = query("PRAGMA index_list('$table')").use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    add(IndexSchema(name, cursor.getInt(2), cursor.getString(3), query("PRAGMA index_info('$name')").use { info ->
                        buildList { while (info.moveToNext()) add(info.getString(2)) }
                    }))
                }
            } }
            TableSchema(columns, foreignKeys, indices)
        }

    private data class TableSchema(val columns: List<ColumnSchema>, val foreignKeys: List<ForeignKeySchema>, val indices: List<IndexSchema>)
    private data class ColumnSchema(val name: String, val type: String, val notNull: Int, val defaultValue: String?, val primaryKey: Int)
    private data class ForeignKeySchema(val table: String, val from: String, val to: String, val onUpdate: String, val onDelete: String)
    private data class IndexSchema(val name: String, val unique: Int, val origin: String, val columns: List<String>)

    private fun copyAll(source: SQLiteDatabase, destination: SupportSQLiteDatabase, sourceVersion: Int, localPaths: Map<String,String>, check: () -> Unit, onCopied: () -> Unit) {
        source.rawQuery("SELECT * FROM image_assets", null).use { copyCursor(it, destination, "image_assets", localPaths, check, onCopied) }
        source.rawQuery("SELECT * FROM brands", null).use { copyCursor(it, destination, "brands", emptyMap(), check, onCopied) }
        copyCatalogItems(source, destination, sourceVersion, check, onCopied)
        copyDrinkRecords(source, destination, sourceVersion, check, onCopied)
        listOf("catalog_updates", "draft_records").forEach { table -> check(); source.rawQuery("SELECT * FROM $table", null).use { copyCursor(it,destination,table,emptyMap(),check,onCopied) } }
    }
    private fun copyTable(source: SupportSQLiteDatabase, destination: SupportSQLiteDatabase, table: String, check: () -> Unit) { source.query("SELECT * FROM $table").use { copyCursor(it,destination,table,emptyMap(),check) } }
    private fun copyCursor(cursor: Cursor, destination: SupportSQLiteDatabase, table: String, localPaths: Map<String,String>, check: () -> Unit, onCopied: () -> Unit = {}) {
        while(cursor.moveToNext()) {
            check()
            val values=ContentValues(cursor.columnCount)
            for(i in 0 until cursor.columnCount) {
                val name=cursor.getColumnName(i)
                if(table=="image_assets" && name=="localPath") values.put(name, localPaths[cursor.getString(cursor.getColumnIndexOrThrow("id"))] ?: cursor.getString(i))
                else when(cursor.getType(i)){ Cursor.FIELD_TYPE_NULL->values.putNull(name); Cursor.FIELD_TYPE_INTEGER->values.put(name,cursor.getLong(i)); Cursor.FIELD_TYPE_FLOAT->values.put(name,cursor.getDouble(i)); Cursor.FIELD_TYPE_STRING->values.put(name,cursor.getString(i)); Cursor.FIELD_TYPE_BLOB->values.put(name,cursor.getBlob(i)) }
            }
            if (table == "drink_records" && !values.containsKey("createdAtEpochMillis")) {
                val occurred = values.getAsLong("occurredAtEpochMillis")
                values.put("createdAtEpochMillis", occurred)
                values.put("updatedAtEpochMillis", occurred)
                values.put("revision", 0)
            }
            if (table == "draft_records" && !values.containsKey("consumedAtEpochMillis")) {
                values.put("consumedAtEpochMillis", values.getAsLong("updatedAtEpochMillis"))
                values.putNull("editingRecordId")
                values.putNull("expectedRecordRevision")
            }
            if(destination.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values)<0) throw BackupValidationException("写入 $table 失败")
            onCopied()
        }
    }
    private fun copyCatalogItems(source: SQLiteDatabase, destination: SupportSQLiteDatabase, sourceVersion: Int, check: () -> Unit, onCopied: () -> Unit) {
        source.rawQuery("SELECT * FROM catalog_items", null).use { cursor ->
            val destinationColumns = CATALOG_ITEM_COLUMNS.joinToString(",")
            val placeholders = CATALOG_ITEM_COLUMNS.joinToString(",") { "?" }
            val insert = "INSERT INTO catalog_items ($destinationColumns) VALUES ($placeholders)"
            while (cursor.moveToNext()) {
                check()
                val values = CATALOG_ITEM_COLUMNS.map { column ->
                    if (column == "chainProductKind" && sourceVersion < 3) {
                        if (cursor.getString(cursor.getColumnIndexOrThrow("type")) == "CHAIN_PRODUCT") legacyChainProductKind(cursor.getString(cursor.getColumnIndexOrThrow("name")), cursor.getString(cursor.getColumnIndexOrThrow("category"))).name else null
                    } else cursorValue(cursor, cursor.getColumnIndexOrThrow(column))
                }.toTypedArray()
                destination.execSQL(insert, values)
                onCopied()
            }
        }
    }
    /** Copy every current record column explicitly; v1-v3 derive the v4 snapshot field. */
    private fun copyDrinkRecords(source: SQLiteDatabase, destination: SupportSQLiteDatabase, sourceVersion: Int, check: () -> Unit, onCopied: () -> Unit) {
        val catalogKinds = if (sourceVersion >= 3) source.rawQuery("SELECT id, chainProductKind FROM catalog_items", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) cursor.getString(1)?.takeIf { it in setOf("BLACK", "FRUIT", "MILK") }?.let { put(cursor.getString(0), it) } }
        } else emptyMap()
        source.rawQuery("SELECT * FROM drink_records", null).use { cursor ->
            while (cursor.moveToNext()) {
                check()
                val values = ContentValues(DRINK_RECORD_COLUMNS.size)
                DRINK_RECORD_COLUMNS.forEach { column ->
                    when (column) {
                        "snapshotCoffeeType" -> values.put(column, if (sourceVersion >= 4) cursor.getString(cursor.getColumnIndexOrThrow(column)) else legacySnapshotCoffeeType(cursor, catalogKinds))
                        "createdAtEpochMillis", "updatedAtEpochMillis" -> values.put(column, if (sourceVersion >= 2) cursor.getLong(cursor.getColumnIndexOrThrow(column)) else cursor.getLong(cursor.getColumnIndexOrThrow("occurredAtEpochMillis")))
                        "revision" -> values.put(column, if (sourceVersion >= 2) cursor.getLong(cursor.getColumnIndexOrThrow(column)) else 0L)
                        else -> putCursorValue(values, column, cursor, cursor.getColumnIndexOrThrow(column))
                    }
                }
                if (destination.insert("drink_records", SQLiteDatabase.CONFLICT_ABORT, values) < 0) throw BackupValidationException("写入 drink_records 失败")
                onCopied()
            }
        }
    }
    private fun legacySnapshotCoffeeType(cursor: Cursor, catalogKinds: Map<String, String>): String {
        if (cursor.getString(cursor.getColumnIndexOrThrow("itemType")) == "PERSONAL_BEAN") return "HAND_BREW"
        catalogKinds[cursor.getString(cursor.getColumnIndexOrThrow("sourceItemId"))]?.let { return it }
        return legacyChainProductKind(cursor.getString(cursor.getColumnIndexOrThrow("snapshotItemName")), null)
            .name.takeIf { it != "PENDING" } ?: "BLACK"
    }
    private fun putCursorValue(values: ContentValues, name: String, cursor: Cursor, index: Int) {
        when (cursor.getType(index)) { Cursor.FIELD_TYPE_NULL -> values.putNull(name); Cursor.FIELD_TYPE_INTEGER -> values.put(name, cursor.getLong(index)); Cursor.FIELD_TYPE_FLOAT -> values.put(name, cursor.getDouble(index)); Cursor.FIELD_TYPE_STRING -> values.put(name, cursor.getString(index)); Cursor.FIELD_TYPE_BLOB -> values.put(name, cursor.getBlob(index)) }
    }
    private fun cursorValue(cursor: Cursor, index: Int): Any? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
        else -> cursor.getBlob(index)
    }
    private fun clearAll(db: SupportSQLiteDatabase) { listOf("draft_records","catalog_updates","drink_records","catalog_items","brands","image_assets").forEach { db.execSQL("DELETE FROM $it") } }
    private fun counts(db: SupportSQLiteDatabase)=BackupCounts(count(db,"brands"),count(db,"catalog_items"),count(db,"drink_records"),count(db,"image_assets"),count(db,"catalog_updates"),count(db,"draft_records"))
    private fun counts(db: SQLiteDatabase)=BackupCounts(count(db,"brands"),count(db,"catalog_items"),count(db,"drink_records"),count(db,"image_assets"),count(db,"catalog_updates"),count(db,"draft_records"))
    private fun count(db: SupportSQLiteDatabase,t:String)=db.query("SELECT COUNT(*) FROM $t").use{it.moveToFirst();it.getInt(0)}
    private fun count(db: SQLiteDatabase,t:String)=db.rawQuery("SELECT COUNT(*) FROM $t",null).use{it.moveToFirst();it.getInt(0)}
    private data class ImageRow(val id:String,val localPath:String,val sha256:String,val kind:String)
    private fun imageRows(db: SupportSQLiteDatabase,check:()->Unit={})=db.query("SELECT id,localPath,sha256,kind FROM image_assets").use{c->buildList{while(c.moveToNext()){check();add(ImageRow(c.getString(0),c.getString(1),c.getString(2),c.getString(3)))}}}
    private fun imageRows(db: SQLiteDatabase,check:()->Unit={})=db.rawQuery("SELECT id,localPath,sha256,kind FROM image_assets",null).use{c->buildList{while(c.moveToNext()){check();add(ImageRow(c.getString(0),c.getString(1),c.getString(2),c.getString(3)))}}}
    private fun activeImagePaths()=database.openHelper.writableDatabase.query("SELECT localPath FROM image_assets").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    private fun managedImage(path:String):File { val root=imageRoot.canonicalFile; val file=try{File(path).canonicalFile}catch(e:Exception){throw BackupValidationException("图片路径无效",e)}; if(file.parentFile!=root)throw BackupValidationException("图片路径越界"); return file }
    private fun newTemp(prefix:String)=File(tempRoot,"$prefix-${UUID.randomUUID()}").apply{if(!mkdirs())throw BackupValidationException("无法创建临时目录")}
    private fun extensionFor(file:File)=when{ isPng(file)->"png"; isJpeg(file)->"jpg"; else->"webp" }
    private fun decodeable(file:File)=BitmapFactory.Options().let{o->o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.absolutePath,o);o.outWidth>0&&o.outHeight>0}
    private fun isPng(f:File)=f.inputStream().use{val b=ByteArray(8);it.read(b)==8&&b[0]==0x89.toByte()&&b[1]==0x50.toByte()}
    private fun isJpeg(f:File)=f.inputStream().use{val b=ByteArray(3);it.read(b)==3&&b[0]==0xFF.toByte()&&b[1]==0xD8.toByte()}
    private fun hasSqliteMagic(f:File)=f.inputStream().use{val b=ByteArray(16);it.read(b)==16&&String(b,Charsets.ISO_8859_1)=="SQLite format 3\u0000"}
    private fun sha256(f:File,check:()->Unit={}):String{val d=MessageDigest.getInstance("SHA-256");f.inputStream().use{i->val b=ByteArray(DEFAULT_BUFFER_SIZE);while(true){check();val n=i.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
    private fun singleResult(db:SQLiteDatabase,sql:String)=db.rawQuery(sql,null).use{it.moveToFirst();it.getString(0)}
    private fun exists(db:SQLiteDatabase,sql:String)=db.rawQuery(sql,null).use{it.moveToFirst()}
    private fun copyBounded(input:java.io.InputStream,output:java.io.OutputStream,max:Long,check:()->Unit={}){val b=ByteArray(DEFAULT_BUFFER_SIZE);var total=0L;while(true){check();val n=input.read(b);if(n<0)break;total+=n;if(total>max)throw BackupValidationException("备份文件过大");output.write(b,0,n)}}
    private data class Snapshot(val database:File,val images:List<BackupImage>,val counts:BackupCounts)
    private fun cleanupStaleStaging() { tempRoot.mkdirs(); tempRoot.listFiles()?.forEach { candidate -> if (candidate.canonicalPath !in ACTIVE_STAGING) candidate.deleteRecursively() } }
    private fun cleanupFailedTarget(target: Uri, original: Throwable) {
        val deleted = if (target.scheme == "file") {
            runCatching { target.path?.let(::File)?.delete() == true }.getOrDefault(false)
        } else runCatching { resolver.delete(target, null, null) > 0 }.getOrDefault(false)
        if (!deleted) {
            runCatching {
                if (target.scheme == "file") FileOutputStream(File(requireNotNull(target.path)), false).use { }
                else resolver.openOutputStream(target, "w")?.use { } ?: error("无法重新打开目标")
            }
                .exceptionOrNull()?.let { original.addSuppressed(BackupValidationException("失败的导出目标无法清空", it)) }
        }
    }
    companion object {
        private val MUTEX=Mutex(); private val ACTIVE_STAGING=mutableSetOf<String>()
        private val TABLES=listOf("image_assets","brands","catalog_items","drink_records","catalog_updates","draft_records")
        private const val ROOM_MASTER_TABLE = "room_master_table"
        private const val ANDROID_METADATA_TABLE = "android_metadata"
        private val CATALOG_ITEM_COLUMNS = listOf("id", "brandId", "type", "name", "normalizedName", "imageAssetId", "origin", "processing", "roastLevel", "flavorNotes", "brewMethod", "status", "caffeineMg", "officialDescription", "purchaseDate", "roastDate", "sourceUrl", "sourceFetchedAt", "informationCompleteness", "category", "specificationDescription", "imageSourceUrl", "chainProductKind")
        private val DRINK_RECORD_COLUMNS = listOf("id", "occurredAtEpochMillis", "localDate", "itemType", "sourceItemId", "brewMethod", "ratingHalfStars", "actualPriceFen", "note", "snapshotBrandName", "snapshotItemName", "snapshotOrigin", "snapshotProcessing", "snapshotImageAssetId", "snapshotBrandLogoAssetId", "snapshotRoastLevel", "snapshotFlavorNotes", "snapshotCoffeeType", "createdAtEpochMillis", "updatedAtEpochMillis", "revision")
        private val ADDED_COLUMNS = mapOf(
            2 to mapOf(
                "drink_records" to setOf("createdAtEpochMillis", "updatedAtEpochMillis", "revision"),
                "draft_records" to setOf("consumedAtEpochMillis", "editingRecordId", "expectedRecordRevision"),
            ),
            3 to mapOf("catalog_items" to setOf("chainProductKind")),
            4 to mapOf("drink_records" to setOf("snapshotCoffeeType")),
        )
        private const val MAX_ARCHIVE_BYTES=512L*1024*1024; private const val MAX_IMAGE_BYTES=20L*1024*1024
    }
}
