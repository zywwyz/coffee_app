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
    primaryContainer = Color(0xFFD9E8E2),
    onPrimaryContainer = Espresso,
    secondary = Caramel,
    onSecondary = Espresso,
    secondaryContainer = Caramel,
    onSecondaryContainer = Espresso,
    tertiary = Color(0xFF725A42),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF1DFC9),
    onTertiaryContainer = Espresso,
    background = Cream,
    onBackground = Espresso,
    surface = Cream,
    onSurface = Espresso,
    surfaceVariant = Color(0xFFF0E8DC),
    onSurfaceVariant = Espresso,
    surfaceTint = Evergreen,
    inverseSurface = Espresso,
    inverseOnSurface = Cream,
    inversePrimary = Color(0xFF9FCFC0),
    outline = Color(0xFF817568),
    outlineVariant = Color(0xFFD5C8B8),
    surfaceBright = Cream,
    surfaceDim = Color(0xFFDED4C8),
    surfaceContainerLowest = Color(0xFFFFFCF7),
    surfaceContainerLow = Color(0xFFF7F1E8),
    surfaceContainer = Color(0xFFF0E8DC),
    surfaceContainerHigh = Color(0xFFE9DED0),
    surfaceContainerHighest = Color(0xFFE2D3C2),
)

@Composable
fun CoffeeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CoffeeColorScheme,
        content = content,
    )
}
