package com.niumi.coffeejournal.core.image

import android.graphics.Bitmap
import com.niumi.coffeejournal.core.database.ImageAssetDao
import com.niumi.coffeejournal.core.database.ImageAssetEntity
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImagePathResolverTest {
    @Test
    fun `asset id resolves through dao to validated local bitmap path`() = runBlocking {
        val image = temporaryBitmap("valid")
        val resolver = RoomImagePathResolver(FakeImageAssetDao(mapOf("asset" to asset("asset", image))))

        assertEquals(image.absolutePath, resolver.resolve("asset"))
    }

    @Test
    fun `missing nonexistent and corrupt assets resolve to null`() = runBlocking {
        val corrupt = File.createTempFile("corrupt", ".png").apply { writeText("not an image") }
        val resolver = RoomImagePathResolver(
            FakeImageAssetDao(
                mapOf(
                    "missing-file" to asset("missing-file", File(corrupt.parentFile, "gone.png")),
                    "corrupt" to asset("corrupt", corrupt),
                ),
            ),
        )

        assertNull(resolver.resolve(null))
        assertNull(resolver.resolve("unknown"))
        assertNull(resolver.resolve("missing-file"))
        assertNull(resolver.resolve("corrupt"))
    }

    @Test
    fun `resolver only accepts managed image extensions`() = runBlocking {
        val image = File.createTempFile("unsupported", ".gif").also { target ->
            temporaryBitmap("unsupported-source").copyTo(target, overwrite = true)
        }
        val resolver = RoomImagePathResolver(FakeImageAssetDao(mapOf("asset" to asset("asset", image))))

        assertNull(resolver.resolve("asset"))
    }

    @Test
    fun `resolver remains compatible with stored webp paths`() = runBlocking {
        val image = File.createTempFile("legacy", ".webp").apply {
            writeBytes(java.util.Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAACQAQCdASoBAAEAAUAmJaQAA3AA/vuUAAA="))
        }
        val resolver = RoomImagePathResolver(FakeImageAssetDao(mapOf("asset" to asset("asset", image))))

        assertEquals(image.absolutePath, resolver.resolve("asset"))
    }

    private fun asset(id: String, file: File) = ImageAssetEntity(id, file.absolutePath, "$id-sha", "product", 1)

    private fun temporaryBitmap(prefix: String): File = File.createTempFile(prefix, ".png").also { file ->
        file.deleteOnExit()
        FileOutputStream(file).use { output ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private class FakeImageAssetDao(
        private val assets: Map<String, ImageAssetEntity>,
    ) : ImageAssetDao {
        override suspend fun upsert(asset: ImageAssetEntity) = Unit
        override suspend fun insertIgnoringExisting(asset: ImageAssetEntity): Long = -1
        override suspend fun get(id: String): ImageAssetEntity? = assets[id]
        override suspend fun getBySha256(sha256: String): ImageAssetEntity? = assets.values.firstOrNull { it.sha256 == sha256 }
        override suspend fun deleteIfUnreferenced(id: String): Int = 0
        override suspend fun referenceCount(id: String): Int = 0
    }
}
