package com.niumi.coffeejournal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A restrained paper grain, deliberately kept behind text and photo surfaces. */
fun Modifier.scrapbookPaper() = background(CoffeeVisuals.cream).drawBehind {
    val step = 18.dp.toPx()
    val radius = 0.7.dp.toPx()
    val dot = CoffeeVisuals.warmOutline.copy(alpha = 0.36f)
    var x = step / 2
    while (x < size.width) {
        var y = step / 2
        while (y < size.height) {
            drawCircle(dot, radius, center = androidx.compose.ui.geometry.Offset(x, y))
            y += step
        }
        x += step
    }
}

@Composable
fun ScrapbookKicker(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.labelSmall,
        color = CoffeeVisuals.secondaryText, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
}

@Composable
fun ScrapbookNote(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.background(CoffeeVisuals.white, RoundedCornerShape(CoffeeVisuals.cornerMedium))) {
        Column(Modifier.padding(14.dp).padding(top = 6.dp), content = content)
        Box(Modifier.align(Alignment.TopCenter).offset(y = (-4).dp).width(54.dp).height(10.dp)
            .graphicsLayer { rotationZ = -4f }.background(CoffeeVisuals.peach))
    }
}
