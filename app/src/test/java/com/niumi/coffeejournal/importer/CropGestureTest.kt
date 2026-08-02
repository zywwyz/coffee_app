package com.niumi.coffeejournal.importer

import com.niumi.coffeejournal.core.image.CropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropGestureTest {
    private val letterboxed = PreviewCoordinateTransform(
        containerWidth = 1000f,
        containerHeight = 1000f,
        imageWidth = 500,
        imageHeight = 1000,
    )

    @Test
    fun `display coordinates account for horizontal letterbox`() {
        assertEquals(ImagePoint(0f, 0f), letterboxed.displayToImage(250f, 0f))
        assertEquals(ImagePoint(500f, 1000f), letterboxed.displayToImage(750f, 1000f))
    }

    @Test
    fun `moving crop maps display delta and clamps inside image`() {
        val moved = applyCropDrag(
            CropRect(100, 100, 400, 800), CropDragHandle.MOVE,
            displayDeltaX = 200f, displayDeltaY = 400f,
            transform = letterboxed, minimumSizePx = 40,
        )
        assertEquals(CropRect(200, 300, 500, 1000), moved)
    }

    @Test
    fun `dragging corner resizes with minimum size`() {
        val resized = applyCropDrag(
            CropRect(100, 100, 400, 800), CropDragHandle.TOP_LEFT,
            displayDeltaX = 500f, displayDeltaY = 900f,
            transform = letterboxed, minimumSizePx = 80,
        )
        assertEquals(CropRect(320, 720, 400, 800), resized)
    }

    @Test
    fun `resizing a slider-created tiny crop restores minimum size without throwing`() {
        val resized = applyCropDrag(
            CropRect(490, 990, 500, 1000), CropDragHandle.BOTTOM_RIGHT,
            displayDeltaX = 20f, displayDeltaY = 20f,
            transform = letterboxed, minimumSizePx = 48,
        )

        assertTrue(resized.width >= 48)
        assertTrue(resized.height >= 48)
        assertEquals(500, resized.right)
        assertEquals(1000, resized.bottom)
    }

    @Test
    fun `hit testing ignores the letterbox outside the displayed image`() {
        val fullImageCrop = CropRect(0, 0, 500, 1000)

        assertEquals(null, hitTestCropHandle(100f, 500f, fullImageCrop, letterboxed, 24f))
    }
}
