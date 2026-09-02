package com.niumi.coffeejournal.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
    val report = if (state.mode == InsightsMode.MONTHLY) state.monthly?.let { it.period to Dashboard(it.habit, it.trend, it.coffeeTypeShares, it.brandShares, it.topBrands, it.topProducts, it.best, it.worst) }
    else state.yearly?.let { it.period to Dashboard(it.habit, it.trend, it.coffeeTypeShares, it.brandShares, it.topBrands, it.topProducts, it.best, it.worst) }
    Column(Modifier.fillMaxSize().background(CoffeeVisuals.cream).testTag(TestTags.InsightsSurface)
        .semantics { this[InsightsSurfaceColor] = CoffeeVisuals.cream }.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("总结", style = MaterialTheme.typography.headlineMedium, color = CoffeeVisuals.forest, modifier = Modifier.testTag(TestTags.RootScreenTitle))
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag(TestTags.RootScreenSettings)) { Text("设置", color = CoffeeVisuals.forest) }
        }
        ModeSelector(state.mode, onShowMonthly, onShowYearly)
        PeriodSelector(state, onPreviousMonth, onNextMonth, onPreviousYear, onNextYear)
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(32.dp), color = CoffeeVisuals.forest)
            state.errorMessage != null -> Text(state.errorMessage, color = CoffeeVisuals.forest)
            report == null || report.first.cupCount == 0 -> EmptyCard(if (state.mode == InsightsMode.MONTHLY) "这个月还没有咖啡记录" else "这一年还没有咖啡记录")
            else -> DashboardContent(report.second, state.mode, imagePathResolver, onOpenRecord)
        }
        Spacer(Modifier.height(12.dp))
    }
}

private data class Dashboard(val habit: HabitSummary, val trend: List<ComparisonPoint>, val types: List<ShareValue>, val brands: List<ShareValue>, val topBrands: List<RankedValue>, val topProducts: List<RankedValue>, val best: HighlightRecord?, val worst: HighlightRecord?)

@Composable private fun ModeSelector(mode: InsightsMode, month: () -> Unit, year: () -> Unit) = Row(Modifier.fillMaxWidth().background(CoffeeVisuals.mint, RoundedCornerShape(CoffeeVisuals.cornerMedium)).padding(4.dp)) {
    ModeButton("月度", mode == InsightsMode.MONTHLY, month); ModeButton("年度", mode == InsightsMode.YEARLY, year)
}
@Composable private fun RowScope.ModeButton(label: String, selected: Boolean, onClick: () -> Unit) = Box(Modifier.weight(1f).background(if (selected) CoffeeVisuals.white else Color.Transparent, RoundedCornerShape(CoffeeVisuals.cornerSmall)).clickable(onClick = onClick).padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Text(label, color = CoffeeVisuals.forest) }

@Composable private fun PeriodSelector(state: InsightsUiState, previousMonth: () -> Unit, nextMonth: () -> Unit, previousYear: () -> Unit, nextYear: () -> Unit) {
    val monthly = state.mode == InsightsMode.MONTHLY
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        TextButton(onClick = if (monthly) previousMonth else previousYear) { Text("上一周期", color = CoffeeVisuals.forest) }
        Text(if (monthly) "${state.year}年${state.month}月" else "${state.year}年", color = CoffeeVisuals.forest, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = if (monthly) nextMonth else nextYear) { Text("下一周期", color = CoffeeVisuals.forest) }
    }
}

@Composable private fun DashboardContent(data: Dashboard, mode: InsightsMode, resolver: ImagePathResolver, onOpenRecord: (String) -> Unit) {
    HabitHero(data.habit)
    TrendChart(data.trend, mode)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DonutCard("咖啡类型", data.types, TestTags.InsightsCoffeeTypeDonut, Modifier.weight(1f))
        DonutCard("常喝品牌", data.brands, TestTags.InsightsBrandDonut, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RankingCard("Top3 品牌", data.topBrands, TestTags.InsightsTopBrands, Modifier.weight(1f))
        RankingCard("Top3 产品", data.topProducts, TestTags.InsightsTopProducts, Modifier.weight(1f))
    }
    data.best?.let { HighlightCard("本期最好", it, TestTags.InsightsBestCard, resolver, onOpenRecord) }
    if (data.worst != null) HighlightCard("本期最差", data.worst, TestTags.InsightsWorstCard, resolver, onOpenRecord)
    else if (data.best != null) Text("本期评分一致", color = CoffeeVisuals.secondaryText)
    else Text("本期暂无评分记录", color = CoffeeVisuals.secondaryText)
}

@Composable private fun HabitHero(habit: HabitSummary) = CoffeeCard(Modifier.fillMaxWidth().testTag(TestTags.InsightsHabitHero)) {
    Text("饮用习惯", color = CoffeeVisuals.forest); Text("${habit.cups}", style = MaterialTheme.typography.displayMedium, color = CoffeeVisuals.forest)
    Text("杯", color = CoffeeVisuals.secondaryText)
    Text("饮用天数 ${habit.drinkingDays} · 最长连续 ${habit.longestStreak} 天", color = CoffeeVisuals.secondaryText)
    Text("平均评分 ${habit.averageRating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"} · 杯数较上期 ${habit.cupDelta?.let { if (it >= 0) "+$it" else "$it" } ?: "—"}", color = CoffeeVisuals.secondaryText)
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text("总消费 ${habit.totalSpendFen?.let(::formatFen) ?: "—"}", color = CoffeeVisuals.secondaryText); Text("杯均 ${habit.averagePriceFen?.let(::formatFen) ?: "—"}", color = CoffeeVisuals.secondaryText) }
}

