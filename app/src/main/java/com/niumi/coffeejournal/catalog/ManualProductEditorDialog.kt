package com.niumi.coffeejournal.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.niumi.coffeejournal.TestTags
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.image.ThumbnailLoader
import com.niumi.coffeejournal.core.image.CalendarThumbnailLoader
import androidx.compose.runtime.remember
import com.niumi.coffeejournal.core.model.ChainProductKind
import com.niumi.coffeejournal.core.image.AssetImportRequester

@Composable
fun ManualProductEditorDialog(
    viewModel: ManualProductEditorViewModel,
    assetImportRequester: AssetImportRequester,
    imagePathResolver: ImagePathResolver = ImagePathResolver { null },
    thumbnailLoader: ThumbnailLoader = remember { CalendarThumbnailLoader() },
) {
    val state by viewModel.state.collectAsState()
    if (!state.open) return
    AlertDialog(
        onDismissRequest = { if (!state.saving) viewModel.dismiss() },
        title = { Text(if (state.editing == null) "新增产品" else "编辑产品") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(state.name, viewModel::setName, label = { Text("产品名称") }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().semantics { contentDescription = TestTags.ManualProductName })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ChainProductKind.BLACK to "黑咖", ChainProductKind.FRUIT to "果咖", ChainProductKind.MILK to "奶咖").forEach { (kind, label) ->
                        FilterChip(selected = state.kind == kind, onClick = { viewModel.setKind(kind) }, label = { Text(label) }, enabled = !state.saving)
                    }
                }
                val previewAssetId = state.imageAssetId ?: state.brand?.logoAssetId
                val bitmap by produceState<ImageBitmap?>(null, previewAssetId, thumbnailLoader) {
                    value = loadManualProductPreview(previewAssetId, imagePathResolver, thumbnailLoader)
                }
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).semantics { contentDescription = TestTags.ManualProductPreview }.padding(12.dp)) {
                    if (bitmap != null) Image(bitmap = bitmap!!, contentDescription = if (state.imageAssetId != null) "产品实拍图" else "品牌 Logo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth())
                    else Text(if (previewAssetId == null) "暂无图片" else "图片无法加载")
                }
                OutlinedButton(onClick = {
                    assetImportRequester(ImageKind.PRODUCT, state.imageAssetId) { selection -> viewModel.acceptImportedAsset(selection.assetId) }
                }, enabled = !state.saving) { Text(if (state.imageAssetId == null) "选择实拍图" else "更换实拍图") }
                if (state.imageAssetId != null) TextButton(onClick = viewModel::removePhoto, enabled = !state.saving) { Text("移除实拍图") }
                state.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (!state.saving) TextButton(onClick = viewModel::retrySelection) { Text("重试选中") }
                }
            }
        },
        confirmButton = { Button(onClick = viewModel::save, enabled = !state.saving) { Text(if (state.saving) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = viewModel::dismiss, enabled = !state.saving) { Text("取消") } },
    )
}

internal suspend fun loadManualProductPreview(
    assetId: String?,
    imagePathResolver: ImagePathResolver,
    thumbnailLoader: ThumbnailLoader,
) = thumbnailLoader.load(imagePathResolver.resolve(assetId))

internal fun publicKindLabel(kind: ChainProductKind): String = when (kind) {
    ChainProductKind.BLACK -> "黑咖"
    ChainProductKind.FRUIT -> "果咖"
    ChainProductKind.MILK -> "奶咖"
    ChainProductKind.PENDING -> "待分类"
}
