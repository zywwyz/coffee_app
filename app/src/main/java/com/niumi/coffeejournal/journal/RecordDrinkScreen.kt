package com.niumi.coffeejournal.journal

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.PersonalProductType
import com.niumi.coffeejournal.core.model.PriceMode
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.TestTags
import com.niumi.coffeejournal.ui.CoffeeVisuals
import com.niumi.coffeejournal.ui.ScrapbookKicker
import com.niumi.coffeejournal.ui.scrapbookPaper
import java.util.Calendar
import java.util.Locale

internal val RecordEditorSurfaceColor = SemanticsPropertyKey<Color>("RecordEditorSurfaceColor")
internal val RecordEditorSectionColor = SemanticsPropertyKey<Color>("RecordEditorSectionColor")
internal val RecordSaveContainerColor = SemanticsPropertyKey<Color>("RecordSaveContainerColor")

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
    onDiscardDraft: () -> Unit = {},
    onBack: () -> Unit,
    onSelectImage: () -> Unit,
    onSkipImage: () -> Unit,
    onAddProduct: () -> Unit = {},
    onConsumedWeightChange: (String) -> Unit = {},
    onConsumedCountChange: (String) -> Unit = {},
    onRestoreAutomaticPrice: () -> Unit = {},
) {
    val editorBusy = state.saving || state.selecting || state.attachingImage
    val hasDraft = state.selectedItemId != null || state.invalidItem || state.editingRecordId != null
    val context = LocalContext.current
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler(enabled = !confirmDiscard) { if (!editorBusy) onBack() }
    val selectedTime = Calendar.getInstance().apply { timeInMillis = state.consumedAtEpochMillis }
    Column(
        modifier = Modifier.fillMaxSize().scrapbookPaper().testTag(TestTags.RecordEditorScroll)
            .semantics { this[RecordEditorSurfaceColor] = CoffeeVisuals.cream }
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack, enabled = !editorBusy) { Text("返回") }
            Column { ScrapbookKicker("COFFEE NOTES"); Text(if (state.editingRecordId == null) "记录一杯" else "修改记录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
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
        if (state.sourceType == ItemType.CHAIN_PRODUCT && state.selectedBrandId != null) {
            OutlinedButton(onClick = onAddProduct, enabled = !editorBusy) { Text("添加新产品") }
        }
        EditorSection("date") {
            Text("饮用日期", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !editorBusy && hasDraft,
                onClick = {
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            onConsumedAtChange(localNoonEpoch("%04d-%02d-%02d".format(Locale.ROOT, year, month + 1, day)))
                        },
                        selectedTime.get(Calendar.YEAR),
                        selectedTime.get(Calendar.MONTH),
                        selectedTime.get(Calendar.DAY_OF_MONTH),
                    ).show()
                },
            ) { Text(localDateForEpoch(state.consumedAtEpochMillis)) }
            }
        }
        EditorSection("rating") {
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
        }
        if (state.sourceType == ItemType.PERSONAL_BEAN && hasDraft) {
            EditorSection("usage") {
                Text(if (state.personalProductType == PersonalProductType.BEANS) "咖啡豆用量" else "挂耳咖啡用量", style = MaterialTheme.typography.titleMedium)
                if (state.personalProductType == PersonalProductType.BEANS) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("手冲", "意式").forEach { method -> FilterChip(state.brewMethod == method, { onBrewMethodChange(method) }, label = { Text(method) }, enabled = !editorBusy) }
                    }
                    OutlinedTextField(state.consumedWeightInput, onConsumedWeightChange, label = { Text("本次用豆（克，可选）") }, modifier = Modifier.fillMaxWidth(), isError = !state.quantityValid, supportingText = { if (!state.quantityValid) Text("请输入正数，最多三位小数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !editorBusy)
                } else {
                    OutlinedTextField(state.consumedCountInput, onConsumedCountChange, label = { Text("本次饮用（包）") }, modifier = Modifier.fillMaxWidth(), isError = !state.quantityValid, supportingText = { if (!state.quantityValid) Text("请输入正整数包数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !editorBusy)
                }
            }
        }
        OutlinedTextField(
            value = state.priceInput,
            onValueChange = onPriceChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (state.sourceType == ItemType.PERSONAL_BEAN) "本次费用（元）" else "实际支付（元）") },
            isError = !state.priceValid,
            supportingText = { if (!state.priceValid) Text("请输入最多两位小数的非负金额") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            enabled = !editorBusy,
        )
        if (state.sourceType == ItemType.PERSONAL_BEAN && hasDraft) {
            Text(if (state.priceMode == PriceMode.MANUAL) "已手动填写，本次用量变化不会覆盖金额" else state.autoPriceMessage.orEmpty(), color = CoffeeVisuals.secondaryText)
            if (state.priceMode == PriceMode.MANUAL) TextButton(onClick = onRestoreAutomaticPrice, enabled = !editorBusy && state.quantityValid) { Text("恢复自动计算") }
        }
        if (state.sourceType == ItemType.CHAIN_PRODUCT) OutlinedTextField(
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
        if (hasDraft) {
            OutlinedButton(
                onClick = { confirmDiscard = true },
                enabled = !editorBusy,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.DiscardDraft),
            ) { Text("放弃草稿并新建") }
        }
        Button(
            onClick = onSave,
            enabled = state.selectedItemId != null && state.priceValid && state.quantityValid && !(state.priceMode == PriceMode.AUTO && state.autoPriceMessage == "金额超出支持范围，请改用手动金额") && !editorBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.ConfirmSave)
                .semantics { this[RecordSaveContainerColor] = CoffeeVisuals.forest },
            colors = ButtonDefaults.buttonColors(containerColor = CoffeeVisuals.forest, contentColor = CoffeeVisuals.white),
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
        MissingImageDialog(!editorBusy, onSelectImage, onSkipImage)
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃当前草稿？") },
            text = { Text("未保存的输入将被删除，已保存的记录不会受影响。") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscardDraft() }) { Text("放弃并新建") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
        )
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
private fun EditorSection(tag: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.RecordEditorSectionPrefix + tag)
            .semantics { this[RecordEditorSectionColor] = CoffeeVisuals.white },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(CoffeeVisuals.cornerMedium),
        colors = CardDefaults.cardColors(containerColor = CoffeeVisuals.white),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun MissingImageDialog(
    enabled: Boolean,
    onSelectImage: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(TestTags.MissingImagePrompt),
        onDismissRequest = onSkip,
        title = { Text("为产品补充图片") },
        text = { Text("当前产品没有图片。选择实拍图片，或使用品牌 Logo。") },
        confirmButton = {
            Column {
                Button(onClick = onSelectImage, enabled = enabled) { Text("选择实拍图片") }
            }
        },
        dismissButton = { TextButton(onClick = onSkip, enabled = enabled) { Text("暂时跳过") } },
    )
}
