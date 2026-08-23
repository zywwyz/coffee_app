package com.niumi.coffeejournal.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.annotation.DrawableRes
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.catalog.bundledBrandLogoRes
import com.niumi.coffeejournal.catalog.ManualProductEditorDialog
import com.niumi.coffeejournal.catalog.ManualProductEditorEvent
import com.niumi.coffeejournal.catalog.ManualProductEditorViewModel
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.image.ImageStore
import com.niumi.coffeejournal.core.image.LocalAssetImage
import com.niumi.coffeejournal.core.image.CompleteImageContentScale
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.AssetImportRequester
import com.niumi.coffeejournal.TestTags
import java.util.Calendar

internal val CalendarForestGreen = Color(0xFF1F4D3A)
internal val CalendarWarmIvory = Color(0xFFFFF8E8)
internal val RecordButtonContainerColor = SemanticsPropertyKey<Color>("RecordButtonContainerColor")
internal val RecordButtonContentColor = SemanticsPropertyKey<Color>("RecordButtonContentColor")

@Composable
fun JournalFeature(
    journalRepository: JournalRepository,
    catalogRepository: CatalogRepository,
    imagePathResolver: ImagePathResolver,
    assetImportRequester: AssetImportRequester = { _, _, _ -> },
    imageStore: ImageStore? = null,
    calendarDisplayPreference: CalendarDisplayPreference = DefaultCalendarDisplayPreference,
    onOpenSettings: () -> Unit = {},
) {
    val today = remember { Calendar.getInstance() }
    val journalViewModel: JournalViewModel = viewModel(
        factory = JournalViewModel.factory(
            journalRepository,
            catalogRepository,
            today.get(Calendar.YEAR),
            today.get(Calendar.MONTH) + 1,
            imagePathResolver,
            calendarDisplayPreference,
        ),
    )
    val state by journalViewModel.uiState.collectAsStateWithLifecycle()
    val productEditor: ManualProductEditorViewModel = viewModel(
        key = "journal-manual-product-editor",
        factory = ManualProductEditorViewModel.factory(catalogRepository, imageStore),
    )
    var editorOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.saveCompletedToken) {
        if (state.saveCompletedToken > 0) editorOpen = false
    }
    LaunchedEffect(productEditor) {
        productEditor.events.collect { event ->
            if (event is ManualProductEditorEvent.Saved) {
                journalViewModel.selectItem(ItemType.CHAIN_PRODUCT, event.itemId) { selected ->
                    if (selected) productEditor.completeSaved() else productEditor.selectionFailed()
                }
            }
        }
    }

    if (editorOpen) {
        RecordDrinkScreen(
            state = state.editor,
            brands = state.brands,
            items = state.items,
            onSourceTypeChange = journalViewModel::setSourceType,
            onBrandSelect = journalViewModel::selectBrand,
            onItemSelect = { journalViewModel.selectItem(state.editor.sourceType, it) },
            onRatingChange = journalViewModel::setRating,
            onPriceChange = journalViewModel::setPriceInput,
            onBrewMethodChange = journalViewModel::setBrewMethod,
            onNoteChange = journalViewModel::setNote,
            onConsumedAtChange = journalViewModel::setConsumedAt,
            onSave = journalViewModel::save,
            onDiscardDraft = journalViewModel::discardDraft,
            onBack = { editorOpen = false },
            onSelectImage = {
                val previousAssetId = state.items.firstOrNull { it.id == state.editor.selectedItemId }?.imageAssetId
                assetImportRequester(ImageKind.PRODUCT, previousAssetId) { selection ->
                    journalViewModel.attachImportedImage(selection.assetId, null)
                }
            },
            onSkipImage = journalViewModel::skipImagePrompt,
            onAddProduct = {
                state.brands.firstOrNull { it.id == state.editor.selectedBrandId }?.let(productEditor::openNew)
            },
        )
        ManualProductEditorDialog(productEditor, assetImportRequester, imagePathResolver)
    } else {
        JournalScreen(
            state = state,
            onPreviousMonth = journalViewModel::previousMonth,
            onNextMonth = journalViewModel::nextMonth,
            onDayClick = journalViewModel::selectDate,
            onRecordDrink = { editorOpen = true },
            onEditRecord = { recordId ->
                journalViewModel.selectDate(null)
                journalViewModel.editRecord(recordId)
                editorOpen = true
            },
            onDeleteRecord = journalViewModel::deleteRecord,
            onOpenSettings = onOpenSettings,
            onCalendarDisplayModeChange = journalViewModel::setCalendarDisplayMode,
        )
    }
}

