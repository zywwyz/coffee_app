package com.niumi.coffeejournal.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun each_bottom_root_has_its_own_title_and_settings_route() {
        val roots = listOf(
            TestTags.BottomCalendarTab to "咖啡日历",
            TestTags.BottomCatalogTab to "我的咖啡豆库",
            TestTags.BottomInsightsTab to "咖啡回顾",
        )

        roots.forEach { (tabTag, title) ->
            compose.onNodeWithTag(tabTag).assertIsDisplayed().also {
                if (tabTag == TestTags.BottomCalendarTab) it.assertTextEquals("咖啡日历")
            }.performClick()
            compose.onNodeWithTag(TestTags.RootScreenTitle).assertIsDisplayed().assertTextEquals(title)
            compose.onNodeWithTag(TestTags.RootScreenSettings).assertIsDisplayed().performClick()
            compose.onNodeWithText("备份与恢复").assertIsDisplayed()
            compose.onNodeWithTag(TestTags.SettingsBack).performClick()
            compose.onNodeWithTag(TestTags.RootScreenTitle).assertIsDisplayed().assertTextEquals(title)
        }

        compose.onNodeWithTag(TestTags.BottomCalendarTab).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Coffee Journal").fetchSemanticsNodes().isEmpty())
    }
}
