package com.niumi.coffeejournal.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CoffeeVisualsTest {
    @Test
    fun cream_forest_visual_tokens_match_the_approved_palette_and_scale() {
        assertEquals(Color(0xFFFAF6EB), CoffeeVisuals.cream)
        assertEquals(Color.White, CoffeeVisuals.white)
        assertEquals(Color(0xFF596A3B), CoffeeVisuals.forest)
        assertEquals(Color(0xFFF6DBA4), CoffeeVisuals.peach)
        assertEquals(Color(0xFFE5EBD5), CoffeeVisuals.mint)
        assertEquals(Color(0xFF363B30), CoffeeVisuals.darkCoffee)
        assertEquals(Color(0xFF777568), CoffeeVisuals.secondaryText)
        assertEquals(12.dp, CoffeeVisuals.cornerSmall)
        assertEquals(18.dp, CoffeeVisuals.cornerMedium)
        assertEquals(24.dp, CoffeeVisuals.cornerLarge)
    }
}
