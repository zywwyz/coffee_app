package com.niumi.coffeejournal.core.image

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

private fun decodeThumbnail(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 256 || bounds.outHeight / sampleSize > 256) sampleSize *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })?.asImageBitmap()
}