@Composable private fun TrendChart(points: List<ComparisonPoint>, mode: InsightsMode) = CoffeeCard(Modifier.fillMaxWidth().testTag(TestTags.InsightsTrendChart)) {
    Text("饮用趋势", style = MaterialTheme.typography.titleMedium, color = CoffeeVisuals.forest)
    Text("森林实线 本期累计杯数  ·  暖灰虚线 上期累计杯数", color = CoffeeVisuals.secondaryText)
    val desc = "饮用趋势：本期累计杯数；上期累计杯数" + if (points.isEmpty()) "；暂无数据" else "；${if (mode == InsightsMode.MONTHLY) "每日" else "每月"}${points.joinToString { " ${it.index}:${it.current ?: 0}/${it.previous ?: 0}" }}"
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = desc }) {
        val max = points.flatMap { listOfNotNull(it.current, it.previous) }.maxOrNull()?.coerceAtLeast(1) ?: 1
        fun point(i: Int, n: Int) = Offset(if (points.size <= 1) size.width / 2 else i * size.width / (points.size - 1), size.height - n.toFloat() / max * (size.height - 16.dp.toPx()) - 8.dp.toPx())
        fun drawSeries(values: List<Int?>, color: Color, dashed: Boolean) { var previous: Offset? = null; values.forEachIndexed { i, value -> value?.let { current -> previous?.let { drawLine(color, it, point(i, current), 3.dp.toPx(), StrokeCap.Round, if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null) }; drawCircle(color, 3.dp.toPx(), point(i, current)); previous = point(i, current) } } }
        drawSeries(points.map { it.previous }, CoffeeVisuals.warmOutline, true); drawSeries(points.map { it.current }, CoffeeVisuals.forest, false)
    }
}

@Composable private fun DonutCard(title: String, shares: List<ShareValue>, tag: String, modifier: Modifier) = CoffeeCard(modifier.testTag(tag)) {
    Text(title, color = CoffeeVisuals.forest, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { Canvas(Modifier.size(90.dp).semantics { contentDescription = "$title：" + shares.joinToString { "${it.label} ${it.cups}杯 ${percent(it)}" } }) { var start = -90f; shares.forEachIndexed { i, s -> val sweep = (s.fraction * 360f).toFloat(); drawArc(listOf(CoffeeVisuals.forest, CoffeeVisuals.peach, CoffeeVisuals.mint, CoffeeVisuals.warmOutline)[i % 4], start, sweep, false, style = Stroke(16.dp.toPx())); start += sweep } }; Text("${shares.sumOf { it.cups }}\n杯", color = CoffeeVisuals.forest) }
    shares.forEach { Text("${it.label} · ${it.cups} 杯 · ${percent(it)}", color = CoffeeVisuals.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
@Composable private fun RankingCard(title: String, values: List<RankedValue>, tag: String, modifier: Modifier) = CoffeeCard(modifier.testTag(tag)) { Text(title, color = CoffeeVisuals.forest); values.take(3).forEachIndexed { i, value -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("${i + 1}", color = CoffeeVisuals.peach); Text(value.name, Modifier.weight(1f).padding(horizontal = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${value.cups}杯", color = CoffeeVisuals.secondaryText) } }; if (values.isEmpty()) Text("—", color = CoffeeVisuals.secondaryText) }
@Composable private fun HighlightCard(title: String, item: HighlightRecord, tag: String, resolver: ImagePathResolver, onOpen: (String) -> Unit) = CoffeeCard(Modifier.fillMaxWidth().testTag(tag).clickable { onOpen(item.recordId) }) { Row(verticalAlignment = Alignment.CenterVertically) { val logo = bundledBrandLogoRes(item.brandName); val fallbackPainter = logo?.let { painterResource(it) }; ResolvedLocalAssetImage(item.imageAssetId, item.brandLogoAssetId, resolver, "$title ${item.brandName}", ContentScale.Fit, Modifier.size(72.dp).border(1.dp, CoffeeVisuals.warmOutline, RoundedCornerShape(CoffeeVisuals.cornerSmall)), fallbackPainter); Column(Modifier.padding(start = 12.dp)) { Text(title, color = CoffeeVisuals.forest); Text(item.brandName, color = CoffeeVisuals.secondaryText); Text(item.itemName, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${item.ratingHalfStars / 2.0}★", color = CoffeeVisuals.peach); if (item.tiedProductCount > 0) Text("另有 ${item.tiedProductCount} 款并列", color = CoffeeVisuals.secondaryText) } } }
@Composable private fun CoffeeCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Card(modifier.semantics { this[InsightsMetricCardColor] = CoffeeVisuals.white }, shape = RoundedCornerShape(CoffeeVisuals.cornerMedium), colors = CardDefaults.cardColors(containerColor = CoffeeVisuals.white), border = BorderStroke(1.dp, CoffeeVisuals.warmOutline)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }
@Composable private fun EmptyCard(title: String) = CoffeeCard(Modifier.fillMaxWidth()) { Text(title, color = CoffeeVisuals.forest); Text("下一杯会从这里开始留下痕迹", color = CoffeeVisuals.secondaryText) }
private fun percent(share: ShareValue) = String.format(Locale.ROOT, "%.0f%%", share.fraction * 100)
internal fun formatFen(fen: Long): String = "¥${fenWithoutSymbol(fen)}"
private fun fenWithoutSymbol(fen: Long): String = "${fen / 100}.${(fen % 100).toString().padStart(2, '0')}"
internal fun formatSignedFen(fen: Long): String { val value = BigInteger.valueOf(fen); val parts = value.abs().divideAndRemainder(BigInteger.valueOf(100)); return (if (value.signum() >= 0) "+" else "-") + "¥${parts[0]}.${parts[1].toString().padStart(2, '0')}" }
