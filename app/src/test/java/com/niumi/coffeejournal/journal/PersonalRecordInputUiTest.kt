package com.niumi.coffeejournal.journal

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.niumi.coffeejournal.core.model.*
import com.niumi.coffeejournal.TestTags
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
class PersonalRecordInputUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `real calendar day detail displays bean weight and drip bag count`() {
        val bean = DrinkRecord("bean", 1, "2026-10-07", ItemType.PERSONAL_BEAN, "bean", "意式", 9, 600, null, DrinkSnapshot("烘焙商", "咖啡豆产品", null, null, null, coffeeType = CoffeeType.ESPRESSO), consumedWeightMg = 15000)
        val bag = DrinkRecord("bag", 2, "2026-10-07", ItemType.PERSONAL_BEAN, "bag", null, 8, 1180, null, DrinkSnapshot("烘焙商", "挂耳产品", null, null, null, coffeeType = CoffeeType.DRIP_BAG), personalProductType = PersonalProductType.DRIP_BAG, consumedCount = 2)
        val state = JournalUiState.empty(2026, 10).copy(records = listOf(bean, bag), selectedDate = "2026-10-07")
        compose.setContent { CoffeeTheme { JournalScreen(state, {}, {}, {}, {}) } }
        compose.onNodeWithText("挂耳咖啡 · 2包").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("咖啡豆 · 意式 · 15克").performScrollTo().assertIsDisplayed()
    }

    @Test fun `bean method quantity manual and automatic controls reach callbacks`() {
        val state = mutableStateOf(RecordEditorUi(sourceType = ItemType.PERSONAL_BEAN, selectedItemId = "bean", brewMethod = "手冲", personalProductType = PersonalProductType.BEANS, priceMode = PriceMode.AUTO))
        var quantity = ""
        var resetCalls = 0
        compose.setContent { CoffeeTheme { RecordDrinkScreen(state.value, emptyList(), emptyList(), {}, {}, {}, {}, { input -> state.value = state.value.copy(priceInput = input, priceMode = PriceMode.MANUAL) }, { method -> state.value = state.value.copy(brewMethod = method) }, {}, onSave = {}, onBack = {}, onSelectImage = {}, onSkipImage = {}, onConsumedWeightChange = { input -> quantity = input; state.value = state.value.copy(consumedWeightInput = input) }, onRestoreAutomaticPrice = { resetCalls++; state.value = state.value.copy(priceMode = PriceMode.AUTO) }) } }
        compose.onNodeWithText("意式").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("意式", state.value.brewMethod) }
        compose.onNodeWithText("本次用豆（克，可选）").performScrollTo().performTextReplacement("18.5")
        compose.runOnIdle { assertEquals("18.5", quantity) }
        compose.onNodeWithText("本次费用（元）").performScrollTo().performTextReplacement("7.25")
        compose.onNodeWithText("已手动填写，本次用量变化不会覆盖金额").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("恢复自动计算").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, resetCalls); assertEquals(PriceMode.AUTO, state.value.priceMode) }
    }

    @Test fun `drip bag uses count and invalid count disables save`() {
        val state = mutableStateOf(RecordEditorUi(sourceType = ItemType.PERSONAL_BEAN, selectedItemId = "bag", personalProductType = PersonalProductType.DRIP_BAG, consumedCountInput = "1", priceMode = PriceMode.AUTO))
        var count = ""
        compose.setContent { CoffeeTheme { RecordDrinkScreen(state.value, emptyList(), emptyList(), {}, {}, {}, {}, {}, {}, {}, onSave = {}, onBack = {}, onSelectImage = {}, onSkipImage = {}, onConsumedCountChange = { input -> count = input; state.value = state.value.copy(consumedCountInput = input, quantityValid = parsePositiveCount(input) != null) }) } }
        compose.onNodeWithText("本次饮用（包）").performScrollTo().assertTextContains("1").performTextReplacement("1.5")
        compose.runOnIdle { assertEquals("1.5", count) }
        compose.onNodeWithText("请输入正整数包数").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(TestTags.ConfirmSave).performScrollTo().assertIsNotEnabled()
        assertTrue(compose.onAllNodesWithText("本次用豆（克，可选）").fetchSemanticsNodes().isEmpty())
    }
}
