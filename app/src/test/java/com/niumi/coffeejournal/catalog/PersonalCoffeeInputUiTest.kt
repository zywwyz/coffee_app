package com.niumi.coffeejournal.catalog

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.niumi.coffeejournal.core.model.*
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
class PersonalCoffeeInputUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `catalog chooses units preserves bad input and prevents silent save`() {
        val brand = Brand("brand", BrandType.ROASTER, "烘焙商", null, MaintenanceMode.MANUAL_ONLY, null)
        val state = mutableStateOf(CatalogUiState(tab = CatalogTab.BEANS, brandOverviews = listOf(BrandOverview(brand, 0)), editorSession = CatalogEditorSession.Item(null, brand, "lease", null)))
        var submitted: ItemEditor? = null
        compose.setContent { CoffeeTheme { CatalogScreen(state.value, {}, {}, {}, {}, { submitted = it }, { _, _ -> }, {}, onUpdateItemDraft = { update ->
            val session = state.value.editorSession as CatalogEditorSession.Item
            state.value = state.value.copy(editorSession = session.copy(draft = update(session.draft)))
        }) } }
        compose.onNodeWithText("购买总价（元，可选）").performScrollTo().performTextReplacement("59")
        compose.onNodeWithText("购买总克重（克，可选）").performScrollTo().performTextReplacement("250.125")
        compose.onNodeWithText("挂耳咖啡").performScrollTo().performClick()
        compose.runOnIdle {
            val draft = (state.value.editorSession as CatalogEditorSession.Item).draft
            assertEquals(PersonalProductType.DRIP_BAG, draft.personalProductType)
            assertEquals("", draft.specificationInput)
            assertNull(draft.packageWeightMg)
            assertEquals("59", draft.purchasePriceInput)
        }
        compose.onNodeWithText("购买总包数（包，可选）").performScrollTo().performTextReplacement("1.5")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertNull(submitted) }
        compose.onNodeWithText("请输入有效购买价格与正数规格，包数须为整数").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("购买总包数（包，可选）").performScrollTo().assertTextContains("1.5").performTextReplacement("10")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals("10", submitted?.specificationInput)
            assertEquals("59", submitted?.purchasePriceInput)
        }
    }
}
