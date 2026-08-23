package com.niumi.coffeejournal.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.niumi.coffeejournal.TestTags
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.image.ResolvedLocalAssetImage
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ChainProductKind

@Composable
fun BrandProductsScreen(brand: Brand, items: List<CatalogItem>, imagePathResolver: ImagePathResolver, onBack: () -> Unit, onEditBrand: () -> Unit, onAddProduct: () -> Unit, onEditProduct: (CatalogItem) -> Unit, onDeleteBrand: () -> Unit = {}, onDeleteProduct: (CatalogItem) -> Unit = {}) {
    var filter by remember { mutableStateOf<ChainProductKind?>(null) }
    var deletingBrand by remember { mutableStateOf(false) }
    var deletingProduct by remember { mutableStateOf<CatalogItem?>(null) }
    val custom = brand.id !in BUNDLED_CHAIN_BRANDS.map { it.brand.id }
    val kinds = listOf(ChainProductKind.BLACK, ChainProductKind.FRUIT, ChainProductKind.MILK)
    val shown = items.filter { filter == null || it.chainProductKind == filter }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("返回") }
        Text(brand.name, style = MaterialTheme.typography.headlineSmall)
        if (custom) {
            TextButton(onClick = onEditBrand) { Text("编辑品牌") }
            TextButton(onClick = { deletingBrand = true }) { Text("删除品牌") }
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(filter == null, { filter = null }, label = { Text("全部") })
            kinds.forEach { kind -> FilterChip(filter == kind, { filter = kind }, label = { Text(publicKindLabel(kind)) }) }
            if (items.any { it.chainProductKind == ChainProductKind.PENDING }) FilterChip(filter == ChainProductKind.PENDING, { filter = ChainProductKind.PENDING }, label = { Text("待分类") })
        }
        if (shown.isEmpty()) Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) { Text("暂无产品"); Button(onClick = onAddProduct) { Text("新增产品") } }
        else LazyVerticalGrid(GridCells.Fixed(2), modifier = Modifier.weight(1f).testTag(TestTags.BrandProductGrid), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { item -> Column(Modifier.testTag(TestTags.BrandProductCardPrefix + item.id).clickable { onEditProduct(item) }) {
                val bundled = BUNDLED_CHAIN_BRANDS.firstOrNull { it.brand.id == brand.id }
                CatalogMediaFrame(Modifier.testTag(TestTags.BrandProductMediaFramePrefix + item.id)) {
                    if (item.imageAssetId == null && bundled != null) Image(painterResource(bundled.logoRes), "${item.name} 图片", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else ResolvedLocalAssetImage(item.imageAssetId, brand.logoAssetId, imagePathResolver, "${item.name} 图片", ContentScale.Fit, Modifier.fillMaxSize())
                }
                Text(item.name, maxLines = 1); Text(item.chainProductKind?.let(::publicKindLabel) ?: "待分类")
                if (custom) TextButton(onClick = { deletingProduct = item }) { Text("删除") }
            } }
        }
        Button(onClick = onAddProduct, modifier = Modifier.fillMaxWidth()) { Text("新增产品") }
    }
    if (deletingBrand) AlertDialog(onDismissRequest = { deletingBrand = false }, title = { Text("删除品牌") }, text = { Text("确认删除该品牌吗？") }, dismissButton = { TextButton(onClick = { deletingBrand = false }) { Text("取消") } }, confirmButton = { TextButton(onClick = { deletingBrand = false; onDeleteBrand() }) { Text("确认删除") } })
    deletingProduct?.let { item -> AlertDialog(onDismissRequest = { deletingProduct = null }, title = { Text("删除产品") }, text = { Text("确认删除 ${item.name} 吗？历史记录不会受影响。") }, dismissButton = { TextButton(onClick = { deletingProduct = null }) { Text("取消") } }, confirmButton = { TextButton(onClick = { deletingProduct = null; onDeleteProduct(item) }) { Text("确认删除") } }) }
}
