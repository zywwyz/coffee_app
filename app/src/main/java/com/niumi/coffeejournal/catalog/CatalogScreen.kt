package com.niumi.coffeejournal.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ChainProductKind
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.image.ImportedAssetSelection
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.image.ResolvedLocalAssetImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class CatalogAssetKind { BRAND_LOGO, CHAIN_PRODUCT_IMAGE, BEAN_PACKAGE }

typealias CatalogAssetPicker = (
    String?,
    CatalogAssetKind,
    suspend (ImportedAssetSelection) -> Boolean,
) -> Unit

@Composable
fun CatalogFeature(
    repository: CatalogRepository,
    imageStore: com.niumi.coffeejournal.core.image.ImageStore? = null,
    imagePathResolver: ImagePathResolver = ImagePathResolver { null },
    onRequestAsset: CatalogAssetPicker = { _, _, _ -> },
    onOpenSettings: () -> Unit = {},
    onOpenChainBrand: (String) -> Unit = {},
) {
    val catalogViewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.factory(repository, imageStore))
    val state by catalogViewModel.uiState.collectAsStateWithLifecycle()
    CatalogScreen(
        state = state,
        onSelectTab = catalogViewModel::selectTab,
        onSelectBrand = catalogViewModel::selectBrand,
        onSelectBeanStatus = catalogViewModel::selectBeanStatus,
        onSaveBrand = catalogViewModel::saveBrand,
        onSaveItem = catalogViewModel::saveItem,
        onSetItemStatus = catalogViewModel::setItemStatus,
        onClearError = catalogViewModel::clearError,
        onRequestAsset = onRequestAsset,
        onRetainAssetLease = catalogViewModel::retainAssetLease,
        onStageAsset = catalogViewModel::stageAsset,
        onDiscardAssetLease = catalogViewModel::discardAssetLease,
        onOpenSettings = onOpenSettings,
        onOpenChainBrand = onOpenChainBrand,
        imagePathResolver = imagePathResolver,
    )
}

@Composable
fun CatalogScreen(
    state: CatalogUiState,
    onSelectTab: (CatalogTab) -> Unit,
    onSelectBrand: (String) -> Unit,
    onSelectBeanStatus: (ItemStatus) -> Unit,
    onSaveBrand: (BrandEditor) -> Unit,
    onSaveItem: (ItemEditor) -> Unit,
    onSetItemStatus: (CatalogItem, ItemStatus) -> Unit,
    onClearError: () -> Unit,
    onRequestAsset: CatalogAssetPicker = { _, _, _ -> },
    onRetainAssetLease: suspend (String, String?) -> Boolean = { _, _ -> true },
    onStageAsset: suspend (String, String?, String) -> Boolean = { _, _, _ -> true },
    onDiscardAssetLease: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenChainBrand: (String) -> Unit = {},
    imagePathResolver: ImagePathResolver = ImagePathResolver { null },
) {
    var brandEditor by remember { mutableStateOf<Brand?>(null) }
    var showNewBrand by remember { mutableStateOf(false) }
    var itemEditor by remember { mutableStateOf<CatalogItem?>(null) }
    var showNewItem by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<PendingCatalogSave?>(null) }
    var lastHandledSaveToken by remember { mutableStateOf(state.saveCompletedToken) }
    val selectedBrand = state.brandOverviews.firstOrNull { it.brand.id == state.selectedBrandId }?.brand

    LaunchedEffect(state.saveCompletedToken) {
        if (state.saveCompletedToken > lastHandledSaveToken) {
            when (pendingSave) {
                PendingCatalogSave.BRAND -> { showNewBrand = false; brandEditor = null }
                PendingCatalogSave.ITEM -> { showNewItem = false; itemEditor = null }
                null -> Unit
            }
            pendingSave = null
            lastHandledSaveToken = state.saveCompletedToken
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("我的咖啡豆库", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 16.dp).testTag(com.niumi.coffeejournal.TestTags.RootScreenTitle))
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag(com.niumi.coffeejournal.TestTags.RootScreenSettings)) { Text("设置") }
        }
        PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
            Tab(state.tab == CatalogTab.CHAINS, { onSelectTab(CatalogTab.CHAINS) }, text = { Text("连锁品牌") })
            Tab(state.tab == CatalogTab.BEANS, { onSelectTab(CatalogTab.BEANS) }, text = { Text("我的豆子") })
        }
        if (state.tab == CatalogTab.CHAINS) {
            ChainBrandRoot(
                brands = state.brandOverviews.map { it.brand }, imagePathResolver = imagePathResolver,
                onOpen = onOpenChainBrand, onAdd = { showNewBrand = true },
            )
        } else Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { showNewBrand = true }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text("新增烘焙品牌") }

            state.brandOverviews.forEach { overview ->
                BrandCard(
                    overview = overview,
                    selected = overview.brand.id == state.selectedBrandId,
                    enabled = !state.saving,
                    onOpen = { onSelectBrand(overview.brand.id) },
                    onEdit = { brandEditor = overview.brand },
                )
            }

            if (state.tab == CatalogTab.BEANS && selectedBrand != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CatalogViewModel.BEAN_FILTERS.forEach { status ->
                        FilterChip(
                            selected = state.beanStatus == status,
                            onClick = { onSelectBeanStatus(status) },
                            label = { Text(status.beanStatusLabel()) },
                        )
                    }
                }
            }

            if (selectedBrand != null) {
                Button(
                    onClick = { showNewItem = true }, enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.tab == CatalogTab.CHAINS) "新增连锁产品" else "新增豆子") }
            }
            state.visibleItems.forEach { item ->
                ItemCard(
                    item = item,
                    onEdit = { itemEditor = item },
                    onStatus = { onSetItemStatus(item, it) },
                    enabled = !state.saving,
                )
            }
        }
    }

    if (showNewBrand || brandEditor != null) {
        BrandEditorDialog(
            initial = brandEditor,
            type = if (state.tab == CatalogTab.CHAINS) BrandType.CHAIN else BrandType.ROASTER,
            saving = state.saving,
            onDismiss = { showNewBrand = false; brandEditor = null; pendingSave = null },
            onSave = { pendingSave = PendingCatalogSave.BRAND; onSaveBrand(it) },
            onRequestAsset = onRequestAsset,
            onRetainAssetLease = onRetainAssetLease,
            onStageAsset = onStageAsset,
            onDiscardAssetLease = onDiscardAssetLease,
        )
    }
    val editorBrand = selectedBrand
    if ((showNewItem || itemEditor != null) && editorBrand != null) {
        ItemEditorDialog(
            initial = itemEditor,
            brand = editorBrand,
            saving = state.saving,
            onDismiss = {
                showNewItem = false
                itemEditor = null
                pendingSave = null
            },
            onSave = { pendingSave = PendingCatalogSave.ITEM; onSaveItem(it) },
            onRequestAsset = onRequestAsset,
            onRetainAssetLease = onRetainAssetLease,
            onStageAsset = onStageAsset,
            onDiscardAssetLease = onDiscardAssetLease,
        )
    }
    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onClearError,
            confirmButton = { TextButton(onClick = onClearError) { Text("知道了") } },
            title = { Text("无法保存") }, text = { Text(message) },
        )
    }
}

