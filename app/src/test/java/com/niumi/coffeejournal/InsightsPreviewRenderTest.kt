package com.niumi.coffeejournal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.niumi.coffeejournal.core.database.DrinkRecordEntity
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.journal.localNoonEpoch
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Produces release-reviewable previews through the real Activity, Room repository and asset resolver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi", application = InMemoryCoffeeJournalApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsightsPreviewRenderTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `renders monthly and yearly insights using Room records and real product assets`() = runBlocking {
        val app = compose.activity.application as InMemoryCoffeeJournalApp
        appToClose = app
        val asset = app.imageStore.importWhole(Uri.fromFile(realFixture("IMG_20260815_193103.png")), ImageKind.PRODUCT)
        app.imageStore.importWhole(Uri.fromFile(realFixture("IMG_20260815_193103-scaled.png")), ImageKind.PRODUCT)
        seed(app, asset.id)

        compose.onNodeWithTag(TestTags.BottomInsightsTab).performClick()
        awaitDashboard()
        captureTop("本月累计杯数", "上月同期累计杯数", "insights-monthly-hero-trend-cream-forest.png")
        captureBreakdown("insights-monthly-breakdown-cream-forest.png")
        compose.onNodeWithContentDescription("本期最好 MANNER", useUnmergedTree = true).assertExists()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("本期最差 瑞幸", useUnmergedTree = true).fetchSemanticsNodes().any {
                runCatching { it.config[SemanticsProperties.StateDescription] }.getOrNull() == "主图片已加载"
            }
        }
        captureHighlights("insights-monthly-highlights-cream-forest.png")

        compose.onNodeWithText("年度").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("今年每月杯数", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        captureTop("今年每月杯数", "去年同期每月杯数", "insights-yearly-hero-trend-cream-forest.png")
        captureBreakdown("insights-yearly-breakdown-cream-forest.png")
        captureHighlights("insights-yearly-highlights-cream-forest.png")
    }

    private fun awaitDashboard() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(TestTags.InsightsHabitHero).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(TestTags.InsightsCoffeeTypeDonut).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun captureTop(currentLegend: String, previousLegend: String, name: String) {
        compose.onNodeWithTag(TestTags.InsightsSurface).performScrollToNode(hasTestTag(TestTags.InsightsTrendChart))
        compose.onNodeWithTag(TestTags.InsightsHabitHero).assertExists()
        compose.onNodeWithTag(TestTags.InsightsTrendChart).assertIsDisplayed()
        compose.onNodeWithText(currentLegend, substring = true).assertIsDisplayed()
        compose.onNodeWithText(previousLegend, substring = true).assertIsDisplayed()
        capture(name)
    }

    private fun captureBreakdown(name: String) {
        compose.onNodeWithTag(TestTags.InsightsSurface).performScrollToNode(hasTestTag(TestTags.InsightsCoffeeTypeDonut))
        compose.onNodeWithTag(TestTags.InsightsCoffeeTypeDonut).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.InsightsBrandDonut).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.InsightsTopBrands).assertExists()
        compose.onNodeWithTag(TestTags.InsightsTopProducts).assertExists()
        compose.onNodeWithText("另有", substring = true).assertExists()
        compose.onAllNodesWithText("第4品牌").assertCountEquals(0)
        compose.onAllNodesWithText("第4产品").assertCountEquals(0)
        compose.onNodeWithTag(TestTags.BottomInsightsTab).assertIsDisplayed()
        capture(name)
    }

    private fun captureHighlights(name: String) {
        compose.onNodeWithTag(TestTags.InsightsWorstCard).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("本期最好 MANNER", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.BottomInsightsTab).assertIsDisplayed()
        capture(name)
    }

    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output = File("build/reports/previews", name)
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val decoded = BitmapFactory.decodeFile(output.absolutePath)
        assertNotNull("$name must be decodable", decoded)
        assertTrue("$name must be review-sized", maxOf(decoded!!.width, decoded.height) > 1000)
        val colors = buildSet { for (y in 0 until decoded.height step 32) for (x in 0 until decoded.width step 32) add(decoded.getPixel(x, y)) }
        assertTrue("$name must not be flat", colors.size > 8)
    }

    private suspend fun seed(app: CoffeeJournalApp, productAssetId: String) {
        val records = listOf(
            row("aug-01", "2026-08-01", "MANNER", "奶油拿铁", "MILK", 10, 1600),
            row("aug-02", "2026-08-02", "MANNER", "奶油拿铁", "MILK", 8, 1600),
            row("aug-03", "2026-08-09", "MANNER", "桂花拿铁", "MILK", 10, null),
            row("aug-04", "2026-08-04", "瑞幸", "超长名称冷萃咖啡限定风味", "BLACK", 2, 990, productAssetId),
            row("aug-05", "2026-08-05", "瑞幸", "超长名称冷萃咖啡限定风味", "BLACK", 2, 990, productAssetId),
            row("aug-06", "2026-08-06", "库迪", "果咖", "FRUIT", 10, 1200),
            row("aug-07", "2026-08-07", "星巴克", "手冲埃塞", "HAND_BREW", 10, 3200, null, "PERSONAL_BEAN", "HAND_BREW"),
            row("aug-08", "2026-08-01", "第4品牌", "第4产品", "BLACK", 6, 1100),
            row("jul-03", "2026-07-03", "MANNER", "上月拿铁", "MILK", 8, 1500),
            row("jul-06", "2026-07-06", "库迪", "上月果咖", "FRUIT", null, null),
            row("last-year", "2025-08-04", "MANNER", "去年拿铁", "MILK", 8, 1400),
        )
        records.forEach { app.database.drinkDao().insert(it) }
    }

    private fun row(id: String, date: String, brand: String, item: String, type: String, rating: Int?, price: Long?, image: String? = null, itemType: String = "CHAIN_PRODUCT", brew: String? = null): DrinkRecordEntity {
        val time = localNoonEpoch(date) + id.length
        return DrinkRecordEntity(id, time, date, itemType, "$brand-$item", brew, rating, price, null, brand, item, null, null, image, null, null, null, type, time, time)
    }

    private fun realFixture(name: String): File {
        val url = checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { "Missing real product fixture $name" }
        return File(url.toURI()).also { require(it.isFile && BitmapFactory.decodeFile(it.absolutePath) != null) }
    }

    private companion object {
        var appToClose: InMemoryCoffeeJournalApp? = null
        @JvmStatic @AfterClass fun closeDatabase() { appToClose?.database?.close(); appToClose = null }
    }
}
