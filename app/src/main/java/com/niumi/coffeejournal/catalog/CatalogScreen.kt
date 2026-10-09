package com.niumi.coffeejournal.catalog

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as columnItems
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ChainProductKind
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.PersonalProductType
import com.niumi.coffeejournal.core.model.parseGramsToMilligrams
import com.niumi.coffeejournal.core.model.parsePositiveCount
import com.niumi.coffeejournal.journal.parseYuanToFen
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.image.ImportedAssetSelection
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.image.ResolvedLocalAssetImage
import com.niumi.coffeejournal.ui.CoffeeVisuals
import com.niumi.coffeejournal.ui.scrapbookPaper
import kotlinx.coroutines.delay
import java.util.Locale

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
    var showAddMenu by remember { mutableStateOf(false) }
    var choosingProductBrand by remember { mutableStateOf(false) }
    val catalogViewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.factory(repository, imageStore))
    val state by catalogViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) catalogViewModel.refreshToday() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { while (true) { delay(60_000); catalogViewModel.refreshToday() } }
    CatalogScreen(
        state = state,
        onSelectTab = catalogViewModel::selectTab,
        onSelectBrand = catalogViewModel::selectBrand,
        onSelectBeanStatus = catalogViewModel::selectBeanStatus,
        onUpdateCatalogQuery = catalogViewModel::updateCatalogQuery,
        onSelectCatalogBrand = catalogViewModel::selectCatalogBrand,
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
    onUpdateCatalogQuery: (String) -> Unit = {},
    onSelectCatalogBrand: (String?) -> Unit = {},
) {
    var showAddMenu by remember { mutableStateOf(false) }
    var choosingProductBrand by remember { mutableStateOf(false) }
    val brands = state.brandOverviews.map(BrandOverview::brand)
    val selectedBrand = brands.firstOrNull { it.id == state.selectedBrandId }
    val visibleProducts = state.visibleItems.filter { item ->
        val brandName = brands.firstOrNull { it.id == item.brandId }?.name.orEmpty()
        state.catalogQuery.trim().let { query -> query.isEmpty() || brandName.contains(query, ignoreCase = true) || item.name.contains(query, ignoreCase = true) }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .testTag(com.niumi.coffeejournal.TestTags.CatalogSurface)
            .background(Color(0xFFFFFCF6))
            .semantics { this[CatalogSurfaceColor] = Color(0xFFFFFCF6) },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.padding(vertical = 6.dp)) { Text("豆库", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag(com.niumi.coffeejournal.TestTags.RootScreenTitle)) }
            Row { TextButton(onClick = onOpenSettings, modifier = Modifier.testTag(com.niumi.coffeejournal.TestTags.RootScreenSettings)) { Text("设置") }; Box { TextButton(onClick = { showAddMenu = true }) { Text("新增") }; DropdownMenu(showAddMenu, { showAddMenu = false }) { DropdownMenuItem({ Text("新增品牌") }, { showAddMenu = false; onOpenBrandEditor(null, if (state.tab == CatalogTab.CHAINS) BrandType.CHAIN else BrandType.ROASTER) }); DropdownMenuItem({ Text("新增产品") }, { showAddMenu = false; choosingProductBrand = true }) } } }
        }
        CatalogTabs(state.tab, onSelectTab)
        OutlinedTextField(value = state.catalogQuery, onValueChange = onUpdateCatalogQuery, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true, label = { Text("搜索品牌或产品") })
        LazyRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(state.catalogBrandId == null, { onSelectCatalogBrand(null) }, label = { Text("全部") }) }
            lazyItems(brands, key = { it.id }) { brand -> FilterChip(state.catalogBrandId == brand.id, { onSelectCatalogBrand(brand.id) }, label = { Text(brand.name) }) }
        }
        if (state.tab == CatalogTab.BEANS) LazyRow(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lazyItems(CatalogViewModel.BEAN_FILTERS) { status -> FilterChip(state.beanStatus == status, { onSelectBeanStatus(status) }, label = { Text(status.beanStatusLabel()) }) }
        }
        val filterBrand = brands.firstOrNull { it.id == state.catalogBrandId } ?: selectedBrand
        if (filterBrand != null) TextButton(onClick = { if (filterBrand.type == BrandType.CHAIN) onOpenChainBrand(filterBrand.id) else onOpenBrandEditor(filterBrand, BrandType.ROASTER) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("管理品牌") }
        if (filterBrand != null) TextButton(onClick = { onOpenItemEditor(null, filterBrand) }, enabled = !state.saving, modifier = Modifier.padding(horizontal = 16.dp)) { Text(if (state.tab == CatalogTab.CHAINS) "为 ${filterBrand.name} 新增产品" else "新增豆子") }
        else if (brands.isNotEmpty()) Text("选择一个品牌后即可新增${if (state.tab == CatalogTab.CHAINS) "产品" else "豆子"}", Modifier.padding(horizontal = 16.dp), color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.bodySmall)
        if (brands.isEmpty()) Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text("还没有${if (state.tab == CatalogTab.CHAINS) "品牌" else "烘焙商"}"); Button(onClick = { onOpenBrandEditor(null, if (state.tab == CatalogTab.CHAINS) BrandType.CHAIN else BrandType.ROASTER) }) { Text("新增品牌") } }
        else if (visibleProducts.isEmpty()) Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text(if (state.catalogQuery.isNotBlank()) "没有匹配的产品" else "这个分类还没有产品") }
        else LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp).testTag(com.niumi.coffeejournal.TestTags.CatalogProductList), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            columnItems(visibleProducts, key = { it.id }) { item ->
                val brand = brands.firstOrNull { it.id == item.brandId } ?: return@columnItems
                CompactCatalogProductRow(item, brand, state.todayLocalDate, imagePathResolver, onClick = { onOpenItemEditor(item, brand) }, onStatus = { onSetItemStatus(item, it) }, enabled = !state.saving)
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
    if (choosingProductBrand) AlertDialog(onDismissRequest = { choosingProductBrand = false }, title = { Text("选择品牌") }, text = { Column { brands.forEach { brand -> TextButton(onClick = { choosingProductBrand = false; onSelectCatalogBrand(brand.id); onOpenItemEditor(null, brand) }) { Text(brand.name) } } } }, confirmButton = {})
}

