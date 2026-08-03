package com.niumi.coffeejournal.insights

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
        compose.onNodeWithText("消费柱", substring = true).assertExists()
        compose.onNodeWithText("评分折线", substring = true).assertExists()
        compose.onNodeWithText("第1周").assertExists()
        compose.onAllNodesWithText("¥10.00")[0].assertExists()
        compose.onAllNodesWithText("4.5★")[0].assertExists()
        compose.onNodeWithContentDescription("第1周 消费10.00元，平均评分4.5星", substring = true).assertExists()
        compose.onNodeWithContentDescription("品牌消费占比图：瑞幸 100%").assertExists()
    }

    @Test
    fun `best record opens immutable snapshot details instead of exposing id`() {
        compose.setContent { CoffeeTheme { InsightsScreen(state(), {}, {}) } }

        compose.onAllNodesWithText("瑞幸 · 生椰拿铁 · 4.5★")[0].performScrollTo().performClick()
        compose.onNodeWithText("原始记录").assertIsDisplayed()
        compose.onNodeWithText("2026-08-01").assertIsDisplayed()
        compose.onNodeWithText("实际支付：¥10.00").assertIsDisplayed()
        compose.onNodeWithText("产地：云南").assertExists()
    }

    @Test
    fun `chart semantics never invent price or rating facts`() {
        val base = state()
        val points = listOf(
            TrendPoint("第1周", null, 0, 1, 4.0),
            TrendPoint("第2周", 0, 1, 0, null),
        )
        val report = requireNotNull(base.monthly).copy(period = base.monthly.period.copy(points = points))
        compose.setContent { CoffeeTheme { InsightsScreen(base.copy(monthly = report), {}, {}) } }

        compose.onNodeWithContentDescription(
            "消费与评分趋势图：第1周 平均评分4.0星；第2周 消费0.00元",
        ).assertExists()
    }

    @Test
    fun `signed delta handles minimum long without double minus`() {
        org.junit.Assert.assertEquals("-¥92233720368547758.08", formatSignedFen(Long.MIN_VALUE))
    }

    @Test
    fun `empty state remains useful`() {
        val empty = state().copy(monthly = InsightsCalculator.monthly(2026, 8, emptyList(), emptyList()))
        compose.setContent { CoffeeTheme { InsightsScreen(empty, {}, {}) } }

        compose.onNodeWithText("这个月还没有咖啡记录").assertIsDisplayed()
    }

    private fun state(): InsightsUiState {
        val point = TrendPoint("第1周", 1_000, 1, 2, 4.5)
        val summary = RatedRecordSummary(
            "one", "2026-08-01", "瑞幸", "生椰拿铁", 9, 1_000, "冰", "清爽".repeat(100),
            origin = "云南", processing = "水洗", roastLevel = "中烘", flavorNotes = "坚果",
        )
        val period = PeriodInsights(
            1, 1_000, 1_000, 4.5,
            listOf(RankedValue("瑞幸", 1)), listOf(RankedValue("生椰拿铁", 1)),
            emptyList(), listOf(RankedValue("冰", 1)), listOf("one"), listOf("one"), listOf(point),
            bestRecords = listOf(summary), worstRecords = listOf(summary),
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
