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
import com.niumi.coffeejournal.core.image.CalendarThumbnailLoader

internal val CompleteImageContentScale = ContentScale.Fit

@Composable
fun LocalAssetImage(primaryPath: String?, fallbackPath: String?, contentDescription: String, contentScale: ContentScale = ContentScale.Crop, modifier: Modifier = Modifier, fallbackPainter: Painter? = null) {
    val loader = androidx.compose.runtime.remember { CalendarThumbnailLoader() }
    val bitmap by produceState<ImageBitmap?>(null, primaryPath, fallbackPath, fallbackPainter) {
        value = loader.load(primaryPath) ?: loader.load(fallbackPath)
    }
    when {
        bitmap != null -> Image(bitmap!!, contentDescription, modifier = modifier, contentScale = contentScale)
        fallbackPainter != null -> Image(fallbackPainter, contentDescription, modifier = modifier, contentScale = contentScale)
        else -> Box(
            modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center,
        ) { Text("☕") }
    }
}

@Composable
fun ResolvedLocalAssetImage(primaryAssetId: String?, fallbackAssetId: String?, resolver: ImagePathResolver, contentDescription: String, contentScale: ContentScale = ContentScale.Crop, modifier: Modifier = Modifier) {
    val paths by produceState<Pair<String?, String?>?>(null, primaryAssetId, fallbackAssetId, resolver) {
        value = resolver.resolve(primaryAssetId) to resolver.resolve(fallbackAssetId)
    }
    LocalAssetImage(paths?.first, paths?.second, contentDescription, contentScale, modifier)
}
