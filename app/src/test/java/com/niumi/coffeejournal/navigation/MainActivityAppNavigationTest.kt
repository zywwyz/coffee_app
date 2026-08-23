package com.niumi.coffeejournal.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.niumi.coffeejournal.catalog.BUNDLED_CHAIN_BRANDS
import androidx.compose.ui.unit.dp
import com.niumi.coffeejournal.InMemoryCoffeeJournalApp
import com.niumi.coffeejournal.MainActivity
import com.niumi.coffeejournal.TestTags
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = InMemoryCoffeeJournalApp::class)
class MainActivityAppNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun custom_bottom_navigation_switches_exact_roots_and_restores_them_from_settings() {
        val roots = listOf(
            Triple(TestTags.BottomCalendarTab, "咖啡日历", "咖啡日历"),
            Triple(TestTags.BottomCatalogTab, "豆库", "我的咖啡豆库"),
            Triple(TestTags.BottomInsightsTab, "总结", "咖啡回顾"),
        )

        roots.forEach { (tabTag, label, title) ->
            compose.onAllNodesWithTag(TestTags.BottomSelectedCapsule, useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithTag(tabTag).assertIsDisplayed().assertTextEquals(label).performClick().assertIsSelected()
            roots.filterNot { it.first == tabTag }.forEach { (otherTag) ->
                compose.onNodeWithTag(otherTag).assertIsNotSelected()
            }
            compose.onAllNodesWithTag(TestTags.BottomSelectedCapsule, useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithTag(TestTags.RootScreenTitle).assertIsDisplayed().assertTextEquals(title)
            compose.onNodeWithTag(TestTags.RootScreenSettings).assertIsDisplayed().performClick()
            compose.onNodeWithText("备份与恢复").assertIsDisplayed()
            compose.onNodeWithTag(TestTags.SettingsBack).performClick()
            compose.onNodeWithTag(TestTags.RootScreenTitle).assertIsDisplayed().assertTextEquals(title)
        }

        compose.onNodeWithTag(TestTags.BottomCalendarTab).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Coffee Journal").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun bottom_tabs_have_equal_widths_and_hide_for_settings() {
        val tabs = listOf(TestTags.BottomCalendarTab, TestTags.BottomCatalogTab, TestTags.BottomInsightsTab)
        val bounds = tabs.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val widths = bounds.map { it.width }
        val minTouchTarget = with(compose.density) { 48.dp.toPx() }
        assertTrue(bounds.all { it.height >= minTouchTarget })
        assertTrue(widths.max() - widths.min() <= 1f)

        compose.onNodeWithTag(TestTags.RootScreenSettings).performClick()
        tabs.forEach { compose.onAllNodesWithTag(it).assertCountEquals(0) }
    }

    @Test
    fun bundled_chain_brand_hides_navigation_until_back_returns_to_catalog_root() {
        val tabs = listOf(TestTags.BottomCalendarTab, TestTags.BottomCatalogTab, TestTags.BottomInsightsTab)
        val brandTag = TestTags.ChainBrandCardPrefix + BUNDLED_CHAIN_BRANDS.first().brand.id

        compose.onNodeWithTag(TestTags.BottomCatalogTab).performClick().assertIsSelected()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(brandTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(brandTag).performClick()
        tabs.forEach { compose.onAllNodesWithTag(it).assertCountEquals(0) }

        compose.activity.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag(TestTags.RootScreenTitle).assertTextEquals("我的咖啡豆库")
        compose.onNodeWithTag(TestTags.BottomCatalogTab).assertIsDisplayed().assertIsSelected()
        compose.onAllNodesWithTag(TestTags.BottomSelectedCapsule, useUnmergedTree = true).assertCountEquals(1)
    }
}
