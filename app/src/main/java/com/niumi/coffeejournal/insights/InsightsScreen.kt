package com.niumi.coffeejournal.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.niumi.coffeejournal.TestTags
import com.niumi.coffeejournal.catalog.bundledBrandLogoRes
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.image.ResolvedLocalAssetImage
import com.niumi.coffeejournal.journal.Clock
import com.niumi.coffeejournal.journal.JournalRepository
import com.niumi.coffeejournal.journal.SystemClock
import com.niumi.coffeejournal.ui.CoffeeVisuals
import java.math.BigInteger
import java.util.Locale

internal val InsightsSurfaceColor = SemanticsPropertyKey<Color>("InsightsSurfaceColor")
internal val InsightsMetricCardColor = SemanticsPropertyKey<Color>("InsightsMetricCardColor")
private val donutColors = listOf(CoffeeVisuals.forest, CoffeeVisuals.peach, CoffeeVisuals.mint, CoffeeVisuals.warmOutline)
private val insightsCanvas = Color(0xFFFFFCF6)
private val sage = Color(0xFF72856A)

@Composable
fun InsightsFeature(
    repository: JournalRepository,
    clock: Clock = SystemClock,
    imagePathResolver: ImagePathResolver = ImagePathResolver { null },
    onOpenRecord: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val factory = remember(repository, clock) { InsightsViewModel.factory(JournalInsightsRepository(repository), clock) }
    val model: InsightsViewModel = viewModel(factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    InsightsScreen(state, model::showMonthly, model::showYearly, model::previousMonth, model::nextMonth,
        model::previousYear, model::nextYear, onOpenSettings, imagePathResolver, onOpenRecord)
}

@Composable
fun InsightsScreen(
    state: InsightsUiState, onShowMonthly: () -> Unit, onShowYearly: () -> Unit,
    onPreviousMonth: () -> Unit = {}, onNextMonth: () -> Unit = {}, onPreviousYear: () -> Unit = {}, onNextYear: () -> Unit = {},
    onOpenSettings: () -> Unit = {}, imagePathResolver: ImagePathResolver = ImagePathResolver { null }, onOpenRecord: (String) -> Unit = {},
) {
    val report = if (state.mode == InsightsMode.MONTHLY) state.monthly?.let { it.period to Dashboard(it.habit, it.coffeeTypeShares, it.brandShares, it.topBrands, it.topProducts, it.best, it.worst) }
    else state.yearly?.let { it.period to Dashboard(it.habit, it.coffeeTypeShares, it.brandShares, it.topBrands, it.topProducts, it.best, it.worst) }
    Column(Modifier.fillMaxSize().background(insightsCanvas).testTag(TestTags.InsightsSurface)
        .semantics { this[InsightsSurfaceColor] = insightsCanvas }.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("总结", style = MaterialTheme.typography.titleLarge, color = CoffeeVisuals.darkCoffee, modifier = Modifier.weight(1f).testTag(TestTags.RootScreenTitle))
            ModeSelector(state.mode, onShowMonthly, onShowYearly, Modifier.width(112.dp))
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag(TestTags.RootScreenSettings)) { Text("设置", color = CoffeeVisuals.forest) }
        }
        PeriodSelector(state, onPreviousMonth, onNextMonth, onPreviousYear, onNextYear)
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(32.dp), color = CoffeeVisuals.forest)
            state.errorMessage != null -> Text(state.errorMessage, color = CoffeeVisuals.forest)
            report == null || report.first.cupCount == 0 -> EmptyCard(if (state.mode == InsightsMode.MONTHLY) "这个月还没有咖啡记录" else "这一年还没有咖啡记录")
            else -> DashboardContent(report.second, imagePathResolver, onOpenRecord)
        }
        Spacer(Modifier.height(12.dp))
    }
}

private data class Dashboard(val habit: HabitSummary, val types: List<ShareValue>, val brands: List<ShareValue>, val topBrands: List<RankedValue>, val topProducts: List<RankedValue>, val best: HighlightRecord?, val worst: HighlightRecord?)

