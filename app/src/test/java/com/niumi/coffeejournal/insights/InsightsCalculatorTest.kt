package com.niumi.coffeejournal.insights

import com.niumi.coffeejournal.core.model.CoffeeType
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import com.niumi.coffeejournal.core.model.ItemType
import org.junit.Assert.*
import org.junit.Test

class InsightsCalculatorTest {
    @Test fun `current month stops daily trend at today and compares prior common days`() {
        val report = InsightsCalculator.monthly(2026, 9, listOf(r("c1", "2026-09-01"), r("c3", "2026-09-03"), r("future", "2026-09-30")), listOf(r("p1", "2026-08-01"), r("p3", "2026-08-03")), today = "2026-09-03")
        assertEquals(3, report.trend.size); assertEquals(listOf(1, 1, 2), report.trend.map { it.current }); assertEquals(listOf(1, 1, 2), report.trend.map { it.previous })
    }
    @Test fun `historical and leap month trends use full current days and null previous overflow`() {
        val report = InsightsCalculator.monthly(2024, 3, listOf(r("d30", "2024-03-30")), listOf(r("d29", "2024-02-29")), today = "2026-09-03")
        assertEquals(31, report.trend.size); assertEquals(1, report.trend[29].current); assertNull(report.trend[29].previous)
    }
    @Test fun `year trend cuts current year and has twelve points for historical years`() {
        val current = InsightsCalculator.yearly(2026, listOf(r("jan", "2026-01-01"), r("apr", "2026-04-01"), r("old", "2025-01-01")), today = "2026-04-10")
        assertEquals(4, current.trend.size); assertEquals(listOf(1, 0, 0, 1), current.trend.map { it.current })
        assertEquals(1, current.trend.first().previous); assertNull(current.trend[1].previous)
        assertEquals(12, InsightsCalculator.yearly(2025, emptyList(), today = "2026-04-10").trend.size)
    }
    @Test fun `legacy yearly points retain real monthly spend and rating facts`() {
        val report = InsightsCalculator.yearly(2025, listOf(r("jan", "2025-01-01", price = 300, rating = 8)))
        assertEquals(300L, report.monthlyPoints.first().spendFen)
        assertEquals(1, report.monthlyPoints.first().pricedCupCount)
        assertEquals(4.0, report.monthlyPoints.first().averageRating!!, 0.0)
    }
    @Test fun `habit uses unique local dates consecutive streak and price denominators`() {
        val habit = InsightsCalculator.period(listOf(r("a", "2026-01-01", price=100, rating=8), r("b", "2026-01-01", price=null), r("c", "2026-01-02", price=300, rating=null), r("d", "2026-01-04", price=null, rating=10))).habit
        assertEquals(3, habit.drinkingDays); assertEquals(2, habit.longestStreak); assertEquals(400L, habit.totalSpendFen); assertEquals(200L, habit.averagePriceFen); assertEquals(4.5, habit.averageRating!!, 0.0)
    }
    @Test fun `coffee type shares cover all four types and brands retain top four plus other`() {
        val records = listOf(r("1","2026-01-01",type=CoffeeType.BLACK,brand="A"),r("2","2026-01-02",type=CoffeeType.FRUIT,brand="B"),r("3","2026-01-03",type=CoffeeType.MILK,brand="C"),r("4","2026-01-04",type=CoffeeType.HAND_BREW,brand="D"),r("5","2026-01-05",brand="E"))
        val p=InsightsCalculator.period(records); assertEquals(4,p.coffeeTypeShares.size); assertEquals(listOf("A","B","C","D","OTHER"),p.brandShares.map { it.key })
    }
    @Test fun `rankings use newest then stable keys and limit three`() {
        val p=InsightsCalculator.period(listOf(r("z","2026-01-01",brand="B",item="same",at=1),r("a","2026-01-01",brand="A",item="same",at=2),r("c","2026-01-01",brand="C"),r("d","2026-01-01",brand="D")))
        assertEquals(listOf("A","B","C"),p.topBrands.map { it.name }); assertEquals("A\u0000same",p.topProducts.first().key)
    }
    @Test fun `source item id keeps the latest real product display name`() {
        val p = InsightsCalculator.period(listOf(r("a", "2026-01-01", "A", "Old", at=1, source="same"), r("b", "2026-01-02", "B", "New", at=2, source="same")))
        assertEquals("B · New", p.topProducts.single().name)
    }
    @Test fun `brand share fourth place uses newest tie breaker before other`() {
        val p=InsightsCalculator.period(listOf(r("a","2026-01-01",brand="A",at=1),r("b","2026-01-01",brand="B",at=2),r("c","2026-01-01",brand="C",at=3),r("d","2026-01-01",brand="D",at=4),r("e","2026-01-01",brand="E",at=5)))
        assertEquals(listOf("E","D","C","B","OTHER"),p.brandShares.map{it.key})
    }
    @Test fun `future current period records affect neither summary nor trend`() {
        val p=InsightsCalculator.monthly(2026,9,listOf(r("now","2026-09-01",rating=10),r("future","2026-09-20",brand="Future",rating=1)),emptyList(),today="2026-09-02")
        assertEquals(1,p.habit.cups); assertEquals("品牌",p.topBrands.single().name); assertEquals("now",p.best!!.recordId); assertEquals(1,p.trend.last().current)
    }
    @Test fun `future current year records affect neither aggregates nor trend`() {
        val p=InsightsCalculator.yearly(2026,listOf(r("now","2026-01-01",price=100),r("future","2026-09-20",price=300,brand="Future")),today="2026-04-10")
        assertEquals(1,p.habit.cups); assertEquals(100L,p.period.totalSpendFen); assertEquals(listOf(1,0,0,0),p.trend.map { it.current })
    }
    @Test fun `empty period has no priced metrics`() {
        val p=InsightsCalculator.period(emptyList())
        assertEquals(0,p.habit.cups); assertNull(p.habit.totalSpendFen); assertNull(p.habit.averagePriceFen)
    }
    @Test fun `spend saturates at long maximum`() {
        val p=InsightsCalculator.period(listOf(r("a","2026-01-01",price=Long.MAX_VALUE),r("b","2026-01-02",price=1)))
        assertEquals(Long.MAX_VALUE,p.habit.totalSpendFen)
    }
    @Test fun `average price uses exact total before display saturation`() {
        val max = InsightsCalculator.period(listOf(r("a","2026-01-01",price=Long.MAX_VALUE),r("b","2026-01-02",price=Long.MAX_VALUE)))
        val mixed = InsightsCalculator.period(listOf(r("a","2026-01-01",price=Long.MAX_VALUE),r("b","2026-01-02",price=1)))
        assertEquals(Long.MAX_VALUE, max.habit.averagePriceFen)
        assertEquals(Long.MAX_VALUE / 2 + 1, mixed.habit.averagePriceFen)
    }
    @Test fun `yearly legacy monthly spend saturates at long maximum`() {
        val p=InsightsCalculator.yearly(2026,listOf(r("a","2026-01-01",price=Long.MAX_VALUE),r("b","2026-01-02",price=1)),today="2026-04-10")
        assertEquals(Long.MAX_VALUE,p.monthlyPoints.first().spendFen)
    }
    @Test fun `best and worst aggregate distinct products retaining latest asset and equal ratings omit worst`() {
        val p=InsightsCalculator.period(listOf(r("old","2026-01-01",brand="A",item="X",rating=10,at=1,image="old"),r("new","2026-01-02",brand="A",item="X",rating=10,at=2,image="new"),r("low","2026-01-03",brand="B",item="Y",rating=4),r("low2","2026-01-04",brand="C",item="Z",rating=4,at=3)))
        assertEquals("new",p.best!!.recordId); assertEquals("new",p.best!!.imageAssetId); assertEquals(0,p.best!!.tiedProductCount); assertEquals("low2",p.worst!!.recordId); assertEquals(1,p.worst!!.tiedProductCount)
        assertNull(InsightsCalculator.period(listOf(r("x","2026-01-01",rating=8),r("y","2026-01-02",rating=8))).worst)
    }
    private fun r(id:String,date:String,brand:String="品牌",item:String="产品",price:Long?=null,rating:Int?=null,type:CoffeeType=CoffeeType.BLACK,at:Long=0,image:String?=null,source:String="")=DrinkRecord(id,at,date,ItemType.CHAIN_PRODUCT,source,null,rating,price,null,DrinkSnapshot(brand,item,null,null,image,brandLogoAssetId="logo",coffeeType=type))
}
