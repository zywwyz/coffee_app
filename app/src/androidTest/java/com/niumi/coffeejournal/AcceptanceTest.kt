package com.niumi.coffeejournal

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.niumi.coffeejournal.core.database.DrinkRecordEntity
import com.niumi.coffeejournal.journal.CalendarDisplayMode
import com.niumi.coffeejournal.journal.localNoonEpoch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Device mirror of the release calendar acceptance; records are preloaded through the real Room DAO. */
class AcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun calendarVisualParityAugust2026() = runBlocking {
        val app = compose.activity.application as AndroidTestCoffeeJournalApp
        seedAugust(app)
        compose.waitUntil(10_000) {
            DATES.all { compose.onAllNodesWithTag(TestTags.CalendarImagePrefix + it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        }
        DATES.forEach { compose.onAllNodesWithTag(TestTags.CalendarDayNumberPrefix + it, useUnmergedTree = true).assertCountEquals(0) }
        compose.onNodeWithText("×2").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.CalendarBrandDisplayMode).performClick().assertIsSelected()
        compose.onNodeWithTag(TestTags.PreviousMonth).performClick()
        compose.onNodeWithText("2026年7月").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.NextMonth).performClick()
        compose.onNodeWithText("2026年8月").assertIsDisplayed()
        compose.onNodeWithText("总结").performClick()
        compose.onNodeWithTag(TestTags.MonthlySpend).assertIsDisplayed()
        compose.onNodeWithText("咖啡日历").performClick()
        compose.onNodeWithTag(TestTags.RecordButton).performClick()
        compose.onNodeWithText("饮用日期").assertIsDisplayed()
        compose.onAllNodesWithText("饮用日期与时间").assertCountEquals(0)
        Unit
    }

    private suspend fun seedAugust(app: CoffeeJournalApp) {
        listOf(
            "2026-08-06" to "M Stand", "2026-08-15" to "瑞幸", "2026-08-18" to "瑞幸",
            "2026-08-20" to "MANNER", "2026-08-20" to "MANNER",
        ).forEachIndexed { index, (date, brand) ->
            val occurredAt = localNoonEpoch(date) + index
            app.database.drinkDao().insert(
                DrinkRecordEntity(
                    id = "device-release-$index", occurredAtEpochMillis = occurredAt, localDate = date,
                    itemType = "CHAIN_PRODUCT", sourceItemId = "fixture-$index", actualPriceFen = 990,
                    snapshotBrandName = brand, snapshotItemName = "$brand 拿铁",
                    createdAtEpochMillis = occurredAt, updatedAtEpochMillis = occurredAt,
                ),
            )
        }
    }

    private companion object { val DATES = listOf("2026-08-06", "2026-08-15", "2026-08-18", "2026-08-20") }
}
