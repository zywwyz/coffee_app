package com.niumi.coffeejournal.core.image

import androidx.compose.ui.layout.ContentScale
import org.junit.Assert.assertSame
import org.junit.Test

class ImagePresentationContractTest {
    @Test
    fun `complete image presentation uses fit scaling`() {
        assertSame(ContentScale.Fit, CompleteImageContentScale)
    }
}
