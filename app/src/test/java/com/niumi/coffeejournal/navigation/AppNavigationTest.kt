package com.niumi.coffeejournal.navigation

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    val compose = createComposeRule()

    @Test
    fun bottom_bar_opens_three_roots() {
        compose.setContent { CoffeeTheme { AppNavigation() } }

        compose.onNodeWithText("日记").assertIsDisplayed()
        compose.onNodeWithText("豆库").performClick()
        compose.onNodeWithText("连锁品牌").assertIsDisplayed()
        compose.onNodeWithText("总结").performClick()
        compose.onNodeWithText("月度总结").assertIsDisplayed()
    }

    @Test fun settings_is_reachable_without_adding_a_fourth_bottom_root() {
        compose.setContent { CoffeeTheme { AppNavigation(backupManager = FakeBackupManager) } }

        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("备份与恢复").assertIsDisplayed()
        compose.onNodeWithText("导出完整备份").assertIsDisplayed()
        compose.onNodeWithText("日记").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("咖啡日历").assertIsDisplayed()
    }

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

    private object FakeBackupManager : BackupManager {
        override suspend fun export(target: Uri) = error("unused")
        override suspend fun validate(source: Uri) = error("unused")
        override suspend fun restore(backup: ValidatedBackup) = error("unused")
        override suspend fun discard(backup: ValidatedBackup) = Unit
    }
}
