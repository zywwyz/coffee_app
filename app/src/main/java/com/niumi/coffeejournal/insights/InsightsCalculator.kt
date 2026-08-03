package com.niumi.coffeejournal.insights

import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.ItemType
import java.math.BigInteger
import java.util.GregorianCalendar

data class RankedValue(val name: String, val count: Int)

data class TrendPoint(
    val label: String,
    val spendFen: Long,
    val averageRating: Double?,
)

data class PeriodInsights(
    val cupCount: Int,
    val totalSpendFen: Long,
    val averagePriceFen: Long?,
    val averageRating: Double?,
    val topBrands: List<RankedValue>,
    val topProducts: List<RankedValue>,
    val topBeans: List<RankedValue>,
    val topBrewMethods: List<RankedValue>,
    val bestRecordIds: List<String>,
    val worstRecordIds: List<String>,
    val points: List<TrendPoint>,
)

enum class SpendDeltaBaseline { AVAILABLE, ZERO, MISSING }

data class SpendDelta(
    val amountFen: Long,
    val percent: Double?,
    val baseline: SpendDeltaBaseline,
)

data class BrandSpendShare(
    val name: String,
    val spendFen: Long,
    val fraction: Double,
)

data class MonthlyInsights(
    val year: Int,
    val month: Int,
    val period: PeriodInsights,
    val spendDelta: SpendDelta,
    val brandSpendShares: List<BrandSpendShare>,
    val ratingTrendText: String?,
)

data class YearlyInsights(
    val year: Int,
    val period: PeriodInsights,
    val averageMonthlySpendFen: Long,
    val monthlyPoints: List<TrendPoint>,
    val highestSpendMonths: List<String>,
    val lowestSpendMonths: List<String>,
    val topRatedRecordIds: List<String>,
    val ratingTrendText: String?,
)

object InsightsCalculator {
    fun period(records: List<DrinkRecord>, points: List<TrendPoint>): PeriodInsights {
        val priced = records.mapNotNull(DrinkRecord::actualPriceFen)
        val rated = records.mapNotNull(DrinkRecord::ratingHalfStars)
        val best = rated.maxOrNull()
        val worst = rated.minOrNull()
        return PeriodInsights(
            cupCount = records.size,
            totalSpendFen = saturatedSum(priced),
            averagePriceFen = averageLong(priced),
            averageRating = rated.takeIf(List<Int>::isNotEmpty)?.average()?.div(2.0),
            topBrands = top(records.map { it.snapshot.brandName }),
            topProducts = top(records.filter { it.itemType == ItemType.CHAIN_PRODUCT }.map { it.snapshot.itemName }),
            topBeans = top(records.filter { it.itemType == ItemType.PERSONAL_BEAN }.map { it.snapshot.itemName }),
            topBrewMethods = top(records.mapNotNull { it.brewMethod?.trim()?.takeIf(String::isNotEmpty) }),
            bestRecordIds = best?.let { value ->
                records.filter { it.ratingHalfStars == value }.map { it.id }.sorted()
            }.orEmpty(),
            worstRecordIds = worst?.let { value ->
                records.filter { it.ratingHalfStars == value }.map { it.id }.sorted()
            }.orEmpty(),
            points = points,
        )
    }

    fun monthly(
        year: Int,
        month: Int,
        records: List<DrinkRecord>,
        previousMonthRecords: List<DrinkRecord>,
    ): MonthlyInsights {
        require(year in 1..9999)
        require(month in 1..12)
        val current = records.filter { parseDate(it.localDate)?.let { date -> date.year == year && date.month == month } == true }
        val weeks = (1..weekCount(year, month)).map { week ->
            val weekRecords = current.filter { record ->
                val day = parseDate(record.localDate)?.day ?: return@filter false
                (day - 1) / 7 + 1 == week
            }
            point("第${week}周", weekRecords)
        }
        val previousDate = GregorianCalendar(year, month - 1, 1).apply { add(GregorianCalendar.MONTH, -1) }
        val prior = previousMonthRecords.filter {
            parseDate(it.localDate)?.let { date ->
                date.year == previousDate.get(GregorianCalendar.YEAR) &&
                    date.month == previousDate.get(GregorianCalendar.MONTH) + 1
            } == true
        }
        val previousExact = exactSum(prior.mapNotNull(DrinkRecord::actualPriceFen))
        val currentExact = exactSum(current.mapNotNull(DrinkRecord::actualPriceFen))
        val delta = SpendDelta(
            amountFen = currentExact.subtract(previousExact).toSignedSaturatedLong(),
            percent = previousExact.takeIf { it.signum() > 0 }?.let {
                currentExact.subtract(it).toDouble() / it.toDouble() * 100.0
            },
            baseline = when {
                previousExact.signum() > 0 -> SpendDeltaBaseline.AVAILABLE
                prior.any { it.actualPriceFen != null } -> SpendDeltaBaseline.ZERO
                else -> SpendDeltaBaseline.MISSING
            },
        )
        return MonthlyInsights(
            year = year,
            month = month,
            period = period(current, weeks),
            spendDelta = delta,
            brandSpendShares = brandShares(current),
            ratingTrendText = trendText(weeks),
        )
    }