@Composable
private fun CompactCatalogProductRow(item: CatalogItem, brand: Brand, today: String, resolver: ImagePathResolver, onClick: () -> Unit, onStatus: (ItemStatus) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().testTag(com.niumi.coffeejournal.TestTags.CatalogProductRowPrefix + item.id).clickable(enabled = enabled, onClick = onClick).padding(vertical = 7.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        val bundled = BUNDLED_CHAIN_BRANDS.firstOrNull { it.brand.id == brand.id }
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(CoffeeVisuals.cornerSmall)).background(CoffeeVisuals.white).testTag(com.niumi.coffeejournal.TestTags.CatalogProductImagePrefix + item.id)) {
            ResolvedLocalAssetImage(item.imageAssetId, brand.logoAssetId, resolver, "${item.name} 图片", ContentScale.Fit, Modifier.fillMaxSize().padding(4.dp), bundled?.let { painterResource(it.logoRes) })
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(item.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall); Text("${brand.name} · ${item.chainProductKind?.let(::publicKindLabel) ?: if (item.type == ItemType.PERSONAL_BEAN) item.status.beanStatusLabel() else "待分类"}", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.bodySmall); if (item.type == ItemType.PERSONAL_BEAN && item.status in listOf(ItemStatus.ACTIVE, ItemStatus.NEEDS_IMAGE, ItemStatus.RESTING)) Text(restingDays(item.roastDate, today)?.let { "${it}天" } ?: "待补充烘焙日期", color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.bodySmall) }
        if (item.type == ItemType.PERSONAL_BEAN) {
            TextButton(onClick = { onStatus(when (item.status) { ItemStatus.RESTING, ItemStatus.DISCONTINUED -> ItemStatus.ACTIVE; else -> ItemStatus.DISCONTINUED }) }, enabled = enabled) { Text(if (item.status == ItemStatus.RESTING || item.status == ItemStatus.DISCONTINUED) "开始喝" else "喝完") }
        }
    }
}

private fun personalBeanStatusText(item: CatalogItem, today: String): String = if (item.status != ItemStatus.RESTING) item.status.beanStatusLabel() else restingDays(item.roastDate, today)?.let { "养豆中 · ${it}天" } ?: "养豆中 · 待补充烘焙日期"

internal fun restingDays(roastDate: String?, today: String): Int? {
    if (!isValidRestingRoastDate(roastDate, today)) return null
    fun day(date: String): Long { val (year, month, value) = date.split('-').map(String::toInt); return java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply { isLenient = false; clear(); set(year, month - 1, value); timeInMillis }.timeInMillis / 86_400_000L }
    return (day(today) - day(roastDate!!)).toInt()
}

@Composable
private fun CatalogTabs(selected: CatalogTab, onSelect: (CatalogTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(CoffeeVisuals.cornerMedium)).background(CoffeeVisuals.mint).padding(4.dp).selectableGroup()) {
        listOf(CatalogTab.CHAINS to "连锁品牌", CatalogTab.BEANS to "我的豆子").forEach { (tab, label) ->
            val active = selected == tab
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(CoffeeVisuals.cornerSmall)).background(if (active) CoffeeVisuals.white else Color.Transparent).selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) }).padding(vertical = 10.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(label, color = if (active) CoffeeVisuals.forest else CoffeeVisuals.secondaryText)
            }
        }
    }
}

