package com.niumi.coffeejournal.journal

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalScreenRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `record button switches from calendar to editor`() {
        var recordRequested = false
        compose.setContent {
            CoffeeTheme {
                JournalScreen(
                    state = JournalUiState.empty(2026, 8),
                    onPreviousMonth = {},
                    onNextMonth = {},
                    onDayClick = {},
                    onRecordDrink = { recordRequested = true },
                )
            }
        }

        compose.onNodeWithText("记录一杯").performClick()
        compose.runOnIdle { assert(recordRequested) }
    }

    @Test
    fun `missing image dialog exposes screenshot select and skip actions`() {
        compose.setContent {
            CoffeeTheme {
                RecordDrinkScreen(
                    state = JournalUiState.empty(2026, 8).editor.copy(needsImagePrompt = true),
                    brands = emptyList(),
                    items = emptyList(),
                    onSourceTypeChange = {}, onBrandSelect = {}, onItemSelect = {},
                    onRatingChange = {}, onPriceChange = {}, onBrewMethodChange = {}, onNoteChange = {},
                    onSave = {}, onBack = {}, onScreenshot = {}, onSelectImage = {}, onSkipImage = {},
                )
            }
        }

        compose.onNodeWithText("上传完整截图").assertIsDisplayed()
        compose.onNodeWithText("选择图片").assertIsDisplayed()
        compose.onNodeWithText("暂时跳过").assertIsDisplayed()
    }

    @Test
    fun `recorded day renders decoded local product image instead of placeholder`() {
        val image = temporaryBitmap("product")
        val state = JournalUiState.empty(2026, 8).let { empty ->
            empty.copy(
                days = empty.days.map { day ->
                    if (day.localDate == "2026-08-05") day.copy(imagePath = image.absolutePath, drinkCount = 1) else day
                },
            )
        }

        compose.setContent {
            CoffeeTheme {
                JournalScreen(state, {}, {}, {}, {})
            }
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("咖啡图片").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("咖啡图片").assertIsDisplayed()
        compose.runOnIdle {
            assert(compose.onAllNodesWithContentDescription("通用咖啡占位图").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun `recorded day renders decoded brand logo fallback`() {
        val logo = temporaryBitmap("logo")
        val corrupt = File.createTempFile("corrupt-product", ".png").apply { writeText("broken") }
        val state = JournalUiState.empty(2026, 8).let { empty ->
            empty.copy(
                days = empty.days.map { day ->
                    if (day.localDate == "2026-08-05") {
                        day.copy(
                            imagePath = corrupt.absolutePath,
                            brandLogoPath = logo.absolutePath,
                            drinkCount = 1,
                        )
                    } else day
                },
            )
        }

        compose.setContent { CoffeeTheme { JournalScreen(state, {}, {}, {}, {}) } }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("咖啡图片").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("咖啡图片").assertIsDisplayed()
    }

    @Test
    fun `all editor controls are disabled while saving`() {
        val brand = Brand("brand", BrandType.CHAIN, "测试品牌", null, MaintenanceMode.MANUAL_ONLY, null)
        val item = CatalogItem(
            "item", "brand", ItemType.CHAIN_PRODUCT, "测试产品", null, null, null, null, null, null, ItemStatus.ACTIVE,
        )
        compose.setContent {
            CoffeeTheme {
                RecordDrinkScreen(
                    state = RecordEditorUi(selectedBrandId = "brand", selectedItemId = "item", saving = true),
                    brands = listOf(brand),
                    items = listOf(item),
                    onSourceTypeChange = {}, onBrandSelect = {}, onItemSelect = {},
                    onRatingChange = {}, onPriceChange = {}, onBrewMethodChange = {}, onNoteChange = {},
                    onSave = {}, onBack = {}, onScreenshot = {}, onSelectImage = {}, onSkipImage = {},
                )
            }
        }

        compose.onNodeWithText("个人豆子").assertIsNotEnabled()
        compose.onNodeWithText("测试品牌").assertIsNotEnabled()
        compose.onNodeWithText("测试产品").assertIsNotEnabled()
        compose.onNodeWithText("5.0").assertIsNotEnabled()
    }

    @Test
    fun `editor and save are disabled while product selection loads`() {
        compose.setContent {
            CoffeeTheme {
                RecordDrinkScreen(
                    state = RecordEditorUi(selectedItemId = "old", selecting = true),
                    brands = emptyList(), items = emptyList(),
                    onSourceTypeChange = {}, onBrandSelect = {}, onItemSelect = {},
                    onRatingChange = {}, onPriceChange = {}, onBrewMethodChange = {}, onNoteChange = {},
                    onSave = {}, onBack = {}, onScreenshot = {}, onSelectImage = {}, onSkipImage = {},
                )
            }
        }

        compose.onNodeWithText("5.0").assertIsNotEnabled()
        compose.onNodeWithText("加载产品…").assertIsNotEnabled()
    }

    @Test
    fun `save is disabled until a concrete item is selected`() {
        compose.setContent {
            CoffeeTheme {
                RecordDrinkScreen(
                    state = RecordEditorUi(selectedBrandId = "brand", selectedItemId = null),
                    brands = emptyList(), items = emptyList(),
                    onSourceTypeChange = {}, onBrandSelect = {}, onItemSelect = {},
                    onRatingChange = {}, onPriceChange = {}, onBrewMethodChange = {}, onNoteChange = {},
                    onSave = {}, onBack = {}, onScreenshot = {}, onSelectImage = {}, onSkipImage = {},
                )
            }
        }

        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.ConfirmSave).assertIsNotEnabled()
    }

    private fun temporaryBitmap(prefix: String): File {
        val file = File.createTempFile(prefix, ".png")
        file.deleteOnExit()
        FileOutputStream(file).use { output ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        return file
    }
}
