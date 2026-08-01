package com.niumi.coffeejournal.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h360dp")
class JournalSmallScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `last calendar cell and summary are reachable by scrolling`() {
        compose.setContent {
            CoffeeTheme { JournalScreen(JournalUiState.empty(2026, 8), {}, {}, {}, {}) }
        }

        compose.onNodeWithContentDescription("日期 2026-09-06").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0 杯").performScrollTo().assertIsDisplayed()
    }
}
