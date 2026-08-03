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
) : BackupManager {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val tempRoot = File(appContext.cacheDir, "backup-staging")
    private val imageRoot = File(appContext.filesDir, "images")

    override suspend fun export(target: Uri): BackupSummary = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            val root = newTemp("export")
            try {
                val snapshot = buildSnapshot(root)
                val archive = File(root, "coffee-journal.zip")
                val manifest = codec.encode(
                    target = archive,
                    database = snapshot.database,
                    images = snapshot.images,
                    counts = snapshot.counts,
                    schemaVersion = 1,
                    exportedAtEpochMillis = now(),
                )
                resolver.openOutputStream(target, "w")?.use { output -> FileInputStream(archive).use { it.copyTo(output) } }
                    ?: throw BackupValidationException("无法写入所选文件")
                BackupSummary(manifest)
            } finally {
                withContext(NonCancellable) { root.deleteRecursively() }
            }
        }
    }

    override suspend fun validate(source: Uri): ValidatedBackup = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            val root = newTemp("validate")
            try {
                val archive = File(root, "source.zip")
                resolver.openInputStream(source)?.use { input -> FileOutputStream(archive).use { output -> copyBounded(input, output, MAX_ARCHIVE_BYTES) } }
                    ?: throw BackupValidationException("无法读取所选文件")
                val decoded = codec.decode(archive, File(root, "unpacked"))
                validateDatabase(decoded)
                ValidatedBackup(root, decoded)
            } catch (error: Throwable) {
                withContext(NonCancellable) { root.deleteRecursively() }
                throw error
            }
        }
    }

    override suspend fun restore(backup: ValidatedBackup): RestoreSummary = withContext(Dispatchers.IO) {
        MUTEX.withLock {
            val stagingPrefix = tempRoot.canonicalPath + File.separator
            if (!backup.root.canonicalPath.startsWith(stagingPrefix)) throw BackupValidationException("备份验证令牌无效")
            val created = mutableListOf<File>()
            var committed = false
            try {
                validateDatabase(backup.decoded)
                imageRoot.mkdirs()
                val localPaths = backup.decoded.images.associate { image ->
                    val extension = image.entry.substringAfterLast('.')
                    val expectedHash = sha256(image.file)
                    val target = File(imageRoot, "$expectedHash.$extension")
                    if (target.exists()) {
                        if (!target.isFile || sha256(target) != expectedHash || !decodeable(target)) {
                            throw BackupValidationException("本地同名图片已损坏")
                        }
                    } else {
                        val staged = File(imageRoot, ".restore-${UUID.randomUUID()}")
                        image.file.copyTo(staged, overwrite = false)
                        if (sha256(staged) != expectedHash || !decodeable(staged)) { staged.delete(); throw BackupValidationException("图片复制校验失败") }
                        if (!staged.renameTo(target)) { staged.delete(); throw BackupValidationException("无法保存恢复图片") }
                        created += target
                    }
                    image.assetId to target.absolutePath
                }
                val oldPaths = activeImagePaths()
                database.withTransaction {
                    val active = database.openHelper.writableDatabase
                    clearAll(active)
                    openBackupDatabase(backup.decoded.databaseFile).use { copyAll(it, active, localPaths) }
                    checkForeignKeys(active)
                    beforeRestoreCommit()
                }
                committed = true
                withContext(NonCancellable) {
                    val retained = activeImagePaths().mapTo(HashSet()) { File(it).canonicalPath }
                    oldPaths.forEach { path -> runCatching { File(path).canonicalFile }.getOrNull()?.takeIf { it.parentFile == imageRoot.canonicalFile && it.canonicalPath !in retained }?.delete() }
                }
                RestoreSummary(backup.manifest.counts)
            } catch (error: Throwable) {
                if (!committed) withContext(NonCancellable) { created.forEach { it.delete() } }
                if (error is CancellationException) throw error
                throw error
            } finally {
                withContext(NonCancellable) { backup.root.deleteRecursively() }
            }
        }
    }

    override suspend fun discard(backup: ValidatedBackup) = withContext(NonCancellable + Dispatchers.IO) {
        MUTEX.withLock { backup.root.deleteRecursively(); Unit }
    }

    private suspend fun buildSnapshot(root: File): Snapshot {
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
                    TABLES.forEach { copyTable(source, destination, it) }
                    destination.setTransactionSuccessful()
                } finally { destination.endTransaction() }
                imageRows(source).forEach { row ->
                    val file = managedImage(row.localPath)
                    if (!file.isFile || file.length() > MAX_IMAGE_BYTES || sha256(file) != row.sha256 || !decodeable(file)) throw BackupValidationException("图片资产 ${row.id} 缺失或损坏")
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

    private fun validateDatabase(decoded: DecodedBackup) {
        try {
            openBackupDatabase(decoded.databaseFile).use {
                singleResult(it, "PRAGMA integrity_check").takeIf { value -> value == "ok" } ?: throw BackupValidationException("数据库完整性检查失败")
                checkForeignKeys(it)
                val actual = counts(it)
                if (actual != decoded.manifest.counts) throw BackupValidationException("数据库计数与清单不一致")
                validateDomains(it)
                val rows = imageRows(it)
                if (rows.map { row -> row.id }.toSet() != decoded.images.map { image -> image.assetId }.toSet()) throw BackupValidationException("图片数据库行与清单不一致")
                val rowById = rows.associateBy { row -> row.id }
                val manifestById = decoded.manifest.images.associateBy { image -> image.assetId }
                decoded.images.forEach { image ->
                    val row = rowById.getValue(image.assetId)
                    val manifest = manifestById.getValue(image.assetId)
                    if (row.sha256 != manifest.sha256 || row.kind != manifest.kind) throw BackupValidationException("图片元数据不一致: ${image.assetId}")
                    if (!decodeable(image.file)) throw BackupValidationException("图片无法解码: ${image.assetId}")
                }
            }
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Exception) {
            throw BackupValidationException("SQLite 数据库无效", error)
        }
    }

    private fun openBackupDatabase(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    private fun checkForeignKeys(db: SupportSQLiteDatabase) { db.query("PRAGMA foreign_key_check").use { if (it.moveToFirst()) throw BackupValidationException("数据库外键检查失败") } }
    private fun checkForeignKeys(db: SQLiteDatabase) { db.rawQuery("PRAGMA foreign_key_check", null).use { if (it.moveToFirst()) throw BackupValidationException("数据库外键检查失败") } }
    private fun validateDomains(db: SQLiteDatabase) {
        db.rawQuery("SELECT ratingHalfStars,actualPriceFen FROM drink_records UNION ALL SELECT ratingHalfStars,actualPriceFen FROM draft_records", null).use { c -> while(c.moveToNext()){ if(!c.isNull(0) && c.getInt(0) !in 1..10) throw BackupValidationException("评分字段无效"); if(!c.isNull(1) && c.getLong(1)<0) throw BackupValidationException("金额字段无效") } }
        db.rawQuery("SELECT informationCompleteness FROM catalog_items", null).use { c -> while(c.moveToNext()) if(c.getInt(0) !in 0..100) throw BackupValidationException("完整度字段无效") }
        if (exists(db, "SELECT 1 FROM brands WHERE id='' OR name='' OR normalizedName='' OR type NOT IN ('CHAIN','ROASTER') OR maintenanceMode NOT IN ('PUBLIC_SOURCE','MANUAL_ONLY') LIMIT 1")) throw BackupValidationException("品牌字段无效")
        if (exists(db, "SELECT 1 FROM catalog_items WHERE id='' OR brandId='' OR name='' OR normalizedName='' OR type NOT IN ('CHAIN_PRODUCT','PERSONAL_BEAN') OR status NOT IN ('ACTIVE','NEEDS_IMAGE','DISCONTINUED','ARCHIVED') LIMIT 1")) throw BackupValidationException("产品字段无效")
        if (exists(db, "SELECT 1 FROM drink_records WHERE id='' OR sourceItemId='' OR snapshotBrandName='' OR snapshotItemName='' OR itemType NOT IN ('CHAIN_PRODUCT','PERSONAL_BEAN') LIMIT 1")) throw BackupValidationException("记录字段无效")
        if (exists(db, "SELECT 1 FROM image_assets WHERE id='' OR sha256 GLOB '*[^0-9a-f]*' OR length(sha256)!=64 OR kind NOT IN ('PRODUCT','BRAND_LOGO','BEAN_PACKAGE','RECORD_SNAPSHOT') LIMIT 1")) throw BackupValidationException("图片资产字段无效")
        if (exists(db, "SELECT 1 FROM draft_records WHERE id='' OR revisionId='' OR (itemType IS NOT NULL AND itemType NOT IN ('CHAIN_PRODUCT','PERSONAL_BEAN')) LIMIT 1")) throw BackupValidationException("草稿字段无效")
    }

    private fun copyAll(source: SQLiteDatabase, destination: SupportSQLiteDatabase, localPaths: Map<String,String>) {
        source.rawQuery("SELECT * FROM image_assets", null).use { copyCursor(it, destination, "image_assets", localPaths) }
        listOf("brands","catalog_items","drink_records","catalog_updates","draft_records").forEach { table -> source.rawQuery("SELECT * FROM $table", null).use { copyCursor(it,destination,table,emptyMap()) } }
    }
    private fun copyTable(source: SupportSQLiteDatabase, destination: SupportSQLiteDatabase, table: String) { source.query("SELECT * FROM $table").use { copyCursor(it,destination,table,emptyMap()) } }
    private fun copyCursor(cursor: Cursor, destination: SupportSQLiteDatabase, table: String, localPaths: Map<String,String>) {
        while(cursor.moveToNext()) {
            val values=ContentValues(cursor.columnCount)
            for(i in 0 until cursor.columnCount) {
                val name=cursor.getColumnName(i)
                if(table=="image_assets" && name=="localPath") values.put(name, localPaths[cursor.getString(cursor.getColumnIndexOrThrow("id"))] ?: cursor.getString(i))
                else when(cursor.getType(i)){ Cursor.FIELD_TYPE_NULL->values.putNull(name); Cursor.FIELD_TYPE_INTEGER->values.put(name,cursor.getLong(i)); Cursor.FIELD_TYPE_FLOAT->values.put(name,cursor.getDouble(i)); Cursor.FIELD_TYPE_STRING->values.put(name,cursor.getString(i)); Cursor.FIELD_TYPE_BLOB->values.put(name,cursor.getBlob(i)) }
            }
            if(destination.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values)<0) throw BackupValidationException("写入 $table 失败")
        }
    }
    private fun clearAll(db: SupportSQLiteDatabase) { listOf("draft_records","catalog_updates","drink_records","catalog_items","brands","image_assets").forEach { db.execSQL("DELETE FROM $it") } }
    private fun counts(db: SupportSQLiteDatabase)=BackupCounts(count(db,"brands"),count(db,"catalog_items"),count(db,"drink_records"),count(db,"image_assets"),count(db,"catalog_updates"),count(db,"draft_records"))
    private fun counts(db: SQLiteDatabase)=BackupCounts(count(db,"brands"),count(db,"catalog_items"),count(db,"drink_records"),count(db,"image_assets"),count(db,"catalog_updates"),count(db,"draft_records"))
    private fun count(db: SupportSQLiteDatabase,t:String)=db.query("SELECT COUNT(*) FROM $t").use{it.moveToFirst();it.getInt(0)}
    private fun count(db: SQLiteDatabase,t:String)=db.rawQuery("SELECT COUNT(*) FROM $t",null).use{it.moveToFirst();it.getInt(0)}
    private data class ImageRow(val id:String,val localPath:String,val sha256:String,val kind:String)
    private fun imageRows(db: SupportSQLiteDatabase)=db.query("SELECT id,localPath,sha256,kind FROM image_assets").use{c->buildList{while(c.moveToNext())add(ImageRow(c.getString(0),c.getString(1),c.getString(2),c.getString(3)))}}
    private fun imageRows(db: SQLiteDatabase)=db.rawQuery("SELECT id,localPath,sha256,kind FROM image_assets",null).use{c->buildList{while(c.moveToNext())add(ImageRow(c.getString(0),c.getString(1),c.getString(2),c.getString(3)))}}
    private fun activeImagePaths()=database.openHelper.writableDatabase.query("SELECT localPath FROM image_assets").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    private fun managedImage(path:String):File { val root=imageRoot.canonicalFile; val file=try{File(path).canonicalFile}catch(e:Exception){throw BackupValidationException("图片路径无效",e)}; if(file.parentFile!=root)throw BackupValidationException("图片路径越界"); return file }
    private fun newTemp(prefix:String)=File(tempRoot,"$prefix-${UUID.randomUUID()}").apply{if(!mkdirs())throw BackupValidationException("无法创建临时目录")}
    private fun extensionFor(file:File)=when{ isPng(file)->"png"; isJpeg(file)->"jpg"; else->"webp" }
    private fun decodeable(file:File)=BitmapFactory.Options().let{o->o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.absolutePath,o);o.outWidth>0&&o.outHeight>0}
    private fun isPng(f:File)=f.inputStream().use{val b=ByteArray(8);it.read(b)==8&&b[0]==0x89.toByte()&&b[1]==0x50.toByte()}
    private fun isJpeg(f:File)=f.inputStream().use{val b=ByteArray(3);it.read(b)==3&&b[0]==0xFF.toByte()&&b[1]==0xD8.toByte()}
    private fun hasSqliteMagic(f:File)=f.inputStream().use{val b=ByteArray(16);it.read(b)==16&&String(b,Charsets.ISO_8859_1)=="SQLite format 3\u0000"}
    private fun sha256(f:File):String{val d=MessageDigest.getInstance("SHA-256");f.inputStream().use{i->val b=ByteArray(DEFAULT_BUFFER_SIZE);while(true){val n=i.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
    private fun singleResult(db:SQLiteDatabase,sql:String)=db.rawQuery(sql,null).use{it.moveToFirst();it.getString(0)}
    private fun exists(db:SQLiteDatabase,sql:String)=db.rawQuery(sql,null).use{it.moveToFirst()}
    private fun copyBounded(input:java.io.InputStream,output:java.io.OutputStream,max:Long){val b=ByteArray(DEFAULT_BUFFER_SIZE);var total=0L;while(true){val n=input.read(b);if(n<0)break;total+=n;if(total>max)throw BackupValidationException("备份文件过大");output.write(b,0,n)}}
    private data class Snapshot(val database:File,val images:List<BackupImage>,val counts:BackupCounts)
    companion object { private val MUTEX=Mutex(); private val TABLES=listOf("image_assets","brands","catalog_items","drink_records","catalog_updates","draft_records"); private const val MAX_ARCHIVE_BYTES=512L*1024*1024; private const val MAX_IMAGE_BYTES=20L*1024*1024 }
}
