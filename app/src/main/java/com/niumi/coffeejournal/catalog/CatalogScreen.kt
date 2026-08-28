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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import com.niumi.coffeejournal.ui.CoffeeVisuals

enum class CatalogAssetKind { BRAND_LOGO, CHAIN_PRODUCT_IMAGE, BEAN_PACKAGE }

internal val CatalogSurfaceColor = SemanticsPropertyKey<Color>("CatalogSurfaceColor")
internal val CatalogMediaFrameColor = SemanticsPropertyKey<Color>("CatalogMediaFrameColor")
internal val CatalogMediaFrameOutlineColor = SemanticsPropertyKey<Color>("CatalogMediaFrameOutlineColor")

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
        onStageAsset = catalogViewModel::stageAsset,
        onUpdateBrandDraft = catalogViewModel::updateBrandDraft,
        onUpdateItemDraft = catalogViewModel::updateItemDraft,
        onUpdateItemCaffeineInput = catalogViewModel::updateItemCaffeineInput,
        onOpenBrandEditor = catalogViewModel::openBrandEditor,
        onOpenItemEditor = catalogViewModel::openItemEditor,
        onCloseEditor = catalogViewModel::closeEditor,
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
    onStageAsset: suspend (String, String?, String) -> Boolean = { _, _, _ -> true },
    onUpdateBrandDraft: ((BrandEditor) -> BrandEditor) -> Unit = {},
    onUpdateItemDraft: ((ItemEditor) -> ItemEditor) -> Unit = {},
    onUpdateItemCaffeineInput: (String) -> Unit = {},
    onOpenBrandEditor: (Brand?, BrandType) -> Unit = { _, _ -> },
    onOpenItemEditor: (CatalogItem?, Brand) -> Unit = { _, _ -> },
    onCloseEditor: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenChainBrand: (String) -> Unit = {},
    imagePathResolver: ImagePathResolver = ImagePathResolver { null },
) {
    val selectedBrand = state.brandOverviews.firstOrNull { it.brand.id == state.selectedBrandId }?.brand

    Column(
        modifier = Modifier.fillMaxSize()
            .testTag(com.niumi.coffeejournal.TestTags.CatalogSurface)
            .background(CoffeeVisuals.cream)
            .semantics { this[CatalogSurfaceColor] = CoffeeVisuals.cream },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("我的咖啡豆库", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 16.dp)
                .testTag(com.niumi.coffeejournal.TestTags.RootScreenTitle))
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag(com.niumi.coffeejournal.TestTags.RootScreenSettings)) { Text("设置") }
        }
        PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
            Tab(state.tab == CatalogTab.CHAINS, { onSelectTab(CatalogTab.CHAINS) }, text = { Text("连锁品牌") })
            Tab(state.tab == CatalogTab.BEANS, { onSelectTab(CatalogTab.BEANS) }, text = { Text("我的豆子") })
        }
        if (state.tab == CatalogTab.CHAINS) {
            ChainBrandRoot(
                brands = state.brandOverviews.map { it.brand }, imagePathResolver = imagePathResolver,
                onOpen = onOpenChainBrand, onAdd = { onOpenBrandEditor(null, BrandType.CHAIN) },
            )
        } else Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { onOpenBrandEditor(null, BrandType.ROASTER) }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text("新增烘焙品牌") }

            state.brandOverviews.forEach { overview ->
                BrandCard(
                    overview = overview,
                    selected = overview.brand.id == state.selectedBrandId,
                    enabled = !state.saving,
                    onOpen = { onSelectBrand(overview.brand.id) },
                    onEdit = { onOpenBrandEditor(overview.brand, overview.brand.type) },
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
                    onClick = { onOpenItemEditor(null, selectedBrand) }, enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.tab == CatalogTab.CHAINS) "新增连锁产品" else "新增豆子") }
            }
            state.visibleItems.forEach { item ->
                ItemCard(
                    item = item,
                    onEdit = { selectedBrand?.let { onOpenItemEditor(item, it) } },
                    onStatus = { onSetItemStatus(item, it) },
                    enabled = !state.saving,
                )
            }
        }
    }

    (state.editorSession as? CatalogEditorSession.Brand)?.let { session ->
        BrandEditorDialog(
            session = session,
            saving = state.saving,
            onDismiss = onCloseEditor,
            onSave = onSaveBrand,
            onRequestAsset = onRequestAsset,
            onStageAsset = onStageAsset,
            onUpdateDraft = onUpdateBrandDraft,
        )
    }
    (state.editorSession as? CatalogEditorSession.Item)?.let { session ->
        ItemEditorDialog(
            session = session,
            saving = state.saving,
            onDismiss = onCloseEditor,
            onSave = onSaveItem,
            onRequestAsset = onRequestAsset,
            onStageAsset = onStageAsset,
            onUpdateDraft = onUpdateItemDraft,
            onUpdateCaffeineInput = onUpdateItemCaffeineInput,
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
                CatalogMediaFrame(Modifier.testTag(com.niumi.coffeejournal.TestTags.ChainBrandMediaFramePrefix + brand.id)) {
                    if (bundled != null) Image(painterResource(bundled.logoRes), "品牌 ${brand.name}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else ResolvedLocalAssetImage(brand.logoAssetId, null, imagePathResolver, "品牌 ${brand.name}", ContentScale.Fit, Modifier.fillMaxSize())
                }
                Text(brand.name, maxLines = 1)
            }
        }
        item {
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("新增品牌") }
        }
    }
}

