package com.niumi.coffeejournal.importer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.niumi.coffeejournal.core.image.CropRect
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImportReviewScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun full_screen_candidate_can_be_edited_reviewed_confirmed_or_cancelled() {
        var name = "候选拿铁"
        var confirmed = false
        var cancelled = false
        compose.setContent {
            CoffeeTheme {
                ImportReviewContent(
                    state = ImportReviewUiState(
                        imageWidth = 1080,
                        imageHeight = 2400,
                        productName = name,
                        actualPriceYuan = "9.90",
                        crop = CropRect(120, 500, 960, 1600),
                        lowConfidenceFields = setOf("productName", "proposedCrop"),
                    ),
                    onNameChange = { name = it },
                    onPriceChange = {},
                    onCropChange = {},
                    onConfirm = { confirmed = true },
                    onCancel = { cancelled = true },
                )
            }
        }

        compose.onNodeWithContentDescription("截图预览 1080×2400").assertIsDisplayed()
        compose.onNodeWithText("识别结果需要确认").assertIsDisplayed()
        compose.onNodeWithContentDescription("产品名称").performTextClearance()
        compose.onNodeWithContentDescription("产品名称").performTextInput("燕麦拿铁")
        compose.onNodeWithContentDescription("调整裁剪左边界").assertIsDisplayed()
        compose.onNodeWithText("确认并保存裁剪图").performClick()
        assertEquals("燕麦拿铁", name)
        assertTrue(confirmed)

        compose.onNodeWithText("取消").performClick()
        assertTrue(cancelled)
    }
}
