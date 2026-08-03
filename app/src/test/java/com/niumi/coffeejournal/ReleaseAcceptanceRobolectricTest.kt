package com.niumi.coffeejournal

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.database.ImageAssetEntity
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.AfterClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    qualifiers = "w393dp-h852dp",
    application = InMemoryCoffeeJournalApp::class,
)
class ReleaseAcceptanceRobolectricTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `main activity journey records two logo-only coffees and preserves history`() = runBlocking {
        val app = compose.activity.application as InMemoryCoffeeJournalApp
        appToClose = app
        val logoFile = bitmapFile(app, "acceptance-logo.png")
        app.database.imageAssetDao().upsert(
            ImageAssetEntity(LOGO_ID, logoFile.absolutePath, "a".repeat(64), "BRAND_LOGO", 1),
        )
        val brand = Brand(BRAND_ID, BrandType.CHAIN, BRAND_NAME, LOGO_ID, MaintenanceMode.MANUAL_ONLY, null)
        val item = CatalogItem(
            ITEM_ID, BRAND_ID, ItemType.CHAIN_PRODUCT, ORIGINAL_ITEM_NAME, null,
            null, null, null, null, null, ItemStatus.NEEDS_IMAGE,
        )
        app.catalogRepository.upsertBrand(brand)
        app.catalogRepository.upsertItem(item)

        recordCup()
        recordCup()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("×2").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("×2").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("咖啡图片").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("咖啡图片").assertIsDisplayed()

        val (year, month) = app.acceptanceYearMonth
        val records = app.journalRepository.observeMonth(
            year, month,
        ).first()
        assertEquals(2, records.size)
        assertEquals(setOf(9), records.map { it.ratingHalfStars }.toSet())
        assertEquals(setOf(990L), records.map { it.actualPriceFen }.toSet())
        assertEquals(setOf(ORIGINAL_ITEM_NAME), records.map { it.snapshot.itemName }.toSet())
        assertEquals(setOf(LOGO_ID), records.map { it.snapshot.brandLogoAssetId }.toSet())
        assertEquals(setOf(null), records.map { it.snapshot.imageAssetId }.toSet())

        val productFile = bitmapFile(app, "acceptance-product.png")
        app.database.imageAssetDao().upsert(
            ImageAssetEntity(PRODUCT_ID, productFile.absolutePath, "b".repeat(64), "PRODUCT", 2),
        )
        app.catalogRepository.upsertItem(
            item.copy(name = UPDATED_ITEM_NAME, imageAssetId = PRODUCT_ID, status = ItemStatus.ACTIVE),
        )

        val afterUpdate = app.journalRepository.observeMonth(
            year, month,
        ).first()
        assertEquals(setOf(ORIGINAL_ITEM_NAME), afterUpdate.map { it.snapshot.itemName }.toSet())
        assertEquals(setOf(null), afterUpdate.map { it.snapshot.imageAssetId }.toSet())
        assertEquals(UPDATED_ITEM_NAME, app.catalogRepository.getItem(ITEM_ID).name)
        assertEquals(PRODUCT_ID, app.catalogRepository.getItem(ITEM_ID).imageAssetId)

        compose.onNodeWithText("总结").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(TestTags.MonthlySpend).fetchSemanticsNodes()
                .any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) }
        }
        compose.onNodeWithTag(TestTags.MonthlySpend).assertIsDisplayed().assertTextEquals("¥19.80")
        Unit
    }

    private fun recordCup() {
        compose.onNodeWithTag(TestTags.RecordButton).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(BRAND_NAME).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(BRAND_NAME).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(ORIGINAL_ITEM_NAME).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(ORIGINAL_ITEM_NAME).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(TestTags.MissingImagePrompt).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(TestTags.MissingImagePrompt).assertIsDisplayed()
        compose.onNodeWithText("暂时跳过").performClick()
        compose.onNodeWithText("4.5").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("实际支付（元）").performTextReplacement("9.90")
        compose.onNodeWithText("9.90").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.ConfirmSave).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(TestTags.Calendar).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun bitmapFile(app: CoffeeJournalApp, name: String): File =
        File(app.cacheDir, name).also { file ->
            FileOutputStream(file).use { output ->
                Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }

    private companion object {
        private var appToClose: InMemoryCoffeeJournalApp? = null

        @JvmStatic
        @AfterClass
        fun closeDatabase() {
            appToClose?.database?.close()
            appToClose = null
        }

        const val BRAND_ID = "acceptance-brand"
        const val ITEM_ID = "acceptance-item"
        const val LOGO_ID = "acceptance-logo"
        const val PRODUCT_ID = "acceptance-product"
        const val BRAND_NAME = "验收连锁"
        const val ORIGINAL_ITEM_NAME = "Logo 拿铁"
        const val UPDATED_ITEM_NAME = "更新后的 Logo 拿铁"
    }
}

class InMemoryCoffeeJournalApp : CoffeeJournalApp() {
    private val fixedReading = acceptanceClockReading()
    override val journalClock = object : com.niumi.coffeejournal.journal.Clock {
        override fun read() = fixedReading
    }
    val acceptanceYearMonth: Pair<Int, Int> = fixedReading.localDate
        .let { it.substring(0, 4).toInt() to it.substring(5, 7).toInt() }

    override val database: CoffeeDatabase by lazy {
        Room.inMemoryDatabaseBuilder(this, CoffeeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }
}

private fun acceptanceClockReading(): com.niumi.coffeejournal.journal.ClockReading {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return com.niumi.coffeejournal.journal.ClockReading(
        calendar.timeInMillis,
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(calendar.time),
    )
}
