package com.niumi.coffeejournal.journal

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import com.niumi.coffeejournal.ui.CoffeeVisuals
import com.niumi.coffeejournal.TestTags
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `record editor groups date only fields on the cream surface`() {
        compose.setContent {
            CoffeeTheme {
                RecordDrinkScreen(
                    state = JournalUiState.empty(2026, 8).editor,
                    brands = emptyList(), items = emptyList(),
                    onSourceTypeChange = {}, onBrandSelect = {}, onItemSelect = {}, onRatingChange = {},
                    onPriceChange = {}, onBrewMethodChange = {}, onNoteChange = {}, onSave = {}, onBack = {},
                    onSelectImage = {}, onSkipImage = {},
                )
            }
        }
        compose.onNodeWithTag(TestTags.RecordEditorSurface).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(RecordEditorSurfaceColor, CoffeeVisuals.cream))
        compose.onNodeWithTag(TestTags.RecordEditorSectionPrefix + "date").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(RecordEditorSectionColor, CoffeeVisuals.white))
        compose.onNodeWithTag(TestTags.ConfirmSave)
            .assert(SemanticsMatcher.expectValue(RecordSaveContainerColor, CoffeeVisuals.forest))
        compose.onAllNodesWithText("饮用时间").assertCountEquals(0)
    }

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
    fun `record button exposes the preview forest green style contract`() {
        compose.setContent {
            CoffeeTheme {
                JournalScreen(JournalUiState.empty(2026, 8), {}, {}, {}, {})
            }
        }

        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.RecordButton)
            .assert(SemanticsMatcher.expectValue(RecordButtonContainerColor, CoffeeVisuals.forest))
            .assert(SemanticsMatcher.expectValue(RecordButtonContentColor, Color.White))
    }

    @Test
    fun `calendar display switch labels are exact and selects one mode`() {
        val selectedMode = mutableStateOf(CalendarDisplayMode.COFFEE)
        var selected: CalendarDisplayMode? = null
        compose.setContent {
            CoffeeTheme {
                JournalScreen(
                    state = JournalUiState.empty(2026, 8).copy(calendarDisplayMode = selectedMode.value),
                    onPreviousMonth = {}, onNextMonth = {}, onDayClick = {}, onRecordDrink = {},
                    onCalendarDisplayModeChange = { mode -> selected = mode; selectedMode.value = mode },
                )
            }
        }

        compose.onNodeWithText("品牌").assertIsDisplayed()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.CalendarCoffeeDisplayMode)
            .assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.CalendarBrandDisplayMode).assertIsNotSelected()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.CalendarBrandDisplayMode).performClick()
        compose.runOnIdle { assertEquals(CalendarDisplayMode.BRAND, selected) }
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.CalendarBrandDisplayMode).assertIsSelected()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.CalendarCoffeeDisplayMode).assertIsNotSelected()
    }

    @Test
    fun `calendar mode control uses exact labels without a selection checkmark`() {
        compose.setContent {
            CoffeeTheme {
                JournalScreen(
                    state = JournalUiState.empty(2026, 8),
                    onPreviousMonth = {}, onNextMonth = {}, onDayClick = {}, onRecordDrink = {},
                )
            }
        }

        compose.onAllNodesWithText("品牌", substring = false).assertCountEquals(1)
        compose.onAllNodesWithText("咖啡", substring = false).assertCountEquals(1)
        compose.onAllNodesWithText("✓", substring = false).assertCountEquals(0)
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.CalendarModeIndicator).assertIsDisplayed()
    }

    @Test
    fun `calendar uses youthful mode and summary component semantics`() {
        compose.setContent {
            CoffeeTheme {
                JournalScreen(
                    state = JournalUiState.empty(2026, 8).copy(calendarDisplayMode = CalendarDisplayMode.BRAND),
                    onPreviousMonth = {}, onNextMonth = {}, onDayClick = {}, onRecordDrink = {},
                )
            }
        }

        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.CalendarModeIndicator).assertIsDisplayed()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.CalendarBrandDisplayMode).assertIsSelected()
        compose.onNodeWithContentDescription("上一月").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一月").assertIsDisplayed()
        compose.onAllNodesWithTag("month-summary-metric", useUnmergedTree = true).assertCountEquals(3)
    }

    @Test
    fun `recorded calendar day retains a distinct media node while empty day remains numbered`() {
        compose.setContent { CoffeeTheme { JournalScreen(calendarState("2026-08-05", drinkCount = 1), {}, {}, {}, {}) } }

        compose.onNodeWithTag("calendar-image-2026-08-05", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("calendar-day-number-2026-08-04", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `recorded calendar day uses one observable compact media safety inset`() {
        compose.setContent { CoffeeTheme { JournalScreen(calendarState("2026-08-05", drinkCount = 1), {}, {}, {}, {}) } }

        compose.onNodeWithTag("calendar-media-frame-2026-08-05", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(CalendarMediaInsetDp, 3f))
        compose.onNodeWithTag("calendar-image-2026-08-05", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `each calendar mode option has a 48dp touch target`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                CoffeeTheme { JournalScreen(JournalUiState.empty(2026, 8), {}, {}, {}, {}) }
            }
        }

        compose.runOnIdle {
            listOf(
                com.niumi.coffeejournal.TestTags.CalendarBrandDisplayMode,
                com.niumi.coffeejournal.TestTags.CalendarCoffeeDisplayMode,
            ).forEach { tag ->
                compose.onNodeWithTag(tag)
                    .assert(SemanticsMatcher.expectValue(CalendarModeTouchTargetMinHeight, 48f))
                assertTrue(compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height >= 48f)
            }
        }
    }

    @Test
    fun `month header labels invoke their respective callbacks once`() {
        var previousCalls = 0
        var nextCalls = 0
        compose.setContent {
            CoffeeTheme {
                JournalScreen(
                    state = JournalUiState.empty(2026, 8),
                    onPreviousMonth = { previousCalls++ }, onNextMonth = { nextCalls++ },
                    onDayClick = {}, onRecordDrink = {},
                )
            }
        }

        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.PreviousMonth)
            .assertIsDisplayed().performClick()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.NextMonth)
            .assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, previousCalls)
            assertEquals(1, nextCalls)
        }
    }

    @Test
    fun `month navigation controls provide 48dp touch targets`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                CoffeeTheme { JournalScreen(JournalUiState.empty(2026, 8), {}, {}, {}, {}) }
            }
        }

        compose.runOnIdle {
            listOf(
                com.niumi.coffeejournal.TestTags.PreviousMonth,
                com.niumi.coffeejournal.TestTags.NextMonth,
            ).forEach { tag ->
                val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.width >= 48f)
                assertTrue(bounds.height >= 48f)
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `calendar controls remain visible in a 360dp viewport and core actions stay reachable`() {
        compose.setContent {
            CoffeeTheme {
                JournalScreen(
                    state = JournalUiState.empty(2026, 8),
                    onPreviousMonth = {}, onNextMonth = {}, onDayClick = {}, onRecordDrink = {},
                )
            }
        }

        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.PreviousMonth).assertIsDisplayed()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.NextMonth).assertIsDisplayed()
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
            compose.onNodeWithText(weekday, substring = false).assertIsDisplayed()
        }
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.Calendar).assertIsDisplayed()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.RecordButton).assertIsDisplayed()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.RootScreenSettings).assertIsDisplayed()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.Calendar).performTouchInput { swipeUp() }
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.MonthSummaryCard).assertIsDisplayed()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.MonthlySpend).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `month header remains fully readable at 360dp with enlarged text`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                CoffeeTheme {
                    JournalScreen(JournalUiState.empty(2026, 8), {}, {}, {}, {})
                }
            }
        }

        compose.onNodeWithContentDescription("上一月").assertIsDisplayed()
        compose.onNodeWithText("2026年8月", substring = false).assertIsDisplayed()
        compose.onNodeWithContentDescription("下一月").assertIsDisplayed()
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
    fun `empty day keeps its date but recorded day replaces the date with media`() {
        val state = calendarState("2026-08-05", drinkCount = 1)

        compose.setContent { CoffeeTheme { JournalScreen(state, {}, {}, {}, {}) } }

        compose.onAllNodesWithTag("calendar-day-number-2026-08-04", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag("calendar-day-number-2026-08-05", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("calendar-day-2026-08-05").assertCountEquals(1)
    }

    @Test
    fun `calendar count badge appears only for multiple drinks`() {
        val state = androidx.compose.runtime.mutableStateOf(calendarState("2026-08-05", drinkCount = 1))
        compose.setContent { CoffeeTheme { JournalScreen(state.value, {}, {}, {}, {}) } }
        compose.onAllNodesWithTag("calendar-count-badge-2026-08-05", useUnmergedTree = true).assertCountEquals(0)

        compose.runOnIdle { state.value = calendarState("2026-08-05", drinkCount = 3) }
        compose.onAllNodesWithTag("calendar-count-badge-2026-08-05", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("×3").assertCountEquals(1)
    }

    @Test
    fun `brand mode renders known bundled brand and custom logo as calendar media`() {
        val customLogo = temporaryBitmap("custom-brand")
        val bundled = calendarState("2026-08-05", drinkCount = 1, brandName = "瑞幸", brandLogoPath = "/bad/legacy-logo")
            .copy(calendarDisplayMode = CalendarDisplayMode.BRAND)
        val state = androidx.compose.runtime.mutableStateOf(bundled)
        compose.setContent { CoffeeTheme { JournalScreen(state.value, {}, {}, {}, {}) } }
        compose.onAllNodesWithTag("calendar-image-2026-08-05", useUnmergedTree = true).assertCountEquals(1)

        val custom = calendarState("2026-08-05", drinkCount = 1, brandName = "自定义", brandLogoPath = customLogo.absolutePath)
            .copy(calendarDisplayMode = CalendarDisplayMode.BRAND)
        compose.runOnIdle { state.value = custom }
        compose.onAllNodesWithTag("calendar-image-2026-08-05", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `coffee mode falls back to known bundled logo when product image is unreadable`() {
        val corrupt = File.createTempFile("corrupt-product", ".png").apply { writeText("broken") }
        val state = calendarState("2026-08-05", drinkCount = 1, imagePath = corrupt.absolutePath, brandName = "瑞幸")
        compose.setContent { CoffeeTheme { JournalScreen(state, {}, {}, {}, {}) } }
        compose.onAllNodesWithTag("calendar-image-2026-08-05", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `calendar media selection protects bundled logos and distinguishes custom and placeholder fallbacks`() {
        val bundled = selectCalendarMedia(
            mode = CalendarDisplayMode.COFFEE,
            imagePath = "/corrupt-product.png",
            brandLogoPath = "/legacy-logo.png",
            brandName = "瑞幸",
        )
        assertEquals("/corrupt-product.png", bundled.primaryPath)
        assertNull(bundled.fallbackPath)
        assertNotNull(bundled.bundledLogoRes)
        assertEquals(CalendarMediaFallback.BUNDLED_LOGO, bundled.fallback)

        val bundledWithoutProduct = selectCalendarMedia(
            mode = CalendarDisplayMode.COFFEE,
            imagePath = null,
            brandLogoPath = "/legacy-logo.png",
            brandName = "瑞幸",
        )
        assertNull(bundledWithoutProduct.primaryPath)
        assertNull(bundledWithoutProduct.fallbackPath)
        assertNotNull(bundledWithoutProduct.bundledLogoRes)

        val custom = selectCalendarMedia(
            mode = CalendarDisplayMode.COFFEE,
            imagePath = null,
            brandLogoPath = "/custom-logo.png",
            brandName = "我的品牌",
        )
        assertEquals("/custom-logo.png", custom.fallbackPath)
        assertNull(custom.bundledLogoRes)
        assertEquals(CalendarMediaFallback.CUSTOM_LOGO, custom.fallback)

        val placeholder = selectCalendarMedia(
            mode = CalendarDisplayMode.COFFEE,
            imagePath = null,
            brandLogoPath = null,
            brandName = null,
        )
        assertNull(placeholder.primaryPath)
        assertNull(placeholder.fallbackPath)
        assertNull(placeholder.bundledLogoRes)
        assertEquals(CalendarMediaFallback.PLACEHOLDER, placeholder.fallback)
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
    fun `editor shows editable date and no time entry`() {
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
        compose.onNodeWithText("饮用日期").assertIsDisplayed()
        compose.onAllNodesWithText("20:00").assertCountEquals(0)
        compose.onAllNodesWithText("饮用日期与时间").assertCountEquals(0)
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

    private fun calendarState(
        date: String,
        drinkCount: Int,
        imagePath: String? = null,
        brandName: String? = null,
        brandLogoPath: String? = null,
    ): JournalUiState = JournalUiState.empty(2026, 8).let { empty ->
        empty.copy(days = empty.days.map { day ->
            if (day.localDate == date) day.copy(
                drinkCount = drinkCount,
                imagePath = imagePath,
                brandName = brandName,
                brandLogoPath = brandLogoPath,
            ) else day
        })
    }
}
