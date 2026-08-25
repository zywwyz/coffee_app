package com.niumi.coffeejournal.core.image

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.niumi.coffeejournal.core.image.CalendarThumbnailLoader

internal val CompleteImageContentScale = ContentScale.Fit

@Composable
fun LocalAssetImage(primaryPath: String?, fallbackPath: String?, contentDescription: String, contentScale: ContentScale = ContentScale.Crop, modifier: Modifier = Modifier, fallbackPainter: Painter? = null) {
    val loader = androidx.compose.runtime.remember { CalendarThumbnailLoader() }
    val loadState by produceState<LocalImageLoadState>(LocalImageLoadState.Loading, primaryPath, fallbackPath) {
        value = loader.load(primaryPath)?.let { LocalImageLoadState.Loaded(it, LocalImageSource.PRIMARY) }
            ?: loader.load(fallbackPath)?.let { LocalImageLoadState.Loaded(it, LocalImageSource.FALLBACK) }
            ?: LocalImageLoadState.Missing
    }
    val currentLoadState = loadState
    val imageModifier = modifier.semantics {
        this.contentDescription = contentDescription
        stateDescription = when {
            currentLoadState is LocalImageLoadState.Loaded && currentLoadState.source == LocalImageSource.PRIMARY -> "主图片已加载"
            currentLoadState is LocalImageLoadState.Loaded -> "备用图片已加载"
            fallbackPainter != null -> "品牌图片"
            currentLoadState is LocalImageLoadState.Missing -> "图片占位"
            else -> "图片加载中"
        }
    }
    when {
        currentLoadState is LocalImageLoadState.Loaded -> Image(currentLoadState.bitmap, null, modifier = imageModifier, contentScale = contentScale)
        fallbackPainter != null -> Image(fallbackPainter, null, modifier = imageModifier, contentScale = contentScale)
        else -> Box(
            imageModifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center,
        ) { Text("☕") }
    }
}

private enum class LocalImageSource { PRIMARY, FALLBACK }

private sealed interface LocalImageLoadState {
    data object Loading : LocalImageLoadState
    data object Missing : LocalImageLoadState
    data class Loaded(val bitmap: ImageBitmap, val source: LocalImageSource) : LocalImageLoadState
}

@Composable
fun ResolvedLocalAssetImage(primaryAssetId: String?, fallbackAssetId: String?, resolver: ImagePathResolver, contentDescription: String, contentScale: ContentScale = ContentScale.Crop, modifier: Modifier = Modifier, fallbackPainter: Painter? = null) {
    val paths by produceState<Pair<String?, String?>?>(null, primaryAssetId, fallbackAssetId, resolver) {
        value = resolver.resolve(primaryAssetId) to resolver.resolve(fallbackAssetId)
    }
    LocalAssetImage(paths?.first, paths?.second, contentDescription, contentScale, modifier, fallbackPainter)
}
