package com.niumi.coffeejournal.backup

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupArchiveCodecTest {
    private val codec = SafeBackupArchiveCodec()

    @Test fun `round trip streams database and referenced images`() {
        val root = createTempDirectory("backup-codec-").toFile()
        try {
            val db = File(root, "source.sqlite").apply { writeBytes("SQLite format 3\u0000payload".toByteArray()) }
            val image = File(root, "a.webp").apply { writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46, 4, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)) }
            val archive = File(root, "backup.zip")
            codec.encode(
                archive, db,
                listOf(BackupImage("asset-a", "images/asset-a.webp", image, "PRODUCT")),
                BackupCounts(1, 1, 1, 1, 1, 1), 1, 42,
            )

            val decoded = codec.decode(archive, File(root, "decoded"))

            assertEquals(1, decoded.manifest.formatVersion)
            assertEquals(1, decoded.manifest.counts.records)
            assertArrayEquals(db.readBytes(), decoded.databaseFile.readBytes())
            assertArrayEquals(image.readBytes(), decoded.images.single().file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun `round trip accepts png jpg jpeg and webp image codecs`() {
        val root = createTempDirectory("backup-codecs-").toFile()
        try {
            val db = File(root, "source.sqlite").apply { writeBytes("SQLite format 3\u0000payload".toByteArray()) }
            val png = byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)
            val jpeg = byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
            val webp = byteArrayOf(0x52,0x49,0x46,0x46,4,0,0,0,0x57,0x45,0x42,0x50)
            val images = listOf("png" to png, "jpg" to jpeg, "jpeg" to jpeg, "webp" to webp).mapIndexed { i, (ext, bytes) ->
                val file = File(root, "$i.$ext").apply { writeBytes(bytes) }
                BackupImage("asset-$i", "images/asset-$i.$ext", file, "PRODUCT")
            }
            val archive = File(root, "backup.zip")
            codec.encode(archive, db, images, BackupCounts(0,0,0,4,0,0), 1, 1)

            assertEquals(4, codec.decode(archive, File(root, "decoded")).images.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun `image extension and magic mismatch is rejected on decode`() {
        val root = createTempDirectory("backup-image-mismatch-").toFile()
        try {
            val db = File(root, "source.sqlite").apply { writeBytes("SQLite format 3\u0000payload".toByteArray()) }
            val png = File(root, "image.png").apply { writeBytes(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)) }
            val archive = File(root, "backup.zip")
            codec.encode(archive, db, listOf(BackupImage("asset", "images/asset.jpg", png, "PRODUCT")), BackupCounts(0,0,0,1,0,0), 1, 1)

            assertThrows(BackupValidationException::class.java) { codec.decode(archive, File(root, "decoded")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun `future format is rejected`() {
        val root = createTempDirectory("backup-future-").toFile()
        try {
            val archive = File(root, "future.zip")
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(Json.encodeToString(BackupManifest.serializer(), BackupManifest(
                    formatVersion = 2, exportedAtEpochMillis = 1, schemaVersion = 1,
                    databaseSha256 = "0".repeat(64), databaseSize = 1,
                    images = emptyList(), counts = BackupCounts(0,0,0,0,0,0),
                )).toByteArray())
                zip.closeEntry()
            }
            assertThrows(BackupValidationException::class.java) { codec.decode(archive, File(root, "decoded")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun `traversal entries are rejected`() {
        listOf("../escape", "/absolute", "images\\evil.webp").forEach { bad ->
            val root = createTempDirectory("backup-entry-").toFile()
            try {
                val archive = File(root, "bad.zip")
                ZipOutputStream(FileOutputStream(archive)).use { zip ->
                    zip.putNextEntry(ZipEntry(bad)); zip.write(byteArrayOf(1)); zip.closeEntry()
                }
                assertThrows(BackupValidationException::class.java) { codec.decode(archive, File(root, "decoded")) }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun `duplicate entries are rejected`() {
        val root = createTempDirectory("backup-duplicate-").toFile()
        try {
            val archive = File(root, "bad.zip")
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("database.sqlite")); zip.write(byteArrayOf(1)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("database.sqlitx")); zip.write(byteArrayOf(2)); zip.closeEntry()
            }
            val bytes = archive.readBytes()
            val old = "database.sqlitx".toByteArray()
            val replacement = "database.sqlite".toByteArray()
            for (index in 0..bytes.size - old.size) {
                if (bytes.copyOfRange(index, index + old.size).contentEquals(old)) {
                    replacement.copyInto(bytes, index)
                }
            }
            archive.writeBytes(bytes)
            assertThrows(BackupValidationException::class.java) { codec.decode(archive, File(root, "decoded")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun `checksum corruption is rejected`() {
        val root = createTempDirectory("backup-corrupt-").toFile()
        try {
            val db = File(root, "source.sqlite").apply { writeBytes("SQLite format 3\u0000payload".toByteArray()) }
            val archive = File(root, "backup.zip")
            codec.encode(archive, db, emptyList(), BackupCounts(0,0,0,0,0,0), 1, 42)
            val rewritten = File(root, "bad.zip")
            java.util.zip.ZipInputStream(archive.inputStream()).use { input ->
                ZipOutputStream(rewritten.outputStream()).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        output.putNextEntry(ZipEntry(entry.name))
                        val bytes = input.readBytes()
                        output.write(if (entry.name == "database.sqlite") bytes + 1 else bytes)
                        output.closeEntry()
                    }
                }
            }
            assertThrows(BackupValidationException::class.java) { codec.decode(rewritten, File(root, "decoded")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun `extra entries and compression bombs are rejected`() {
        val root = createTempDirectory("backup-bomb-").toFile()
        try {
            val extra = File(root, "extra.zip")
            ZipOutputStream(extra.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write("{}".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("unexpected")); zip.write(byteArrayOf(1)); zip.closeEntry()
            }
            assertThrows(BackupValidationException::class.java) { codec.decode(extra, File(root, "extra-out")) }

            val bomb = File(root, "bomb.zip")
            ZipOutputStream(bomb.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(ByteArray(20_000)); zip.closeEntry()
            }
            val strict = SafeBackupArchiveCodec(BackupLimits(maxCompressionRatio = 2, maxManifestBytes = 30_000))
            assertThrows(BackupValidationException::class.java) { strict.decode(bomb, File(root, "bomb-out")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun `missing manifest image is rejected`() {
        val root = createTempDirectory("backup-missing-").toFile()
        try {
            val db = File(root, "source.sqlite").apply { writeBytes("SQLite format 3\u0000payload".toByteArray()) }
            val image = File(root, "a.webp").apply { writeBytes(byteArrayOf(0x52,0x49,0x46,0x46,4,0,0,0,0x57,0x45,0x42,0x50)) }
            val complete = File(root, "complete.zip")
            codec.encode(complete, db, listOf(BackupImage("a", "images/a.webp", image, "PRODUCT")), BackupCounts(0,0,0,1,0,0), 1, 1)
            val missing = File(root, "missing.zip")
            java.util.zip.ZipInputStream(complete.inputStream()).use { input ->
                ZipOutputStream(missing.outputStream()).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (entry.name != "images/a.webp") {
                            output.putNextEntry(ZipEntry(entry.name)); input.copyTo(output); output.closeEntry()
                        }
                    }
                }
            }
            assertThrows(BackupValidationException::class.java) { codec.decode(missing, File(root, "out")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun `streaming archive loop observes cancellation checks`() {
        val root = createTempDirectory("backup-cancel-loop-").toFile()
        try {
            val db = File(root, "source.sqlite").apply {
                outputStream().use { output -> output.write("SQLite format 3\u0000".toByteArray()); output.write(ByteArray(512 * 1024)) }
            }
            var checks = 0
            assertThrows(CancellationException::class.java) {
                codec.encode(File(root, "cancelled.zip"), db, emptyList(), BackupCounts(0,0,0,0,0,0), 1, 1) {
                    if (++checks == 8) throw CancellationException("cancel")
                }
            }
            org.junit.Assert.assertTrue(checks >= 8)
        } finally { root.deleteRecursively() }
    }

    @Test fun `streaming extraction loop propagates cancellation and cleans output`() {
        val root = createTempDirectory("backup-decode-cancel-").toFile()
        try {
            val permissiveCodec = SafeBackupArchiveCodec(BackupLimits(maxCompressionRatio = 20_000))
            val db = File(root, "source.sqlite").apply { outputStream().use { it.write("SQLite format 3\u0000".toByteArray()); it.write(ByteArray(256 * 1024)) } }
            val archive = File(root, "source.zip")
            permissiveCodec.encode(archive, db, emptyList(), BackupCounts(0,0,0,0,0,0), 1, 1)
            val output = File(root, "output")
            var checks = 0

            assertThrows(CancellationException::class.java) {
                permissiveCodec.decode(archive, output) { if (++checks == 6) throw CancellationException("cancel") }
            }

            org.junit.Assert.assertFalse(output.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun `actual expanded bytes defeat a low reported central directory size`() {
        val root = createTempDirectory("backup-central-size-").toFile()
        try {
            val db = File(root, "source.sqlite").apply {
                outputStream().use { output -> output.write("SQLite format 3\u0000".toByteArray()); output.write(ByteArray(1024 * 1024)) }
            }
            val archive = File(root, "malicious.zip")
            codec.encode(archive, db, emptyList(), BackupCounts(0,0,0,0,0,0), 1, 1)
            val bytes = archive.readBytes()
            val name = "database.sqlite".toByteArray()
            var patched = false
            for (index in 0..bytes.size - 46 - name.size) {
                if (bytes[index] == 0x50.toByte() && bytes[index + 1] == 0x4b.toByte() && bytes[index + 2] == 0x01.toByte() && bytes[index + 3] == 0x02.toByte() &&
                    bytes.copyOfRange(index + 46, index + 46 + name.size).contentEquals(name)) {
                    byteArrayOf(1, 0, 0, 0).copyInto(bytes, index + 24)
                    patched = true
                    break
                }
            }
            org.junit.Assert.assertTrue("central database entry was patched", patched)
            archive.writeBytes(bytes)
            val output = File(root, "output")
            val strict = SafeBackupArchiveCodec(BackupLimits(maxExpandedBytes = 128 * 1024, maxDatabaseBytes = 2L * 1024 * 1024))

            assertThrows(BackupValidationException::class.java) { strict.decode(archive, output) }

            org.junit.Assert.assertFalse(output.exists())
        } finally { root.deleteRecursively() }
    }
}
