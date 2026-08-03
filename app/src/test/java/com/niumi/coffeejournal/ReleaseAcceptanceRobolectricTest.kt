package com.niumi.coffeejournal

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.niumi.coffeejournal.catalog.RoomCatalogRepository
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.database.ImageAssetEntity
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import com.niumi.coffeejournal.insights.InsightsCalculator
import com.niumi.coffeejournal.insights.InsightsMode
import com.niumi.coffeejournal.insights.InsightsScreen
import com.niumi.coffeejournal.insights.InsightsUiState
import com.niumi.coffeejournal.journal.Clock
import com.niumi.coffeejournal.journal.ClockReading
import com.niumi.coffeejournal.journal.DefaultJournalRepository
import com.niumi.coffeejournal.journal.JournalScreen
import com.niumi.coffeejournal.journal.JournalUiState
import com.niumi.coffeejournal.journal.RecordDrinkScreen
import com.niumi.coffeejournal.journal.RecordEditorUi
import com.niumi.coffeejournal.journal.RoomDrinkStore
import com.niumi.coffeejournal.journal.projectMonth
import com.niumi.coffeejournal.journal.summarizeMonth
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h760dp")
class ReleaseAcceptanceRobolectricTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var database: CoffeeDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            CoffeeDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `chain release journey keeps logo snapshots count rating and spend`() = runBlocking {
        val logo = temporaryBitmap("chain-logo")
        database.imageAssetDao().upsert(
            ImageAssetEntity("logo", logo.absolutePath, "a".repeat(64), "BRAND_LOGO", 1),
        )
        val catalog = RoomCatalogRepository(database.brandDao(), database.catalogItemDao(), database.drinkDao())
        val brand = Brand("brand", BrandType.CHAIN, "测试连锁", "logo", MaintenanceMode.MANUAL_ONLY, null)
        val item = CatalogItem(
            "item", "brand", ItemType.CHAIN_PRODUCT, "Logo 拿铁", null,
            null, null, null, null, null, ItemStatus.NEEDS_IMAGE,
        )
        catalog.upsertBrand(brand)
        catalog.upsertItem(item)
        val clock = SequenceClock()
        val journal = DefaultJournalRepository(catalog, RoomDrinkStore(database, clock), clock)

        repeat(2) {
            val draft = journal.newDraft(ItemType.CHAIN_PRODUCT, "item")
                .copy(ratingHalfStars = 9, actualPriceFen = 990)
            journal.save(draft)
        }
        database.imageAssetDao().upsert(
            ImageAssetEntity("new-image", logo.absolutePath, "b".repeat(64), "PRODUCT", 2),
        )
        catalog.upsertItem(item.copy(name = "更新后的拿铁", imageAssetId = "new-image"))

