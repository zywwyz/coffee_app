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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CatalogAssetKind { BRAND_LOGO, ITEM_IMAGE }

typealias CatalogAssetPicker = (String?, CatalogAssetKind, (String?) -> Unit) -> Unit

@Composable
fun CatalogFeature(
    repository: CatalogRepository,
    onRequestAsset: CatalogAssetPicker = { _, _, _ -> },
) {
    val viewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.factory(repository))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CatalogScreen(
        state = state,
        onSelectTab = viewModel::selectTab,
        onSelectBrand = viewModel::selectBrand,
        onSelectBeanStatus = viewModel::selectBeanStatus,
        onSaveBrand = viewModel::saveBrand,
        onSaveItem = viewModel::saveItem,
        onSetItemStatus = viewModel::setItemStatus,
        onClearError = viewModel::clearError,
        onRequestAsset = onRequestAsset,
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
) {
    var brandEditor by remember { mutableStateOf<Brand?>(null) }
    var showNewBrand by remember { mutableStateOf(false) }
    var itemEditor by remember { mutableStateOf<CatalogItem?>(null) }
    var showNewItem by remember { mutableStateOf(false) }
    val selectedBrand = state.brandOverviews.firstOrNull { it.brand.id == state.selectedBrandId }?.brand

    Column(modifier = Modifier.fillMaxSize()) {
        Text("我的咖啡豆库", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
            Tab(state.tab == CatalogTab.CHAINS, { onSelectTab(CatalogTab.CHAINS) }, text = { Text("连锁品牌") })
            Tab(state.tab == CatalogTab.BEANS, { onSelectTab(CatalogTab.BEANS) }, text = { Text("我的豆子") })
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { showNewBrand = true }, enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.tab == CatalogTab.CHAINS) "新增连锁品牌" else "新增烘焙品牌") }

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
            onDismiss = { showNewBrand = false; brandEditor = null },
            onSave = { onSaveBrand(it); showNewBrand = false; brandEditor = null },
            onRequestAsset = onRequestAsset,
        )
    }
    if ((showNewItem || itemEditor != null) && selectedBrand != null) {
        ItemEditorDialog(
            initial = itemEditor,
            brand = selectedBrand,
            saving = state.saving,
            onDismiss = { showNewItem = false; itemEditor = null },
            onSave = { onSaveItem(it); showNewItem = false; itemEditor = null },
            onRequestAsset = onRequestAsset,
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
private fun BrandEditorDialog(
    initial: Brand?, type: BrandType, saving: Boolean,
    onDismiss: () -> Unit, onSave: (BrandEditor) -> Unit, onRequestAsset: CatalogAssetPicker,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var logoAssetId by remember(initial) { mutableStateOf(initial?.logoAssetId) }
    var sourceUrl by remember(initial) { mutableStateOf(initial?.publicSourceUrl.orEmpty()) }
    var mode by remember(initial) { mutableStateOf(initial?.maintenanceMode ?: MaintenanceMode.MANUAL_ONLY) }
    EditorDialog(
        title = if (initial == null) "新增品牌" else "编辑品牌", saving = saving, onDismiss = onDismiss,
        onSave = { onSave(BrandEditor(type, name, logoAssetId, mode, sourceUrl, initial?.id)) },
    ) {
        Field(name, { name = it }, "品牌名称")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { mode = MaintenanceMode.MANUAL_ONLY }, label = { Text("仅手工维护") })
            AssistChip(onClick = { mode = MaintenanceMode.PUBLIC_SOURCE }, label = { Text("公开网页更新") })
        }
        Field(sourceUrl, { sourceUrl = it }, "公开产品页（可选）")
        OutlinedButton(onClick = { onRequestAsset(logoAssetId, CatalogAssetKind.BRAND_LOGO) { logoAssetId = it } }) {
            Text(if (logoAssetId == null) "选择 Logo" else "更换 Logo")
        }
    }
}

@Composable
private fun ItemEditorDialog(
    initial: CatalogItem?, brand: Brand, saving: Boolean,
    onDismiss: () -> Unit, onSave: (ItemEditor) -> Unit, onRequestAsset: CatalogAssetPicker,
) {
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
    val type = if (brand.type == BrandType.CHAIN) ItemType.CHAIN_PRODUCT else ItemType.PERSONAL_BEAN
    EditorDialog(
        title = if (initial == null) "新增${if (type == ItemType.PERSONAL_BEAN) "豆子" else "产品"}" else "编辑条目",
        saving = saving, onDismiss = onDismiss,
        onSave = {
            onSave(
                ItemEditor(
                    brand.id, type, name, image, origin, processing, roast, flavors, brew, status,
                    caffeine.toDoubleOrNull(), description, purchaseDate, roastDate, sourceUrl, initial?.id,
                    category, specification,
                ),
            )
        },
    ) {
        Field(name, { name = it }, "名称")
        Field(origin, { origin = it }, "产地（可选）")
        Field(processing, { processing = it }, "处理法（可选）")
        Field(roast, { roast = it }, "烘焙度（可选）")
        Field(flavors, { flavors = it }, "风味描述（可选）")
        Field(brew, { brew = it }, "默认冲煮方式（可选）")
        if (type == ItemType.CHAIN_PRODUCT) {
            Field(category, { category = it }, "产品分类（可选）")
            Field(specification, { specification = it }, "规格描述（可选）")
            Field(caffeine, { caffeine = it }, "咖啡因 mg（可选）")
            Field(description, { description = it }, "官方描述（可选）")
            Field(sourceUrl, { sourceUrl = it }, "来源链接（可选）")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("当前状态")
                listOf(ItemStatus.ACTIVE, ItemStatus.NEEDS_IMAGE, ItemStatus.DISCONTINUED, ItemStatus.ARCHIVED)
                    .forEach { value ->
                        FilterChip(status == value, { status = value }, label = { Text(statusLabel(value)) })
                    }
            }
        } else {
            Field(description, { description = it }, "备注（可选）")
            Field(purchaseDate, { purchaseDate = it }, "购买日期 YYYY-MM-DD（可选）")
            Field(roastDate, { roastDate = it }, "烘焙日期 YYYY-MM-DD（可选）")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CatalogViewModel.BEAN_FILTERS.forEach { value ->
                    FilterChip(status == value, { status = value }, label = { Text(value.beanStatusLabel()) })
                }
            }
        }
        OutlinedButton(onClick = { onRequestAsset(image, CatalogAssetKind.ITEM_IMAGE) { image = it } }) {
            Text(if (image == null) "选择图片" else "更换图片")
        }
    }
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
private fun Field(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

private fun statusLabel(status: ItemStatus): String = when (status) {
    ItemStatus.ACTIVE -> "在售"
    ItemStatus.NEEDS_IMAGE -> "待补充图片"
    ItemStatus.DISCONTINUED -> "已下架"
    ItemStatus.ARCHIVED -> "归档"
}

private fun formatCatalogTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(epochMillis))
