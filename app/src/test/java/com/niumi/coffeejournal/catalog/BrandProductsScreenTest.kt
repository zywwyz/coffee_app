package com.niumi.coffeejournal.catalog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.Role
import com.niumi.coffeejournal.TestTags
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ChainProductKind
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import com.niumi.coffeejournal.ui.CoffeeVisuals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.io.File

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
        compose.onNodeWithTag(TestTags.BrandProductMediaFramePrefix + "item", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(CatalogMediaFrameColor, CoffeeVisuals.white))
            .assert(SemanticsMatcher.expectValue(CatalogMediaFrameOutlineColor, androidx.compose.ui.graphics.Color.Transparent))
        compose.onNodeWithText("冷萃").assertIsDisplayed()
        compose.onAllNodesWithText("黑咖").assertCountEquals(2)
    }

    @Test fun `custom child header exposes edit but bundled header does not`() {
        var edits = 0
        compose.setContent { CoffeeTheme { BrandProductsScreen(Brand("custom", BrandType.CHAIN, "自定义", "logo", MaintenanceMode.MANUAL_ONLY, null), emptyList(), { null }, {}, { edits++ }, {}, {}) } }
        compose.onNodeWithText("编辑品牌").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, edits) }
    }

    @Test fun `missing built in product image falls back to its bundled logo instead of placeholder`() {
        compose.setContent { CoffeeTheme {
            BrandProductsScreen(
                brand = BUNDLED_CHAIN_BRANDS.first().brand,
                items = listOf(CatalogItem("missing", BUNDLED_CHAIN_BRANDS.first().brand.id, ItemType.CHAIN_PRODUCT, "缺失图", null, null, null, null, null, "missing-product", ItemStatus.ACTIVE, chainProductKind = ChainProductKind.BLACK)),
                imagePathResolver = { null }, onBack = {}, onEditBrand = {}, onAddProduct = {}, onEditProduct = {},
            )
        } }

        compose.onNodeWithContentDescription("缺失图 图片").assertIsDisplayed()
        compose.onAllNodesWithText("☕").assertCountEquals(0)
    }

    @Test fun `corrupt built in product image falls back to its bundled logo instead of placeholder`() {
        val corruptFile = File(RuntimeEnvironment.getApplication().cacheDir, "corrupt-product-image.jpg").apply { writeText("not an image") }
        compose.setContent { CoffeeTheme {
            BrandProductsScreen(
                brand = BUNDLED_CHAIN_BRANDS.first().brand,
                items = listOf(CatalogItem("corrupt", BUNDLED_CHAIN_BRANDS.first().brand.id, ItemType.CHAIN_PRODUCT, "损坏图", null, null, null, null, null, "corrupt-product", ItemStatus.ACTIVE, chainProductKind = ChainProductKind.BLACK)),
                imagePathResolver = { corruptFile.absolutePath }, onBack = {}, onEditBrand = {}, onAddProduct = {}, onEditProduct = {},
            )
        } }

        compose.onNodeWithContentDescription("损坏图 图片").assertIsDisplayed()
        compose.onAllNodesWithText("☕").assertCountEquals(0)
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

    @Test fun `pending filter remains selectable on narrow product page`() {
        var edited: CatalogItem? = null
        val pending = CatalogItem("pending", "brand", ItemType.CHAIN_PRODUCT, "待分类产品", null, null, null, null, null, null, ItemStatus.ACTIVE, chainProductKind = ChainProductKind.PENDING)
        val black = CatalogItem("black", "brand", ItemType.CHAIN_PRODUCT, "黑咖产品", null, null, null, null, null, null, ItemStatus.ACTIVE, chainProductKind = ChainProductKind.BLACK)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) { CoffeeTheme { BrandProductsScreen(Brand("brand", BrandType.CHAIN, "品牌", null, MaintenanceMode.MANUAL_ONLY, null), listOf(black, pending), { null }, {}, {}, {}, { edited = it }) } } }
        compose.onNode(hasText("待分类") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, Role.Checkbox)).performScrollTo().performClick()
        compose.onNodeWithTag(TestTags.BrandProductCardPrefix + "black").assertDoesNotExist()
        compose.onNodeWithTag(TestTags.BrandProductCardPrefix + "pending").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(pending, edited) }
    }

    @Test fun `long brand title keeps the back action available`() {
        var backs = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) { CoffeeTheme { BrandProductsScreen(Brand("brand", BrandType.CHAIN, "很长很长很长很长很长很长的品牌名称", null, MaintenanceMode.MANUAL_ONLY, null), emptyList(), { null }, { backs++ }, {}, {}, {}) } } }
        compose.onNodeWithText("返回").assertIsDisplayed().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, backs) }
    }

}
