package com.niumi.coffeejournal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.niumi.coffeejournal.catalog.BrandProductsScreen
import com.niumi.coffeejournal.catalog.CatalogTab
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ChainProductKind
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performScrollTo
import com.niumi.coffeejournal.catalog.BrandOverview
import com.niumi.coffeejournal.catalog.CatalogScreen
import com.niumi.coffeejournal.catalog.CatalogUiState
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.journal.RecordDrinkScreen
import com.niumi.coffeejournal.journal.RecordEditorUi
import com.niumi.coffeejournal.journal.localNoonEpoch
import com.niumi.coffeejournal.journal.projectMonth
import com.niumi.coffeejournal.journal.summarizeMonth
import com.niumi.coffeejournal.catalog.BUNDLED_CHAIN_BRANDS
import com.niumi.coffeejournal.journal.CalendarDisplayMode
import com.niumi.coffeejournal.journal.JournalScreen
import com.niumi.coffeejournal.journal.JournalUiState
import com.niumi.coffeejournal.navigation.CoffeeBottomNavigation
import com.niumi.coffeejournal.navigation.Journal
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Produces release-reviewable previews from the production Compose hierarchy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarPreviewRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `renders brand calendar preview with bundled logos`() = renderPreview(
        fileName = "calendar-brand-cream-forest.png",
        state = previewState(CalendarDisplayMode.BRAND, imagePath = null),
    )

    @Test
    fun `renders coffee calendar preview with local product image`() = renderPreview(
        fileName = "calendar-coffee-cream-forest.png",
        state = previewState(CalendarDisplayMode.COFFEE, imagePath = previewProductImage().absolutePath),
    )

    @Test
    fun `renders scrapbook normal month with consistent photo records and recent note`() {
        val image = previewProductImage().absolutePath
        renderPreview("calendar-coffee-scrapbook.png", sparseState(image), fullMonth = false)
        compose.onNodeWithTag(TestTags.RecentDrinkNote).performScrollTo().assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(TestTags.RecentDrinkImage, useUnmergedTree = true).fetchSemanticsNodes().any {
                runCatching { it.config[SemanticsProperties.StateDescription] }.getOrNull() == "主图片已加载"
            }
        }
        capture("calendar-recent-scrapbook.png")
    }

    @Test
    fun `renders scrapbook empty month`() {
        setCalendarContent(JournalUiState.empty(2026, 9))
        compose.onNodeWithText("2026年9月").assertIsDisplayed()
        capture("calendar-empty-scrapbook.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h480dp-xhdpi")
    fun `renders small screen with reachable calendar and record action`() {
        setCalendarContent(sparseState(previewProductImage().absolutePath))
        compose.onNodeWithTag(TestTags.Calendar).assertIsDisplayed()
        capture("calendar-small-top-scrapbook.png")
        compose.onNodeWithTag(TestTags.RecentDrinkNote).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(TestTags.RecordButton).assertIsDisplayed()
        val noteBounds = compose.onNodeWithTag(TestTags.RecentDrinkNote).fetchSemanticsNode().boundsInRoot
        val buttonBounds = compose.onNodeWithTag(TestTags.RecordButton).fetchSemanticsNode().boundsInRoot
        assertTrue("The fixed record action must not cover the recent drink note", noteBounds.bottom <= buttonBounds.top)
        compose.onNodeWithTag(TestTags.BottomCalendarTab).assertIsDisplayed()
        capture("calendar-small-bottom-scrapbook.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h760dp-xhdpi")
    fun `narrow phone gives photos space and keeps mode beside title at enlarged font`() {
        setCalendarContent(sparseState(previewProductImage().absolutePath), fontScale = 1.3f)
        val title = compose.onNodeWithTag(TestTags.RootScreenTitle).fetchSemanticsNode().boundsInRoot
        val mode = compose.onNodeWithTag(TestTags.CalendarModeIndicator).fetchSemanticsNode().boundsInRoot
        assertTrue("Mode must share header row instead of creating an empty second row", mode.top < title.bottom && mode.bottom > title.top)
        val photo = compose.onNodeWithTag(TestTags.CalendarImagePrefix + "2026-09-01", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("Photo width must use the seven-column space", photo.width >= 38.dp.toPx())
            assertTrue("Tall product photos need at least 48dp height", photo.height >= 48.dp.toPx())
        }
        val settingsLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("设置", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(settingsLayouts) }
        // Native paragraph width can retain the parent constraint after Text wraps its size.
        // Check the visible glyph range and line bounds rather than that constraint width.
        assertTrue("Settings label must show both characters at enlarged font", settingsLayouts.isNotEmpty() && settingsLayouts.all {
            it.lineCount == 1 && it.getLineEnd(0, visibleEnd = true) == 2 &&
                it.getLineRight(0) <= it.size.width + 1f && !it.didOverflowHeight
        })
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(TestTags.RecentDrinkImage, useUnmergedTree = true).fetchSemanticsNodes().any {
                runCatching { it.config[SemanticsProperties.StateDescription] }.getOrNull() == "主图片已加载"
            }
        }
        compose.onNodeWithTag(TestTags.RecordButton).assertIsDisplayed()
        capture("calendar-narrow-largefont-scrapbook.png")
    }

    @Test
    fun `renders redesigned product photo collection with long names`() {
        val brand = BUNDLED_CHAIN_BRANDS.first().brand
        val products = listOf("经典冰拿铁", "桂花燕麦拿铁秋日限定超长产品名称", "柚子美式", "冷萃咖啡").mapIndexed { index, name ->
            CatalogItem("photo-$index", brand.id, ItemType.CHAIN_PRODUCT, name, "preview-photo", null, null, null, null, null, ItemStatus.ACTIVE, chainProductKind = ChainProductKind.MILK)
        }
        compose.setContent { CoffeeTheme {
            BrandProductsScreen(brand, products, { previewProductImage().absolutePath }, {}, {}, {}, {})
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("经典冰拿铁 图片", useUnmergedTree = true).fetchSemanticsNodes().any {
                runCatching { it.config[SemanticsProperties.StateDescription] }.getOrNull() == "主图片已加载"
            }
        }
        compose.onNodeWithTag(TestTags.BrandProductGrid).assertIsDisplayed()
        compose.onNodeWithText("桂花燕麦拿铁秋日限定超长产品名称").assertIsDisplayed()
        capture("catalog-products-scrapbook.png")
    }

    @Test
    fun `renders scrapbook brand collection`() {
        compose.setContent {
            CoffeeTheme {
                Scaffold(bottomBar = { CoffeeBottomNavigation(com.niumi.coffeejournal.navigation.Catalog, {}) }) { padding ->
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(padding)) {
                        CatalogScreen(
                            state = CatalogUiState(brandOverviews = BUNDLED_CHAIN_BRANDS.map { BrandOverview(it.brand, 0) }),
                            onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {}, onSaveBrand = {},
                            onSaveItem = {}, onSetItemStatus = { _, _ -> }, onClearError = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag(TestTags.ChainBrandGrid).assertIsDisplayed()
        capture("catalog-scrapbook.png")
    }

    @Test
    fun `renders redesigned personal bean notebook`() {
        val brand = Brand("roaster", BrandType.ROASTER, "日常烘焙", null, MaintenanceMode.MANUAL_ONLY, null)
        val bean = CatalogItem("bean", brand.id, ItemType.PERSONAL_BEAN, "埃塞俄比亚 · 耶加雪菲", null, "埃塞俄比亚", "水洗", "浅烘", "茉莉、柑橘", "手冲", ItemStatus.ACTIVE)
        compose.setContent { CoffeeTheme {
            Scaffold(bottomBar = { CoffeeBottomNavigation(com.niumi.coffeejournal.navigation.Catalog, {}) }) { padding ->
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(padding)) {
                    CatalogScreen(CatalogUiState(tab = CatalogTab.BEANS, brandOverviews = listOf(BrandOverview(brand, 1)), selectedBrandId = brand.id, items = listOf(bean)), {}, {}, {}, {}, {}, { _, _ -> }, {})
                }
            }
        } }
        compose.onNodeWithText(bean.name).performScrollTo().assertIsDisplayed()
        capture("catalog-beans-scrapbook.png")
    }

    @Test
    fun `renders scrapbook record form`() {
        compose.setContent {
            CoffeeTheme {
                RecordDrinkScreen(
                    state = RecordEditorUi(consumedAtEpochMillis = localNoonEpoch("2026-09-11")),
                    brands = BUNDLED_CHAIN_BRANDS.map { it.brand }, items = emptyList(),
                    onSourceTypeChange = {}, onBrandSelect = {}, onItemSelect = {}, onRatingChange = {},
                    onPriceChange = {}, onBrewMethodChange = {}, onNoteChange = {}, onSave = {},
                    onBack = {}, onSelectImage = {}, onSkipImage = {},
                )
            }
        }
        compose.onNodeWithTag(TestTags.RecordEditorSurface).assertIsDisplayed()
        capture("record-form-scrapbook.png")
    }

    private fun setCalendarContent(state: JournalUiState, fontScale: Float = 1f) {
        compose.setContent {
            CoffeeTheme {
                Scaffold(bottomBar = { CoffeeBottomNavigation(selectedRoot = Journal, onRootSelected = {}) }) { padding ->
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(padding)) {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                            JournalScreen(state, {}, {}, {}, {}, imagePathResolver = ImagePathResolver { assetId ->
                                if (assetId == "preview-photo") previewProductImage().absolutePath else null
                            })
                        }
                    }
                }
            }
        }
    }

    private fun renderPreview(fileName: String, state: JournalUiState, fullMonth: Boolean = true) {
        val recordedDates = state.days.filter { it.inDisplayedMonth && it.drinkCount > 0 }.map { it.localDate }
        val expectedState = if (state.calendarDisplayMode == CalendarDisplayMode.BRAND) "品牌图片" else "主图片已加载"
        setCalendarContent(state)

        // LocalAssetImage loads paths asynchronously; every recorded day must reach its final image state.
        compose.waitUntil(10_000) {
            recordedDates.all { date ->
                compose.onAllNodesWithTag(TestTags.CalendarImagePrefix + date, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .any { runCatching { it.config[SemanticsProperties.StateDescription] }.getOrNull() == expectedState }
            }
        }
        assertTrue("brand preview must cover all 12 bundled brands", BUNDLED_CHAIN_BRANDS.size == 12)
        assertTrue("preview must populate intended dates", recordedDates.size == if (fullMonth) 31 else 7)
        recordedDates.forEach { date ->
            compose.onAllNodesWithTag(TestTags.CalendarDayNumberPrefix + date, useUnmergedTree = true).assertCountEquals(0)
            val imageStates = compose.onAllNodesWithTag(TestTags.CalendarImagePrefix + date, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .mapNotNull { runCatching { it.config[SemanticsProperties.StateDescription] }.getOrNull() }
            assertTrue("$date must render $expectedState rather than a placeholder", expectedState in imageStates)
        }
        if (fullMonth) compose.onNodeWithTag(TestTags.CalendarCountBadgePrefix + "2026-08-20", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(TestTags.RootScreenTitle).assertIsDisplayed()
        compose.onNodeWithText("${state.year}年${state.month}月").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.PreviousMonth).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.NextMonth).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.Calendar).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.MonthSummaryCard).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.BottomCalendarTab).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.BottomCatalogTab).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.BottomInsightsTab).assertIsDisplayed()
        capture(fileName)
    }

    private fun capture(fileName: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val decorView = compose.activity.window.decorView
            require(decorView.width > 0 && decorView.height > 0) { "decor view must be laid out" }
            decorView.invalidate()
            val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
            decorView.draw(Canvas(bitmap))
            val output = File("build/reports/previews", fileName)
            output.parentFile?.mkdirs()
            FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val decoded = BitmapFactory.decodeFile(output.absolutePath)
            assertNotNull("preview must be a decodable PNG", decoded)
            assertTrue("preview must use a readable native resolution", decoded!!.width >= 640)
            assertTrue("preview must not be empty", output.length() > 0)
            val colors = buildSet {
                for (y in 0 until decoded.height step 32) for (x in 0 until decoded.width step 32) add(decoded.getPixel(x, y))
            }
            assertTrue("preview must contain rendered, non-flat pixels", colors.size > 8)
        }
    }

    private fun previewState(mode: CalendarDisplayMode, imagePath: String?): JournalUiState {
        val brandNames = BUNDLED_CHAIN_BRANDS.map { it.brand.name }
        val empty = JournalUiState.empty(2026, 8)
        val records = (1..31).flatMap { day ->
            List(if (day == 20) 2 else 1) { cup ->
                previewRecord("2026-08-${day.toString().padStart(2, '0')}", brandNames[(day - 1) % brandNames.size], cup)
            }
        }
        return empty.copy(
            calendarDisplayMode = mode,
            records = records,
            summary = summarizeMonth(records),
            days = projectMonth(2026, 8, records, records.associate { it.id to imagePath }),
        )
    }

    private fun sparseState(imagePath: String): JournalUiState {
        val records = listOf(1, 2, 4, 6, 8, 9, 11).map { day ->
            previewRecord("2026-09-${day.toString().padStart(2, '0')}", "MANNER", 0)
        }
        return JournalUiState.empty(2026, 9).copy(
            calendarDisplayMode = CalendarDisplayMode.COFFEE,
            records = records,
            summary = summarizeMonth(records),
            days = projectMonth(2026, 9, records, records.associate { it.id to imagePath }),
        )
    }

    private fun previewRecord(date: String, brand: String, cup: Int) = DrinkRecord(
        id = "$date-$cup", occurredAtEpochMillis = localNoonEpoch(date), localDate = date,
        itemType = ItemType.CHAIN_PRODUCT, sourceItemId = "preview-product", brewMethod = null,
        ratingHalfStars = 8, actualPriceFen = 1500L, note = null,
        snapshot = DrinkSnapshot(brand, "拿铁", null, null, "preview-photo"),
    )

    private fun previewProductImage(): File {
        val supplied = checkNotNull(javaClass.classLoader?.getResource("fixtures/IMG_20260815_193103.png")) {
            "Bundled real product photo fixture is required for the release preview"
        }
        return File(supplied.toURI()).also {
            require(it.isFile && BitmapFactory.decodeFile(it.absolutePath) != null) {
                "Bundled real product photo fixture must be decodable"
            }
        }
    }
}
