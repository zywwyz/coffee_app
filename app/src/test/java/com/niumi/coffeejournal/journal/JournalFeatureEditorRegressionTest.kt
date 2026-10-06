package com.niumi.coffeejournal.journal

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import com.niumi.coffeejournal.TestTags
import com.niumi.coffeejournal.catalog.RoomCatalogRepository
import com.niumi.coffeejournal.core.database.ImageAssetEntity
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ChainProductKind
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalFeatureEditorRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val database = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), CoffeeDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val catalog = RoomCatalogRepository(database.brandDao(), database.catalogItemDao(), database.drinkDao())
    private val clock = object : Clock {
        override fun read() = ClockReading(localNoonEpoch("2026-08-05"), "2026-08-05")
    }
    private val journal = DefaultJournalRepository(catalog, RoomDrinkStore(database, clock), clock)

    @After fun close() = database.close()

    @Test
    fun `edit back then record a new cup leaves the historical record unchanged`() = verifyExit(false)

    @Test
    fun `system back abandons unsaved edits before recording a new cup`() = verifyExit(true)

    private fun verifyExit(systemBack: Boolean) {
        val original = runBlocking {
            database.imageAssetDao().upsert(ImageAssetEntity("image", "/fixture.jpg", "a".repeat(64), "PRODUCT", 1))
            catalog.upsertBrand(Brand("brand", BrandType.CHAIN, "测试品牌", null, MaintenanceMode.MANUAL_ONLY, null))
            catalog.upsertItem(CatalogItem(
                id = "item", brandId = "brand", type = ItemType.CHAIN_PRODUCT, name = "测试咖啡",
                imageAssetId = "image", origin = null, processing = null, roastLevel = null,
                flavorNotes = null, brewMethod = "冰", status = ItemStatus.ACTIVE,
                chainProductKind = ChainProductKind.BLACK,
            ))
            val draft = journal.newDraft(ItemType.CHAIN_PRODUCT, "item")
            val id = journal.save(draft.copy(ratingHalfStars = 8, actualPriceFen = 1_280, note = "原始备注"))
            requireNotNull(journal.get(id))
        }

        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CoffeeTheme { JournalFeature(journal, catalog, { null }, clock = clock) }
        }
        compose.onNodeWithTag(TestTags.CalendarDayPrefix + "2026-08-05").performClick()
        compose.onNodeWithText("编辑").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("修改记录").fetchSemanticsNodes().isNotEmpty() }
        if (systemBack) {
            compose.onNodeWithText("备注（可选）").performScrollTo().performTextReplacement("放弃这次修改")
            compose.runOnIdle { requireNotNull(dispatcher).onBackPressed() }
        } else {
            compose.onNodeWithText("返回").performClick()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(TestTags.RecordButton).fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(runBlocking { journal.currentDraft() })
        assertEquals(original, runBlocking { journal.get(original.id) })
        compose.onNodeWithText("记录一杯").performClick()
        compose.onNodeWithText("选择品牌").assertIsDisplayed()
        compose.onNodeWithText("测试品牌").performClick()
        compose.onNodeWithText("测试咖啡").performClick()
        compose.waitForIdle()
        compose.waitUntil(10_000) { runBlocking { journal.currentDraft()?.sourceItemId == "item" } }
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.ConfirmSave).performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.ConfirmSave).assertIsEnabled().performClick()
        compose.waitForIdle()

        compose.waitUntil(10_000) {
            runBlocking { journal.observeRange("2026-08-05", "2026-08-05").first().size == 2 }
        }
        val records = runBlocking { journal.observeRange("2026-08-05", "2026-08-05").first() }
        assertEquals(2, records.size)
        assertEquals(original, runBlocking { journal.get(original.id) })
        val added = records.single { it.id != original.id }
        assertNotEquals(original.id, added.id)
        assertNull(added.note)
    }
}