@Composable
private fun ChainBrandRoot(brands: List<Brand>, imagePathResolver: ImagePathResolver, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(3), modifier = Modifier.fillMaxSize().padding(12.dp).testTag(com.niumi.coffeejournal.TestTags.ChainBrandGrid), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(brands, key = { it.id }) { brand ->
            Column(Modifier.testTag(com.niumi.coffeejournal.TestTags.ChainBrandCardPrefix + brand.id).clickable { onOpen(brand.id) }) {
                val bundled = BUNDLED_CHAIN_BRANDS.firstOrNull { it.brand.id == brand.id }
                if (bundled != null) Image(painterResource(bundled.logoRes), "品牌 ${brand.name}", modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
                else ResolvedLocalAssetImage(brand.logoAssetId, null, imagePathResolver, "品牌 ${brand.name}", ContentScale.Crop, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(brand.name, maxLines = 1)
            }
        }
        item { Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("新增品牌") } }
    }
}

@Composable
private fun BrandCard(
    overview: BrandOverview, selected: Boolean, enabled: Boolean,
    onOpen: () -> Unit, onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen)
            .semantics { contentDescription = "品牌 ${overview.brand.name}" },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(overview.brand.name, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onEdit, enabled = enabled) { Text("编辑") }
            }
            Text("${overview.itemCount} 个产品")
            Text(overview.lastUpdatedAtEpochMillis?.let { "最后更新 ${formatCatalogTime(it)}" } ?: "尚未更新")
            if (selected) Text("已展开", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ItemCard(
    item: CatalogItem,
    onEdit: () -> Unit,
    onStatus: (ItemStatus) -> Unit,
    enabled: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            listOfNotNull(item.origin, item.processing, item.roastLevel, item.flavorNotes).takeIf { it.isNotEmpty() }
                ?.let { Text(it.joinToString(" · ")) }
            Text(if (item.type == ItemType.PERSONAL_BEAN) item.status.beanStatusLabel() else statusLabel(item.status))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, enabled = enabled) { Text("编辑") }
                if (item.status != ItemStatus.ARCHIVED) {
                    OutlinedButton(onClick = { onStatus(ItemStatus.ARCHIVED) }, enabled = enabled) { Text("归档") }
                }
                if (item.type == ItemType.PERSONAL_BEAN && item.status == ItemStatus.ACTIVE) {
                    OutlinedButton(onClick = { onStatus(ItemStatus.DISCONTINUED) }, enabled = enabled) { Text("标记已喝完") }
                }
            }
        }
    }
}

