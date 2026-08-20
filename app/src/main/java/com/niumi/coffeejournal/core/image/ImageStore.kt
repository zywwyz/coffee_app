package com.niumi.coffeejournal.core.image

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.niumi.coffeejournal.core.database.ImageAssetDao
import com.niumi.coffeejournal.core.database.ImageAssetEntity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ImageStore {
    suspend fun importWhole(source: Uri, kind: ImageKind): ImageAsset
    suspend fun deleteIfUnreferenced(assetId: String): Boolean
}

/** Serializes all mutations of managed image files with their image_assets rows. */
internal object ImageMutationCoordinator {
    val mutex = Mutex()
}

internal interface WholeImportHooks {
    suspend fun afterPrepared()
    suspend fun beforeMutationLock()
    suspend fun afterMutationCommitted()
    suspend fun afterMutationReturned()
    suspend fun beforeRollback()

    data object None : WholeImportHooks {
        override suspend fun afterPrepared() = Unit
        override suspend fun beforeMutationLock() = Unit
        override suspend fun afterMutationCommitted() = Unit
        override suspend fun afterMutationReturned() = Unit
        override suspend fun beforeRollback() = Unit
    }
}

enum class ImageKind { PRODUCT, BRAND_LOGO, BEAN_PACKAGE, RECORD_SNAPSHOT }

data class ImageAsset(
    val id: String,
    val localPath: String,
    val sha256: String,
    val kind: ImageKind,
)

class ImageDecodeException : IllegalArgumentException("The selected image cannot be decoded")

