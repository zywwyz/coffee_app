package com.niumi.coffeejournal.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.niumi.coffeejournal.catalog.bundledBrandLogoRes
import com.niumi.coffeejournal.core.image.CompleteImageContentScale
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.image.ResolvedLocalAssetImage
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.ui.CoffeeVisuals

@Composable
internal fun DayPhotoDetailDialog(
    localDate: String,
    records: List<DrinkRecord>,
    onDismiss: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onChangePhoto: (DrinkRecord) -> Unit,
    imagePathResolver: ImagePathResolver,
    recordDetailText: (DrinkRecord) -> String? = { null },
) {
    var deleteCandidate by remember { mutableStateOf<DrinkRecord?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CoffeeVisuals.cream,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(localDate, color = CoffeeVisuals.darkCoffee, style = MaterialTheme.typography.titleLarge)
                Text("当天 ${records.size} 杯", color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.labelMedium)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("day-photo-records"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (records.isEmpty()) Text("当天没有咖啡记录")
                records.forEach { record ->
                    DayPhotoRecordCard(record, onEdit, { deleteCandidate = record }, onChangePhoto, imagePathResolver, recordDetailText(record))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = CoffeeVisuals.forest) } },
    )
    deleteCandidate?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("删除这条记录？") },
            text = { Text("${record.snapshot.brandName} · ${record.snapshot.itemName} 将从日历和总结中移除，产品图片不会被删除。") },
            confirmButton = { TextButton(onClick = { onDelete(record.id); deleteCandidate = null }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("取消") } },
        )
    }
}

/** Kept independent of the dialog window for previews and accessibility checks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DayPhotoRecordCard(
    record: DrinkRecord,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    onChangePhoto: (DrinkRecord) -> Unit,
    imagePathResolver: ImagePathResolver,
    detailText: String? = null,
) {
    Card(
        Modifier.fillMaxWidth().testTag("day-photo-card-${record.id}"),
        shape = RoundedCornerShape(CoffeeVisuals.cornerMedium),
        colors = CardDefaults.cardColors(containerColor = CoffeeVisuals.white),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ResolvedLocalAssetImage(
                primaryAssetId = record.snapshot.imageAssetId,
                fallbackAssetId = record.snapshot.brandLogoAssetId,
                resolver = imagePathResolver,
                contentDescription = "当天实拍图 ${record.snapshot.itemName}",
                contentScale = CompleteImageContentScale,
                modifier = Modifier.fillMaxWidth().height(190.dp)
                    .clip(RoundedCornerShape(CoffeeVisuals.cornerSmall))
                    .background(CoffeeVisuals.cream).padding(12.dp).testTag("day-photo-image-${record.id}"),
                fallbackPainter = bundledBrandLogoRes(record.snapshot.brandName)?.let { painterResource(it) },
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(record.snapshot.brandName, color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.labelMedium)
                Text(record.snapshot.itemName, color = CoffeeVisuals.darkCoffee, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("评分", color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.labelSmall)
                    Text(record.ratingHalfStars?.let { "${it / 2.0} 星" } ?: "未评分", color = CoffeeVisuals.forest, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text("本次花费", color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.labelSmall)
                    Text(record.actualPriceFen?.let { "¥${it / 100}.${(it % 100).toString().padStart(2, '0')}" } ?: "未记录", color = CoffeeVisuals.darkCoffee, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            (detailText ?: record.brewMethod)?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            record.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.bodyMedium)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onChangePhoto(record) }) { Text(if (record.snapshot.imageAssetId == null) "添加图片" else "更换图片", color = CoffeeVisuals.forest) }
                TextButton(onClick = { onEdit(record.id) }) { Text("编辑", color = CoffeeVisuals.forest) }
                TextButton(onClick = onDelete) { Text("删除", color = CoffeeVisuals.secondaryText) }
            }
        }
    }
}
