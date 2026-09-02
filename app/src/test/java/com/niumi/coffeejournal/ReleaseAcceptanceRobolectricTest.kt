package com.niumi.coffeejournal

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.database.DrinkRecordEntity
import com.niumi.coffeejournal.journal.CalendarDisplayMode
import com.niumi.coffeejournal.journal.CalendarDisplayPreference
import com.niumi.coffeejournal.journal.Clock
import com.niumi.coffeejournal.journal.ClockReading
import com.niumi.coffeejournal.journal.localNoonEpoch
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Release-facing UI acceptance: real activity, Room store and projection fixed to August 2026. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp", application = InMemoryCoffeeJournalApp::class)
class ReleaseAcceptanceRobolectricTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `calendar renders bundled brand fallbacks and usable controls for August 2026`() = runBlocking {
        val app = compose.activity.application as InMemoryCoffeeJournalApp
        appToClose = app
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("2026年", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("2026年8月").assertIsDisplayed()
        seedAugust(app)

        compose.waitUntil(10_000) {
            DATES.all { date ->
                compose.onAllNodesWithTag(TestTags.CalendarImagePrefix + date, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        DATES.forEach { date ->
            compose.onAllNodesWithTag(TestTags.CalendarDayNumberPrefix + date, useUnmergedTree = true).assertCountEquals(0)
        }
        compose.onNodeWithTag(TestTags.CalendarCountBadgePrefix + "2026-08-20", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("×2").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.CalendarCoffeeDisplayMode).assertIsSelected()

        compose.onNodeWithTag(TestTags.CalendarBrandDisplayMode).performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(CalendarDisplayMode.BRAND, app.calendarDisplayPreference.value) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("咖啡图片").fetchSemanticsNodes().size >= DATES.size
        }

        compose.onNodeWithTag(TestTags.PreviousMonth).performClick()
        compose.onNodeWithText("2026年7月").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.NextMonth).performClick()
        compose.onNodeWithText("2026年8月").assertIsDisplayed()
        compose.onNodeWithText("总结").performClick()
        compose.onNodeWithText("2026年8月").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(TestTags.InsightsHabitHero).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(TestTags.InsightsHabitHero).assertIsDisplayed()
        compose.onNodeWithText("年度").performClick().assertIsSelected()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("今年每月杯数", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("去年同期每月杯数", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.BottomInsightsTab).assertIsDisplayed()
        compose.onNodeWithText("咖啡日历").performClick()
        compose.onNodeWithTag(TestTags.RecordButton).performClick()
        compose.onNodeWithText("饮用日期").assertIsDisplayed()
        compose.onAllNodesWithText("饮用日期与时间").assertCountEquals(0)
        Unit
    }

    private suspend fun seedAugust(app: CoffeeJournalApp) {
        listOf(
            "2026-08-06" to "M Stand",
            "2026-08-15" to "瑞幸",
            "2026-08-18" to "瑞幸",
            "2026-08-20" to "MANNER",
            "2026-08-20" to "MANNER",
        ).forEachIndexed { index, (date, brand) ->
            val occurredAt = localNoonEpoch(date) + index
            app.database.drinkDao().insert(
                DrinkRecordEntity(
                    id = "release-$index", occurredAtEpochMillis = occurredAt, localDate = date,
                    itemType = "CHAIN_PRODUCT", sourceItemId = "fixture-$index", actualPriceFen = 990,
                    snapshotBrandName = brand, snapshotItemName = "$brand 拿铁",
                    createdAtEpochMillis = occurredAt, updatedAtEpochMillis = occurredAt,
                ),
            )
        }
    }

    private companion object {
        val DATES = listOf("2026-08-06", "2026-08-15", "2026-08-18", "2026-08-20")
        var appToClose: InMemoryCoffeeJournalApp? = null

        @JvmStatic @AfterClass fun closeDatabase() { appToClose?.database?.close(); appToClose = null }
    }
}

class InMemoryCoffeeJournalApp : CoffeeJournalApp() {
    override val journalClock: Clock = object : Clock {
        override fun read() = ClockReading(localNoonEpoch("2026-08-20"), "2026-08-20")
    }
    override val calendarDisplayPreference = FakeCalendarDisplayPreference()
    override val database: CoffeeDatabase by lazy {
        Room.inMemoryDatabaseBuilder(this, CoffeeDatabase::class.java).allowMainThreadQueries().build()
    }
}

class FakeCalendarDisplayPreference(var value: CalendarDisplayMode = CalendarDisplayMode.COFFEE) : CalendarDisplayPreference {
    override fun read() = value
    override fun write(mode: CalendarDisplayMode) { value = mode }
}
