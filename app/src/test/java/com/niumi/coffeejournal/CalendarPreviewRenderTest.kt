package com.niumi.coffeejournal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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

    private fun renderPreview(fileName: String, state: JournalUiState) {
        val recordedDates = state.days.filter { it.inDisplayedMonth && it.drinkCount > 0 }.map { it.localDate }
        val expectedState = if (state.calendarDisplayMode == CalendarDisplayMode.BRAND) "品牌图片" else "主图片已加载"
        compose.setContent {
            CoffeeTheme {
                // This deliberately mirrors AppNavigation: outer root Scaffold + JournalScreen + custom tabs.
                Scaffold(bottomBar = { CoffeeBottomNavigation(selectedRoot = Journal, onRootSelected = {}) }) { padding ->
                    androidx.compose.foundation.layout.Box(
                        modifier = androidx.compose.ui.Modifier.padding(padding),
                    ) {
                        JournalScreen(state, {}, {}, {}, {})
                    }
                }
            }
        }

        // LocalAssetImage loads paths asynchronously; every recorded day must reach its final image state.
        compose.waitUntil(10_000) {
            recordedDates.all { date ->
                compose.onAllNodesWithTag(TestTags.CalendarImagePrefix + date, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .any { runCatching { it.config[SemanticsProperties.StateDescription] }.getOrNull() == expectedState }
            }
        }
        assertTrue("brand preview must cover all 12 bundled brands", BUNDLED_CHAIN_BRANDS.size == 12)
        assertTrue("preview must populate every August date", recordedDates.size == 31)
        recordedDates.forEach { date ->
            compose.onAllNodesWithTag(TestTags.CalendarDayNumberPrefix + date, useUnmergedTree = true).assertCountEquals(0)
            val imageStates = compose.onAllNodesWithTag(TestTags.CalendarImagePrefix + date, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .mapNotNull { runCatching { it.config[SemanticsProperties.StateDescription] }.getOrNull() }
            assertTrue("$date must render $expectedState rather than a placeholder", expectedState in imageStates)
        }
        compose.onNodeWithTag(TestTags.CalendarCountBadgePrefix + "2026-08-20", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(TestTags.Calendar).assertExists()
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
            assertTrue("preview must use xxhdpi-sized output", maxOf(decoded!!.width, decoded.height) > 1000)
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
        return empty.copy(
            calendarDisplayMode = mode,
            days = empty.days.mapIndexed { index, day ->
                if (!day.inDisplayedMonth) day else day.copy(
                    brandName = brandNames[index % brandNames.size],
                    drinkCount = when (mode) {
                        CalendarDisplayMode.BRAND -> if (day.localDate == "2026-08-20") 2 else 1
                        CalendarDisplayMode.COFFEE -> if (day.localDate == "2026-08-20") 2 else 1
                    },
                    imagePath = imagePath,
                )
            },
        )
    }

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
