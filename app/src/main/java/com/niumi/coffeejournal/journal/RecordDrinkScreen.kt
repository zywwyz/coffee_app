package com.niumi.coffeejournal.journal

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.TestTags
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun RecordDrinkScreen(
    state: RecordEditorUi,
    brands: List<Brand>,
    items: List<CatalogItem>,
    onSourceTypeChange: (ItemType) -> Unit,
    onBrandSelect: (String) -> Unit,
    onItemSelect: (String) -> Unit,
    onRatingChange: (Int?) -> Unit,
    onPriceChange: (String) -> Unit,
    onBrewMethodChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onConsumedAtChange: (Long) -> Unit = {},
    onSave: () -> Unit,
    onBack: () -> Unit,
    onScreenshot: () -> Unit,
    onSelectImage: () -> Unit,
    onSkipImage: () -> Unit,
) {
    val editorBusy = state.saving || state.selecting || state.attachingImage
    val hasDraft = state.selectedItemId != null || state.invalidItem || state.editingRecordId != null
    val context = LocalContext.current
    val selectedTime = Calendar.getInstance().apply { timeInMillis = state.consumedAtEpochMillis }
    Column(
        modifier = Modifier.fillMaxSize().testTag(TestTags.RecordEditorScroll)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack, enabled = !editorBusy) { Text("返回") }
            Text(
                if (state.editingRecordId == null) "记录一杯" else "修改记录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.sourceType == ItemType.CHAIN_PRODUCT,
                enabled = !state.saving,
                onClick = { onSourceTypeChange(ItemType.CHAIN_PRODUCT) },
                label = { Text("连锁产品") },
            )
            FilterChip(
                selected = state.sourceType == ItemType.PERSONAL_BEAN,
                enabled = !state.saving,
                onClick = { onSourceTypeChange(ItemType.PERSONAL_BEAN) },
                label = { Text("个人豆子") },
            )
        }
        SelectionRow("选择品牌", brands, state.selectedBrandId, !state.saving, { it.id }, { it.name }, onBrandSelect)
        SelectionRow("选择产品", items, state.selectedItemId, !state.saving, { it.id }, { it.name }, onItemSelect)
        Text("饮用日期与时间", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !editorBusy && hasDraft,
                onClick = {
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            val changed = Calendar.getInstance().apply {
                                timeInMillis = state.consumedAtEpochMillis
                                set(year, month, day)
                            }
                            onConsumedAtChange(changed.timeInMillis)
                        },
                        selectedTime.get(Calendar.YEAR),
                        selectedTime.get(Calendar.MONTH),
                        selectedTime.get(Calendar.DAY_OF_MONTH),
                    ).show()
                },
            ) { Text(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(state.consumedAtEpochMillis))) }
            OutlinedButton(
                enabled = !editorBusy && hasDraft,
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            val changed = Calendar.getInstance().apply {
                                timeInMillis = state.consumedAtEpochMillis
                                set(Calendar.HOUR_OF_DAY, hour)
                                set(Calendar.MINUTE, minute)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            onConsumedAtChange(changed.timeInMillis)
                        },
                        selectedTime.get(Calendar.HOUR_OF_DAY),
                        selectedTime.get(Calendar.MINUTE),
                        true,
                    ).show()
                },
            ) { Text(SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(state.consumedAtEpochMillis))) }
        }
        Text("评分（支持半星）", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = state.ratingHalfStars == null,
                enabled = !editorBusy,
                onClick = { onRatingChange(null) },
                label = { Text("未评分") },
            )
            (1..10).forEach { halfStars ->
                FilterChip(
                    selected = state.ratingHalfStars == halfStars,
                    enabled = !editorBusy,
                    onClick = { onRatingChange(halfStars) },
                    label = { Text("${halfStars / 2.0}") },
                )
            }
        }
        OutlinedTextField(
            value = state.priceInput,
            onValueChange = onPriceChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("实际支付（元）") },
            isError = !state.priceValid,
            supportingText = { if (!state.priceValid) Text("请输入最多两位小数的非负金额") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            enabled = !editorBusy,
        )
        OutlinedTextField(
            value = state.brewMethod,
            onValueChange = onBrewMethodChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("冲煮方式") },
            enabled = !editorBusy,
        )
        OutlinedTextField(
            value = state.note,
            onValueChange = onNoteChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("备注（可选）") },
            minLines = 3,
            enabled = !editorBusy,
        )
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = onSave,
            enabled = state.selectedItemId != null && state.priceValid && !editorBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.ConfirmSave),
        ) {
            Text(
                when {
                    state.selecting -> "加载产品…"
                    state.attachingImage -> "正在关联图片…"
                    state.saving -> "保存中…"
                    else -> if (state.editingRecordId == null) "保存记录" else "保存修改"
                },
            )
        }
    }

    if (state.needsImagePrompt) {
        MissingImageDialog(!editorBusy, onScreenshot, onSelectImage, onSkipImage)
    }
}

@Composable
private fun <T> SelectionRow(
    title: String,
    values: List<T>,
    selectedId: String?,
    enabled: Boolean,
    id: (T) -> String,
    label: (T) -> String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (values.isEmpty()) Text("暂无可选项", style = MaterialTheme.typography.bodySmall)
            values.forEach { value ->
                FilterChip(
                    selected = selectedId == id(value),
                    enabled = enabled,
                    onClick = { onSelect(id(value)) },
                    label = { Text(label(value)) },
                )
            }
        }
    }
}

@Composable
private fun MissingImageDialog(
    enabled: Boolean,
    onScreenshot: () -> Unit,
    onSelectImage: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(TestTags.MissingImagePrompt),
        onDismissRequest = onSkip,
        title = { Text("为产品补充图片") },
        text = { Text("当前产品没有图片。可以上传原始屏幕截图，稍后由本机裁剪；也可以选择已裁好的图片，或先使用品牌 Logo。") },
        confirmButton = {
            Column {
                Button(onClick = onScreenshot, enabled = enabled) { Text("上传完整截图") }
                OutlinedButton(onClick = onSelectImage, enabled = enabled) { Text("选择图片") }
            }
        },
        dismissButton = { TextButton(onClick = onSkip, enabled = enabled) { Text("暂时跳过") } },
    )
}
