package com.niumi.coffeejournal.importer

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.niumi.coffeejournal.core.image.CropRect
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
class ImportReviewScreenRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `full screenshot review exposes editable low confidence fields crop controls and actions`() {
        val state = mutableStateOf(
            ImportReviewUiState(
                imageWidth = 1080, imageHeight = 2400, productName = "候选拿铁",
                actualPriceYuan = "9.90", crop = CropRect(120, 500, 960, 1600),
                lowConfidenceFields = setOf("productName", "proposedCrop"),
            ),
        )
        var confirmed = false
        var cancelled = false
        compose.setContent {
            CoffeeTheme {
                ImportReviewContent(
                    state.value,
                    onNameChange = { state.value = state.value.copy(productName = it) },
                    onPriceChange = { state.value = state.value.copy(actualPriceYuan = it) },
                    onCropChange = { state.value = state.value.copy(crop = it) },
                    onConfirm = { confirmed = true },
                    onCancel = { cancelled = true },
                )
            }
        }

        compose.onNodeWithContentDescription("截图预览 1080×2400").assertIsDisplayed()
        compose.onNodeWithText("识别结果需要确认").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("产品名称").performTextReplacement("燕麦拿铁")
        compose.onNodeWithContentDescription("调整裁剪左边界").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("确认并保存裁剪图").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(confirmed); assertEquals("燕麦拿铁", state.value.productName) }
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }
}
