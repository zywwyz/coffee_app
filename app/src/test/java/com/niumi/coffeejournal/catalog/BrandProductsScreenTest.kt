package com.niumi.coffeejournal.catalog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import com.niumi.coffeejournal.TestTags
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ChainProductKind
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
class BrandProductsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `product page presents labeled two column product grid`() {
        compose.setContent { CoffeeTheme {
            BrandProductsScreen(
                brand = Brand("brand", BrandType.CHAIN, "品牌", null, MaintenanceMode.MANUAL_ONLY, null),
                items = listOf(CatalogItem("item", "brand", ItemType.CHAIN_PRODUCT, "冷萃", null, null, null, null, null, null, ItemStatus.ACTIVE, chainProductKind = ChainProductKind.BLACK)),
                imagePathResolver = { null }, onBack = {}, onEditBrand = {}, onAddProduct = {}, onEditProduct = {},
            )
        } }
        compose.onNodeWithTag(TestTags.BrandProductGrid).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.BrandProductCardPrefix + "item").assertIsDisplayed()
        compose.onNodeWithText("冷萃").assertIsDisplayed()
        compose.onAllNodesWithText("黑咖").assertCountEquals(2)
    }

    @Test fun `custom child header exposes edit but bundled header does not`() {
        var edits = 0
        compose.setContent { CoffeeTheme { BrandProductsScreen(Brand("custom", BrandType.CHAIN, "自定义", "logo", MaintenanceMode.MANUAL_ONLY, null), emptyList(), { null }, {}, { edits++ }, {}, {}) } }
        compose.onNodeWithText("编辑品牌").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, edits) }
    }

    @Test fun `custom child header exposes a cancellable brand delete action`() {
        compose.setContent { CoffeeTheme {
            BrandProductsScreen(
                Brand("custom", BrandType.CHAIN, "自定义", "logo", MaintenanceMode.MANUAL_ONLY, null),
                emptyList(), { null }, {}, {}, {}, {},
            )
        } }

        compose.onNodeWithText("删除品牌").assertIsDisplayed().performClick()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("确认删除").assertIsDisplayed()
    }

}