@Composable
private fun ChainBrandRoot(brands: List<Brand>, imagePathResolver: ImagePathResolver, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(3), modifier = Modifier.fillMaxSize().padding(12.dp).testTag(com.niumi.coffeejournal.TestTags.ChainBrandGrid), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(brands, key = { it.id }) { brand ->
            Card(Modifier.testTag(com.niumi.coffeejournal.TestTags.ChainBrandCardPrefix + brand.id).clickable { onOpen(brand.id) }, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = CoffeeVisuals.white)) {
                val bundled = BUNDLED_CHAIN_BRANDS.firstOrNull { it.brand.id == brand.id }
                CatalogMediaFrame(Modifier.testTag(com.niumi.coffeejournal.TestTags.ChainBrandMediaFramePrefix + brand.id)) {
                    if (bundled != null) Image(painterResource(bundled.logoRes), "品牌 ${brand.name}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else ResolvedLocalAssetImage(brand.logoAssetId, null, imagePathResolver, "品牌 ${brand.name}", ContentScale.Fit, Modifier.fillMaxSize())
                }
                Text(brand.name, maxLines = 1, modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("新增品牌") }
        }
    }
}

@Composable
internal fun CatalogMediaFrame(modifier: Modifier = Modifier, image: @Composable () -> Unit) {
    Box(
        modifier = modifier.fillMaxWidth().aspectRatio(1f).semantics {
            this[CatalogMediaFrameColor] = CoffeeVisuals.white
            this[CatalogMediaFrameOutlineColor] = Color.Transparent
        }.clip(RoundedCornerShape(CoffeeVisuals.cornerMedium))
            .background(CoffeeVisuals.white).padding(12.dp),
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
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(overview.brand.name, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("${overview.itemCount} 个产品", color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = onEdit, enabled = enabled) { Text("编辑") }
                }
            }
            if (selected) Text("已展开", color = CoffeeVisuals.forest)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemCard(
    item: CatalogItem,
    imagePathResolver: ImagePathResolver,
    onEdit: () -> Unit,
    onStatus: (ItemStatus) -> Unit,
    enabled: Boolean,
) {
    Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = CoffeeVisuals.white)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.Top) {
                if (item.imageAssetId != null) {
                    ResolvedLocalAssetImage(item.imageAssetId, null, imagePathResolver, "${item.name} 图片", ContentScale.Fit, Modifier.size(64.dp).clip(RoundedCornerShape(CoffeeVisuals.cornerSmall)).background(CoffeeVisuals.cream))
                }
                Column(Modifier.weight(1f).padding(start = if (item.imageAssetId != null) 10.dp else 0.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (item.type == ItemType.PERSONAL_BEAN) item.status.beanStatusLabel() else statusLabel(item.status), color = CoffeeVisuals.forest, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp).background(CoffeeVisuals.mint, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            listOfNotNull(item.origin, item.processing, item.roastLevel, item.flavorNotes).takeIf { it.isNotEmpty() }
                ?.let { Text(it.joinToString(" · "), color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
    var costError by remember(initial) { mutableStateOf<String?>(null) }
    var caffeineError by remember(initial) { mutableStateOf<String?>(null) }
    val type = if (brand.type == BrandType.CHAIN) ItemType.CHAIN_PRODUCT else ItemType.PERSONAL_BEAN
    EditorDialog(
        title = if (initial == null) "新增${if (type == ItemType.PERSONAL_BEAN) "豆子" else "产品"}" else "编辑条目",
        saving = saving, onDismiss = onDismiss,
        onSave = {
            if (type == ItemType.PERSONAL_BEAN) {
                val priceValid = draft.purchasePriceInput.isBlank() || parseYuanToFen(draft.purchasePriceInput) != null
                val specValid = draft.specificationInput.isBlank() || (if (draft.personalProductType == PersonalProductType.BEANS) parseGramsToMilligrams(draft.specificationInput) else parsePositiveCount(draft.specificationInput)) != null
                if (!priceValid || !specValid) { costError = "请输入有效购买价格与正数规格，包数须为整数"; return@EditorDialog }
                costError = null
            }
            val caffeineResult = validateCaffeineInput(session.caffeineInput)
            if (caffeineResult is CaffeineInput.Invalid) {
                caffeineError = "请输入非负的有效咖啡因数值"
                return@EditorDialog
            }
            caffeineError = null
            onSave(draft.copy(caffeineMg = (caffeineResult as CaffeineInput.Valid).milligrams))
        },
    ) {
        if (type == ItemType.PERSONAL_BEAN) {
            Text("产品类型")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonalProductType.entries.forEach { value ->
                    FilterChip(draft.personalProductType == value, { onUpdateDraft { if (it.personalProductType == value) it else it.copy(personalProductType = value, specificationInput = "", packageWeightMg = null, packageCount = null) }; costError = null }, label = { Text(if (value == PersonalProductType.BEANS) "咖啡豆" else "挂耳咖啡") }, enabled = !saving)
                }
            }
            Field(draft.purchasePriceInput, { value -> onUpdateDraft { it.copy(purchasePriceInput = value) }; costError = null }, "购买总价（元，可选）", enabled = !saving, keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
            Field(draft.specificationInput, { value -> onUpdateDraft { it.copy(specificationInput = value) }; costError = null }, if (draft.personalProductType == PersonalProductType.BEANS) "购买总克重（克，可选）" else "购买总包数（包，可选）", enabled = !saving, keyboardType = if (draft.personalProductType == PersonalProductType.BEANS) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Number)
            Text(if (draft.personalProductType == PersonalProductType.BEANS) "克重最多三位小数，按本次用豆量计价" else "包数填写正整数，按本次包数计价", style = MaterialTheme.typography.bodySmall)
            costError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Field(draft.name, { v -> onUpdateDraft { it.copy(name = v) } }, "名称", enabled = !saving)
        Field(draft.origin.orEmpty(), { v -> onUpdateDraft { it.copy(origin = v) } }, "产地（可选）", enabled = !saving)
        EditableSuggestionField(draft.processing.orEmpty(), { v -> onUpdateDraft { it.copy(processing = v) } }, "处理法（可选）", listOf("水洗", "日晒", "蜜处理", "湿刨", "厌氧发酵", "二氧化碳浸渍"), !saving)
        EditableSuggestionField(draft.roastLevel.orEmpty(), { v -> onUpdateDraft { it.copy(roastLevel = v) } }, "烘焙度（可选）", listOf("极浅烘焙", "浅烘焙", "中浅烘焙", "中度烘焙", "中深烘焙", "深烘焙", "极深烘焙"), !saving)
        Field(draft.flavorNotes.orEmpty(), { v -> onUpdateDraft { it.copy(flavorNotes = v) } }, "风味描述（可选）", enabled = !saving)
        if (type == ItemType.CHAIN_PRODUCT) Field(draft.brewMethod.orEmpty(), { v -> onUpdateDraft { it.copy(brewMethod = v) } }, "默认冲煮方式（可选）", enabled = !saving)
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
            DateField(draft.purchaseDate, "购买日期（可选）", "购买", !saving) { onUpdateDraft { draft -> draft.copy(purchaseDate = it) } }
            DateField(draft.roastDate, "烘焙日期（可选）", "烘焙", !saving) { onUpdateDraft { draft -> draft.copy(roastDate = it) } }
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
private fun Field(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true, keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, enabled = enabled,
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable private fun EditableSuggestionField(value: String, onChange: (String) -> Unit, label: String, suggestions: List<String>, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedTextField(value, onChange, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = { TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "展开${label.removeSuffix("（可选）")}选项" }) { Text("⌄") } }); DropdownMenu(expanded, { expanded = false }) { suggestions.forEach { suggestion -> DropdownMenuItem({ Text(suggestion) }, { onChange(suggestion); expanded = false }) } } }
}

@Composable private fun DateField(value: String?, label: String, kind: String, enabled: Boolean, onChange: (String?) -> Unit) {
    val context = LocalContext.current
    Column { Text(label); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(enabled = enabled, modifier = Modifier.weight(1f), onClick = { val c = java.util.Calendar.getInstance(); value?.split('-')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 3 }?.let { (y, m, d) -> c.set(y, m - 1, d) }; DatePickerDialog(context, { _, y, m, d -> onChange(String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d)) }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show() }) { Text(value ?: "选择${kind}日期") }; if (value != null) TextButton(enabled = enabled, modifier = Modifier.semantics { contentDescription = "清除${kind}日期" }, onClick = { onChange(null) }) { Text("清除") } } }
}


private fun statusLabel(status: ItemStatus): String = when (status) {
    ItemStatus.ACTIVE -> "在售"
    ItemStatus.NEEDS_IMAGE -> "待补充图片"
    ItemStatus.DISCONTINUED -> "已下架"
    ItemStatus.ARCHIVED -> "归档"
    ItemStatus.RESTING -> "养豆中"
}
