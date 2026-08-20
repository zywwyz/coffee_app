package com.niumi.coffeejournal.journal

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `calendar display switch labels are exact and selects one mode`() {
        var selected: CalendarDisplayMode? = null
        compose.setContent {
            CoffeeTheme {
                JournalScreen(
                    state = JournalUiState.empty(2026, 8),
                    onPreviousMonth = {}, onNextMonth = {}, onDayClick = {}, onRecordDrink = {},
                    onCalendarDisplayModeChange = { selected = it },
                )
            }
        }

        compose.onNodeWithText("品牌").assertIsDisplayed()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.CalendarCoffeeDisplayMode)
            .assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.CalendarBrandDisplayMode).assertIsNotSelected()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.CalendarBrandDisplayMode).performClick()
        compose.runOnIdle { assertEquals(CalendarDisplayMode.BRAND, selected) }
    }

    @Test
    fun `missing image dialog exposes real image and brand logo actions`() {
        compose.setContent {
            CoffeeTheme {
                RecordDrinkScreen(
                    state = JournalUiState.empty(2026, 8).editor.copy(needsImagePrompt = true),
                    brands = emptyList(),
                    items = emptyList(),
                    onSourceTypeChange = {}, onBrandSelect = {}, onItemSelect = {},
                    onRatingChange = {}, onPriceChange = {}, onBrewMethodChange = {}, onNoteChange = {},
                    onSave = {}, onBack = {}, onSelectImage = {}, onSkipImage = {},
                )
            }
        }

        compose.onNodeWithText("选择实拍图片").assertIsDisplayed()
        compose.onNodeWithText("当前产品没有图片。选择实拍图片，或使用品牌 Logo。").assertIsDisplayed()
        compose.onNodeWithText("暂时跳过").assertIsDisplayed()
    }

    @Test
    fun `chain brand selection exposes quick add product action`() {
        var requested = false
        compose.setContent {
            CoffeeTheme {
                RecordDrinkScreen(
                    state = JournalUiState.empty(2026, 8).editor.copy(selectedBrandId = "brand"),
                    brands = emptyList(), items = emptyList(),
                    onSourceTypeChange = {}, onBrandSelect = {}, onItemSelect = {},
                    onRatingChange = {}, onPriceChange = {}, onBrewMethodChange = {}, onNoteChange = {},
                    onSave = {}, onBack = {}, onSelectImage = {}, onSkipImage = {},
                    onAddProduct = { requested = true },
                )
            }
        }

        compose.onNodeWithText("添加新产品").assertIsEnabled().performClick()
        compose.runOnIdle { assert(requested) }
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
                    onSave = {}, onBack = {}, onSelectImage = {}, onSkipImage = {},
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
                    onSave = {}, onBack = {}, onSelectImage = {}, onSkipImage = {},
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
                    onSave = {}, onBack = {}, onSelectImage = {}, onSkipImage = {},
                )
            }
        }

        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.ConfirmSave).assertIsNotEnabled()
    }

    @Test
    fun `discard draft asks for confirmation before clearing unsaved input`() {
        var discarded = false
        compose.setContent {
            CoffeeTheme {
                RecordDrinkScreen(
                    state = RecordEditorUi(selectedItemId = "item", note = "未保存输入"),
                    brands = emptyList(), items = emptyList(),
                    onSourceTypeChange = {}, onBrandSelect = {}, onItemSelect = {},
                    onRatingChange = {}, onPriceChange = {}, onBrewMethodChange = {}, onNoteChange = {},
                    onSave = {}, onDiscardDraft = { discarded = true }, onBack = {},
                    onSelectImage = {}, onSkipImage = {},
                )
            }
        }

        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.RecordEditorScroll).performTouchInput {
            swipeUp()
            swipeUp()
            swipeUp()
            swipeUp()
        }
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.DiscardDraft).assertIsDisplayed().performClick()
        compose.onNodeWithText("放弃并新建").performClick()
        compose.runOnIdle { assert(discarded) }
    }

    @Test
    fun `editor shows editable date time and clear rating control`() {
        compose.setContent {
            CoffeeTheme {
                RecordDrinkScreen(
                    state = RecordEditorUi(
                        selectedItemId = "item",
                        consumedAtEpochMillis = 1_754_049_600_000L,
                        ratingHalfStars = 9,
                    ),
                    brands = emptyList(), items = emptyList(),
                    onSourceTypeChange = {}, onBrandSelect = {}, onItemSelect = {},
                    onRatingChange = {}, onPriceChange = {}, onBrewMethodChange = {}, onNoteChange = {},
                    onSave = {}, onBack = {}, onSelectImage = {}, onSkipImage = {},
                )
            }
        }

        compose.onNodeWithText("2025-08-01").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("20:00").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.RecordEditorScroll).performTouchInput {
            swipeUp(startY = height * 0.75f, endY = height * 0.35f)
        }
        compose.onNodeWithText("未评分").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `day detail edits and confirms before delete`() {
        val record = DrinkRecord(
            "r", 1, "2026-08-05", ItemType.CHAIN_PRODUCT, "item", null, null, null, null,
            DrinkSnapshot("品牌", "产品", null, null, null),
        )
        val state = JournalUiState.empty(2026, 8).copy(records = listOf(record), selectedDate = "2026-08-05")
        var edited: String? = null
        var deleted: String? = null
        compose.setContent {
            CoffeeTheme {
                JournalScreen(state, {}, {}, {}, {}, onEditRecord = { edited = it }, onDeleteRecord = { deleted = it })
            }
        }

        compose.onNodeWithText("编辑").performClick()
        compose.runOnIdle { assertEquals("r", edited) }
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithText("确认删除").performClick()
        compose.runOnIdle { assertEquals("r", deleted) }
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
