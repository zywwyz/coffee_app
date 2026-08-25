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
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
        fileName = "brand-calendar.png",
        state = previewState(CalendarDisplayMode.BRAND, imagePath = null),
    )

    @Test
    fun `renders coffee calendar preview with local product image`() = renderPreview(
        fileName = "coffee-calendar.png",
        state = previewState(CalendarDisplayMode.COFFEE, imagePath = previewProductImage().absolutePath),
    )

    private fun renderPreview(fileName: String, state: JournalUiState) {
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

        // LocalAssetImage loads paths asynchronously; wait for it and for every preview day to compose.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("咖啡图片", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                listOf("2026-08-06", "2026-08-15", "2026-08-18", "2026-08-20").all { date ->
                    val nodes = compose.onAllNodesWithTag(TestTags.CalendarImagePrefix + date, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                    nodes.isNotEmpty() && (state.calendarDisplayMode != CalendarDisplayMode.COFFEE || nodes.any {
                        runCatching { it.config[SemanticsProperties.StateDescription] }.getOrNull() == "主图片已加载"
                    })
                }
        }
        compose.onNodeWithTag(TestTags.Calendar).assertExists()
        compose.runOnIdle {
            val decorView = compose.activity.window.decorView
            require(decorView.width > 0 && decorView.height > 0) { "decor view must be laid out" }
            decorView.invalidate()
            val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
            decorView.draw(Canvas(bitmap))
            val output = File("build/reports/calendar-previews", fileName)
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
        val displayDates = setOf("2026-08-06", "2026-08-15", "2026-08-18", "2026-08-20")
        val brandNames = BUNDLED_CHAIN_BRANDS.map { it.brand.name }
        val empty = JournalUiState.empty(2026, 8)
        return empty.copy(
            calendarDisplayMode = mode,
            days = empty.days.mapIndexed { index, day ->
                if (!day.inDisplayedMonth) day else day.copy(
                    brandName = brandNames[index % brandNames.size],
                    drinkCount = when (mode) {
                        CalendarDisplayMode.BRAND -> if (day.localDate == "2026-08-20") 2 else 1
                        CalendarDisplayMode.COFFEE -> if (day.localDate == "2026-08-20") 2 else if (day.localDate in displayDates) 1 else 0
                    },
                    imagePath = if (day.localDate in displayDates) imagePath else null,
                )
            },
        )
    }

    private fun previewProductImage(): File {
        val supplied = System.getenv("COFFEE_PREVIEW_PRODUCT_IMAGE")?.let(::File)
            ?: error("COFFEE_PREVIEW_PRODUCT_IMAGE must point to the real product photo used for review")
        require(supplied.isFile && BitmapFactory.decodeFile(supplied.absolutePath) != null) {
            "COFFEE_PREVIEW_PRODUCT_IMAGE must be a decodable image file"
        }
        return supplied
    }
}