@Composable
fun JournalScreen(
    state: JournalUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (String?) -> Unit,
    onRecordDrink: () -> Unit,
    onEditRecord: (String) -> Unit = {},
    onDeleteRecord: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onCalendarDisplayModeChange: (CalendarDisplayMode) -> Unit = {},
) {
    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("咖啡日历", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(TestTags.RootScreenTitle))
                TextButton(onClick = onOpenSettings, modifier = Modifier.testTag(TestTags.RootScreenSettings)) { Text("设置") }
            }
        },
        floatingActionButton = {
            Button(
                onClick = onRecordDrink,
                modifier = Modifier
                    .testTag(TestTags.RecordButton)
                    .semantics {
                        this[RecordButtonContainerColor] = CalendarForestGreen
                        this[RecordButtonContentColor] = Color.White
                    },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CalendarForestGreen, contentColor = Color.White),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text("记录一杯")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CalendarDisplayModeControl(state.calendarDisplayMode, onCalendarDisplayModeChange)
            MonthHeader(state.year, state.month, onPreviousMonth, onNextMonth)
            WeekdayHeader()
            Column(Modifier.fillMaxWidth().testTag(TestTags.Calendar)) {
                state.days.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            CalendarDay(
                                day = day,
                                onClick = { onDayClick(day.localDate) },
                                modifier = Modifier.weight(1f),
                                mode = state.calendarDisplayMode,
                            )
                        }
                    }
                }
            }
            MonthSummary(state.summary)
        }
    }

    if (state.selectedDate != null) {
        DayDetailDialog(
            localDate = state.selectedDate,
            records = state.selectedDayRecords,
            onDismiss = { onDayClick(null) },
            onEdit = onEditRecord,
            onDelete = onDeleteRecord,
        )
    }
}

