package com.niumi.coffeejournal.catalog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class CatalogScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun catalog_supports_chain_and_bean_tabs_and_brand_editor() {
        var requestedTab: CatalogTab? = null
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = CatalogUiState(),
                    onSelectTab = { requestedTab = it }, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = {}, onSaveItem = {}, onSetItemStatus = { _, _ -> }, onClearError = {},
                )
            }
        }

        compose.onNodeWithText("连锁品牌").assertIsDisplayed()
        compose.onNodeWithText("我的豆子").performClick()
        compose.runOnIdle { assertEquals(CatalogTab.BEANS, requestedTab) }
        compose.onNodeWithText("新增连锁品牌").performClick()
        compose.onNodeWithText("品牌名称").assertIsDisplayed()
        compose.onNodeWithText("选择 Logo").assertIsDisplayed()
    }
}