@Composable private fun ModeSelector(mode: InsightsMode, month: () -> Unit, year: () -> Unit, modifier: Modifier = Modifier) = Row(modifier.selectableGroup().background(CoffeeVisuals.mint, RoundedCornerShape(CoffeeVisuals.cornerMedium)).padding(4.dp)) {
    ModeButton("月度", mode == InsightsMode.MONTHLY, month); ModeButton("年度", mode == InsightsMode.YEARLY, year)
}
@Composable private fun RowScope.ModeButton(label: String, selected: Boolean, onClick: () -> Unit) = Box(Modifier.weight(1f).background(if (selected) CoffeeVisuals.white else Color.Transparent, RoundedCornerShape(CoffeeVisuals.cornerSmall)).heightIn(min = 48.dp).selectable(selected = selected, role = Role.Tab, onClick = onClick).padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Text(label, color = CoffeeVisuals.forest) }

@Composable private fun PeriodSelector(state: InsightsUiState, previousMonth: () -> Unit, nextMonth: () -> Unit, previousYear: () -> Unit, nextYear: () -> Unit) {
    val monthly = state.mode == InsightsMode.MONTHLY
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        TextButton(onClick = if (monthly) previousMonth else previousYear, modifier = Modifier.semantics { contentDescription = "上一周期" }) { Text("‹", color = CoffeeVisuals.forest, style = MaterialTheme.typography.headlineSmall) }
        Text(if (monthly) "${state.year}年${state.month}月" else "${state.year}年", color = CoffeeVisuals.forest, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = if (monthly) nextMonth else nextYear, modifier = Modifier.semantics { contentDescription = "下一周期" }) { Text("›", color = CoffeeVisuals.forest, style = MaterialTheme.typography.headlineSmall) }
    }
}

@Composable private fun DashboardContent(data: Dashboard, resolver: ImagePathResolver, onOpenRecord: (String) -> Unit) {
    HabitHero(data.habit)
    ShareBars("咖啡类型", data.types, TestTags.InsightsCoffeeTypeDonut, Modifier.fillMaxWidth())
    ShareBars("常喝品牌", data.brands, TestTags.InsightsBrandDonut, Modifier.fillMaxWidth())
    RankingCard("Top3 品牌", data.topBrands, TestTags.InsightsTopBrands, Modifier.fillMaxWidth())
    RankingCard("Top3 产品", data.topProducts, TestTags.InsightsTopProducts, Modifier.fillMaxWidth())
    data.best?.let { HighlightCard("本期最好", it, TestTags.InsightsBestCard, resolver, onOpenRecord) }
    if (data.worst != null) HighlightCard("本期最差", data.worst, TestTags.InsightsWorstCard, resolver, onOpenRecord)
    else if (data.best != null) Text("本期评分一致", color = CoffeeVisuals.secondaryText)
    else Text("本期暂无评分记录", color = CoffeeVisuals.secondaryText)
}