internal class LocalImageStore(
    context: Context,
    private val imageAssetDao: ImageAssetDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val newAssetId: () -> String = { UUID.randomUUID().toString() },
    private val persistAsset: suspend (ImageAssetEntity) -> ImageAssetEntity = { candidate ->
        imageAssetDao.insertIgnoringExisting(candidate)
        imageAssetDao.getBySha256(candidate.sha256)
            ?: error("Image asset was not persisted")
    },
    private val beforeAssetDelivery: suspend (ImageAsset) -> Unit = {},
    private val wholeImportHooks: WholeImportHooks = WholeImportHooks.None,
) : ImageStore {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val imageDirectory = File(context.applicationContext.filesDir, "images")

    override suspend fun importWhole(source: Uri, kind: ImageKind): ImageAsset {
        var temporary: File? = null
        try {
            val prepared = withContext(Dispatchers.IO) {
                imageDirectory.mkdirs()
                val createdTemporary = File.createTempFile("whole-", ".tmp", imageDirectory)
                temporary = createdTemporary
                try {
                    streamSourceToTemporary(source, createdTemporary)
                    requireDecodeableBounds(createdTemporary)
                    val extension = imageExtension(createdTemporary) ?: throw ImageDecodeException()
                    val preparedImage = WholeImagePreparation(createdTemporary, extension, sha256(createdTemporary))
                    wholeImportHooks.afterPrepared()
                    preparedImage
                } catch (error: Throwable) {
                    createdTemporary.delete()
                    throw error
                }
            }
            val preparedTemporary = prepared.temporary
            coroutineContext.ensureActive()
            wholeImportHooks.beforeMutationLock()
            return ImageMutationCoordinator.mutex.withLock {
                var target: File? = null
                var createdTarget = false
                var createdAsset: ImageAssetEntity? = null
                try {
                    val delivered = withContext(Dispatchers.IO) {
                        imageAssetDao.getBySha256(prepared.sha256)?.let { existing ->
                            preparedTemporary.delete()
                            temporary = null
                            return@withContext validateStored(existing, prepared.sha256)
                        }
                        target = File(imageDirectory, "${prepared.sha256}.${prepared.extension}")
                        if (target.exists()) {
                            preparedTemporary.delete()
                        } else {
                            if (!preparedTemporary.renameTo(target)) throw IllegalStateException("Unable to atomically store image")
                            createdTarget = true
                        }
                        val candidate = ImageAssetEntity(
                            newAssetId(),
                            checkNotNull(target).absolutePath,
                            prepared.sha256,
                            kind.name,
                            now(),
                        )
                        var stored: ImageAssetEntity? = null
                        withContext(NonCancellable) {
                            stored = persistAsset(candidate)
                            if (stored?.id == candidate.id) createdAsset = stored
                        }
                        val delivered = validateStored(checkNotNull(stored), prepared.sha256)
                        wholeImportHooks.afterMutationCommitted()
                        beforeAssetDelivery(delivered)
                        coroutineContext.ensureActive()
                        temporary = null
                        delivered
                    }
                    wholeImportHooks.afterMutationReturned()
                    delivered
                } catch (error: Throwable) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        wholeImportHooks.beforeRollback()
                        preparedTemporary.delete()
                        temporary = null
                        createdAsset?.let { owned ->
                            runCatching {
                                if (imageAssetDao.referenceCount(owned.id) == 0 && imageAssetDao.deleteIfUnreferenced(owned.id) == 1) {
                                    managedFileOrNull(owned.localPath)?.delete()
                                }
                            }
                        }
                        if (createdTarget) {
                            val hasDatabaseOwner = target?.let { file ->
                                runCatching { imageAssetDao.getBySha256(file.nameWithoutExtension) != null }.getOrDefault(false)
                            } == true
                            if (!hasDatabaseOwner) target?.delete()
                        }
                    }
                    throw error
                }
            }
        } finally {
            temporary?.let { lingeringTemporary ->
                withContext(NonCancellable + Dispatchers.IO) {
                    lingeringTemporary.delete()
                }
            }
        }
    }

    override suspend fun deleteIfUnreferenced(assetId: String): Boolean = withContext(Dispatchers.IO) {
        ImageMutationCoordinator.mutex.withLock {
            val entity = imageAssetDao.get(assetId) ?: return@withLock false
            val managedFile = managedFileOrNull(entity.localPath) ?: return@withLock false
            if (imageAssetDao.referenceCount(assetId) != 0) return@withLock false
            if (imageAssetDao.deleteIfUnreferenced(assetId) != 1) return@withLock false
            if (!managedFile.exists() || managedFile.delete()) return@withLock true
            imageAssetDao.upsert(entity)
            false
        }
    }

    private suspend fun importConfirmed(
        source: Uri,
        kind: ImageKind,
        transform: (DecodedImage) -> Bitmap,
    ): ImageAsset = withContext(Dispatchers.IO) {
        ImageMutationCoordinator.mutex.withLock {
            coroutineContext.ensureActive()
            imageDirectory.mkdirs()
            val decoded = decodeOriented(source)
            val confirmed = try {
                transform(decoded)
            } catch (error: Throwable) {
                decoded.bitmap.recycle()
                throw error
            }
            if (confirmed !== decoded.bitmap) decoded.bitmap.recycle()
            val temporary = File.createTempFile("confirmed-", ".tmp", imageDirectory)
            var target: File? = null
            var createdTarget = false
            var createdAsset: ImageAssetEntity? = null
            try {
                FileOutputStream(temporary).use { output ->
                    if (!confirmed.compress(Bitmap.CompressFormat.WEBP, 90, output)) {
                        throw IllegalStateException("Image encoding failed")
                    }
                    output.fd.sync()
                }
                confirmed.recycle()
                coroutineContext.ensureActive()
                val sha256 = sha256(temporary)
                imageAssetDao.getBySha256(sha256)?.let { existing ->
                    temporary.delete()
                    return@withLock validateStored(existing, sha256)
                }
                target = File(imageDirectory, "$sha256.webp")
                if (target.exists()) {
                    temporary.delete()
                } else {
                    if (!temporary.renameTo(target)) throw IllegalStateException("Unable to atomically store image")
                    createdTarget = true
                }
                val candidate = ImageAssetEntity(newAssetId(), target.absolutePath, sha256, kind.name, now())
                var stored: ImageAssetEntity? = null
                withContext(NonCancellable) {
                    stored = persistAsset(candidate)
                    if (stored?.id == candidate.id) createdAsset = stored
                }
                val delivered = validateStored(checkNotNull(stored), sha256)
                beforeAssetDelivery(delivered)
                coroutineContext.ensureActive()
                delivered
            } catch (error: Throwable) {
                if (!confirmed.isRecycled) confirmed.recycle()
                withContext(NonCancellable) {
                    temporary.delete()
                    createdAsset?.let { owned ->
                        runCatching {
                            if (imageAssetDao.referenceCount(owned.id) == 0 && imageAssetDao.deleteIfUnreferenced(owned.id) == 1) {
                                managedFileOrNull(owned.localPath)?.delete()
                            }
                        }
                    }
                    if (createdTarget) {
                        val hasDatabaseOwner = target?.let { file ->
                            try {
                                imageAssetDao.getBySha256(file.nameWithoutExtension) != null
                            } catch (_: Exception) {
                                false
                            }
                        } == true
                        if (!hasDatabaseOwner) target?.delete()
                    }
                }
                throw error
            }
        }
    }

    private fun decodeOriented(source: Uri): DecodedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw ImageDecodeException()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImageDecodeException()
        val orientation = resolver.openInputStream(source)?.use { input ->
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        val bitmap = resolver.openInputStream(source)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw ImageDecodeException()
        val oriented = applyOrientation(bitmap, orientation)
        if (oriented !== bitmap) bitmap.recycle()
        val swapsAxes = orientation in setOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
        return DecodedImage(
            bitmap = oriented,
            orientedWidth = if (swapsAxes) bounds.outHeight else bounds.outWidth,
            orientedHeight = if (swapsAxes) bounds.outWidth else bounds.outHeight,
        )
    }

    private suspend fun streamSourceToTemporary(source: Uri, temporary: File) {
        resolver.openInputStream(source)?.use { input ->
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_WHOLE_IMAGE_BYTES) throw ImageDecodeException()
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        } ?: throw ImageDecodeException()
    }

    private fun requireDecodeableBounds(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImageDecodeException()
    }

    private fun managedFileOrNull(path: String): File? {
        val root = imageDirectory.canonicalFile
        val candidate = try { File(path).canonicalFile } catch (_: Exception) { return null }
        return candidate.takeIf { it.parentFile == root && it.name.matches(SAFE_FILE_NAME) }
    }

    private fun validateStored(entity: ImageAssetEntity, expectedSha256: String): ImageAsset {
        val file = managedFileOrNull(entity.localPath)
            ?: throw IllegalStateException("Image database path is outside private storage")
        if (!file.isFile || entity.sha256 != expectedSha256 || sha256(file) != expectedSha256) {
            throw IllegalStateException("Image database row and private file do not match")
        }
        return entity.toDomain()
    }

    private data class DecodedImage(val bitmap: Bitmap, val orientedWidth: Int, val orientedHeight: Int)
    private data class WholeImagePreparation(val temporary: File, val extension: String, val sha256: String)

    private companion object {
        const val MAX_DECODE_DIMENSION = 2048
        const val MAX_WHOLE_IMAGE_BYTES = 20L * 1024 * 1024
        val SAFE_FILE_NAME = Regex("[0-9a-f]{64}\\.(png|jpg|jpeg|webp)")

        fun imageExtension(file: File): String? = file.inputStream().use { input ->
            val header = ByteArray(12)
            val count = input.read(header)
            when {
                count >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_MAGIC) -> "png"
                count >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> "jpg"
                count >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
                else -> null
            }
        }

        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        fun sampleSize(width: Int, height: Int): Int {
            var sample = 1
            while (width / sample > MAX_DECODE_DIMENSION || height / sample > MAX_DECODE_DIMENSION) sample *= 2
            return sample
        }

        fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
                else -> return source
            }
            return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

private fun ImageAssetEntity.toDomain() = ImageAsset(
    id = id,
    localPath = localPath,
    sha256 = sha256,
    kind = ImageKind.valueOf(kind),
)
