package com.niumi.coffeejournal.importer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
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

    @Test
    fun `loading update exposes cancel action`() {
        var cancelled = false
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = catalogState(), onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = {}, onSaveItem = {}, onSetItemStatus = { _, _ -> }, onClearError = {},
                    updateState = CatalogUpdateUiState(
                        phase = UpdatePhase.LOADING, brandId = "brand", brandName = "瑞幸",
                    ),
                    onDismissUpdate = { cancelled = true },
                )
            }
        }

        compose.onNodeWithText("取消更新").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(cancelled) }
    }

    @Test
    fun `all update failures offer classified message retry screenshot and manual fallback`() {
        var retried: String? = null
        val failure = mutableStateOf(FailureKind.OFFLINE)
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = catalogState(), onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = {}, onSaveItem = {}, onSetItemStatus = { _, _ -> }, onClearError = {},
                    updateState = CatalogUpdateUiState(
                        phase = UpdatePhase.FAILURE, brandId = "brand", brandName = "瑞幸",
                        failureKind = failure.value, message = "测试失败",
                    ),
                    onUpdateBrand = { retried = it.id },
                )
            }
        }

        listOf(
            FailureKind.OFFLINE to "当前离线，请联网后重试。",
            FailureKind.HTTP to "官网暂时无法访问。",
            FailureKind.PARSE_CHANGED to "官网页面结构发生变化。",
            FailureKind.NO_PUBLIC_CATALOG to "该品牌暂无稳定公开产品目录。",
        ).forEach { (kind, label) ->
            compose.runOnIdle { failure.value = kind }
            compose.waitForIdle()
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithText("重试官网更新").assertIsDisplayed()
            compose.onNodeWithText("上传截图").assertIsDisplayed()
            compose.onNodeWithText("手工录入").assertIsDisplayed()
        }
        compose.onNodeWithText("重试官网更新").performClick()
        compose.runOnIdle { assertEquals("brand", retried) }
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
