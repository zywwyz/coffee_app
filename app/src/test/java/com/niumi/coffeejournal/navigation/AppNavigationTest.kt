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
import com.niumi.coffeejournal.TestTags
import androidx.compose.ui.graphics.Color
import com.niumi.coffeejournal.ui.theme.Caramel
import com.niumi.coffeejournal.ui.theme.CoffeeTheme
import com.niumi.coffeejournal.ui.theme.Espresso
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
import com.niumi.coffeejournal.core.image.CropRect
import com.niumi.coffeejournal.importer.ImageImportMode
import com.niumi.coffeejournal.importer.ImportedAssetSelection
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
            assertEquals(Color(0xFFF0E8DC), colors.surfaceContainer)
            assertEquals(Espresso, colors.onSurface)
            assertEquals(Caramel, colors.secondaryContainer)
            assertEquals(Espresso, colors.onSecondaryContainer)
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
    fun catalog_screenshot_picker_wires_product_screenshot_mode_directly() {
        var requestKind: ImageKind? = null
        var requestMode: ImageImportMode? = null
        var calls = 0
        val picker = catalogScreenshotAssetPicker { kind, mode, _, callback ->
            calls++
            requestKind = kind
            requestMode = mode
            runBlocking { callback(ImportedAssetSelection("asset")) }
        }

        picker(null, com.niumi.coffeejournal.catalog.CatalogAssetKind.CHAIN_PRODUCT_IMAGE) { true }

        assertEquals(1, calls)
        assertEquals(ImageKind.PRODUCT, requestKind)
        assertEquals(ImageImportMode.SCREENSHOT, requestMode)
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
                    assetImportRequester = { _, _, _, callback ->
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
        var requestedMode: ImageImportMode? = null
        val repository = MutableChainCatalogRepository()
        val imageStore = RecordingImageStore()
        compose.setContent {
            CoffeeTheme {
                AppNavigation(
                    journalRepository = FakeJournalRepository,
                    catalogRepository = repository,
                    imageStore = imageStore,
                    assetImportRequester = { kind, mode, _, callback ->
                        requestedKind = kind
                        requestedMode = mode
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
        assertEquals(ImageImportMode.WHOLE_IMAGE, requestedMode)
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

        override suspend fun importCropped(source: Uri, crop: CropRect, kind: ImageKind): ImageAsset = error("unused")
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
