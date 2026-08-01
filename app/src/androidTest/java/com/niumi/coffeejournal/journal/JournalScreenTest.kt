package com.niumi.coffeejournal.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Rule
import org.junit.Test

class JournalScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun calendar_shows_fixed_month_summary_and_record_action() {
        compose.setContent {
            CoffeeTheme {
                JournalScreen(
                    state = JournalUiState.empty(2026, 8),
                    onPreviousMonth = {}, onNextMonth = {}, onDayClick = {}, onRecordDrink = {},
                )
            }
        }

        compose.onNodeWithText("2026年8月").assertIsDisplayed()
        compose.onNodeWithText("0 杯").assertIsDisplayed()
        compose.onNodeWithText("记录一杯").assertIsDisplayed()
    }
}
