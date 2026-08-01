package com.niumi.coffeejournal.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.core.model.DrinkRecord
import java.util.Calendar

@Composable
fun JournalFeature(
    journalRepository: JournalRepository,
    catalogRepository: CatalogRepository,
    onScreenshotRequested: () -> Unit = {},
    onImageRequested: () -> Unit = {},
) {
    val today = remember { Calendar.getInstance() }
    val journalViewModel: JournalViewModel = viewModel(
        factory = JournalViewModel.factory(
            journalRepository,
            catalogRepository,
            today.get(Calendar.YEAR),
            today.get(Calendar.MONTH) + 1,
        ),
    )
    val state by journalViewModel.uiState.collectAsStateWithLifecycle()
    var editorOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.saveCompletedToken) {
        if (state.saveCompletedToken > 0) editorOpen = false
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
            onSave = journalViewModel::save,
            onBack = { editorOpen = false },
            onScreenshot = onScreenshotRequested,
            onSelectImage = onImageRequested,
            onSkipImage = journalViewModel::skipImagePrompt,
        )
    } else {
        JournalScreen(
            state = state,
            onPreviousMonth = journalViewModel::previousMonth,
            onNextMonth = journalViewModel::nextMonth,
            onDayClick = journalViewModel::selectDate,
            onRecordDrink = { editorOpen = true },
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
) {
    Scaffold(
        floatingActionButton = { Button(onClick = onRecordDrink) { Text("记录一杯") } },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MonthHeader(state.year, state.month, onPreviousMonth, onNextMonth)
            WeekdayHeader()
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier.fillMaxWidth().weight(1f),
                userScrollEnabled = false,
            ) {
                items(state.days, key = { it.localDate }) { day ->
                    CalendarDay(day = day, onClick = { onDayClick(day.localDate) })
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
        )
    }
}

@Composable
private fun MonthHeader(year: Int, month: Int, previous: () -> Unit, next: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = previous) { Text("上月") }
        Text("${year}年${month}月", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        TextButton(onClick = next) { Text("下月") }
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
private fun CalendarDay(day: CalendarDayUi, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(0.82f)
            .padding(2.dp)
            .alpha(if (day.inDisplayedMonth) 1f else 0.38f)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        if (day.drinkCount == 0) {
            Text(day.dayNumber.toString(), modifier = Modifier.align(Alignment.Center))
        } else {
            Text(
                text = "☕",
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { contentDescription = "咖啡图片 ${day.imagePath}" },
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(day.dayNumber.toString(), style = MaterialTheme.typography.labelSmall)
            if (day.drinkCount > 1) {
                Text(
                    "×${day.drinkCount}",
                    modifier = Modifier.align(Alignment.BottomEnd),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun MonthSummary(summary: MonthSummaryUi) {
    val rating = summary.averageRatingStars?.let { "%.2f 星".format(it) } ?: "暂无评分"
    Card(Modifier.fillMaxWidth().padding(bottom = 76.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${summary.cupCount} 杯")
            Text("¥${summary.totalSpendFen / 100}.${(summary.totalSpendFen % 100).toString().padStart(2, '0')}")
            Text(rating)
        }
    }
}

@Composable
private fun DayDetailDialog(localDate: String, records: List<DrinkRecord>, onDismiss: () -> Unit) {
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
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