    fun yearly(year: Int, records: List<DrinkRecord>): YearlyInsights {
        require(year in 1..9999)
        val current = records.filter { parseDate(it.localDate)?.year == year }
        val points = (1..12).map { month ->
            point("${month}月", current.filter { parseDate(it.localDate)?.month == month })
        }
        val populated = points.filterIndexed { index, _ ->
            current.any { parseDate(it.localDate)?.month == index + 1 && it.actualPriceFen != null }
        }
        val high = populated.maxOfOrNull(TrendPoint::spendFen)
        val low = populated.minOfOrNull(TrendPoint::spendFen)
        val period = period(current, points)
        return YearlyInsights(
            year = year,
            period = period,
            averageMonthlySpendFen = exactSum(current.mapNotNull(DrinkRecord::actualPriceFen))
                .divide(BigInteger.valueOf(12)).toSaturatedLong(),
            monthlyPoints = points,
            highestSpendMonths = populated.filter { it.spendFen == high }.map { it.label },
            lowestSpendMonths = populated.filter { it.spendFen == low }.map { it.label },
            topRatedRecordIds = current.filter { it.ratingHalfStars != null }
                .sortedWith(compareByDescending<DrinkRecord> { it.ratingHalfStars }.thenBy { it.id })
                .take(5)
                .map { it.id },
            ratingTrendText = trendText(points),
        )
    }

    private fun point(label: String, records: List<DrinkRecord>) = TrendPoint(
        label = label,
        spendFen = saturatedSum(records.mapNotNull(DrinkRecord::actualPriceFen)),
        averageRating = records.mapNotNull(DrinkRecord::ratingHalfStars)
            .takeIf(List<Int>::isNotEmpty)?.average()?.div(2.0),
    )

    private fun trendText(points: List<TrendPoint>): String? {
        val rated = points.mapNotNull(TrendPoint::averageRating)
        if (rated.size < 2) return null
        return when {
            rated.last() > rated.first() -> "评分趋势上升"
            rated.last() < rated.first() -> "评分趋势下降"
            else -> "评分趋势平稳"
        }
    }

    private fun brandShares(records: List<DrinkRecord>): List<BrandSpendShare> {
        val grouped = records.filter { it.actualPriceFen != null }
            .groupBy { it.snapshot.brandName }
            .mapValues { (_, values) -> exactSum(values.mapNotNull(DrinkRecord::actualPriceFen)) }
        val total = grouped.values.fold(BigInteger.ZERO, BigInteger::add)
        return grouped.entries.sortedWith(compareByDescending<Map.Entry<String, BigInteger>> { it.value }.thenBy { it.key })
            .map { (name, spend) ->
                BrandSpendShare(
                    name, spend.toSaturatedLong(),
                    if (total.signum() == 0) 0.0 else spend.toDouble() / total.toDouble(),
                )
            }
    }

    private fun top(names: List<String>): List<RankedValue> {
        val counts = names.map(String::trim).filter(String::isNotEmpty).groupingBy { it }.eachCount()
        val max = counts.values.maxOrNull() ?: return emptyList()
        return counts.filterValues { it == max }.map { RankedValue(it.key, it.value) }.sortedBy(RankedValue::name)
    }

    private fun exactSum(values: Collection<Long>): BigInteger = values.fold(BigInteger.ZERO) { total, value ->
        total + BigInteger.valueOf(value)
    }

    private fun saturatedSum(values: Collection<Long>): Long = exactSum(values).toSaturatedLong()

    private fun averageLong(values: List<Long>): Long? = values.takeIf(List<Long>::isNotEmpty)?.let {
        it.fold(BigInteger.ZERO) { total, value -> total + BigInteger.valueOf(value) }
            .divide(BigInteger.valueOf(it.size.toLong())).coerceIn(BigInteger.ZERO, LONG_MAX).toLong()
    }

    private fun BigInteger.toSaturatedLong(): Long = coerceIn(BigInteger.ZERO, LONG_MAX).toLong()
    private fun BigInteger.toSignedSaturatedLong(): Long = coerceIn(LONG_MIN, LONG_MAX).toLong()

    private fun weekCount(year: Int, month: Int): Int =
        if (GregorianCalendar(year, month - 1, 1).getActualMaximum(GregorianCalendar.DAY_OF_MONTH) > 28) 5 else 4

    private fun parseDate(value: String): DateParts? {
        if (!DATE.matches(value)) return null
        val year = value.substring(0, 4).toInt()
        val month = value.substring(5, 7).toInt()
        val day = value.substring(8, 10).toInt()
        return try {
            GregorianCalendar(year, month - 1, day).apply { isLenient = false }.time
            DateParts(year, month, day)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private data class DateParts(val year: Int, val month: Int, val day: Int)

    private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
    private val LONG_MIN: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)
}
