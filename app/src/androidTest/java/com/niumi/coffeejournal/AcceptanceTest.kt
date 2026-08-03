package com.niumi.coffeejournal

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
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
import com.niumi.coffeejournal.journal.JournalScreen
import com.niumi.coffeejournal.journal.JournalUiState
import com.niumi.coffeejournal.journal.RecordDrinkScreen
import com.niumi.coffeejournal.journal.RecordEditorUi
import com.niumi.coffeejournal.journal.projectMonth
import com.niumi.coffeejournal.journal.summarizeMonth
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test

class AcceptanceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun chainProductReleaseJourney() {
        val logo = temporaryLogo()
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

        compose.onNodeWithTag(TestTags.Calendar).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.RecordButton).assertIsDisplayed()
        compose.onNodeWithText("×2").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.MonthlySpend).assertTextEquals("¥19.80")
    }

    @Test
    fun missingProductImageCanUseScreenshotOrLogoWithoutBlockingSave() {
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

        compose.onNodeWithTag(TestTags.MissingImagePrompt).assertIsDisplayed()
        compose.onNodeWithText("上传完整截图").assertIsDisplayed()
        compose.onNodeWithText("暂时跳过").performClick()
        compose.onNodeWithTag(TestTags.ConfirmSave).assertIsEnabled()
    }

    @Test
    fun monthlyInsightsShowsAcceptedSpend() {
        val report = InsightsCalculator.monthly(
            2026, 8, listOf(record("first", 1), record("second", 2)), emptyList(),
        )
        compose.setContent {
            CoffeeTheme {
                InsightsScreen(
                    InsightsUiState(2026, 8, InsightsMode.MONTHLY, loading = false, monthly = report),
                    onShowMonthly = {}, onShowYearly = {},
                )
            }
        }
        compose.onNodeWithTag(TestTags.MonthlySpend).assertIsDisplayed().assertTextEquals("¥19.80")
    }

    private fun record(id: String, timestamp: Long) = DrinkRecord(
        id, timestamp, "2026-08-03", ItemType.CHAIN_PRODUCT, "item",
        null, 9, 990, null,
        DrinkSnapshot("测试连锁", "Logo 拿铁", null, null, null, brandLogoAssetId = "logo"),
    )

    private fun temporaryLogo(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "acceptance-logo.png").also { file ->
            FileOutputStream(file).use { output ->
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
    }
}
