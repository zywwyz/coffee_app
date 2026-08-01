package com.niumi.coffeejournal.catalog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.mutableStateOf
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.MaintenanceMode
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
class CatalogScreenRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `small catalog shows tabs brand metadata and scrollable add action`() {
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = state(), onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = {}, onSaveItem = {}, onSetItemStatus = { _, _ -> }, onClearError = {},
                )
            }
        }

        compose.onNodeWithText("连锁品牌").assertIsDisplayed()
        compose.onNodeWithText("我的豆子").assertIsDisplayed()
        compose.onNodeWithText("1 个产品").assertIsDisplayed()
        compose.onNodeWithText("尚未更新").assertIsDisplayed()
        compose.onNodeWithText("新增连锁品牌").assertIsDisplayed()
    }

    @Test
    fun `brand editor exposes replaceable asset boundary and disables save while saving`() {
        var assetRequested = false
        val screenState = mutableStateOf(state())
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = screenState.value, onSelectTab = {}, onSelectBrand = {},
                    onSelectBeanStatus = {}, onSaveBrand = {}, onSaveItem = {},
                    onSetItemStatus = { _, _ -> }, onClearError = {},
                    onRequestAsset = { _, kind, _ -> assetRequested = kind == CatalogAssetKind.BRAND_LOGO },
                )
            }
        }
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithText("选择 Logo").performClick()
        compose.runOnIdle { assert(assetRequested) }
        compose.runOnIdle { screenState.value = screenState.value.copy(saving = true) }
        compose.onNodeWithText("保存中…").assertIsNotEnabled()
    }

    @Test
    fun `chain product editor exposes category specification and brew as distinct fields`() {
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = state().copy(selectedBrandId = "brand"),
                    onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = {}, onSaveItem = {}, onSetItemStatus = { _, _ -> }, onClearError = {},
                )
            }
        }

        compose.onNodeWithText("新增连锁产品").performClick()
        compose.onNodeWithText("产品分类（可选）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("规格描述（可选）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("默认冲煮方式（可选）").performScrollTo().assertIsDisplayed()
    }

    private fun state() = CatalogUiState(
        brandOverviews = listOf(
            BrandOverview(
                Brand("brand", BrandType.CHAIN, "瑞幸", null, MaintenanceMode.MANUAL_ONLY, null),
                itemCount = 1, lastUpdatedAtEpochMillis = null,
            ),
        ),
        beanStatus = ItemStatus.ACTIVE,
    )
}
