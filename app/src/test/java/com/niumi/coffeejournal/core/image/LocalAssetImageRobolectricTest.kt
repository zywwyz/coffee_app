package com.niumi.coffeejournal.core.image

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalAssetImageRobolectricTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `primary local image reports primary source`() {
        val image = png("primary")
        compose.setContent { LocalAssetImage(image.absolutePath, null, "产品图") }
        waitForState("产品图", "主图片已加载")
    }

    @Test
    fun `fallback local image reports fallback source`() {
        val image = png("fallback")
        compose.setContent { LocalAssetImage("/missing/primary.png", image.absolutePath, "产品图") }
        waitForState("产品图", "备用图片已加载")
    }

    @Test
    fun `missing local image reports placeholder instead of loading forever`() {
        compose.setContent { LocalAssetImage(null, null, "产品图") }
        waitForState("产品图", "图片占位")
    }

    private fun waitForState(description: String, state: String) {
        val matcher = hasContentDescription(description) and hasStateDescription(state)
        compose.waitUntil(5_000) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun png(prefix: String): File = File.createTempFile(prefix, ".png").also { file ->
        FileOutputStream(file).use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.deleteOnExit()
    }
}
