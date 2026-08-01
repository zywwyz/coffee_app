package com.niumi.coffeejournal.journal

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemType

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
    onSave: () -> Unit,
    onBack: () -> Unit,
    onScreenshot: () -> Unit,
    onSelectImage: () -> Unit,
    onSkipImage: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack, enabled = !state.saving) { Text("返回") }
            Text("记录一杯", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
        Text("评分（支持半星）", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..10).forEach { halfStars ->
                FilterChip(
                    selected = state.ratingHalfStars == halfStars,
                    enabled = !state.saving,
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
            enabled = !state.saving,
        )
        OutlinedTextField(
            value = state.brewMethod,
            onValueChange = onBrewMethodChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("冲煮方式") },
            enabled = !state.saving,
        )
        OutlinedTextField(
            value = state.note,
            onValueChange = onNoteChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("备注（可选）") },
            minLines = 3,
            enabled = !state.saving,
        )
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = onSave,
            enabled = state.selectedItemId != null && state.priceValid && !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.saving) "保存中…" else "保存记录") }
    }

    if (state.needsImagePrompt) {
        MissingImageDialog(!state.saving, onScreenshot, onSelectImage, onSkipImage)
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
        onDismissRequest = onSkip,
        title = { Text("为产品补充图片") },
        text = { Text("官网图片未能下载。可以上传原始屏幕截图，稍后由本机裁剪；也可以选择已裁好的图片，或先使用品牌 Logo。") },
        confirmButton = {
            Column {
                Button(onClick = onScreenshot, enabled = enabled) { Text("上传完整截图") }
                OutlinedButton(onClick = onSelectImage, enabled = enabled) { Text("选择图片") }
            }
        },
        dismissButton = { TextButton(onClick = onSkip, enabled = enabled) { Text("暂时跳过") } },
    )
}