@Composable
private fun CalendarDisplayModeControl(
    selectedMode: CalendarDisplayMode,
    onModeChange: (CalendarDisplayMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CalendarWarmIvory)
            .border(BorderStroke(1.dp, CalendarForestGreen), RoundedCornerShape(14.dp))
            .selectableGroup()
            .testTag(TestTags.CalendarModeIndicator),
    ) {
        listOf(CalendarDisplayMode.BRAND to "品牌", CalendarDisplayMode.COFFEE to "咖啡").forEach { (mode, label) ->
            val selected = selectedMode == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) CalendarForestGreen else CalendarWarmIvory)
                    .selectable(selected = selected, onClick = { onModeChange(mode) }, role = Role.RadioButton)
                    .testTag(
                        if (mode == CalendarDisplayMode.BRAND) TestTags.CalendarBrandDisplayMode
                        else TestTags.CalendarCoffeeDisplayMode,
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (selected) Color.White else CalendarForestGreen, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MonthHeader(year: Int, month: Int, previous: () -> Unit, next: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = previous,
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 52.dp).testTag(TestTags.PreviousMonth),
            colors = ButtonDefaults.buttonColors(containerColor = CalendarForestGreen, contentColor = Color.White),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        ) { Text("上一月", style = MaterialTheme.typography.labelLarge, maxLines = 2) }
        Box(Modifier.weight(1.15f), contentAlignment = Alignment.Center) {
            Text("${year}年${month}月", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
        }
        Button(
            onClick = next,
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 52.dp).testTag(TestTags.NextMonth),
            colors = ButtonDefaults.buttonColors(containerColor = CalendarForestGreen, contentColor = Color.White),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        ) { Text("下一月", style = MaterialTheme.typography.labelLarge, maxLines = 2) }
    }
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth()) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach {
            Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CalendarDay(
    day: CalendarDayUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mode: CalendarDisplayMode,
) {
    Box(
        modifier = modifier
            .aspectRatio(0.82f)
            .padding(2.dp)
            .alpha(if (day.inDisplayedMonth) 1f else 0.38f)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "日期 ${day.localDate}" }
            .testTag(TestTags.CalendarDayPrefix + day.localDate)
            .padding(4.dp),
    ) {
        if (day.drinkCount == 0) {
            Text(
                day.dayNumber.toString(),
                modifier = Modifier.align(Alignment.Center).testTag(TestTags.CalendarDayNumberPrefix + day.localDate),
            )
        } else {
            val media = selectCalendarMedia(mode, day.imagePath, day.brandLogoPath, day.brandName)
            LocalAssetImage(
                primaryPath = media.primaryPath,
                fallbackPath = media.fallbackPath,
                contentDescription = "咖啡图片",
                contentScale = CompleteImageContentScale,
                fallbackPainter = media.bundledLogoRes?.let { painterResource(it) },
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(6.dp)
                    .testTag(TestTags.CalendarImagePrefix + day.localDate),
            )
            if (day.drinkCount > 1) {
                Text(
                    "×${day.drinkCount}",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        .testTag(TestTags.CalendarCountBadgePrefix + day.localDate),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

internal enum class CalendarMediaFallback {
    BUNDLED_LOGO,
    CUSTOM_LOGO,
    PLACEHOLDER,
}

internal data class CalendarMedia(
    val primaryPath: String?,
    val fallbackPath: String?,
    @DrawableRes val bundledLogoRes: Int?,
    val fallback: CalendarMediaFallback,
)

internal fun selectCalendarMedia(
    mode: CalendarDisplayMode,
    imagePath: String?,
    brandLogoPath: String?,
    brandName: String?,
): CalendarMedia {
    val bundledLogo = bundledBrandLogoRes(brandName)
    val fallback = when {
        bundledLogo != null -> CalendarMediaFallback.BUNDLED_LOGO
        brandLogoPath != null -> CalendarMediaFallback.CUSTOM_LOGO
        else -> CalendarMediaFallback.PLACEHOLDER
    }
    return when (mode) {
        CalendarDisplayMode.BRAND -> CalendarMedia(
            primaryPath = brandLogoPath.takeIf { bundledLogo == null },
            fallbackPath = null,
            bundledLogoRes = bundledLogo,
            fallback = fallback,
        )
        CalendarDisplayMode.COFFEE -> CalendarMedia(
            primaryPath = imagePath,
            fallbackPath = brandLogoPath.takeIf { bundledLogo == null },
            bundledLogoRes = bundledLogo,
            fallback = fallback,
        )
    }
}

@Composable
private fun MonthSummary(summary: MonthSummaryUi) {
    val rating = summary.averageRatingStars?.let { "%.2f 星".format(it) } ?: "暂无评分"
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 76.dp).testTag(TestTags.MonthSummaryCard),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFFF3E7CF)),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${summary.cupCount} 杯", color = Color(0xFF1F4D3A), fontWeight = FontWeight.Bold)
            Text(
                "¥${summary.totalSpendFen / 100}.${(summary.totalSpendFen % 100).toString().padStart(2, '0')}",
                modifier = Modifier.testTag(TestTags.MonthlySpend),
                color = Color(0xFF1F4D3A),
            )
            Text(rating, color = Color(0xFF1F4D3A))
        }
    }
}

@Composable
private fun DayDetailDialog(
    localDate: String,
    records: List<DrinkRecord>,
    onDismiss: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var deleteCandidate by remember { mutableStateOf<DrinkRecord?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localDate) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (records.isEmpty()) Text("当天没有咖啡记录")
                records.forEach { record ->
                    Column {
                        Text("${record.snapshot.brandName} · ${record.snapshot.itemName}", fontWeight = FontWeight.Bold)
                        Text(
                            listOfNotNull(
                                record.brewMethod,
                                record.ratingHalfStars?.let { "${it / 2.0} 星" },
                                record.actualPriceFen?.let { "¥${it / 100}.${(it % 100).toString().padStart(2, '0')}" },
                            ).joinToString(" · "),
                        )
                        record.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row {
                            TextButton(onClick = { onEdit(record.id) }) { Text("编辑") }
                            TextButton(onClick = { deleteCandidate = record }) { Text("删除") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
    deleteCandidate?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("删除这条记录？") },
            text = { Text("${record.snapshot.brandName} · ${record.snapshot.itemName} 将从日历和总结中移除，产品图片不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(record.id)
                    deleteCandidate = null
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("取消") } },
        )
    }
}
