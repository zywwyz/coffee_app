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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.niumi.coffeejournal.TestTags
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.catalog.CatalogFeature
import com.niumi.coffeejournal.catalog.CatalogAssetPicker
import com.niumi.coffeejournal.catalog.BrandProductsScreen
import com.niumi.coffeejournal.catalog.ManualProductEditorDialog
import com.niumi.coffeejournal.catalog.ManualProductEditorViewModel
import com.niumi.coffeejournal.catalog.CatalogViewModel
import com.niumi.coffeejournal.catalog.BrandEditorDialog
import com.niumi.coffeejournal.catalog.CatalogAssetKind
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.niumi.coffeejournal.journal.CalendarDisplayPreference
import com.niumi.coffeejournal.journal.DefaultCalendarDisplayPreference
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

@Serializable
data class ChainBrandProducts(val brandId: String) : NavKey

private data class RootDestination(
    val key: NavKey,
    val label: String,
    val iconLabel: String,
)

private val RootDestinations = listOf(
    RootDestination(Journal, "咖啡日历", "咖啡"),
    RootDestination(Catalog, "豆库", "豆"),
    RootDestination(Insights, "总结", "图"),
)

@Composable
fun AppNavigation(
    journalRepository: JournalRepository? = null,
    catalogRepository: CatalogRepository? = null,
    calendarDisplayPreference: CalendarDisplayPreference = DefaultCalendarDisplayPreference,
    imagePathResolver: ImagePathResolver = ImagePathResolver { null },
    imageStore: ImageStore? = null,
    screenshotTextRecognizer: ScreenshotTextRecognizer? = null,
    catalogUpdateSources: CatalogSourceProvider? = null,
    catalogUpdateGateway: CatalogUpdateGateway? = null,
    backupManager: BackupManager? = null,
    assetImportRequester: AssetImportRequester? = null,
) {
    if (assetImportRequester != null) {
        AppNavigationContent(
            journalRepository, catalogRepository, imagePathResolver,
            calendarDisplayPreference = calendarDisplayPreference,
            imageStore = imageStore,
            assetImportRequester = assetImportRequester,
            catalogUpdateSources = catalogUpdateSources,
            catalogUpdateGateway = catalogUpdateGateway,
            backupManager = backupManager,
        )
    } else if (imageStore != null && screenshotTextRecognizer != null) {
        ImageImportHost(imageStore, screenshotTextRecognizer) { requester ->
            AppNavigationContent(
                journalRepository, catalogRepository, imagePathResolver,
                calendarDisplayPreference = calendarDisplayPreference,
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
            calendarDisplayPreference = calendarDisplayPreference,
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
    calendarDisplayPreference: CalendarDisplayPreference,
    imageStore: ImageStore? = null,
    assetImportRequester: AssetImportRequester = { _, _, _, _ -> },
    catalogUpdateSources: CatalogSourceProvider? = null,
    catalogUpdateGateway: CatalogUpdateGateway? = null,
    backupManager: BackupManager? = null,
) {
    val backStack = rememberNavBackStack(Journal)
    val selectedRoot = backStack.last()
    val showRootNavigation = selectedRoot is Journal || selectedRoot is Catalog || selectedRoot is Insights

    Scaffold(
        bottomBar = {
            if (showRootNavigation)
            NavigationBar {
                RootDestinations.forEach { destination ->
                    NavigationBarItem(
                        modifier = Modifier.testTag(
                            when (destination.key) {
                                Journal -> TestTags.BottomCalendarTab
                                Catalog -> TestTags.BottomCatalogTab
                                else -> TestTags.BottomInsightsTab
                            },
                        ),
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
                        JournalFeature(
                            journalRepository, catalogRepository, imagePathResolver, assetImportRequester,
                            calendarDisplayPreference,
                        ) { backStack.add(Settings) }
                    } else {
                        RootContent("咖啡日历", "记录今天的咖啡") { backStack.add(Settings) }
                    }
                }
                entry<Catalog> {
                    if (catalogRepository != null) {
                        CatalogFeature(
                            repository = catalogRepository,
                            imageStore = imageStore,
                            imagePathResolver = imagePathResolver,
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
                            onOpenSettings = { backStack.add(Settings) },
                            onOpenChainBrand = { backStack.add(ChainBrandProducts(it)) },
                        )
                    }
                    else RootContent("我的咖啡豆库", "管理连锁产品与个人豆库") { backStack.add(Settings) }
                }
                entry<ChainBrandProducts> { destination ->
                    if (catalogRepository != null) {
                        ChainBrandProductsDestination(catalogRepository, imageStore, assetImportRequester, destination.brandId, imagePathResolver) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    }
                }
                entry<Insights> {
                    if (journalRepository != null) InsightsFeature(journalRepository) { backStack.add(Settings) }
                    else RootContent("咖啡回顾", "查看饮用、评分与消费趋势") { backStack.add(Settings) }
                }
                entry<Settings> {
                    if (backupManager != null) SettingsScreen(backupManager, onBack = { backStack.removeAt(backStack.lastIndex) })
                    else RootContent("设置", "备份与恢复") { backStack.removeAt(backStack.lastIndex) }
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
private fun RootContent(title: String, subtitle: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(text = subtitle, style = MaterialTheme.typography.bodyLarge)
        androidx.compose.material3.TextButton(onClick = onAction) { Text(if (title == "设置") "返回" else "设置") }
    }
}

@Composable
private fun ChainBrandProductsDestination(repository: CatalogRepository, imageStore: ImageStore?, assetImportRequester: AssetImportRequester, brandId: String, imagePathResolver: ImagePathResolver, onBack: () -> Unit) {
    val brand by produceState<com.niumi.coffeejournal.core.model.Brand?>(null, brandId) { value = repository.getBrand(brandId) }
    val items by repository.observeItems(brandId).collectAsState(initial = emptyList())
    val editor: ManualProductEditorViewModel = viewModel(factory = ManualProductEditorViewModel.factory(repository, imageStore))
    val catalog: CatalogViewModel = viewModel(factory = CatalogViewModel.factory(repository, imageStore))
    val catalogState by catalog.uiState.collectAsState()
    LaunchedEffect(editor) {
        editor.events.collect { editor.completeSaved() }
    }
    var editingBrand by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.niumi.coffeejournal.core.model.Brand?>(null) }
    var handledSaveToken by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(catalogState.saveCompletedToken) }
    LaunchedEffect(catalogState.saveCompletedToken) {
        if (catalogState.saveCompletedToken > handledSaveToken) editingBrand = null
        handledSaveToken = catalogState.saveCompletedToken
    }
    brand?.let {
        BrandProductsScreen(it, items, imagePathResolver, onBack, onEditBrand = { editingBrand = it }, onAddProduct = { editor.openNew(it) }, onEditProduct = { item -> editor.openEdit(it, item) })
        ManualProductEditorDialog(editor, assetImportRequester, imagePathResolver)
    }
    editingBrand?.let { editable ->
        BrandEditorDialog(
            initial = editable, type = com.niumi.coffeejournal.core.model.BrandType.CHAIN, saving = catalogState.saving,
            onDismiss = { editingBrand = null }, onSave = catalog::saveBrand,
            onRequestAsset = { previous, kind, callback ->
                check(kind == CatalogAssetKind.BRAND_LOGO)
                assetImportRequester(ImageKind.BRAND_LOGO, ImageImportMode.WHOLE_IMAGE, previous, callback)
            },
            onRetainAssetLease = catalog::retainAssetLease, onStageAsset = catalog::stageAsset, onDiscardAssetLease = catalog::discardAssetLease,
        )
    }
    catalogState.errorMessage?.let { message ->
        AlertDialog(onDismissRequest = catalog::clearError, title = { Text("无法保存") }, text = { Text(message) }, confirmButton = { TextButton(onClick = catalog::clearError) { Text("知道了") } })
    }
}
