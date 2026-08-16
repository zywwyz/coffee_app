package com.niumi.coffeejournal.catalog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.runtime.mutableStateOf
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.MaintenanceMode
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import com.niumi.coffeejournal.importer.ImportedAssetSelection
import kotlinx.coroutines.runBlocking
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
    fun `chain catalog shows compact brand grid without legacy metadata`() {
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
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.ChainBrandGrid).assertIsDisplayed()
        compose.onNodeWithTag(com.niumi.coffeejournal.TestTags.ChainBrandCardPrefix + "brand").assertIsDisplayed()
        compose.onNodeWithText("新增品牌").assertIsDisplayed()
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
                    onSetItemStatus = { _, _ -> },
                    onClearError = { screenState.value = screenState.value.copy(errorMessage = null) },
                    onRequestAsset = { _, kind, _ -> assetRequested = kind == CatalogAssetKind.BRAND_LOGO },
                )
            }
        }
        compose.onNodeWithText("新增品牌").performClick()
        compose.onNodeWithText("选择 Logo").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(assetRequested) }
        compose.runOnIdle { screenState.value = screenState.value.copy(saving = true) }
        compose.onNodeWithText("保存中…").assertIsNotEnabled()
    }

    @Test
    fun `new chain brand requires logo before save`() {
        var saves = 0
        compose.setContent { CoffeeTheme { CatalogScreen(state(), {}, {}, {}, { saves++ }, {}, { _, _ -> }, {}) } }
        compose.onNodeWithText("新增品牌").performClick()
        compose.onNodeWithText("品牌名称").performTextInput("自定义")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("请先选择 Logo").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, saves) }
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

    @Test
    fun `failed brand save keeps dialog and entered values`() {
        val screenState = mutableStateOf(state())
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = screenState.value, onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = { screenState.value = screenState.value.copy(errorMessage = "同名") },
                    onSaveItem = {}, onSetItemStatus = { _, _ -> }, onClearError = {},
                )
            }
        }
        compose.onNodeWithText("新增连锁品牌").performClick()
        compose.onNodeWithText("品牌名称").performTextInput("重复品牌")
        compose.onNodeWithText("保存").performClick()

        compose.onNodeWithText("新增品牌").assertIsDisplayed()
        compose.onNodeWithText("品牌名称").assertTextContains("重复品牌")
    }

    @Test
    fun `failed item save retains complete local form and invalid caffeine blocks callback`() {
        val screenState = mutableStateOf(state().copy(selectedBrandId = "brand"))
        var saveCalls = 0
        var submitted: ItemEditor? = null
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = screenState.value, onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = {}, onSaveItem = {
                        saveCalls++
                        submitted = it
                        screenState.value = screenState.value.copy(errorMessage = "外键错误")
                    },
                    onSetItemStatus = { _, _ -> },
                    onClearError = { screenState.value = screenState.value.copy(errorMessage = null) },
                )
            }
        }
        compose.onNodeWithText("新增连锁产品").performClick()
        compose.onNodeWithText("名称").performTextInput("澳白")
        compose.onNodeWithText("默认冲煮方式（可选）").performTextInput("浓缩")
        compose.onNodeWithText("产品分类（可选）").performTextInput("意式")
        compose.onNodeWithText("规格描述（可选）").performTextInput("大杯")
        compose.onNodeWithText("咖啡因 mg（可选）").performTextInput("NaN")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, saveCalls) }
        compose.onNodeWithText("请输入非负的有效咖啡因数值").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("咖啡因 mg（可选）").performScrollTo().assertTextContains("NaN")

        compose.onNodeWithText("咖啡因 mg（可选）").performTextReplacement("50")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, saveCalls) }
        compose.runOnIdle { org.junit.Assert.assertEquals(50.0, submitted?.caffeineMg) }
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("名称").performScrollTo().assertTextContains("澳白")
        compose.onNodeWithText("产品分类（可选）").performScrollTo().assertTextContains("意式")
        compose.onNodeWithText("规格描述（可选）").performScrollTo().assertTextContains("大杯")
    }

    @Test
    fun `confirmed save closes only pending editor while stale token does not`() {
        val screenState = mutableStateOf(state().copy(saveCompletedToken = 7))
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = screenState.value, onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = { screenState.value = screenState.value.copy(saveCompletedToken = 8) },
                    onSaveItem = {}, onSetItemStatus = { _, _ -> }, onClearError = {},
                )
            }
        }
        compose.onNodeWithText("新增连锁品牌").performClick()
        compose.onNodeWithText("新增品牌").assertIsDisplayed()
        compose.onNodeWithText("品牌名称").performTextInput("成功品牌")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(0, compose.onAllNodesWithText("新增品牌").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `confirmed item save closes item editor`() {
        val screenState = mutableStateOf(state().copy(selectedBrandId = "brand"))
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = screenState.value, onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = {},
                    onSaveItem = { screenState.value = screenState.value.copy(saveCompletedToken = 1) },
                    onSetItemStatus = { _, _ -> }, onClearError = {},
                )
            }
        }
        compose.onNodeWithText("新增连锁产品").performClick()
        compose.onNodeWithText("名称").performTextInput("澳白")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(0, compose.onAllNodesWithText("新增产品").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `confirmed product image selection is included in saved catalog item`() {
        var submitted: ItemEditor? = null
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = state().copy(selectedBrandId = "brand"),
                    onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {}, onSaveBrand = {},
                    onSaveItem = { submitted = it }, onSetItemStatus = { _, _ -> }, onClearError = {},
                    onRequestAsset = { _, kind, callback ->
                        org.junit.Assert.assertEquals(CatalogAssetKind.CHAIN_PRODUCT_IMAGE, kind)
                        runBlocking {
                            org.junit.Assert.assertTrue(
                                callback(ImportedAssetSelection("real-product-image", "截图候选名", 990)),
                            )
                        }
                    },
                )
            }
        }
        compose.onNodeWithText("新增连锁产品").performClick()
        compose.onNodeWithText("选择图片").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()

        compose.runOnIdle {
            org.junit.Assert.assertEquals("real-product-image", submitted?.imageAssetId)
            org.junit.Assert.assertEquals("截图候选名", submitted?.name)
        }
    }

    @Test
    fun `disposing an editor releases its staged image lease`() {
        val showCatalog = mutableStateOf(true)
        var stagedLeaseId: String? = null
        var discardedLeaseId: String? = null
        compose.setContent {
            CoffeeTheme {
                if (showCatalog.value) {
                    CatalogScreen(
                        state = state().copy(selectedBrandId = "brand"),
                        onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                        onSaveBrand = {}, onSaveItem = {}, onSetItemStatus = { _, _ -> },
                        onClearError = {},
                        onStageAsset = { leaseId, _, _ ->
                            stagedLeaseId = leaseId
                            true
                        },
                        onDiscardAssetLease = { discardedLeaseId = it },
                        onRequestAsset = { _, _, callback ->
                            runBlocking {
                                org.junit.Assert.assertTrue(
                                    callback(ImportedAssetSelection("staged-image", null, null)),
                                )
                            }
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("新增连锁产品").performClick()
        compose.onNodeWithText("选择图片").performScrollTo().performClick()
        compose.runOnIdle {
            org.junit.Assert.assertNotNull(stagedLeaseId)
            showCatalog.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { org.junit.Assert.assertEquals(stagedLeaseId, discardedLeaseId) }
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
