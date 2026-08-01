package com.niumi.coffeejournal.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalScreenRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `record button switches from calendar to editor`() {
        var recordRequested = false
        compose.setContent {
            CoffeeTheme {
                JournalScreen(
                    state = JournalUiState.empty(2026, 8),
                    onPreviousMonth = {},
                    onNextMonth = {},
                    onDayClick = {},
                    onRecordDrink = { recordRequested = true },
                )
            }
        }

        compose.onNodeWithText("记录一杯").performClick()
        compose.runOnIdle { assert(recordRequested) }
    }

    @Test
    fun `missing image dialog exposes screenshot select and skip actions`() {
        compose.setContent {
            CoffeeTheme {
                RecordDrinkScreen(
                    state = JournalUiState.empty(2026, 8).editor.copy(needsImagePrompt = true),
                    brands = emptyList(),
                    items = emptyList(),
                    onSourceTypeChange = {}, onBrandSelect = {}, onItemSelect = {},
                    onRatingChange = {}, onPriceChange = {}, onBrewMethodChange = {}, onNoteChange = {},
                    onSave = {}, onBack = {}, onScreenshot = {}, onSelectImage = {}, onSkipImage = {},
                )
            }
        }

        compose.onNodeWithText("上传完整截图").assertIsDisplayed()
        compose.onNodeWithText("选择图片").assertIsDisplayed()
        compose.onNodeWithText("暂时跳过").assertIsDisplayed()
    }
}
