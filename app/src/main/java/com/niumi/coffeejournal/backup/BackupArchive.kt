package com.niumi.coffeejournal.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException

@Serializable
data class BackupCounts(
    val brands: Int,
    val catalogItems: Int,
    val records: Int,
    val images: Int,
    val catalogUpdates: Int,
    val drafts: Int,
)

@Serializable
data class BackupImageManifest(
    val assetId: String,
    val entry: String,
    val sha256: String,
    val size: Long,
    val kind: String,
)

@Serializable
data class BackupManifest(
    val formatVersion: Int = CURRENT_FORMAT,
    val exportedAtEpochMillis: Long,
    val schemaVersion: Int,
    val databaseSha256: String,
    val databaseSize: Long,
    val images: List<BackupImageManifest>,
    val counts: BackupCounts,
) {
    companion object { const val CURRENT_FORMAT = 1 }
}

data class BackupImage(val assetId: String, val entry: String, val file: File, val kind: String)
data class DecodedBackup(val manifest: BackupManifest, val databaseFile: File, val images: List<BackupImage>)

class BackupValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SafeBackupArchiveCodec(
    private val limits: BackupLimits = BackupLimits(),
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encode(
        target: File,
        database: File,
        images: List<BackupImage>,
        counts: BackupCounts,
        schemaVersion: Int,
        exportedAtEpochMillis: Long,
        checkCancelled: () -> Unit = {},
    ): BackupManifest {
        checkCancelled()
        require(database.isFile && database.length() in 1..limits.maxDatabaseBytes)
        require(images.size <= limits.maxImages)
        val ids = HashSet<String>()
        val entries = HashSet<String>()
        val imageManifest = images.map { image ->
            checkCancelled()
            require(ids.add(image.assetId) && entries.add(image.entry))
            requireSafeImageEntry(image.entry)
            require(image.file.isFile && image.file.length() in 1..limits.maxImageBytes)
            BackupImageManifest(image.assetId, image.entry, sha256(image.file), image.file.length(), image.kind)
        }
        val manifest = BackupManifest(
            exportedAtEpochMillis = exportedAtEpochMillis,
            schemaVersion = schemaVersion,
            databaseSha256 = sha256(database),
            databaseSize = database.length(),
            images = imageManifest,
            counts = counts.copy(images = images.size),
        )
        target.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            putBytes(zip, MANIFEST, json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            putFile(zip, DATABASE, database, checkCancelled)
            images.forEach { checkCancelled(); putFile(zip, it.entry, it.file, checkCancelled) }
        }
        return manifest
    }

    fun decode(archive: File, destination: File, checkCancelled: () -> Unit = {}): DecodedBackup {
        checkCancelled()
        if (!archive.isFile || archive.length() !in 1..limits.maxArchiveBytes) fail("备份文件大小无效")
        destination.deleteRecursively()
        destination.mkdirs()
        try {
            ZipFile(archive).use { zip ->
                val seen = HashSet<String>()
                val all = zip.entries().asSequence().toList()
                if (all.size > limits.maxEntries) fail("备份条目过多")
                all.forEach { entry ->
                    checkCancelled()
                    if (entry.isDirectory || !safeName(entry.name) || !seen.add(entry.name)) fail("备份包含不安全或重复条目")
                    if (entry.size < 0 || entry.compressedSize < 0) fail("备份条目大小未知")
                    val max = if (entry.name == DATABASE) limits.maxDatabaseBytes else if (entry.name == MANIFEST) limits.maxManifestBytes else limits.maxImageBytes
                    if (entry.size > max) fail("备份条目过大")
                    if (entry.compressedSize == 0L && entry.size > 0 || entry.compressedSize > 0 && entry.size / entry.compressedSize > limits.maxCompressionRatio) fail("备份压缩比异常")
                }
                val manifestEntry = zip.getEntry(MANIFEST) ?: fail("缺少 manifest.json")
                val manifestBytes = zip.getInputStream(manifestEntry).use { readBounded(it, limits.maxManifestBytes, checkCancelled) }
                val manifest = try { json.decodeFromString(BackupManifest.serializer(), manifestBytes.toString(Charsets.UTF_8)) }
                    catch (error: Exception) { fail("清单无法解析", error) }
                if (manifest.formatVersion != BackupManifest.CURRENT_FORMAT) fail("不支持的备份格式版本 ${manifest.formatVersion}")
                if (manifest.schemaVersion != 1) fail("不支持的数据库版本 ${manifest.schemaVersion}")
                if (manifest.images.size > limits.maxImages || manifest.counts.images != manifest.images.size) fail("图片清单计数无效")
                val expected = mutableSetOf(MANIFEST, DATABASE)
                val assetIds = HashSet<String>()
                manifest.images.forEach {
                    requireSafeImageEntry(it.entry)
                    if (!assetIds.add(it.assetId) || !expected.add(it.entry)) fail("图片清单重复")
                }
                if (seen != expected) fail("备份包含缺失或额外条目")
                var expanded = 0L
                fun extract(name: String, max: Long): File {
                    val entry = zip.getEntry(name) ?: fail("缺少 $name")
                    expanded += entry.size
                    if (expanded > limits.maxExpandedBytes) fail("备份解压总量过大")
                    val out = File(destination, name)
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> FileOutputStream(out).use { output -> copyBounded(input, output, max, checkCancelled) } }
                    return out
                }
                val db = extract(DATABASE, limits.maxDatabaseBytes)
                if (db.length() != manifest.databaseSize || sha256(db) != manifest.databaseSha256 || !hasSqliteMagic(db)) fail("数据库校验失败")
                val images = manifest.images.map { item ->
                    checkCancelled()
                    val file = extract(item.entry, limits.maxImageBytes)
                    if (file.length() != item.size || sha256(file) != item.sha256 || !hasImageMagic(file, item.entry.substringAfterLast('.'))) fail("图片校验失败: ${item.assetId}")
                    BackupImage(item.assetId, item.entry, file, item.kind)
                }
                return DecodedBackup(manifest, db, images)
            }
        } catch (error: BackupValidationException) {
            destination.deleteRecursively(); throw error
        } catch (cancelled: CancellationException) {
            destination.deleteRecursively(); throw cancelled
        } catch (error: Exception) {
            destination.deleteRecursively(); fail("备份文件损坏", error)
        }
    }

    fun verifyDecoded(decoded: DecodedBackup, checkCancelled: () -> Unit = {}) {
        checkCancelled()
        val manifest = decoded.manifest
        val database = decoded.databaseFile
        if (!database.isFile || database.length() != manifest.databaseSize || sha256(database, checkCancelled) != manifest.databaseSha256 || !hasSqliteMagic(database)) fail("数据库暂存文件已变化")
        val files = decoded.images.associateBy { it.assetId }
        if (files.keys != manifest.images.map { it.assetId }.toSet()) fail("图片暂存清单已变化")
        manifest.images.forEach { item ->
            checkCancelled()
            val image = files.getValue(item.assetId).file
            if (!image.isFile || image.length() != item.size || sha256(image, checkCancelled) != item.sha256 || !hasImageMagic(image, item.entry.substringAfterLast('.'))) fail("图片暂存文件已变化: ${item.assetId}")
        }
    }

    private fun requireSafeImageEntry(name: String) {
        if (!IMAGE_ENTRY.matches(name) || !safeName(name)) fail("图片条目名无效")
    }
    private fun safeName(name: String) = name.isNotEmpty() && !name.startsWith('/') && !name.startsWith('\\') && '\\' !in name && name.split('/').none { it == ".." || it == "." || it.isEmpty() }
    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
    private fun putFile(zip: ZipOutputStream, name: String, file: File, check: () -> Unit) { zip.putNextEntry(ZipEntry(name)); FileInputStream(file).use { copyBounded(it, zip, Long.MAX_VALUE, check) }; zip.closeEntry() }
    private fun readBounded(input: java.io.InputStream, max: Long, check: () -> Unit = {}): ByteArray { val output = java.io.ByteArrayOutputStream(); copyBounded(input, output, max, check); return output.toByteArray() }
    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, max: Long, check: () -> Unit = {}) { val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var total = 0L; while (true) { check(); val n=input.read(buffer); if(n<0) break; total += n; if(total>max) fail("条目超过限制"); output.write(buffer,0,n) } }
    private fun hasSqliteMagic(file: File) = file.inputStream().use { input -> val b=ByteArray(16); input.read(b)==16 && b.contentEquals("SQLite format 3\u0000".toByteArray()) }
    private fun hasImageMagic(file: File, extension: String): Boolean = file.inputStream().use { input ->
        val b=ByteArray(12); val n=input.read(b)
        when (extension) {
            "jpg", "jpeg" -> n>=3 && b[0]==0xFF.toByte() && b[1]==0xD8.toByte() && b[2]==0xFF.toByte()
            "png" -> n>=8 && b.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A))
            "webp" -> n>=12 && String(b,0,4)=="RIFF" && String(b,8,4)=="WEBP"
            else -> false
        }
    }
    private fun sha256(file: File, check: () -> Unit = {}): String { val d=MessageDigest.getInstance("SHA-256"); BufferedInputStream(FileInputStream(file)).use { input -> val b=ByteArray(DEFAULT_BUFFER_SIZE); while(true){check();val n=input.read(b);if(n<0)break;d.update(b,0,n)} }; return d.digest().joinToString(""){"%02x".format(it)} }
    private fun fail(message: String, cause: Throwable? = null): Nothing = throw BackupValidationException(message, cause)
    companion object { const val MANIFEST="manifest.json"; const val DATABASE="database.sqlite"; val IMAGE_ENTRY=Regex("images/[A-Za-z0-9_-]{1,128}\\.(webp|png|jpg|jpeg)") }
}

data class BackupLimits(
    val maxArchiveBytes: Long = 512L * 1024 * 1024,
    val maxExpandedBytes: Long = 768L * 1024 * 1024,
    val maxDatabaseBytes: Long = 256L * 1024 * 1024,
    val maxImageBytes: Long = 20L * 1024 * 1024,
    val maxManifestBytes: Long = 2L * 1024 * 1024,
    val maxEntries: Int = 10_002,
    val maxImages: Int = 10_000,
    val maxCompressionRatio: Long = 200,
)
