package com.niumi.coffeejournal.insights

import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import com.niumi.coffeejournal.core.model.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsCalculatorTest {
    @Test
    fun `unrated and unpriced cups use the correct denominators`() {
        val report = InsightsCalculator.monthly(
            year = 2026,
            month = 8,
            records = listOf(
                record("rated", "2026-08-01", price = 990, rating = 9),
                record("unrated", "2026-08-02", price = 2_000, rating = null),
                record("unpriced", "2026-08-03", price = null, rating = 10),
            ),
            previousMonthRecords = emptyList(),
        )

        assertEquals(3, report.period.cupCount)
        assertEquals(2_990L, report.period.totalSpendFen)
        assertEquals(1_495L, report.period.averagePriceFen)
        assertEquals(4.75, report.period.averageRating!!, 0.0)
        assertNull(report.spendDelta.percent)
        assertEquals(SpendDeltaBaseline.MISSING, report.spendDelta.baseline)
    }

    @Test
    fun `ties are preserved and deterministically sorted`() {
        val report = InsightsCalculator.period(
            listOf(
                record("z", "2026-08-01", brand = "瑞幸", item = "生椰", rating = 8),
                record("a", "2026-08-02", brand = "Manner", item = "澳白", rating = 8),
                record("low2", "2026-08-03", brand = "瑞幸", item = "美式", rating = 4),
                record("low1", "2026-08-04", brand = "Manner", item = "拿铁", rating = 4),
            ),
            points = emptyList(),
        )

        assertEquals(listOf("Manner", "瑞幸"), report.topBrands.map { it.name })
        assertEquals(listOf("a", "z"), report.bestRecordIds)
        assertEquals(listOf("low1", "low2"), report.worstRecordIds)
    }

    @Test
    fun `spend saturates and shares only include priced records`() {
        val report = InsightsCalculator.monthly(
            2026, 8,
            listOf(
                record("max", "2026-08-01", brand = "A", price = Long.MAX_VALUE),
                record("overflow", "2026-08-02", brand = "A", price = 1),
                record("free", "2026-08-03", brand = "B", price = null),
            ),
            emptyList(),
        )

        assertEquals(Long.MAX_VALUE, report.period.totalSpendFen)
        assertEquals(listOf("A"), report.brandSpendShares.map { it.name })
        assertEquals(Long.MAX_VALUE, report.brandSpendShares.single().spendFen)
    }

    @Test
    fun `annual average and brand fractions use exact totals before display saturation`() {
        val report = InsightsCalculator.yearly(
            2026,
            listOf(
                record("a", "2026-01-01", brand = "A", price = Long.MAX_VALUE),
                record("b", "2026-02-01", brand = "B", price = Long.MAX_VALUE),
            ),
        )
        val monthly = InsightsCalculator.monthly(
            2026, 1,
            listOf(
                record("a", "2026-01-01", brand = "A", price = Long.MAX_VALUE),
                record("b", "2026-01-02", brand = "B", price = Long.MAX_VALUE),
            ),
            emptyList(),
        )

        assertEquals(Long.MAX_VALUE / 6, report.averageMonthlySpendFen)
        assertEquals(listOf(0.5, 0.5), monthly.brandSpendShares.map { it.fraction })
    }

    @Test
    fun `monthly validates dates and builds five local date weeks`() {
        val report = InsightsCalculator.monthly(
            2026, 8,
            listOf(
                record("first", "2026-08-01", price = 100),
                record("last", "2026-08-31", price = 200),
                record("bad", "2026-02-30", price = 999),
                record("other", "2026-09-01", price = 999),
            ),
            emptyList(),
        )

        assertEquals(2, report.period.cupCount)
        assertEquals(5, report.period.points.size)
        assertEquals(100L, report.period.points.first().spendFen)
        assertEquals(200L, report.period.points.last().spendFen)
    }

    @Test
    fun `snapshot names isolate reports from current catalog`() {
        val original = record("one", "2026-08-01", brand = "保存时品牌", item = "保存时产品")
        val report = InsightsCalculator.period(listOf(original), emptyList())

        assertEquals("保存时品牌", report.topBrands.single().name)
        assertEquals("保存时产品", report.topProducts.single().name)
    }

    @Test
    fun `year has twelve points and preserves high low ties among populated months`() {
        val report = InsightsCalculator.yearly(
            2026,
            listOf(
                record("jan", "2026-01-02", price = 100, rating = 8),
                record("feb", "2026-02-02", price = 200, rating = 9),
                record("mar", "2026-03-02", price = 200, rating = 10),
                record("apr", "2026-04-02", price = 100, rating = 7),
            ),
        )

        assertEquals(12, report.monthlyPoints.size)
        assertEquals(50L, report.averageMonthlySpendFen)
        assertEquals(listOf("2月", "3月"), report.highestSpendMonths)
        assertEquals(listOf("1月", "4月"), report.lowestSpendMonths)
        assertEquals(listOf("mar", "feb", "jan", "apr"), report.topRatedRecordIds)
        assertTrue(report.ratingTrendText != null)
    }

    @Test
    fun `fewer than two rated periods has facts but no trend conclusion`() {
        val monthly = InsightsCalculator.monthly(
            2026, 8, listOf(record("one", "2026-08-01", rating = 8)), emptyList(),
        )
        val yearly = InsightsCalculator.yearly(
            2026, listOf(record("one", "2026-08-01", rating = 8)),
        )

        assertNull(monthly.ratingTrendText)
        assertNull(yearly.ratingTrendText)
    }

    @Test
    fun `unrated records are never reported as best or worst`() {
        val report = InsightsCalculator.period(
            listOf(record("unrated", "2026-08-01", rating = null)), emptyList(),
        )

        assertTrue(report.bestRecordIds.isEmpty())
        assertTrue(report.worstRecordIds.isEmpty())
    }

    @Test
    fun `zero prior spend differs from missing prior prices without fake percentage`() {
        val zero = InsightsCalculator.monthly(
            2026, 8, listOf(record("now", "2026-08-01", price = 100)),
            listOf(record("prior", "2026-07-01", price = 0)),
        )

        assertEquals(SpendDeltaBaseline.ZERO, zero.spendDelta.baseline)
        assertNull(zero.spendDelta.percent)
    }

    private fun record(
        id: String,
        date: String,
        brand: String = "品牌",
        item: String = "产品",
        price: Long? = null,
        rating: Int? = null,
        itemType: ItemType = ItemType.CHAIN_PRODUCT,
        brew: String? = "拿铁",
    ) = DrinkRecord(
        id = id,
        occurredAtEpochMillis = 0,
        localDate = date,
        itemType = itemType,
        sourceItemId = "source-$id",
        brewMethod = brew,
        ratingHalfStars = rating,
        actualPriceFen = price,
        note = null,
        snapshot = DrinkSnapshot(brand, item, null, null, null),
    )
}
