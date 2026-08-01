package com.niumi.coffeejournal.navigation

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.Color
import com.niumi.coffeejournal.ui.theme.Caramel
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import com.niumi.coffeejournal.ui.theme.Espresso
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bottom_bar_opens_three_roots() {
        compose.setContent { CoffeeTheme { AppNavigation() } }

        compose.onNodeWithText("日记").assertIsDisplayed()
        compose.onNodeWithText("豆库").performClick()
        compose.onNodeWithText("连锁品牌").assertIsDisplayed()
        compose.onNodeWithText("总结").performClick()
        compose.onNodeWithText("月度总结").assertIsDisplayed()
    }

    @Test
    fun theme_supplies_warm_navigation_bar_tokens() {
        var captured: ColorScheme? = null
        compose.setContent {
            CoffeeTheme {
                captured = MaterialTheme.colorScheme
            }
        }

        compose.runOnIdle {
            val colors = requireNotNull(captured)
            assertEquals(Color(0xFFF0E8DC), colors.surfaceContainer)
            assertEquals(Espresso, colors.onSurface)
            assertEquals(Caramel, colors.secondaryContainer)
            assertEquals(Espresso, colors.onSecondaryContainer)
        }
    }
}
