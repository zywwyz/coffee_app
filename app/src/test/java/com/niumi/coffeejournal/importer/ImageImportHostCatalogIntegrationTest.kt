package com.niumi.coffeejournal.importer

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.niumi.coffeejournal.core.image.CropRect
import com.niumi.coffeejournal.core.image.ImageAsset
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageImportHostEditorStateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `review keeps editor subtree and callback alive until accepted image is saved`() {
        val store = RecordingStore()
        var savedAssetId: String? = null
        compose.setContent {
            CoffeeTheme {
                ImageImportHost(
                    imageStore = store,
                    recognizer = ScreenshotTextRecognizer { emptyList() },
                    pickImage = { _, callback -> callback(Uri.parse("content://test/screenshot")) },
                    showReviewInDialog = false,
                    reviewContent = { request ->
                        LaunchedEffect(request.source) {
                            request.onConfirmed(ConfirmedScreenshotImport("候选拿铁", 990, "new-asset"))
                        }
                    },
                ) { requester ->
                    var editorName by remember { mutableStateOf("手输名称") }
                    var editorImage by remember { mutableStateOf<String?>(null) }
                    Column {
                        Text("编辑器:$editorName")
                        Text("图片:${editorImage ?: "无"}")
                        Button(onClick = {
                            requester(ImageKind.PRODUCT, ImageImportMode.SCREENSHOT, "old-asset") { selection ->
                                editorImage = selection.assetId
                                true
                            }
                        }) { Text("选择图片") }
                        Button(onClick = { savedAssetId = editorImage }) { Text("保存编辑器") }
                        Button(onClick = { editorName = "被重置" }) { Text("改名") }
                    }
                }
            }
        }

        compose.onNodeWithText("选择图片").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("编辑器:手输名称").assertIsDisplayed()
        compose.onNodeWithText("图片:new-asset").assertIsDisplayed()
        compose.onNodeWithText("保存编辑器").performClick()

        compose.runOnIdle {
            assertEquals("new-asset", savedAssetId)
            assertEquals(listOf("old-asset"), store.deleted)
            assertFalse("new-asset" in store.deleted)
        }
    }

    private class RecordingStore : ImageStore {
        val deleted = mutableListOf<String>()
        override suspend fun importCropped(source: Uri, crop: CropRect, kind: ImageKind) =
            ImageAsset("new-asset", "/private/new.webp", "sha", kind)
        override suspend fun importWhole(source: Uri, kind: ImageKind) =
            ImageAsset("new-asset", "/private/new.webp", "sha", kind)
        override suspend fun deleteIfUnreferenced(assetId: String): Boolean {
            deleted += assetId
            return true
        }
    }
}
