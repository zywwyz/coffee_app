package com.niumi.coffeejournal.catalog

import androidx.compose.ui.graphics.ImageBitmap
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.image.ThumbnailLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualProductEditorDialogTest {
    @Test fun `dialog labels public product kinds`() {
        assertEquals("黑咖", publicKindLabel(com.niumi.coffeejournal.core.model.ChainProductKind.BLACK))
        assertEquals("果咖", publicKindLabel(com.niumi.coffeejournal.core.model.ChainProductKind.FRUIT))
        assertEquals("奶咖", publicKindLabel(com.niumi.coffeejournal.core.model.ChainProductKind.MILK))
    }
    @Test fun `preview resolves asset asynchronously through thumbnail loader`() = runBlocking {
        var requested: String? = null
        val resolver = ImagePathResolver { id -> requested = id; "/safe/thumb.jpg" }
        val loader = ThumbnailLoader { path -> assertEquals("/safe/thumb.jpg", path); null }
        assertEquals(null, loadManualProductPreview("product", resolver, loader))
        assertEquals("product", requested)
    }
}
