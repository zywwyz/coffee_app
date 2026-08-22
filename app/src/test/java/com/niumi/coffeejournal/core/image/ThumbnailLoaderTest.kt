package com.niumi.coffeejournal.core.image

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThumbnailLoaderTest {
    @Test
    fun `jpeg thumbnail applies exif rotation and mirror orientation`() = runBlocking {
        val rotated = jpeg("rotated", ExifInterface.ORIENTATION_ROTATE_90)
        val mirrored = jpeg("mirrored", ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface(rotated).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        val loader = CalendarThumbnailLoader()

        val rotatedThumbnail = loader.load(rotated.absolutePath)
        val mirroredThumbnail = loader.load(mirrored.absolutePath)

        assertNotNull(rotatedThumbnail)
        assertEquals(20, rotatedThumbnail!!.width)
        assertEquals(30, rotatedThumbnail.height)
        val rotatedTransform = exifTransform(ExifInterface.ORIENTATION_ROTATE_90, 30, 20)
        assertEquals(20, rotatedTransform.targetWidth)
        assertEquals(30, rotatedTransform.targetHeight)
        assertEquals(listOf(19 to 0, 19 to 29, 0 to 0, 0 to 29), mapCorners(rotatedTransform))
        assertNotNull(mirroredThumbnail)
        assertEquals(30, mirroredThumbnail!!.width)
        assertEquals(20, mirroredThumbnail.height)
        val mirroredTransform = exifTransform(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, 30, 20)
        assertEquals(listOf(29 to 0, 0 to 0, 29 to 19, 0 to 19), mapCorners(mirroredTransform))
    }

    @Test
    fun `thumbnail loader accepts supported extensions and safely rejects corrupt files`() = runBlocking {
        val loader = CalendarThumbnailLoader()
        val png = bitmap("png", "png", Bitmap.CompressFormat.PNG)
        val jpg = bitmap("jpg", "jpg", Bitmap.CompressFormat.JPEG)
        val jpeg = bitmap("jpeg", "jpeg", Bitmap.CompressFormat.JPEG)
        val webp = File.createTempFile("webp", ".webp").apply {
            writeBytes(java.util.Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAACQAQCdASoBAAEAAUAmJaQAA3AA/vuUAAA="))
        }
        val corrupt = File.createTempFile("corrupt", ".jpg").apply { writeText("bad") }

        listOf(png, jpg, jpeg, webp).forEach { assertNotNull(loader.load(it.absolutePath)) }
        assertNull(loader.load(corrupt.absolutePath))
    }

    private fun jpeg(name: String, orientation: Int): File = bitmap(name, "jpg", Bitmap.CompressFormat.JPEG).also {
        ExifInterface(it).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
    }

    private fun bitmap(name: String, extension: String, format: Bitmap.CompressFormat): File =
        File.createTempFile(name, ".$extension").also { file ->
            val bitmap = markerBitmap()
            FileOutputStream(file).use { bitmap.compress(format, 100, it) }
        }

    private fun markerBitmap(): Bitmap = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888).apply {
        for (x in 0..5) for (y in 0..5) setPixel(x, y, 0xffff0000.toInt())
        for (x in 24..29) for (y in 14..19) setPixel(x, y, 0xff0000ff.toInt())
    }

    private fun mapCorners(transform: ExifTransform): List<Pair<Int, Int>> {
        val points = floatArrayOf(0f, 0f, 29f, 0f, 0f, 19f, 29f, 19f)
        transform.matrix?.mapPoints(points)
        val minX = points.filterIndexed { index, _ -> index % 2 == 0 }.min()
        val minY = points.filterIndexed { index, _ -> index % 2 == 1 }.min()
        return points.asList().chunked(2).map { (x, y) -> (x - minX).toInt() to (y - minY).toInt() }
    }

}
