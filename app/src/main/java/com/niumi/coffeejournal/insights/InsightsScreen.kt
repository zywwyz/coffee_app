package com.niumi.coffeejournal.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.niumi.coffeejournal.journal.JournalRepository
import com.niumi.coffeejournal.TestTags
import java.util.GregorianCalendar
import java.util.Locale
import java.math.BigInteger

@Composable
fun InsightsFeature(repository: JournalRepository) {
    val factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val now = GregorianCalendar()
                return InsightsViewModel(
                    JournalInsightsRepository(repository),
                    now.get(GregorianCalendar.YEAR),
                    now.get(GregorianCalendar.MONTH) + 1,
                ) as T
            }
        }
    }
    val model: InsightsViewModel = viewModel(factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    InsightsScreen(
        state = state,
        onShowMonthly = model::showMonthly,
        onShowYearly = model::showYearly,
        onPreviousMonth = model::previousMonth,
        onNextMonth = model::nextMonth,
        onPreviousYear = model::previousYear,
        onNextYear = model::nextYear,
    )
}

@Composable
fun InsightsScreen(
    state: InsightsUiState,
    onShowMonthly: () -> Unit,
    onShowYearly: () -> Unit,
    onPreviousMonth: () -> Unit = {},
    onNextMonth: () -> Unit = {},
    onPreviousYear: () -> Unit = {},
    onNextYear: () -> Unit = {},
) {
    var selectedRecord by remember { mutableStateOf<RatedRecordSummary?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text("咖啡回顾", style = MaterialTheme.typography.headlineMedium)
            Text("把每一杯，慢慢看清", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PrimaryTabRow(selectedTabIndex = if (state.mode == InsightsMode.MONTHLY) 0 else 1) {
            Tab(
                selected = state.mode == InsightsMode.MONTHLY,
                onClick = onShowMonthly,
                text = { Text("月度总结") },
            )
            Tab(
                selected = state.mode == InsightsMode.YEARLY,
                onClick = onShowYearly,
                text = { Text("年度总结") },
            )
        }
        PeriodSelector(state, onPreviousMonth, onNextMonth, onPreviousYear, onNextYear)
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(32.dp))
            state.errorMessage != null -> Text(
                state.errorMessage,
                Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.error,
            )
            state.mode == InsightsMode.MONTHLY -> MonthlyContent(state.monthly, onOpenRecord = { selectedRecord = it })
            else -> YearlyContent(state.yearly, onOpenRecord = { selectedRecord = it })
        }
    }
    selectedRecord?.let { record ->
        RecordDetailDialog(record = record, onDismiss = { selectedRecord = null })
    }
}

@Composable
private fun PeriodSelector(
    state: InsightsUiState,
    previousMonth: () -> Unit,
    nextMonth: () -> Unit,
    previousYear: () -> Unit,
    nextYear: () -> Unit,
) {
    val monthly = state.mode == InsightsMode.MONTHLY
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = if (monthly) previousMonth else previousYear) { Text("上一${if (monthly) "月" else "年"}") }
        Text(
            if (monthly) "${state.year}年 ${state.month}月" else "${state.year}年",
            style = MaterialTheme.typography.titleMedium,
        )
        Button(onClick = if (monthly) nextMonth else nextYear) { Text("下一${if (monthly) "月" else "年"}") }
    }
}

