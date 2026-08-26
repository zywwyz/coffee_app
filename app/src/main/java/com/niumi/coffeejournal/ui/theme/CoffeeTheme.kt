package com.niumi.coffeejournal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.niumi.coffeejournal.ui.CoffeeVisuals

private val CoffeeColorScheme = lightColorScheme(
    primary = Evergreen,
    onPrimary = Color.White,
    primaryContainer = CoffeeVisuals.mint,
    onPrimaryContainer = Espresso,
    secondary = Caramel,
    onSecondary = Espresso,
    secondaryContainer = Caramel,
    onSecondaryContainer = Espresso,
    tertiary = CoffeeVisuals.mint,
    onTertiary = Espresso,
    tertiaryContainer = CoffeeVisuals.mint,
    onTertiaryContainer = Espresso,
    background = Cream,
    onBackground = Espresso,
    surface = CoffeeVisuals.white,
    onSurface = Espresso,
    surfaceVariant = CoffeeVisuals.mint,
    onSurfaceVariant = CoffeeVisuals.secondaryText,
    surfaceTint = Evergreen,
    inverseSurface = Espresso,
    inverseOnSurface = Cream,
    inversePrimary = CoffeeVisuals.mint,
    outline = CoffeeVisuals.warmOutline,
    outlineVariant = CoffeeVisuals.warmOutline,
    surfaceBright = CoffeeVisuals.white,
    surfaceDim = Color(0xFFF0ECE5),
    surfaceContainerLowest = CoffeeVisuals.white,
    surfaceContainerLow = Color(0xFFFFFCF8),
    surfaceContainer = Cream,
    surfaceContainerHigh = Color(0xFFF3EEE7),
    surfaceContainerHighest = Color(0xFFECE6DE),
)

@Composable
fun CoffeeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CoffeeColorScheme,
        content = content,
    )
}