        val records = journal.observeMonth(2026, 8).first()
        assertEquals(2, records.size)
        assertEquals(setOf("Logo 拿铁"), records.map { it.snapshot.itemName }.toSet())
        assertEquals(setOf("logo"), records.map { it.snapshot.brandLogoAssetId }.toSet())
        assertEquals(setOf(9), records.map { it.ratingHalfStars }.toSet())
        assertEquals(1_980L, summarizeMonth(records).totalSpendFen)
        assertEquals(1_980L, InsightsCalculator.monthly(2026, 8, records, emptyList()).period.totalSpendFen)
        assertEquals(2, projectMonth(2026, 8, records).single { it.localDate == "2026-08-03" }.drinkCount)
        assertNull(records.first().snapshot.imageAssetId)
        assertEquals("new-image", catalog.getItem("item").imageAssetId)
    }

    @Test
    fun `release surfaces expose stable acceptance tags and logo fallback`() {
        val logo = temporaryBitmap("calendar-logo")
        val records = listOf(record("first", 1), record("second", 2))
        val empty = JournalUiState.empty(2026, 8)
        val state = empty.copy(
            records = records,
            days = projectMonth(
                2026, 8, records,
                brandLogoPathsByRecordId = mapOf("first" to logo.absolutePath, "second" to logo.absolutePath),
            ),
            summary = summarizeMonth(records),
        )

        compose.setContent { CoffeeTheme { JournalScreen(state, {}, {}, {}, {}) } }

        compose.onNodeWithTag("journal-calendar").assertIsDisplayed()
        compose.onNodeWithTag("record-drink").assertIsDisplayed()
        compose.onNodeWithText("×2").assertIsDisplayed()
        compose.onNodeWithText("¥19.80").assertIsDisplayed()
    }

    @Test
    fun `missing image prompt and optional fields do not disable save`() {
        val brand = Brand("brand", BrandType.CHAIN, "测试连锁", "logo", MaintenanceMode.MANUAL_ONLY, null)
        val item = CatalogItem(
            "item", "brand", ItemType.CHAIN_PRODUCT, "Logo 拿铁", null,
            null, null, null, null, null, ItemStatus.NEEDS_IMAGE,
        )
        compose.setContent {
            CoffeeTheme {
                RecordDrinkScreen(
                    state = RecordEditorUi(
                        selectedBrandId = "brand", selectedItemId = "item", ratingHalfStars = 9,
                        priceInput = "9.90", priceValid = true, actualPriceFen = 990,
                        needsImagePrompt = true,
                    ),
                    brands = listOf(brand), items = listOf(item),
                    onSourceTypeChange = {}, onBrandSelect = {}, onItemSelect = {},
                    onRatingChange = {}, onPriceChange = {}, onBrewMethodChange = {}, onNoteChange = {},
                    onSave = {}, onBack = {}, onScreenshot = {}, onSelectImage = {}, onSkipImage = {},
                )
            }
        }

        compose.onNodeWithTag("missing-image-prompt").assertIsDisplayed()
        compose.onNodeWithText("上传完整截图").assertIsDisplayed()
        compose.onNodeWithText("暂时跳过").performClick()
        compose.onNodeWithTag("confirm-save").assertIsEnabled()
    }

    @Test
    fun `only item selection is required to save a record`() {
        val item = CatalogItem(
            "item", "brand", ItemType.CHAIN_PRODUCT, "无选填字段咖啡", null,
            null, null, null, null, null, ItemStatus.ACTIVE,
        )
        compose.setContent {
            CoffeeTheme {
                RecordDrinkScreen(
                    state = RecordEditorUi(selectedBrandId = "brand", selectedItemId = "item"),
                    brands = emptyList(), items = listOf(item),
                    onSourceTypeChange = {}, onBrandSelect = {}, onItemSelect = {},
                    onRatingChange = {}, onPriceChange = {}, onBrewMethodChange = {}, onNoteChange = {},
                    onSave = {}, onBack = {}, onScreenshot = {}, onSelectImage = {}, onSkipImage = {},
                )
            }
        }

        compose.onNodeWithTag("confirm-save").assertIsEnabled()
    }

    @Test
    fun `monthly insights exposes exact accepted spend`() {
        val records = listOf(record("first", 1), record("second", 2))
        val report = InsightsCalculator.monthly(2026, 8, records, emptyList())
        compose.setContent {
            CoffeeTheme {
                InsightsScreen(
                    state = InsightsUiState(
                        year = 2026, month = 8, mode = InsightsMode.MONTHLY,
                        loading = false, monthly = report,
                    ),
                    onShowMonthly = {}, onShowYearly = {},
                )
            }
        }

        compose.onNodeWithTag("monthly-spend").assertIsDisplayed().assertTextEquals("¥19.80")
    }

    private fun record(id: String, timestamp: Long) = DrinkRecord(
        id, timestamp, "2026-08-03", ItemType.CHAIN_PRODUCT, "item",
        null, 9, 990, null,
        DrinkSnapshot("测试连锁", "Logo 拿铁", null, null, null, brandLogoAssetId = "logo"),
    )

    private fun temporaryBitmap(prefix: String): File {
        val file = File.createTempFile(prefix, ".png").apply { deleteOnExit() }
        FileOutputStream(file).use { output ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        return file
    }

    private class SequenceClock : Clock {
        private var next = 1L
        override fun read() = ClockReading(next++, "2026-08-03")
    }
}
