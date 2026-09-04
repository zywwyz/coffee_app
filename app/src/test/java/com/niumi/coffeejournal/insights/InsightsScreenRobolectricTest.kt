package com.niumi.coffeejournal.insights

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.niumi.coffeejournal.TestTags
import com.niumi.coffeejournal.ui.CoffeeVisuals
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
class InsightsScreenRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `summary uses approved cream forest and rounded card semantics`() {
        compose.setContent { CoffeeTheme { InsightsScreen(state(), {}, {}) } }
        compose.onNodeWithTag(TestTags.InsightsSurface).assertIsDisplayed()
            .assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(InsightsSurfaceColor, CoffeeVisuals.cream))
        compose.onNodeWithTag(TestTags.InsightsHabitHero).assertIsDisplayed()
            .assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(InsightsMetricCardColor, CoffeeVisuals.white))
    }

    @Test fun `switches periods and dispatches previous next callbacks`() {
        var monthly = 0; var yearly = 0; var previous = 0; var next = 0
        compose.setContent { CoffeeTheme { InsightsScreen(state(), { monthly++ }, { yearly++ }, { previous++ }, { next++ }) } }
        compose.onNodeWithText("月度").assertIsDisplayed()
        compose.onNodeWithText("年度").performClick()
        compose.onNodeWithText("上一周期").performClick()
        compose.onNodeWithText("下一周期").performClick()
        compose.runOnIdle { assertEquals(0, monthly); assertEquals(1, yearly); assertEquals(1, previous); assertEquals(1, next) }
    }

    @Test fun `mode tabs expose selected tab semantics`() {
        compose.setContent { CoffeeTheme { InsightsScreen(state(), {}, {}) } }
        compose.onNodeWithText("月度").assertIsSelected()
        compose.onNodeWithText("年度").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, Role.Tab))
    }

    @Test fun `mode tabs provide 48dp selectable targets`() {
        compose.setContent { CoffeeTheme { InsightsScreen(state(), {}, {}) } }
        val minimumHeight = with(compose.density) { 48.dp.toPx() }
        listOf("月度", "年度").forEach { label ->
            org.junit.Assert.assertTrue(compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height >= minimumHeight)
        }
    }

    @Test fun `habit hero exposes exact values and price gaps`() {
        compose.setContent { CoffeeTheme { InsightsScreen(state(), {}, {}) } }
        compose.onNodeWithTag(TestTags.InsightsHabitHero).assertIsDisplayed()
        compose.onNodeWithText("7").assertIsDisplayed()
        compose.onNodeWithText("饮用天数 4", substring = true).assertIsDisplayed()
        compose.onNodeWithText("最长连续 3 天", substring = true).assertIsDisplayed()
        compose.onNodeWithText("平均评分 4.5", substring = true).assertIsDisplayed()
        compose.onNodeWithText("杯数较上期 +2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("总消费 —").assertIsDisplayed()
        compose.onNodeWithText("杯均 —").assertIsDisplayed()
    }

    @Test fun `habit hero renders a negative cup comparison`() {
        val monthly = state().monthly!!
        compose.setContent {
            CoffeeTheme {
                InsightsScreen(state().copy(monthly = monthly.copy(habit = monthly.habit.copy(cupDelta = -2))), {}, {})
            }
        }
        compose.onNodeWithText("杯数较上期 -2", substring = true).assertIsDisplayed()
    }

    @Test fun `trend and donut legends have non color accessibility facts`() {
        compose.setContent { CoffeeTheme { InsightsScreen(state(), {}, {}) } }
        compose.onNodeWithTag(TestTags.InsightsTrendChart).performScrollTo()
        compose.onNodeWithContentDescription("饮用趋势：本月累计杯数；上月同期累计杯数", substring = true).assertExists()
        compose.onNodeWithTag(TestTags.InsightsCoffeeTypeDonut).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("黑咖 · 4杯 · 57%").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.InsightsBrandDonut).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("瑞幸 · 5杯 · 71%").assertIsDisplayed()
    }

    @Test fun `yearly state renders monthly comparison without cumulative wording`() {
        val monthly = state().monthly!!
        val yearly = YearlyInsights(
            year = 2026, period = monthly.period, averageMonthlySpendFen = 0,
            monthlyPoints = emptyList(), highestSpendMonths = emptyList(), lowestSpendMonths = emptyList(),
            topRatedRecordIds = emptyList(), ratingTrendText = null,
            habit = monthly.habit, trend = listOf(ComparisonPoint(1, 3, 2)),
            coffeeTypeShares = monthly.coffeeTypeShares, brandShares = monthly.brandShares,
            topBrands = monthly.topBrands, topProducts = monthly.topProducts, best = monthly.best, worst = monthly.worst,
        )
        compose.setContent { CoffeeTheme { InsightsScreen(state().copy(mode = InsightsMode.YEARLY, monthly = null, yearly = yearly), {}, {}) } }

        compose.onNodeWithTag(TestTags.InsightsTrendChart).performScrollTo()
        compose.onNodeWithText("今年每月", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("饮用趋势：今年每月杯数；去年同期每月杯数", substring = true).assertExists()
        compose.onAllNodesWithContentDescription("累计", substring = true).assertCountEquals(0)
    }

    @Test fun `rankings stay at three and long names are present`() {
        compose.setContent { CoffeeTheme { InsightsScreen(state(), {}, {}) } }
        compose.onNodeWithTag(TestTags.InsightsTopBrands).performScrollTo()
        compose.onAllNodesWithText("1").assertCountEquals(2)
        compose.onNodeWithText("一个特别特别特别特别特别特别特别特别长的产品名称").assertExists()
    }

    @Test fun `insight cards use full width and wrap long legend and ranking names at 320dp`() {
        val base = state()
        val longBrand = "一个特别特别特别特别特别特别特别特别特别特别特别特别长的咖啡品牌名称"
        val longProduct = "一个特别特别特别特别特别特别特别特别特别特别特别特别长的咖啡产品名称"
        val monthly = base.monthly!!.copy(
            brandShares = listOf(ShareValue("long", longBrand, 5, 5.0 / 7)),
            topProducts = listOf(RankedValue(longProduct, 4)),
        )
        compose.setContent { CoffeeTheme { InsightsScreen(base.copy(monthly = monthly), {}, {}) } }

        val minimumCardWidth = with(compose.density) { 280.dp.toPx() }
        listOf(
            TestTags.InsightsCoffeeTypeDonut,
            TestTags.InsightsBrandDonut,
            TestTags.InsightsTopBrands,
            TestTags.InsightsTopProducts,
        ).forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo()
            org.junit.Assert.assertTrue(compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width >= minimumCardWidth)
        }

        val brandCard = compose.onNodeWithTag(TestTags.InsightsBrandDonut).fetchSemanticsNode().boundsInRoot
        val brand = compose.onNodeWithText(longBrand).fetchSemanticsNode().boundsInRoot
        val productCard = compose.onNodeWithTag(TestTags.InsightsTopProducts).fetchSemanticsNode().boundsInRoot
        val product = compose.onNodeWithText(longProduct).fetchSemanticsNode().boundsInRoot
        val twoLines = with(compose.density) { 36.dp.toPx() }
        org.junit.Assert.assertTrue(brand.height >= twoLines)
        org.junit.Assert.assertTrue(product.height >= twoLines)
        org.junit.Assert.assertTrue(brand.left >= brandCard.left && brand.right <= brandCard.right)
        org.junit.Assert.assertTrue(product.left >= productCard.left && product.right <= productCard.right)
    }

    @Test fun `highlight opens the supplied record id and indicates equal ratings`() {
        var opened: String? = null
        compose.setContent { CoffeeTheme { InsightsScreen(state(), {}, {}, onOpenRecord = { opened = it }) } }
        compose.onNodeWithTag(TestTags.InsightsBestCard).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("best", opened) }
        compose.onNodeWithText("本期评分一致").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(TestTags.InsightsWorstCard).assertCountEquals(0)
    }

    @Test fun `long highlight names stay inside the card at 320dp`() {
        val base = state()
        val longBrand = "特别特别特别特别特别特别长的品牌名称"
        val longItem = "特别特别特别特别特别特别长的产品名称"
        val period = base.monthly!!.period.copy(best = HighlightRecord("best", longBrand, longItem, 9, null, null, 0))
        compose.setContent { CoffeeTheme { InsightsScreen(base.copy(monthly = base.monthly.copy(period = period, best = period.best)), {}, {}) } }
        val card = compose.onNodeWithTag(TestTags.InsightsBestCard).fetchSemanticsNode().boundsInRoot
        val brand = compose.onNodeWithText(longBrand).fetchSemanticsNode().boundsInRoot
        val item = compose.onNodeWithText(longItem).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(brand.right <= card.right)
        org.junit.Assert.assertTrue(item.right <= card.right)
    }

    @Test fun `empty and unrated states remain informative`() {
        val empty = state().copy(monthly = InsightsCalculator.monthly(2026, 8, emptyList(), emptyList()))
        compose.setContent { CoffeeTheme { InsightsScreen(empty, {}, {}) } }
        compose.onNodeWithText("这个月还没有咖啡记录").assertIsDisplayed()
    }

    private fun state(): InsightsUiState {
        val habit = HabitSummary(7, 4, 3, 4.5, null, null, 2)
        val types = listOf(ShareValue("BLACK", "BLACK", 4, 4.0 / 7), ShareValue("MILK", "MILK", 3, 3.0 / 7))
        val brands = listOf(ShareValue("luckin", "瑞幸", 5, 5.0 / 7), ShareValue("manner", "MANNER", 2, 2.0 / 7))
        val period = PeriodInsights(
            cupCount = 7, totalSpendFen = 0, averagePriceFen = null, averageRating = 4.5,
            topBrands = listOf(RankedValue("瑞幸", 5), RankedValue("MANNER", 2), RankedValue("库迪", 1), RankedValue("多余", 1)),
            topProducts = listOf(RankedValue("一个特别特别特别特别特别特别特别特别长的产品名称", 4), RankedValue("拿铁", 2), RankedValue("美式", 1), RankedValue("多余", 1)),
            topBeans = emptyList(), topBrewMethods = emptyList(), bestRecordIds = emptyList(), worstRecordIds = emptyList(), points = emptyList(),
            habit = habit, coffeeTypeShares = types, brandShares = brands,
            best = HighlightRecord("best", "瑞幸", "生椰拿铁", 9, null, null, 2), worst = null,
        )
        return InsightsUiState(2026, 8, loading = false, monthly = MonthlyInsights(2026, 8, period, SpendDelta(0, null, SpendDeltaBaseline.MISSING), emptyList(), null, habit, listOf(ComparisonPoint(1, 1, 0)), types, brands, period.topBrands, period.topProducts, period.best, null))
    }
}
