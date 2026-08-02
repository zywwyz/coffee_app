package com.niumi.coffeejournal.importer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.niumi.coffeejournal.catalog.BrandOverview
import com.niumi.coffeejournal.catalog.CatalogScreen
import com.niumi.coffeejournal.catalog.CatalogTab
import com.niumi.coffeejournal.catalog.CatalogUiState
import com.niumi.coffeejournal.catalog.CatalogFallbackActions
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.MaintenanceMode
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
class CatalogUpdateUiRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `brand update is manual and review shows field changes with checkboxes`() {
        var requested: String? = null
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = catalogState(), onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = {}, onSaveItem = {}, onSetItemStatus = { _, _ -> }, onClearError = {},
                    updateState = CatalogUpdateUiState(
                        phase = UpdatePhase.REVIEW, brandId = "brand", brandName = "瑞幸",
                        review = review(), selectedKeys = setOf("new"),
                    ),
                    onUpdateBrand = { requested = it.id }, onToggleUpdateSelection = {},
                    onConfirmUpdate = {}, onDismissUpdate = {},
                )
            }
        }

        compose.onNodeWithText("更新该品牌").performClick()
        compose.runOnIdle { assertEquals("brand", requested) }
        compose.onNodeWithText("审阅官网更新").assertIsDisplayed()
        compose.onNodeWithText("官方描述：旧描述 → 新描述").assertIsDisplayed()
        compose.onNodeWithText("确认所选项").assertIsDisplayed()
    }

    @Test
    fun `screenshot and manual fallback actions stay distinct`() {
        var screenshot = 0
        var manual = 0
        compose.setContent {
            CoffeeTheme {
                CatalogFallbackActions(
                    brand = brand(),
                    onScreenshot = { screenshot++ },
                    onManual = { manual++ },
                )
            }
        }

        compose.onNodeWithText("上传截图").performClick()
        compose.onNodeWithText("手工录入").performClick()
        compose.runOnIdle {
            assertEquals(1, screenshot)
            assertEquals(1, manual)
        }
    }

    private fun catalogState() = CatalogUiState(
        tab = CatalogTab.CHAINS,
        brandOverviews = listOf(BrandOverview(brand(), 2, 1_700_000_000_000)),
    )
    private fun brand() = Brand("brand", BrandType.CHAIN, "瑞幸", null, MaintenanceMode.PUBLIC_SOURCE, "https://official")
    private fun review() = CatalogReview(
        "brand", 9, "https://official",
        listOf(
            CatalogChange(
                "new", ChangeType.MODIFIED, "拿铁", null,
                CatalogCandidate("拿铁", null, null, "新描述", "https://official", null),
                listOf(FieldChange("officialDescription", "旧描述", "新描述")),
            ),
        ),
    )
}
