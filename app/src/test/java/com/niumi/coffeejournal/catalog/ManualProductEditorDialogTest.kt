package com.niumi.coffeejournal.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualProductEditorDialogTest {
    @Test fun `dialog labels public product kinds`() {
        assertEquals("黑咖", publicKindLabel(com.niumi.coffeejournal.core.model.ChainProductKind.BLACK))
        assertEquals("果咖", publicKindLabel(com.niumi.coffeejournal.core.model.ChainProductKind.FRUIT))
        assertEquals("奶咖", publicKindLabel(com.niumi.coffeejournal.core.model.ChainProductKind.MILK))
    }
}
