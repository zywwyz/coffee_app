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
import com.niumi.coffeejournal.core.image.ImportedAssetSelection
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
    fun `editor session asset is rendered after a new composition`() {
        val session = CatalogEditorSession.Brand(null, BrandType.ROASTER, "stable-lease", "persisted-logo")
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = beanState().copy(editorSession = session), onSelectTab = {}, onSelectBrand = {},
                    onSelectBeanStatus = {}, onSaveBrand = {}, onSaveItem = {},
                    onSetItemStatus = { _, _ -> }, onClearError = {},
                )
            }
        }

        compose.onNodeWithText("更换 Logo").assertIsDisplayed()
    }

    @Test
    fun `raw caffeine input is retained and validated before saving`() {
        var saved: ItemEditor? = null
        val chain = Brand("chain", BrandType.CHAIN, "连锁", null, MaintenanceMode.MANUAL_ONLY, null)
        val screenState = mutableStateOf(state().copy(editorSession = CatalogEditorSession.Item(null, chain, "lease", null)))
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = screenState.value, onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = {}, onSaveItem = { saved = it }, onSetItemStatus = { _, _ -> }, onClearError = {},
                    onUpdateItemCaffeineInput = { updateCaffeineInput(screenState, it) },
                )
            }
        }

        compose.onNodeWithText("咖啡因 mg（可选）").performTextInput("1")
        compose.onNodeWithText("咖啡因 mg（可选）").assertTextContains("1")
        compose.onNodeWithText("咖啡因 mg（可选）").performTextReplacement("10")
        compose.onNodeWithText("咖啡因 mg（可选）").assertTextContains("10")
        compose.onNodeWithText("咖啡因 mg（可选）").performTextReplacement("abc")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("请输入非负的有效咖啡因数值").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals(null, saved) }
        compose.onNodeWithText("咖啡因 mg（可选）").assertTextContains("abc")
        compose.onNodeWithText("咖啡因 mg（可选）").performTextReplacement("10")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(10.0, saved?.caffeineMg) }
    }

    @Test
    fun `brand editor exposes replaceable asset boundary and disables save while saving`() {
        var assetRequested = false
        val screenState = mutableStateOf(beanState())
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = screenState.value, onSelectTab = {}, onSelectBrand = {},
                    onSelectBeanStatus = {}, onSaveBrand = {}, onSaveItem = {},
                    onSetItemStatus = { _, _ -> },
                    onClearError = { screenState.value = screenState.value.copy(errorMessage = null) },
                    onRequestAsset = { _, kind, _ -> assetRequested = kind == CatalogAssetKind.BRAND_LOGO },
                    onOpenBrandEditor = { initial, type -> openBrandSession(screenState, initial, type) },
                    onUpdateBrandDraft = { update -> updateBrandDraft(screenState, update) },
                )
            }
        }
        compose.onNodeWithText("新增烘焙品牌").performClick()
        compose.onNodeWithText("选择 Logo").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(assetRequested) }
        compose.runOnIdle { screenState.value = screenState.value.copy(saving = true) }
        compose.onNodeWithText("保存中…").assertIsNotEnabled()
    }

    @Test
    fun `new chain brand requires logo before save`() {
        var saves = 0
        val screenState = mutableStateOf(state())
        compose.setContent { CoffeeTheme { CatalogScreen(screenState.value, {}, {}, {}, { saves++ }, {}, { _, _ -> }, {}, onOpenBrandEditor = { initial, type -> openBrandSession(screenState, initial, type) }, onUpdateBrandDraft = { update -> updateBrandDraft(screenState, update) }) } }
        compose.onNodeWithText("新增品牌").performClick()
        compose.onNodeWithText("品牌名称").performTextInput("自定义")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("请先选择 Logo").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, saves) }
    }

    @Test
    fun `personal bean editor retains its fields`() {
        val screenState = mutableStateOf(beanState().copy(selectedBrandId = "brand"))
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = screenState.value,
                    onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = {}, onSaveItem = {}, onSetItemStatus = { _, _ -> }, onClearError = {},
                    onOpenItemEditor = { initial, brand -> openItemSession(screenState, initial, brand) },
                    onUpdateItemDraft = { update -> updateItemDraft(screenState, update) },
                )
            }
        }

        compose.onNodeWithText("新增豆子").performScrollTo().performClick()
        compose.onNodeWithText("产地（可选）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("默认冲煮方式（可选）").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `failed brand save keeps dialog and entered values`() {
        val screenState = mutableStateOf(beanState())
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = screenState.value, onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = { screenState.value = screenState.value.copy(errorMessage = "同名") },
                    onSaveItem = {}, onSetItemStatus = { _, _ -> }, onClearError = {},
                    onOpenBrandEditor = { initial, type -> openBrandSession(screenState, initial, type) },
                    onUpdateBrandDraft = { update -> updateBrandDraft(screenState, update) },
                )
            }
        }
        compose.onNodeWithText("新增烘焙品牌").performClick()
        compose.onNodeWithText("品牌名称").performTextInput("重复品牌")
        compose.onNodeWithText("保存").performClick()

        compose.onNodeWithText("新增品牌").assertIsDisplayed()
        compose.onNodeWithText("品牌名称").assertTextContains("重复品牌")
    }

    @Test
    fun `failed personal bean save retains entered form`() {
        val screenState = mutableStateOf(beanState().copy(selectedBrandId = "brand"))
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
                    onOpenItemEditor = { initial, brand -> openItemSession(screenState, initial, brand) },
                    onUpdateItemDraft = { update -> updateItemDraft(screenState, update) },
                )
            }
        }
        compose.onNodeWithText("新增豆子").performScrollTo().performClick()
        compose.onNodeWithText("名称").performTextInput("澳白")
        compose.onNodeWithText("产地（可选）").performTextInput("云南")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, saveCalls) }
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("名称").performScrollTo().assertTextContains("澳白")
        compose.onNodeWithText("产地（可选）").performScrollTo().assertTextContains("云南")
    }

    @Test
    fun `confirmed save closes only pending editor while stale token does not`() {
        val screenState = mutableStateOf(beanState().copy(saveCompletedToken = 7))
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = screenState.value, onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = { screenState.value = screenState.value.copy(saveCompletedToken = 8, editorSession = null) },
                    onSaveItem = {}, onSetItemStatus = { _, _ -> }, onClearError = {},
                    onOpenBrandEditor = { initial, type -> openBrandSession(screenState, initial, type) },
                    onUpdateBrandDraft = { update -> updateBrandDraft(screenState, update) },
                )
            }
        }
        compose.onNodeWithText("新增烘焙品牌").performClick()
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
        val screenState = mutableStateOf(beanState().copy(selectedBrandId = "brand"))
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = screenState.value, onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                    onSaveBrand = {},
                    onSaveItem = { screenState.value = screenState.value.copy(saveCompletedToken = 1, editorSession = null) },
                    onSetItemStatus = { _, _ -> }, onClearError = {},
                    onOpenItemEditor = { initial, brand -> openItemSession(screenState, initial, brand) },
                    onUpdateItemDraft = { update -> updateItemDraft(screenState, update) },
                )
            }
        }
        compose.onNodeWithText("新增豆子").performScrollTo().performClick()
        compose.onNodeWithText("名称").performTextInput("澳白")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(0, compose.onAllNodesWithText("新增产品").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `personal bean image selection is included in saved catalog item`() {
        var submitted: ItemEditor? = null
        val screenState = mutableStateOf(beanState().copy(selectedBrandId = "brand"))
        compose.setContent {
            CoffeeTheme {
                CatalogScreen(
                    state = screenState.value,
                    onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {}, onSaveBrand = {},
                    onSaveItem = { submitted = it }, onSetItemStatus = { _, _ -> }, onClearError = {},
                    onRequestAsset = { _, kind, callback ->
                        org.junit.Assert.assertEquals(CatalogAssetKind.BEAN_PACKAGE, kind)
                        runBlocking {
                            org.junit.Assert.assertTrue(
                                callback(ImportedAssetSelection("real-product-image")),
                            )
                        }
                    },
                    onOpenItemEditor = { initial, brand -> openItemSession(screenState, initial, brand) },
                    onStageAsset = { leaseId, _, assetId -> stageSessionAsset(screenState, leaseId, assetId) },
                )
            }
        }
        compose.onNodeWithText("新增豆子").performScrollTo().performClick()
        compose.onNodeWithText("选择图片").performScrollTo().performClick()
        compose.onNodeWithText("保存").performClick()

        compose.runOnIdle { org.junit.Assert.assertEquals("real-product-image", submitted?.imageAssetId) }
    }

    @Test
    fun `disposing an editor does not release its staged image lease`() {
        val showCatalog = mutableStateOf(true)
        var stagedLeaseId: String? = null
        compose.setContent {
            CoffeeTheme {
                if (showCatalog.value) {
                    CatalogScreen(
                        state = beanState().copy(
                            selectedBrandId = "brand",
                            editorSession = CatalogEditorSession.Item(null, Brand("brand", BrandType.ROASTER, "烘焙商", null, MaintenanceMode.MANUAL_ONLY, null), "stable-lease", null),
                        ),
                        onSelectTab = {}, onSelectBrand = {}, onSelectBeanStatus = {},
                        onSaveBrand = {}, onSaveItem = {}, onSetItemStatus = { _, _ -> },
                        onClearError = {},
                        onStageAsset = { leaseId, _, _ ->
                            stagedLeaseId = leaseId
                            true
                        },
                        onRequestAsset = { _, _, callback ->
                            runBlocking {
                                org.junit.Assert.assertTrue(
                                    callback(ImportedAssetSelection("staged-image")),
                                )
                            }
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("选择图片").performScrollTo().performClick()
        compose.runOnIdle {
            org.junit.Assert.assertNotNull(stagedLeaseId)
            showCatalog.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { org.junit.Assert.assertNotNull(stagedLeaseId) }
    }

    private fun state() = CatalogUiState(
        brandOverviews = listOf(
            BrandOverview(
                Brand("brand", BrandType.CHAIN, "瑞幸", null, MaintenanceMode.MANUAL_ONLY, null),
                itemCount = 1,
            ),
        ),
        beanStatus = ItemStatus.ACTIVE,
    )

    private fun beanState() = state().copy(
        tab = CatalogTab.BEANS,
        brandOverviews = listOf(BrandOverview(Brand("brand", BrandType.ROASTER, "烘焙商", null, MaintenanceMode.MANUAL_ONLY, null), 0)),
    )

    private fun openBrandSession(
        state: androidx.compose.runtime.MutableState<CatalogUiState>,
        initial: Brand?,
        type: BrandType,
    ) {
        state.value = state.value.copy(
            editorSession = CatalogEditorSession.Brand(initial, type, "brand-lease", initial?.logoAssetId),
        )
    }

    private fun openItemSession(
        state: androidx.compose.runtime.MutableState<CatalogUiState>,
        initial: com.niumi.coffeejournal.core.model.CatalogItem?,
        brand: Brand,
    ) {
        state.value = state.value.copy(
            editorSession = CatalogEditorSession.Item(initial, brand, "item-lease", initial?.imageAssetId),
        )
    }

    private fun stageSessionAsset(
        state: androidx.compose.runtime.MutableState<CatalogUiState>,
        leaseId: String,
        assetId: String,
    ): Boolean {
        val session = state.value.editorSession ?: return false
        if (session.leaseId != leaseId) return false
        state.value = state.value.copy(editorSession = when (session) {
            is CatalogEditorSession.Brand -> session.copy(assetId = assetId, draft = session.draft.copy(logoAssetId = assetId))
            is CatalogEditorSession.Item -> session.copy(assetId = assetId, draft = session.draft.copy(imageAssetId = assetId))
        })
        return true
    }

    private fun updateBrandDraft(state: androidx.compose.runtime.MutableState<CatalogUiState>, update: (BrandEditor) -> BrandEditor) {
        val session = state.value.editorSession as? CatalogEditorSession.Brand ?: return
        state.value = state.value.copy(editorSession = session.copy(draft = update(session.draft)))
    }

    private fun updateItemDraft(state: androidx.compose.runtime.MutableState<CatalogUiState>, update: (ItemEditor) -> ItemEditor) {
        val session = state.value.editorSession as? CatalogEditorSession.Item ?: return
        state.value = state.value.copy(editorSession = session.copy(draft = update(session.draft)))
    }

    private fun updateCaffeineInput(state: androidx.compose.runtime.MutableState<CatalogUiState>, input: String) {
        val session = state.value.editorSession as? CatalogEditorSession.Item ?: return
        state.value = state.value.copy(editorSession = session.copy(caffeineInput = input))
    }
}
