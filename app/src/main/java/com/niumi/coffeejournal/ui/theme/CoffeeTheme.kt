package com.niumi.coffeejournal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Cream = Color(0xFFFAF7F1)
val Espresso = Color(0xFF2E241A)
val Evergreen = Color(0xFF2F5D50)
val Caramel = Color(0xFFC78956)

private val CoffeeColorScheme = lightColorScheme(
    primary = Evergreen,
    onPrimary = Color.White,
    secondary = Caramel,
    onSecondary = Espresso,
    background = Cream,
    onBackground = Espresso,
    surface = Cream,
    onSurface = Espresso,
    surfaceVariant = Color(0xFFF0E8DC),
    onSurfaceVariant = Espresso,
)

@Composable
fun CoffeeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CoffeeColorScheme,
        content = content,
    )
}
