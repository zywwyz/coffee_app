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
import androidx.compose.ui.layout.ContentScale
import com.niumi.coffeejournal.core.image.CalendarThumbnailLoader

@Composable
fun LocalAssetImage(primaryPath: String?, fallbackPath: String?, contentDescription: String, contentScale: ContentScale = ContentScale.Crop, modifier: Modifier = Modifier) {
    val loader = androidx.compose.runtime.remember { CalendarThumbnailLoader() }
    val bitmap by produceState<ImageBitmap?>(null, primaryPath, fallbackPath) {
        value = loader.load(primaryPath) ?: loader.load(fallbackPath)
    }
    bitmap?.let { Image(it, contentDescription, modifier = modifier, contentScale = contentScale) } ?: Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center,
    ) { Text("☕") }
}

@Composable
fun ResolvedLocalAssetImage(primaryAssetId: String?, fallbackAssetId: String?, resolver: ImagePathResolver, contentDescription: String, contentScale: ContentScale = ContentScale.Crop, modifier: Modifier = Modifier) {
    val paths by produceState<Pair<String?, String?>?>(null, primaryAssetId, fallbackAssetId, resolver) {
        value = resolver.resolve(primaryAssetId) to resolver.resolve(fallbackAssetId)
    }
    LocalAssetImage(paths?.first, paths?.second, contentDescription, contentScale, modifier)
}
