package com.niumi.coffeejournal.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CoffeeVisualsTest {
    @Test
    fun cream_forest_visual_tokens_match_the_approved_palette_and_scale() {
        assertEquals(Color(0xFFFAF7F0), CoffeeVisuals.cream)
        assertEquals(Color.White, CoffeeVisuals.white)
        assertEquals(Color(0xFF1F5B49), CoffeeVisuals.forest)
        assertEquals(Color(0xFFEEA56E), CoffeeVisuals.peach)
        assertEquals(Color(0xFFDDEDE6), CoffeeVisuals.mint)
        assertEquals(Color(0xFF271C17), CoffeeVisuals.darkCoffee)
        assertEquals(Color(0xFF766A63), CoffeeVisuals.secondaryText)
        assertEquals(12.dp, CoffeeVisuals.cornerSmall)
        assertEquals(18.dp, CoffeeVisuals.cornerMedium)
        assertEquals(24.dp, CoffeeVisuals.cornerLarge)
    }
}