@Composable
fun BrandEditorDialog(
    initial: Brand?, type: BrandType, saving: Boolean,
    onDismiss: () -> Unit, onSave: (BrandEditor) -> Unit, onRequestAsset: CatalogAssetPicker,
    onRetainAssetLease: suspend (String, String?) -> Boolean,
    onStageAsset: suspend (String, String?, String) -> Boolean,
    onDiscardAssetLease: (String) -> Unit,
) {
    val leaseId = remember(initial) { UUID.randomUUID().toString() }
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var logoAssetId by remember(initial) { mutableStateOf(initial?.logoAssetId) }
    var logoError by remember(initial) { mutableStateOf(false) }
    DisposableEffect(leaseId) { onDispose { onDiscardAssetLease(leaseId) } }
    LaunchedEffect(leaseId) { onRetainAssetLease(leaseId, initial?.logoAssetId) }
    EditorDialog(
        title = if (initial == null) "新增品牌" else "编辑品牌", saving = saving,
        onDismiss = { onDiscardAssetLease(leaseId); onDismiss() },
        onSave = {
            if (type == BrandType.CHAIN && logoAssetId == null) logoError = true
            else onSave(BrandEditor(type, name, logoAssetId, id = initial?.id, assetLeaseId = leaseId))
        },
    ) {
        Field(name, { name = it }, "品牌名称", enabled = !saving)
        OutlinedButton(
            onClick = {
                onRequestAsset(logoAssetId, CatalogAssetKind.BRAND_LOGO) { selection ->
                    if (onStageAsset(leaseId, initial?.logoAssetId, selection.assetId)) {
                        logoAssetId = selection.assetId
                        logoError = false
                        true
                    } else false
                }
            },
            enabled = !saving,
        ) {
            Text(if (logoAssetId == null) "选择 Logo" else "更换 Logo")
        }
        if (logoError) Text("请先选择 Logo", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ItemEditorDialog(
    initial: CatalogItem?, brand: Brand, saving: Boolean,
    onDismiss: () -> Unit, onSave: (ItemEditor) -> Unit, onRequestAsset: CatalogAssetPicker,
    onRetainAssetLease: suspend (String, String?) -> Boolean,
    onStageAsset: suspend (String, String?, String) -> Boolean,
    onDiscardAssetLease: (String) -> Unit,
) {
    val leaseId = remember(initial) { UUID.randomUUID().toString() }
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var image by remember(initial) { mutableStateOf(initial?.imageAssetId) }
    var origin by remember(initial) { mutableStateOf(initial?.origin.orEmpty()) }
    var processing by remember(initial) { mutableStateOf(initial?.processing.orEmpty()) }
    var roast by remember(initial) { mutableStateOf(initial?.roastLevel.orEmpty()) }
    var flavors by remember(initial) { mutableStateOf(initial?.flavorNotes.orEmpty()) }
    var brew by remember(initial) { mutableStateOf(initial?.brewMethod.orEmpty()) }
    var caffeine by remember(initial) { mutableStateOf(initial?.caffeineMg?.toString().orEmpty()) }
    var description by remember(initial) { mutableStateOf(initial?.officialDescription.orEmpty()) }
    var purchaseDate by remember(initial) { mutableStateOf(initial?.purchaseDate.orEmpty()) }
    var roastDate by remember(initial) { mutableStateOf(initial?.roastDate.orEmpty()) }
    var sourceUrl by remember(initial) { mutableStateOf(initial?.sourceUrl.orEmpty()) }
    var category by remember(initial) { mutableStateOf(initial?.category.orEmpty()) }
    var specification by remember(initial) { mutableStateOf(initial?.specificationDescription.orEmpty()) }
    var status by remember(initial) { mutableStateOf(initial?.status ?: ItemStatus.ACTIVE) }
    var chainProductKind by remember(initial) { mutableStateOf(initial?.chainProductKind) }
    var caffeineError by remember(initial) { mutableStateOf<String?>(null) }
    val type = if (brand.type == BrandType.CHAIN) ItemType.CHAIN_PRODUCT else ItemType.PERSONAL_BEAN
    DisposableEffect(leaseId) { onDispose { onDiscardAssetLease(leaseId) } }
    LaunchedEffect(leaseId) { onRetainAssetLease(leaseId, initial?.imageAssetId) }
    EditorDialog(
        title = if (initial == null) "新增${if (type == ItemType.PERSONAL_BEAN) "豆子" else "产品"}" else "编辑条目",
        saving = saving, onDismiss = { onDiscardAssetLease(leaseId); onDismiss() },
        onSave = {
            val caffeineResult = validateCaffeineInput(caffeine)
            if (caffeineResult is CaffeineInput.Invalid) {
                caffeineError = "请输入非负的有效咖啡因数值"
                return@EditorDialog
            }
            caffeineError = null
            onSave(
                ItemEditor(
                    brand.id, type, name, image, origin, processing, roast, flavors, brew, status,
                    (caffeineResult as CaffeineInput.Valid).milligrams,
                    description, purchaseDate, roastDate, sourceUrl, initial?.id,
                    category, specification, leaseId, chainProductKind,
                ),
            )
        },
    ) {
        Field(name, { name = it }, "名称", enabled = !saving)
        Field(origin, { origin = it }, "产地（可选）", enabled = !saving)
        Field(processing, { processing = it }, "处理法（可选）", enabled = !saving)
        Field(roast, { roast = it }, "烘焙度（可选）", enabled = !saving)
        Field(flavors, { flavors = it }, "风味描述（可选）", enabled = !saving)
        Field(brew, { brew = it }, "默认冲煮方式（可选）", enabled = !saving)
        if (type == ItemType.CHAIN_PRODUCT) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("咖啡类型")
                listOf(ChainProductKind.BLACK, ChainProductKind.FRUIT, ChainProductKind.MILK).forEach { value ->
                    FilterChip(chainProductKind == value, { chainProductKind = value }, label = { Text(chainProductKindLabel(value)) }, enabled = !saving)
                }
            }
            Field(category, { category = it }, "产品分类（可选）", enabled = !saving)
            Field(specification, { specification = it }, "规格描述（可选）", enabled = !saving)
            Field(caffeine, { caffeine = it; caffeineError = null }, "咖啡因 mg（可选）", enabled = !saving)
            caffeineError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Field(description, { description = it }, "官方描述（可选）", enabled = !saving)
            Field(sourceUrl, { sourceUrl = it }, "来源链接（可选）", enabled = !saving)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("当前状态")
                listOf(ItemStatus.ACTIVE, ItemStatus.NEEDS_IMAGE, ItemStatus.DISCONTINUED, ItemStatus.ARCHIVED)
                    .forEach { value ->
                        FilterChip(status == value, { status = value }, label = { Text(statusLabel(value)) }, enabled = !saving)
                    }
            }
        } else {
            Field(description, { description = it }, "备注（可选）", enabled = !saving)
            Field(purchaseDate, { purchaseDate = it }, "购买日期 YYYY-MM-DD（可选）", enabled = !saving)
            Field(roastDate, { roastDate = it }, "烘焙日期 YYYY-MM-DD（可选）", enabled = !saving)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CatalogViewModel.BEAN_FILTERS.forEach { value ->
                    FilterChip(status == value, { status = value }, label = { Text(value.beanStatusLabel()) }, enabled = !saving)
                }
            }
        }
        OutlinedButton(
            onClick = {
                val assetKind = if (type == ItemType.CHAIN_PRODUCT) {
                    CatalogAssetKind.CHAIN_PRODUCT_IMAGE
                } else {
                    CatalogAssetKind.BEAN_PACKAGE
                }
                onRequestAsset(image, assetKind) { selection ->
                    if (onStageAsset(leaseId, initial?.imageAssetId, selection.assetId)) {
                        image = selection.assetId
                        true
                    } else false
                }
            },
            enabled = !saving,
        ) {
            Text(if (image == null) "选择图片" else "更换图片")
        }
    }
}

private fun chainProductKindLabel(kind: ChainProductKind): String = when (kind) {
    ChainProductKind.BLACK -> "黑咖"
    ChainProductKind.FRUIT -> "果咖"
    ChainProductKind.MILK -> "奶咖"
    ChainProductKind.PENDING -> "待分类"
}

@Composable
private fun EditorDialog(
    title: String, saving: Boolean, onDismiss: () -> Unit, onSave: () -> Unit,
    fields: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { fields() }
        },
        confirmButton = { Button(onClick = onSave, enabled = !saving) { Text(if (saving) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, enabled = enabled,
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

private enum class PendingCatalogSave { BRAND, ITEM }

private data class PendingScreenshotImport(val brandId: String, val token: String)

private fun statusLabel(status: ItemStatus): String = when (status) {
    ItemStatus.ACTIVE -> "在售"
    ItemStatus.NEEDS_IMAGE -> "待补充图片"
    ItemStatus.DISCONTINUED -> "已下架"
    ItemStatus.ARCHIVED -> "归档"
}

private fun formatCatalogTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(epochMillis))