@Composable internal fun HabitHero(habit: HabitSummary) = Column(Modifier.fillMaxWidth().testTag(TestTags.InsightsHabitHero).semantics { this[InsightsMetricCardColor] = insightsCanvas }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("本期饮用习惯", color = sage, style = MaterialTheme.typography.labelLarge)
    androidx.compose.foundation.layout.BoxWithConstraints {
        val values = listOf("杯数" to "${habit.cups}杯", "总消费" to (habit.totalSpendFen?.let(::formatFen) ?: "—"), "杯均" to (habit.averagePriceFen?.let(::formatFen) ?: "—"), "平均评分" to (habit.averageRating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"))
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val labelStyle = MaterialTheme.typography.labelSmall
        val valueStyle = MaterialTheme.typography.titleMedium
        val widths = values.map { (label, value) -> with(density) { maxOf(measurer.measure(AnnotatedString(label), labelStyle).size.width, measurer.measure(AnnotatedString(value), valueStyle).size.width).toDp() } }
        val gap = 8.dp
        val columns = when {
            widths.max() * 4 + gap * 3 <= maxWidth -> 4
            widths.max() * 2 + gap <= maxWidth -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { values.chunked(columns).forEach { row -> MetricRow(row) } }
    }
    Text("饮用天数 ${habit.drinkingDays} 天 · 最长连续 ${habit.longestStreak} 天", color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.bodySmall)
    Text("杯数较上期 ${habit.cupDelta?.let { if (it >= 0) "+$it" else "$it" } ?: "—"}", color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.bodySmall)
}

@Composable private fun MetricRow(values: List<Pair<String, String>>) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { values.forEach { (label, value) -> Column(Modifier.weight(1f)) { Text(label, color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.labelSmall); Text(value, color = CoffeeVisuals.darkCoffee, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) } } }

@Composable private fun ShareBars(title: String, shares: List<ShareValue>, tag: String, modifier: Modifier) = Column(modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, color = sage, style = MaterialTheme.typography.titleMedium)
    if (shares.isEmpty()) Text("—", color = CoffeeVisuals.secondaryText)
    shares.forEachIndexed { index, share ->
        val label = donutLabel(share.label)
        Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label · ${share.cups}杯 · ${percent(share)}" }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = CoffeeVisuals.darkCoffee); Text("${share.cups}杯 · ${percent(share)}", color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.bodySmall) }
            Box(Modifier.fillMaxWidth().height(8.dp).background(CoffeeVisuals.mint, RoundedCornerShape(99.dp))) { Box(Modifier.fillMaxWidth(share.fraction.coerceIn(0.0, 1.0).toFloat()).height(8.dp).background(if (index % 2 == 0) sage else CoffeeVisuals.peach, RoundedCornerShape(99.dp)).testTag("$tag-bar-$index").semantics { progressBarRangeInfo = ProgressBarRangeInfo(share.fraction.coerceIn(0.0, 1.0).toFloat(), 0f..1f) }) }
        }
    }
}
@Composable private fun RankingCard(title: String, values: List<RankedValue>, tag: String, modifier: Modifier) = Column(modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, color = sage, style = MaterialTheme.typography.titleMedium); values.take(3).forEachIndexed { i, value -> Row(Modifier.fillMaxWidth().semantics { contentDescription = "$title 第${i + 1}名 ${value.name} ${value.cups}杯" }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) { Text("${i + 1}", Modifier.size(24.dp).background(CoffeeVisuals.peach, CircleShape).padding(top = 2.dp), color = CoffeeVisuals.darkCoffee, textAlign = TextAlign.Center); Text(value.name, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${value.cups}杯", color = CoffeeVisuals.secondaryText) } }; if (values.isEmpty()) Text("—", color = CoffeeVisuals.secondaryText) }
@Composable private fun HighlightCard(title: String, item: HighlightRecord, tag: String, resolver: ImagePathResolver, onOpen: (String) -> Unit) = Row(Modifier.fillMaxWidth().testTag(tag).clickable { onOpen(item.recordId) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { val logo = bundledBrandLogoRes(item.brandName); val fallbackPainter = logo?.let { painterResource(it) }; val imageTag = if (tag == TestTags.InsightsBestCard) TestTags.InsightsBestImage else TestTags.InsightsWorstImage; ResolvedLocalAssetImage(item.imageAssetId, item.brandLogoAssetId, resolver, "$title ${item.brandName}", ContentScale.Fit, Modifier.size(40.dp).testTag(imageTag).background(CoffeeVisuals.white, RoundedCornerShape(CoffeeVisuals.cornerSmall)), fallbackPainter); Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(title, color = sage, style = MaterialTheme.typography.labelMedium); Text(item.brandName, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(item.itemName, maxLines = 1, overflow = TextOverflow.Ellipsis); if (item.tiedProductCount > 0) Text("另有 ${item.tiedProductCount} 款并列", color = CoffeeVisuals.secondaryText, style = MaterialTheme.typography.bodySmall) }; Text("${item.ratingHalfStars / 2.0}★", color = sage, maxLines = 1) }
@Composable private fun EmptyCard(title: String) = Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, color = sage); Text("下一杯会从这里开始留下痕迹", color = CoffeeVisuals.secondaryText) }
private fun percent(share: ShareValue) = String.format(Locale.ROOT, "%.0f%%", share.fraction * 100)
private fun donutLabel(label: String) = when (label) { "BLACK" -> "黑咖"; "FRUIT" -> "果咖"; "MILK" -> "奶咖"; "HAND_BREW" -> "手冲"; "OTHER" -> "其他"; else -> label }
internal fun formatFen(fen: Long): String = "¥${fenWithoutSymbol(fen)}"
private fun fenWithoutSymbol(fen: Long): String = "${fen / 100}.${(fen % 100).toString().padStart(2, '0')}"
internal fun formatSignedFen(fen: Long): String { val value = BigInteger.valueOf(fen); val parts = value.abs().divideAndRemainder(BigInteger.valueOf(100)); return (if (value.signum() >= 0) "+" else "-") + "¥${parts[0]}.${parts[1].toString().padStart(2, '0')}" }
