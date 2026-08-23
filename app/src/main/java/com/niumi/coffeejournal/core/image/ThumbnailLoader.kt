package com.niumi.coffeejournal.core.image

import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val THUMBNAIL_TARGET_EDGE_PX = 512

fun interface ThumbnailLoader {
    suspend fun load(path: String?): ImageBitmap?
}

class CalendarThumbnailLoader(
    maxEntries: Int = 24,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ThumbnailLoader {
    private val cache = BoundedLruCache<ThumbnailKey, ImageBitmap>(maxEntries)
    private val loadMutex = Mutex()

    override suspend fun load(path: String?): ImageBitmap? {
        if (path == null) return null
        return withContext(ioDispatcher) {
            loadMutex.withLock {
                val file = File(path)
                if (!file.isFile) return@withLock null
                if (!file.hasSupportedThumbnailHeader()) return@withLock null
                val key = ThumbnailKey(file.absolutePath, file.lastModified(), file.length())
                cache.get(key)?.let { return@withLock it }
                decodeThumbnail(file.absolutePath)?.also { cache.put(key, it) }
            }
        }
    }
}

internal class BoundedLruCache<K, V>(private val maxEntries: Int) {
    init {
        require(maxEntries > 0) { "Cache capacity must be positive" }
    }

    private val entries = LinkedHashMap<K, V>(maxEntries, 0.75f, true)

    @Synchronized fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = value
        while (entries.size > maxEntries) entries.remove(entries.entries.first().key)
    }

    val size: Int
        @Synchronized get() = entries.size
}

private data class ThumbnailKey(val path: String, val modifiedAt: Long, val length: Long)

private fun File.hasSupportedThumbnailHeader(): Boolean {
    if (extension.lowercase() !in setOf("png", "jpg", "jpeg", "webp")) return false
    val header = ByteArray(12)
    val read = inputStream().buffered().use { it.read(header) }
    return (read >= 8 && header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) ||
        (read >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()) ||
        (read >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP")
}

private fun decodeThumbnail(path: String): ImageBitmap? {
    return decodeThumbnailBitmap(path)?.asImageBitmap()
}

internal fun decodeThumbnailBitmap(path: String): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= THUMBNAIL_TARGET_EDGE_PX) sampleSize *= 2
    val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize }) ?: return null
    val orientation = runCatching {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val oriented = exifTransform(orientation, bitmap.width, bitmap.height).apply(bitmap)
    if (oriented !== bitmap) bitmap.recycle()
    return oriented
}

internal data class ExifTransform(
    val targetWidth: Int,
    val targetHeight: Int,
    internal val matrix: Matrix?,
    private val map: (Int, Int) -> Pair<Int, Int>,
) {
    fun apply(source: android.graphics.Bitmap): android.graphics.Bitmap = matrix?.let {
        android.graphics.Bitmap.createBitmap(source, 0, 0, source.width, source.height, it, true)
    } ?: source
    fun mapSource(x: Int, y: Int): Pair<Int, Int> = map(x, y)
}

internal fun exifTransform(orientation: Int, width: Int, height: Int): ExifTransform {
    val matrix = Matrix()
    val mapping: (Int, Int) -> Pair<Int, Int>
    val targetWidth: Int
    val targetHeight: Int
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> { matrix.setScale(-1f, 1f); mapping = { x, y -> width - 1 - x to y }; targetWidth = width; targetHeight = height }
        ExifInterface.ORIENTATION_ROTATE_180 -> { matrix.setRotate(180f); mapping = { x, y -> width - 1 - x to height - 1 - y }; targetWidth = width; targetHeight = height }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.setScale(1f, -1f); mapping = { x, y -> x to height - 1 - y }; targetWidth = width; targetHeight = height }
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f); mapping = { x, y -> y to x }; targetWidth = height; targetHeight = width }
        ExifInterface.ORIENTATION_ROTATE_90 -> { matrix.setRotate(90f); mapping = { x, y -> height - 1 - y to x }; targetWidth = height; targetHeight = width }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f); mapping = { x, y -> height - 1 - y to width - 1 - x }; targetWidth = height; targetHeight = width }
        ExifInterface.ORIENTATION_ROTATE_270 -> { matrix.setRotate(-90f); mapping = { x, y -> y to width - 1 - x }; targetWidth = height; targetHeight = width }
        else -> return ExifTransform(width, height, null) { x, y -> x to y }
    }
    return ExifTransform(targetWidth, targetHeight, matrix, mapping)
}
