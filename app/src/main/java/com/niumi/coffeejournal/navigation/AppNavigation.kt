package com.niumi.coffeejournal.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.catalog.CatalogFeature
import com.niumi.coffeejournal.catalog.CatalogAssetKind
import com.niumi.coffeejournal.catalog.CatalogAssetPicker
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import com.niumi.coffeejournal.importer.AssetImportRequester
import com.niumi.coffeejournal.importer.ImageImportHost
import com.niumi.coffeejournal.importer.ImageImportMode
import com.niumi.coffeejournal.importer.ScreenshotTextRecognizer
import com.niumi.coffeejournal.importer.CatalogSourceProvider
import com.niumi.coffeejournal.importer.CatalogUpdateGateway
import com.niumi.coffeejournal.journal.JournalFeature
import com.niumi.coffeejournal.journal.JournalRepository
import com.niumi.coffeejournal.insights.InsightsFeature
import com.niumi.coffeejournal.backup.BackupManager
import com.niumi.coffeejournal.settings.SettingsScreen
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

@Serializable
data object Journal : NavKey

@Serializable
data object Catalog : NavKey

@Serializable
data object Insights : NavKey

@Serializable
data object Settings : NavKey

private data class RootDestination(
    val key: NavKey,
    val label: String,
    val iconLabel: String,
)

private val RootDestinations = listOf(
    RootDestination(Journal, "日记", "咖啡"),
    RootDestination(Catalog, "豆库", "豆"),
    RootDestination(Insights, "总结", "图"),
)

@Composable
fun AppNavigation(
    journalRepository: JournalRepository? = null,
    catalogRepository: CatalogRepository? = null,
    imagePathResolver: ImagePathResolver = ImagePathResolver { null },
    imageStore: ImageStore? = null,
    screenshotTextRecognizer: ScreenshotTextRecognizer? = null,
    catalogUpdateSources: CatalogSourceProvider? = null,
    catalogUpdateGateway: CatalogUpdateGateway? = null,
    backupManager: BackupManager? = null,
) {
    if (imageStore != null && screenshotTextRecognizer != null) {
        ImageImportHost(imageStore, screenshotTextRecognizer) { requester ->
            AppNavigationContent(
                journalRepository, catalogRepository, imagePathResolver,
                imageStore = imageStore,
                assetImportRequester = requester,
                catalogUpdateSources = catalogUpdateSources,
                catalogUpdateGateway = catalogUpdateGateway,
                backupManager = backupManager,
            )
        }
    } else {
        AppNavigationContent(
            journalRepository, catalogRepository, imagePathResolver,
            catalogUpdateSources = catalogUpdateSources,
            catalogUpdateGateway = catalogUpdateGateway,
            backupManager = backupManager,
        )
    }
}

@Composable
private fun AppNavigationContent(
    journalRepository: JournalRepository?,
    catalogRepository: CatalogRepository?,
    imagePathResolver: ImagePathResolver,
    imageStore: ImageStore? = null,
    assetImportRequester: AssetImportRequester = { _, _, _, _ -> },
    catalogUpdateSources: CatalogSourceProvider? = null,
    catalogUpdateGateway: CatalogUpdateGateway? = null,
    backupManager: BackupManager? = null,
) {
    val backStack = rememberNavBackStack(Journal)
    val selectedRoot = backStack.last()

    Scaffold(
        topBar = {
            if (backupManager != null) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (selectedRoot == Settings) {
                        TextButton(onClick = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }) { Text("返回") }
                    } else {
                        TextButton(onClick = { backStack.add(Settings) }) { Text("设置") }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                RootDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = selectedRoot == destination.key,
                        onClick = {
                            if (backStack.last() != destination.key) {
                                backStack.clear()
                                backStack.add(destination.key)
                            }
                        },
                        icon = { Text(destination.iconLabel) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.padding(contentPadding),
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryProvider = entryProvider {
                entry<Journal> {
                    if (journalRepository != null && catalogRepository != null) {
                        JournalFeature(journalRepository, catalogRepository, imagePathResolver, assetImportRequester)
                    } else {
                        RootContent("咖啡日历", "记录今天的咖啡")
                    }
                }
                entry<Catalog> {
                    if (catalogRepository != null) {
                        CatalogFeature(
                            repository = catalogRepository,
                            imageStore = imageStore,
                            updateSources = catalogUpdateSources,
                            updateGateway = catalogUpdateGateway,
                            onRequestAsset = { _, kind, callback ->
                                val imageKind = when (kind) {
                                    com.niumi.coffeejournal.catalog.CatalogAssetKind.BRAND_LOGO -> ImageKind.BRAND_LOGO
                                    com.niumi.coffeejournal.catalog.CatalogAssetKind.CHAIN_PRODUCT_IMAGE -> ImageKind.PRODUCT
                                    com.niumi.coffeejournal.catalog.CatalogAssetKind.BEAN_PACKAGE -> ImageKind.BEAN_PACKAGE
                                }
                                val mode = if (imageKind == ImageKind.PRODUCT) ImageImportMode.ASK else ImageImportMode.WHOLE_IMAGE
                                assetImportRequester(imageKind, mode, null) { selection -> callback(selection) }
                            },
                            onRequestScreenshotAsset = catalogScreenshotAssetPicker(assetImportRequester),
                        )
                    }
                    else RootContent("连锁品牌", "管理连锁产品与个人豆库")
                }
                entry<Insights> {
                    if (journalRepository != null) InsightsFeature(journalRepository)
                    else RootContent("月度总结", "查看饮用、评分与消费趋势")
                }
                entry<Settings> {
                    if (backupManager != null) SettingsScreen(backupManager)
                    else RootContent("设置", "备份与恢复")
                }
            },
        )
    }
}

internal fun catalogScreenshotAssetPicker(requester: AssetImportRequester): CatalogAssetPicker =
    { previousAssetId, kind, callback ->
        require(kind == CatalogAssetKind.CHAIN_PRODUCT_IMAGE)
        requester(ImageKind.PRODUCT, ImageImportMode.SCREENSHOT, previousAssetId, callback)
    }

@Composable
private fun RootContent(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(text = subtitle, style = MaterialTheme.typography.bodyLarge)
    }
}
