package com.niumi.coffeejournal.insights

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
class InsightsScreenRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `tabs expose monthly yearly and switch callbacks`() {
        var yearlyClicks = 0
        val state = state()
        compose.setContent {
            CoffeeTheme {
                InsightsScreen(state, onShowMonthly = {}, onShowYearly = { yearlyClicks++ })
            }
        }

        compose.onNodeWithText("月度总结").assertIsDisplayed()
        compose.onNodeWithText("年度总结").performClick()
        compose.runOnIdle { assert(yearlyClicks == 1) }
    }

    @Test
    fun `small screen scrolls to preferences and charts have accessible facts`() {
        compose.setContent { CoffeeTheme { InsightsScreen(state(), {}, {}) } }

        compose.onNodeWithText("偏好排行").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("消费趋势图：第1周 10.00元").assertExists()
        compose.onNodeWithContentDescription("品牌消费占比图：瑞幸 100%").assertExists()
    }

    @Test
    fun `empty state remains useful`() {
        val empty = state().copy(monthly = InsightsCalculator.monthly(2026, 8, emptyList(), emptyList()))
        compose.setContent { CoffeeTheme { InsightsScreen(empty, {}, {}) } }

        compose.onNodeWithText("这个月还没有咖啡记录").assertIsDisplayed()
    }

    private fun state(): InsightsUiState {
        val point = TrendPoint("第1周", 1_000, 4.5)
        val period = PeriodInsights(
            1, 1_000, 1_000, 4.5,
            listOf(RankedValue("瑞幸", 1)), listOf(RankedValue("生椰拿铁", 1)),
            emptyList(), listOf(RankedValue("冰", 1)), listOf("one"), listOf("one"), listOf(point),
        )
        return InsightsUiState(
            year = 2026,
            month = 8,
            loading = false,
            monthly = MonthlyInsights(
                2026, 8, period,
                SpendDelta(1_000, null, SpendDeltaBaseline.MISSING),
                listOf(BrandSpendShare("瑞幸", 1_000, 1.0)),
                null,
            ),
        )
    }
}