@Composable
private fun MonthlyContent(report: MonthlyInsights?, onOpenRecord: (RatedRecordSummary) -> Unit) {
    if (report == null || report.period.cupCount == 0) {
        EmptyCard("这个月还没有咖啡记录", "下一杯会从这里开始留下痕迹")
        return
    }
    val period = report.period
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MetricGrid(
            listOf(
                "喝了几杯" to "${period.cupCount} 杯",
                "实际消费" to formatFen(period.totalSpendFen),
                "单杯均价" to period.averagePriceFen?.let(::formatFen).orDash(),
                "平均评分" to period.averageRating?.let { String.format(Locale.ROOT, "%.1f ★", it) }.orDash(),
            ),
        )
        val delta = report.spendDelta
        Text(
            when (delta.baseline) {
                SpendDeltaBaseline.AVAILABLE ->
                    "比上月 ${signedFen(delta.amountFen)}（${String.format(Locale.ROOT, "%+.0f%%", delta.percent)}）"
                SpendDeltaBaseline.ZERO -> "上月消费为 ¥0.00，本月 ${formatFen(period.totalSpendFen)}"
                SpendDeltaBaseline.MISSING -> "上月没有价格记录，本月 ${formatFen(period.totalSpendFen)}"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionTitle("消费与评分趋势")
        TrendChart(period.points)
        report.ratingTrendText?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        SectionTitle("品牌消费占比")
        BrandShareChart(report.brandSpendShares)
        SectionTitle("偏好排行")
        RankingGroups(period)
        SectionTitle("最好与最差")
        Text("最高分")
        RatedRecordRows(period.bestRecords, onOpenRecord)
        Text("最低分")
        RatedRecordRows(period.worstRecords, onOpenRecord)
    }
}

@Composable
private fun YearlyContent(report: YearlyInsights?, onOpenRecord: (RatedRecordSummary) -> Unit) {
    if (report == null || report.period.cupCount == 0) {
        EmptyCard("这一年还没有咖啡记录", "切换年份看看往年的咖啡足迹")
        return
    }
    val period = report.period
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MetricGrid(
            listOf(
                "全年杯数" to "${period.cupCount} 杯",
                "全年消费" to formatFen(period.totalSpendFen),
                "月均消费" to formatFen(report.averageMonthlySpendFen),
                "单杯均价" to period.averagePriceFen?.let(::formatFen).orDash(),
            ),
        )
        SectionTitle("十二个月趋势")
        TrendChart(report.monthlyPoints)
        report.ratingTrendText?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text("消费最高：${report.highestSpendMonths.joinToString().ifBlank { "—" }}")
        Text("消费最低：${report.lowestSpendMonths.joinToString().ifBlank { "—" }}")
        SectionTitle("偏好排行")
        RankingGroups(period)
        SectionTitle("年度最高分")
        RatedRecordRows(report.highestRatedRecords, onOpenRecord)
        SectionTitle("年度评分 Top 5")
        RatedRecordRows(report.topRatedRecords, onOpenRecord)
    }
}

