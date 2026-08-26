package com.niumi.coffeejournal.navigation

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import com.niumi.coffeejournal.TestTags
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.niumi.coffeejournal.ui.theme.Caramel
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import com.niumi.coffeejournal.ui.theme.Espresso
import com.niumi.coffeejournal.ui.CoffeeVisuals
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.DrinkDraft
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageAsset
import com.niumi.coffeejournal.core.image.ImageStore
import com.niumi.coffeejournal.core.image.ImportedAssetSelection
import kotlinx.coroutines.runBlocking
import com.niumi.coffeejournal.journal.JournalRepository
import com.niumi.coffeejournal.backup.BackupManager
import com.niumi.coffeejournal.backup.ValidatedBackup
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theme_supplies_warm_navigation_bar_tokens() {
        var captured: ColorScheme? = null
        compose.setContent {
            CoffeeTheme {
                captured = MaterialTheme.colorScheme
            }
        }

        compose.runOnIdle {
            val colors = requireNotNull(captured)
            assertEquals(CoffeeVisuals.cream, colors.surfaceContainer)
            assertEquals(Espresso, colors.onSurface)
            assertEquals(Caramel, colors.secondaryContainer)
            assertEquals(Espresso, colors.onSecondaryContainer)
        }
    }

    @Test
    fun bottom_navigation_applies_injected_navigation_inset_without_shrinking_tab_targets() {
        val insetPx = with(compose.density) { 32.dp.roundToPx() }
        compose.setContent {
            CoffeeTheme {
                Column {
                    CoffeeBottomNavigation(
                        selectedRoot = Journal, onRootSelected = {}, navigationInsets = WindowInsets(),
                        modifier = Modifier.testTag("bottom-navigation-no-inset"),
                    )
                    CoffeeBottomNavigation(
                        selectedRoot = Journal, onRootSelected = {}, navigationInsets = WindowInsets(bottom = insetPx),
                        modifier = Modifier.testTag("bottom-navigation-with-inset"),
                    )
                }
            }
        }

        val containers = compose.onAllNodesWithTag("bottom-navigation-no-inset").fetchSemanticsNodes()
        val noInset = containers.single().boundsInRoot
        val withInset = compose.onAllNodesWithTag("bottom-navigation-with-inset").fetchSemanticsNodes().single().boundsInRoot
        val calendar = compose.onAllNodesWithTag(TestTags.BottomCalendarTab).fetchSemanticsNodes()[0].boundsInRoot
        val catalog = compose.onAllNodesWithTag(TestTags.BottomCatalogTab).fetchSemanticsNodes()[0].boundsInRoot
        val minTarget = with(compose.density) { 48.dp.toPx() }
        assertEquals(calendar.width, catalog.width, 1f)
        assertEquals(insetPx.toFloat(), withInset.height - noInset.height, 1f)
        org.junit.Assert.assertTrue(calendar.height >= minTarget)
    }

    @Test
    fun bottom_navigation_keeps_all_labels_visible_at_360dp_and_large_font_scale() {
        compose.setContent {
            CoffeeTheme {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                    Box(Modifier.width(360.dp)) {
                        CoffeeBottomNavigation(selectedRoot = Journal, onRootSelected = {})
                    }
                }
            }
        }

        listOf("咖啡日历", "豆库", "总结").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        val minTarget = with(compose.density) { 48.dp.toPx() }
        listOf(TestTags.BottomCalendarTab, TestTags.BottomCatalogTab, TestTags.BottomInsightsTab).forEach {
            org.junit.Assert.assertTrue(compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.height >= minTarget)
        }
    }

    @Test
    fun repositories_replace_journal_placeholder_with_calendar_feature() {
        compose.setContent {
            CoffeeTheme { AppNavigation(FakeJournalRepository, FakeCatalogRepository) }
        }

        compose.onNodeWithText("记录一杯").assertIsDisplayed()
        compose.onNodeWithText("总结").performClick()
        compose.onNodeWithText("咖啡回顾").assertIsDisplayed()
        compose.onNodeWithText("2026年", substring = true).assertExists()
    }

    @Test
    fun chainBrandOpensChildPageAndHidesRootNavigation() {
        compose.setContent { CoffeeTheme { AppNavigation(FakeJournalRepository, ChainCatalogRepository) } }
        compose.onNodeWithText("豆库").performClick()
        compose.onNodeWithTag(TestTags.ChainBrandCardPrefix + "custom").performClick()
        compose.onNodeWithText("自定义连锁").assertIsDisplayed()
        compose.onAllNodesWithTag(TestTags.BottomCatalogTab).assertCountEquals(0)

        compose.activity.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }

        compose.onNodeWithTag(TestTags.ChainBrandCardPrefix + "custom").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.BottomCatalogTab).assertIsDisplayed()
    }

    @Test
    fun failedChildBrandSaveRetainsDialogTextUntilSuccessfulRetry() {
        val repository = MutableChainCatalogRepository(failSaves = true)
        val imageStore = RecordingImageStore()
        compose.setContent {
            CoffeeTheme {
                AppNavigation(
                    journalRepository = FakeJournalRepository,
                    catalogRepository = repository,
                    imageStore = imageStore,
                    assetImportRequester = { _, _, callback ->
                        runBlocking { callback(ImportedAssetSelection("replacement-logo")) }
                    },
                )
            }
        }
        compose.onNodeWithText("豆库").performClick()
        compose.onNodeWithTag(TestTags.ChainBrandCardPrefix + "custom").performClick()
        compose.onNodeWithText("编辑品牌").performClick()
        compose.onNodeWithText("更换 Logo").performClick()
        compose.onNodeWithText("品牌名称").performTextClearance()
        compose.onNodeWithText("品牌名称").performTextInput("重试品牌")
        compose.onNodeWithText("保存").performClick()

        compose.onNodeWithText("无法保存").assertIsDisplayed()
        compose.onNodeWithText("重试品牌").assertIsDisplayed()
        assertEquals(false, imageStore.deletedAssetIds.contains("replacement-logo"))
        compose.onNodeWithText("知道了").performClick()
        repository.failSaves = false
        compose.onNodeWithText("保存").performClick()

        compose.onAllNodesWithText("品牌名称").assertCountEquals(0)
        assertEquals("重试品牌", repository.savedBrand?.name)
        assertEquals("replacement-logo", repository.savedBrand?.logoAssetId)
        assertEquals(false, imageStore.deletedAssetIds.contains("replacement-logo"))
    }

    @Test
    fun childBrandEditorRequestsWholeImageLogoThroughNavigationRequester() {
        var requestedKind: ImageKind? = null
        val repository = MutableChainCatalogRepository()
        val imageStore = RecordingImageStore()
        compose.setContent {
            CoffeeTheme {
                AppNavigation(
                    journalRepository = FakeJournalRepository,
                    catalogRepository = repository,
                    imageStore = imageStore,
                    assetImportRequester = { kind, _, callback ->
                        requestedKind = kind
                        runBlocking { callback(ImportedAssetSelection("replacement-logo")) }
                    },
                )
            }
        }
        compose.onNodeWithText("豆库").performClick()
        compose.onNodeWithTag(TestTags.ChainBrandCardPrefix + "custom").performClick()
        compose.onNodeWithText("编辑品牌").performClick()
        compose.onNodeWithText("更换 Logo").performClick()

        assertEquals(ImageKind.BRAND_LOGO, requestedKind)
        compose.onNodeWithText("保存").performClick()
        assertEquals("replacement-logo", repository.savedBrand?.logoAssetId)
        assertEquals(false, imageStore.deletedAssetIds.contains("replacement-logo"))
    }

    private object FakeJournalRepository : JournalRepository {
        override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> = flowOf(emptyList())
        override suspend fun newDraft(type: ItemType, itemId: String): DrinkDraft = error("unused")
        override suspend fun save(draft: DrinkDraft): String = error("unused")
        override suspend fun saveDraft(draft: DrinkDraft) = true
        override suspend fun delete(recordId: String) = Unit
    }

    private object FakeCatalogRepository : CatalogRepository {
        override fun observeBrands(type: BrandType): Flow<List<Brand>> = flowOf(emptyList())
        override fun observeItems(brandId: String): Flow<List<CatalogItem>> = flowOf(emptyList())
        override suspend fun getBrand(brandId: String): Brand = error("unused")
        override suspend fun getItem(itemId: String): CatalogItem = error("unused")
        override suspend fun upsertBrand(brand: Brand) = Unit
        override suspend fun upsertItem(item: CatalogItem) = Unit
        override suspend fun lastPriceFen(itemId: String): Long? = null
    }

    private object ChainCatalogRepository : CatalogRepository {
        private val brand = Brand("custom", BrandType.CHAIN, "自定义连锁", "logo", com.niumi.coffeejournal.core.model.MaintenanceMode.MANUAL_ONLY, null)
        override fun observeBrands(type: BrandType): Flow<List<Brand>> = flowOf(if (type == BrandType.CHAIN) listOf(brand) else emptyList())
        override fun observeItems(brandId: String): Flow<List<CatalogItem>> = flowOf(emptyList())
        override suspend fun getBrand(brandId: String): Brand = brand
        override suspend fun getItem(itemId: String): CatalogItem = error("unused")
        override suspend fun upsertBrand(brand: Brand) = Unit
        override suspend fun upsertItem(item: CatalogItem) = Unit
        override suspend fun lastPriceFen(itemId: String): Long? = null
    }

    private class MutableChainCatalogRepository(
        var failSaves: Boolean = false,
    ) : CatalogRepository {
        private val brand = Brand("custom", BrandType.CHAIN, "自定义连锁", "logo", com.niumi.coffeejournal.core.model.MaintenanceMode.MANUAL_ONLY, null)
        var savedBrand: Brand? = null

        override fun observeBrands(type: BrandType): Flow<List<Brand>> = flowOf(if (type == BrandType.CHAIN) listOf(brand) else emptyList())
        override fun observeItems(brandId: String): Flow<List<CatalogItem>> = flowOf(emptyList())
        override suspend fun getBrand(brandId: String): Brand = brand
        override suspend fun getItem(itemId: String): CatalogItem = error("unused")
        override suspend fun upsertBrand(brand: Brand) {
            if (failSaves) error("save failed")
            savedBrand = brand
        }
        override suspend fun upsertItem(item: CatalogItem) = Unit
        override suspend fun lastPriceFen(itemId: String): Long? = null
    }

    private class RecordingImageStore : ImageStore {
        val deletedAssetIds = mutableListOf<String>()

        override suspend fun importWhole(source: Uri, kind: ImageKind): ImageAsset = error("unused")
        override suspend fun deleteIfUnreferenced(assetId: String): Boolean {
            deletedAssetIds += assetId
            return true
        }
    }

    private object FakeBackupManager : BackupManager {
        override suspend fun export(target: Uri) = error("unused")
        override suspend fun validate(source: Uri) = error("unused")
        override suspend fun restore(backup: ValidatedBackup) = error("unused")
        override suspend fun discard(backup: ValidatedBackup) = Unit
    }
}
