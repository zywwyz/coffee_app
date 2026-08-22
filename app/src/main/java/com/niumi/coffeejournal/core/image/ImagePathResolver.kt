package com.niumi.coffeejournal.core.image

import android.graphics.BitmapFactory
import com.niumi.coffeejournal.core.database.ImageAssetDao
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface ImagePathResolver {
    suspend fun resolve(assetId: String?): String?
}

class RoomImagePathResolver(
    private val imageAssetDao: ImageAssetDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImagePathResolver {
    override suspend fun resolve(assetId: String?): String? {
        if (assetId == null) return null
        return withContext(ioDispatcher) {
            val path = imageAssetDao.get(assetId)?.localPath ?: return@withContext null
            val file = File(path)
            if (!file.isFile || !file.canRead()) return@withContext null
            if (file.extension.lowercase() !in SUPPORTED_EXTENSIONS) return@withContext null
            if (!file.hasSupportedImageHeader()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            file.absolutePath.takeIf { bounds.outWidth > 0 && bounds.outHeight > 0 }
        }
    }
}

private fun File.hasSupportedImageHeader(): Boolean {
    val header = ByteArray(12)
    val read = inputStream().buffered().use { it.read(header) }
    if (read < 3) return false
    val png = read >= 8 && header.copyOfRange(0, 8).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
    )
    val jpeg = header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()
    val webp = read >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
    return png || jpeg || webp
}

private val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