@Composable
internal fun CatalogMediaFrame(modifier: Modifier = Modifier, image: @Composable () -> Unit) {
    Box(
        modifier = modifier.fillMaxWidth().aspectRatio(1f).semantics {
            this[CatalogMediaFrameColor] = CoffeeVisuals.white
            this[CatalogMediaFrameOutlineColor] = CoffeeVisuals.warmOutline
        }.clip(RoundedCornerShape(CoffeeVisuals.cornerMedium))
            .background(CoffeeVisuals.white).border(1.dp, CoffeeVisuals.warmOutline, RoundedCornerShape(CoffeeVisuals.cornerMedium)).padding(12.dp),
    ) { image() }
}

@Composable
private fun BrandCard(
    overview: BrandOverview, selected: Boolean, enabled: Boolean,
    onOpen: () -> Unit, onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen)
            .semantics { contentDescription = "品牌 ${overview.brand.name}" },
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = CoffeeVisuals.white),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(overview.brand.name, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onEdit, enabled = enabled) { Text("编辑") }
            }
            Text("${overview.itemCount} 个产品", color = CoffeeVisuals.secondaryText)
            if (selected) Text("已展开", color = CoffeeVisuals.forest)
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
    Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = CoffeeVisuals.white)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            listOfNotNull(item.origin, item.processing, item.roastLevel, item.flavorNotes).takeIf { it.isNotEmpty() }
                ?.let { Text(it.joinToString(" · ")) }
            Text(
                if (item.type == ItemType.PERSONAL_BEAN) item.status.beanStatusLabel() else statusLabel(item.status),
                color = CoffeeVisuals.forest,
                modifier = Modifier.background(CoffeeVisuals.mint, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
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
    session: CatalogEditorSession.Brand, saving: Boolean,
    onDismiss: () -> Unit, onSave: (BrandEditor) -> Unit, onRequestAsset: CatalogAssetPicker,
    onStageAsset: suspend (String, String?, String) -> Boolean,
    onUpdateDraft: ((BrandEditor) -> BrandEditor) -> Unit,
) {
    val initial = session.initial
    val type = session.type
    val leaseId = session.leaseId
    var logoError by remember(initial) { mutableStateOf(false) }
    EditorDialog(
        title = if (initial == null) "新增品牌" else "编辑品牌", saving = saving,
        onDismiss = onDismiss,
        onSave = {
            if (type == BrandType.CHAIN && session.currentAssetId == null) logoError = true
            else onSave(session.draft)
        },
    ) {
        Field(session.draft.name, { value -> onUpdateDraft { it.copy(name = value) } }, "品牌名称", enabled = !saving)
        OutlinedButton(
            onClick = {
                onRequestAsset(session.currentAssetId, CatalogAssetKind.BRAND_LOGO) { selection ->
                    if (onStageAsset(leaseId, initial?.logoAssetId, selection.assetId)) {
                        logoError = false
                        true
                    } else false
                }
            },
            enabled = !saving,
        ) {
            Text(if (session.currentAssetId == null) "选择 Logo" else "更换 Logo")
        }
        if (logoError) Text("请先选择 Logo", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ItemEditorDialog(
    session: CatalogEditorSession.Item, saving: Boolean,
    onDismiss: () -> Unit, onSave: (ItemEditor) -> Unit, onRequestAsset: CatalogAssetPicker,
    onStageAsset: suspend (String, String?, String) -> Boolean,
    onUpdateDraft: ((ItemEditor) -> ItemEditor) -> Unit,
    onUpdateCaffeineInput: (String) -> Unit,
) {
    val initial = session.initial
    val brand = session.brand
    val leaseId = session.leaseId
    val draft = session.draft
    var caffeineError by remember(initial) { mutableStateOf<String?>(null) }
    val type = if (brand.type == BrandType.CHAIN) ItemType.CHAIN_PRODUCT else ItemType.PERSONAL_BEAN
    EditorDialog(
        title = if (initial == null) "新增${if (type == ItemType.PERSONAL_BEAN) "豆子" else "产品"}" else "编辑条目",
        saving = saving, onDismiss = onDismiss,
        onSave = {
            val caffeineResult = validateCaffeineInput(session.caffeineInput)
            if (caffeineResult is CaffeineInput.Invalid) {
                caffeineError = "请输入非负的有效咖啡因数值"
                return@EditorDialog
            }
            caffeineError = null
            onSave(draft.copy(caffeineMg = (caffeineResult as CaffeineInput.Valid).milligrams))
        },
    ) {
        Field(draft.name, { v -> onUpdateDraft { it.copy(name = v) } }, "名称", enabled = !saving)
        Field(draft.origin.orEmpty(), { v -> onUpdateDraft { it.copy(origin = v) } }, "产地（可选）", enabled = !saving)
        Field(draft.processing.orEmpty(), { v -> onUpdateDraft { it.copy(processing = v) } }, "处理法（可选）", enabled = !saving)
        Field(draft.roastLevel.orEmpty(), { v -> onUpdateDraft { it.copy(roastLevel = v) } }, "烘焙度（可选）", enabled = !saving)
        Field(draft.flavorNotes.orEmpty(), { v -> onUpdateDraft { it.copy(flavorNotes = v) } }, "风味描述（可选）", enabled = !saving)
        Field(draft.brewMethod.orEmpty(), { v -> onUpdateDraft { it.copy(brewMethod = v) } }, "默认冲煮方式（可选）", enabled = !saving)
        if (type == ItemType.CHAIN_PRODUCT) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("咖啡类型")
                listOf(ChainProductKind.BLACK, ChainProductKind.FRUIT, ChainProductKind.MILK).forEach { value ->
                    FilterChip(draft.chainProductKind == value, { onUpdateDraft { it.copy(chainProductKind = value) } }, label = { Text(chainProductKindLabel(value)) }, enabled = !saving)
                }
            }
            Field(draft.category.orEmpty(), { v -> onUpdateDraft { it.copy(category = v) } }, "产品分类（可选）", enabled = !saving)
            Field(draft.specificationDescription.orEmpty(), { v -> onUpdateDraft { it.copy(specificationDescription = v) } }, "规格描述（可选）", enabled = !saving)
            Field(session.caffeineInput, { v -> onUpdateCaffeineInput(v); caffeineError = null }, "咖啡因 mg（可选）", enabled = !saving)
            caffeineError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Field(draft.officialDescription.orEmpty(), { v -> onUpdateDraft { it.copy(officialDescription = v) } }, "官方描述（可选）", enabled = !saving)
            Field(draft.sourceUrl.orEmpty(), { v -> onUpdateDraft { it.copy(sourceUrl = v) } }, "来源链接（可选）", enabled = !saving)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("当前状态")
                listOf(ItemStatus.ACTIVE, ItemStatus.NEEDS_IMAGE, ItemStatus.DISCONTINUED, ItemStatus.ARCHIVED)
                    .forEach { value ->
                        FilterChip(draft.status == value, { onUpdateDraft { it.copy(status = value) } }, label = { Text(statusLabel(value)) }, enabled = !saving)
                    }
            }
        } else {
            Field(draft.officialDescription.orEmpty(), { v -> onUpdateDraft { it.copy(officialDescription = v) } }, "备注（可选）", enabled = !saving)
            Field(draft.purchaseDate.orEmpty(), { v -> onUpdateDraft { it.copy(purchaseDate = v) } }, "购买日期 YYYY-MM-DD（可选）", enabled = !saving)
            Field(draft.roastDate.orEmpty(), { v -> onUpdateDraft { it.copy(roastDate = v) } }, "烘焙日期 YYYY-MM-DD（可选）", enabled = !saving)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CatalogViewModel.BEAN_FILTERS.forEach { value ->
                    FilterChip(draft.status == value, { onUpdateDraft { it.copy(status = value) } }, label = { Text(value.beanStatusLabel()) }, enabled = !saving)
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
                onRequestAsset(session.currentAssetId, assetKind) { selection ->
                    if (onStageAsset(leaseId, initial?.imageAssetId, selection.assetId)) {
                        true
                    } else false
                }
            },
            enabled = !saving,
        ) {
            Text(if (session.currentAssetId == null) "选择图片" else "更换图片")
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


private fun statusLabel(status: ItemStatus): String = when (status) {
    ItemStatus.ACTIVE -> "在售"
    ItemStatus.NEEDS_IMAGE -> "待补充图片"
    ItemStatus.DISCONTINUED -> "已下架"
    ItemStatus.ARCHIVED -> "归档"
}
