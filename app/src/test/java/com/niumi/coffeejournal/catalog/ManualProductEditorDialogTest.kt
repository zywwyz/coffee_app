package com.niumi.coffeejournal.catalog

import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import com.niumi.coffeejournal.R
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.image.ThumbnailLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManualProductEditorDialogTest {
    @Test fun `dialog labels public product kinds`() {
        assertEquals("黑咖", publicKindLabel(com.niumi.coffeejournal.core.model.ChainProductKind.BLACK))
        assertEquals("果咖", publicKindLabel(com.niumi.coffeejournal.core.model.ChainProductKind.FRUIT))
        assertEquals("奶咖", publicKindLabel(com.niumi.coffeejournal.core.model.ChainProductKind.MILK))
    }
    @Test fun `preview resolves asset asynchronously through thumbnail loader`() = runBlocking {
        val requested = mutableListOf<String?>()
        val resolver = ImagePathResolver { id -> requested += id; "/safe/thumb.jpg" }
        val bitmap = BitmapFactory.decodeResource(RuntimeEnvironment.getApplication().resources, R.drawable.brand_logo_luckin).asImageBitmap()
        val loader = ThumbnailLoader { path -> assertEquals("/safe/thumb.jpg", path); bitmap }
        val preview = loadManualProductPreview("product", null, resolver, loader)
        assertNotNull(preview.bitmap)
        org.junit.Assert.assertTrue(preview.usesProductImage)
        assertEquals(listOf("product"), requested)
    }

    @Test fun `corrupt product preview falls back to a valid brand logo`() = runBlocking {
        val requested = mutableListOf<String?>()
        val resolver = ImagePathResolver { id -> requested += id; id }
        val bitmap = BitmapFactory.decodeResource(RuntimeEnvironment.getApplication().resources, R.drawable.brand_logo_luckin).asImageBitmap()
        val loader = ThumbnailLoader { path -> if (path == "brand") bitmap else null }

        val preview = loadManualProductPreview("product", "brand", resolver, loader)

        assertNotNull(preview.bitmap)
        assertFalse(preview.usesProductImage)
        assertEquals(listOf("product", "brand"), requested)
    }

    @Test fun `corrupt product and brand previews use placeholder result`() = runBlocking {
        val loader = ThumbnailLoader { null }

        val preview = loadManualProductPreview("product", "brand", ImagePathResolver { it }, loader)

        assertEquals(null, preview.bitmap)
        assertFalse(preview.usesProductImage)
    }
}