@Composable
private fun MetricGrid(values: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Card(
                        Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                value,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = if (label == "实际消费") {
                                    Modifier.testTag(TestTags.MonthlySpend)
                                } else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RankingGroups(period: PeriodInsights) {
    RankingLine("品牌", period.topBrands)
    RankingLine("产品", period.topProducts)
    RankingLine("豆子", period.topBeans)
    RankingLine("冲煮", period.topBrewMethods)
}

@Composable
private fun RankingLine(label: String, values: List<RankedValue>) {
    Text("$label：${values.joinToString { "${it.name} ${it.count}次" }.ifBlank { "—" }}")
}

@Composable
private fun RatedRecordRows(records: List<RatedRecordSummary>, onOpenRecord: (RatedRecordSummary) -> Unit) {
    if (records.isEmpty()) {
        Text("—")
        return
    }
    records.take(20).forEach { record ->
        TextButton(onClick = { onOpenRecord(record) }) {
            Text("${record.brandName} · ${record.itemName} · ${record.ratingHalfStars / 2.0}★")
        }
    }
    if (records.size > 20) Text("另有 ${records.size - 20} 条并列记录")
}

@Composable
private fun RecordDetailDialog(record: RatedRecordSummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("原始记录") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${record.brandName} · ${record.itemName}", style = MaterialTheme.typography.titleMedium)
                Text(record.localDate)
                Text("评分：${record.ratingHalfStars / 2.0}★")
                Text("实际支付：${record.actualPriceFen?.let(::formatFen) ?: "未记录"}")
                Text("冲煮方式：${record.brewMethod ?: "未记录"}")
                Text("产地：${record.origin ?: "未记录"}")
                Text("处理法：${record.processing ?: "未记录"}")
                Text("烘焙度：${record.roastLevel ?: "未记录"}")
                Text("风味：${record.flavorNotes ?: "未记录"}")
                Text("备注：${record.note ?: "未记录"}")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun TrendChart(points: List<TrendPoint>) {
    val description = "消费与评分趋势图：" + points.mapNotNull { point ->
        val facts = buildList {
            point.spendFen?.let { add("消费${fenWithoutSymbol(it)}元") }
            point.averageRating?.let { add("平均评分${String.format(Locale.ROOT, "%.1f", it)}星") }
        }
        facts.takeIf(List<String>::isNotEmpty)?.let { "${point.label} ${it.joinToString("，")}" }
    }.joinToString("；")
        .ifBlank { "暂无数据" }
    val barColor = MaterialTheme.colorScheme.secondary
    val ratingColor = MaterialTheme.colorScheme.primary
    val chartScroll = rememberScrollState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("■ 消费柱", color = barColor)
            Text("● 评分折线", color = ratingColor)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val pointWidth = 72.dp
            val contentWidth = maxOf(maxWidth, pointWidth * points.size.coerceAtLeast(1))
            val cellWidth = contentWidth / points.size.coerceAtLeast(1)
            Column(Modifier.fillMaxWidth().horizontalScroll(chartScroll)) {
                Column(Modifier.width(contentWidth)) {
                    Canvas(
                        Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = description },
                    ) {
                        val maxSpend = points.mapNotNull(TrendPoint::spendFen).maxOrNull()?.takeIf { it > 0 } ?: 1L
                        val centers = chartPointCenters(size.width, points.size)
                        val cell = size.width / points.size.coerceAtLeast(1)
                        var lastRating: Offset? = null
                        points.forEachIndexed { index, point ->
                            point.spendFen?.let { spend ->
                                val barHeight = size.height * .72f * (spend.toDouble() / maxSpend.toDouble()).toFloat()
                                drawRoundRect(
                                    color = barColor.copy(alpha = .55f),
                                    topLeft = Offset(centers[index] - cell * .225f, size.height - barHeight),
                                    size = androidx.compose.ui.geometry.Size(cell * .45f, barHeight),
                                )
                            }
                            point.averageRating?.let { rating ->
                                val current = Offset(centers[index], size.height * (1f - (rating / 5.0).toFloat()))
                                lastRating?.let { drawLine(ratingColor, it, current, 4.dp.toPx(), StrokeCap.Round) }
                                drawCircle(ratingColor, 4.dp.toPx(), current)
                                lastRating = current
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        points.forEach { point ->
                            Column(
                                Modifier.width(cellWidth).padding(vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(point.label)
                                point.spendFen?.let { Text(formatFen(it)) }
                                point.averageRating?.let { Text("${String.format(Locale.ROOT, "%.1f", it)}★") }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun chartPointCenters(chartWidthPx: Float, pointCount: Int): List<Float> {
    if (pointCount <= 0) return emptyList()
    val cell = chartWidthPx / pointCount
    return List(pointCount) { index -> (index + .5f) * cell }
}

@Composable
private fun BrandShareChart(shares: List<BrandSpendShare>) {
    val description = "品牌消费占比图：" + shares.joinToString("，") {
        "${it.name} ${String.format(Locale.ROOT, "%.0f%%", it.fraction * 100)}"
    }.ifBlank { "暂无数据" }
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.outline,
    )
    Canvas(Modifier.fillMaxWidth().height(130.dp).semantics { contentDescription = description }) {
        var start = -90f
        shares.forEachIndexed { index, share ->
            val sweep = (share.fraction * 360).toFloat()
            drawArc(colors[index % colors.size], start, sweep, false, style = Stroke(22.dp.toPx()))
            start += sweep
        }
    }
    shares.forEach { Text("${it.name} · ${formatFen(it.spendFen)} · ${String.format(Locale.ROOT, "%.0f%%", it.fraction * 100)}") }
}

@Composable private fun SectionTitle(value: String) = Text(value, style = MaterialTheme.typography.titleMedium)

@Composable
private fun EmptyCard(title: String, subtitle: String) {
    Card(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun formatFen(fen: Long): String = "¥${fenWithoutSymbol(fen)}"
private fun fenWithoutSymbol(fen: Long): String = "${fen / 100}.${(fen % 100).toString().padStart(2, '0')}"
internal fun formatSignedFen(fen: Long): String {
    val value = BigInteger.valueOf(fen)
    val parts = value.abs().divideAndRemainder(BigInteger.valueOf(100))
    val amount = "¥${parts[0]}.${parts[1].toString().padStart(2, '0')}"
    return (if (value.signum() >= 0) "+" else "-") + amount
}
private fun signedFen(fen: Long): String = formatSignedFen(fen)
private fun String?.orDash(): String = this ?: "—"
