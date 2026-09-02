package com.niumi.coffeejournal.insights

import com.niumi.coffeejournal.core.model.CoffeeType
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.ItemType
import java.time.LocalDate

data class ComparisonPoint(val index: Int, val current: Int?, val previous: Int?)
data class ShareValue(val key: String, val label: String, val cups: Int, val fraction: Double)
data class RankedValue(val key: String, val name: String, val cups: Int, val latestAt: Long) { constructor(name: String, count: Int): this(name, name, count, 0); val count get() = cups }
data class HighlightRecord(val recordId: String, val brandName: String, val itemName: String, val ratingHalfStars: Int, val imageAssetId: String?, val brandLogoAssetId: String?, val tiedProductCount: Int)
data class HabitSummary(val cups: Int, val drinkingDays: Int, val longestStreak: Int, val averageRating: Double?, val totalSpendFen: Long?, val averagePriceFen: Long?, val cupDelta: Int?)
data class TrendPoint(val label:String,val spendFen:Long?,val pricedCupCount:Int,val ratingSampleCount:Int,val averageRating:Double?)
data class RatedRecordSummary(val recordId:String,val localDate:String,val brandName:String,val itemName:String,val ratingHalfStars:Int,val actualPriceFen:Long?,val brewMethod:String?,val note:String?,val origin:String?=null,val processing:String?=null,val roastLevel:String?=null,val flavorNotes:String?=null)
data class PeriodInsights(val cupCount:Int,val totalSpendFen:Long,val averagePriceFen:Long?,val averageRating:Double?,val topBrands:List<RankedValue>,val topProducts:List<RankedValue>,val topBeans:List<RankedValue>,val topBrewMethods:List<RankedValue>,val bestRecordIds:List<String>,val worstRecordIds:List<String>,val points:List<TrendPoint>,val bestRecords:List<RatedRecordSummary> = emptyList(),val worstRecords:List<RatedRecordSummary> = emptyList(),val habit: HabitSummary = HabitSummary(0,0,0,null,null,null,null),val coffeeTypeShares:List<ShareValue> = emptyList(),val brandShares:List<ShareValue> = emptyList(),val best:HighlightRecord?=null,val worst:HighlightRecord?=null)
enum class SpendDeltaBaseline { AVAILABLE, ZERO, MISSING }
data class SpendDelta(val amountFen:Long,val percent:Double?,val baseline:SpendDeltaBaseline)
data class BrandSpendShare(val name:String,val spendFen:Long,val fraction:Double)
data class MonthlyInsights(val year:Int,val month:Int,val period:PeriodInsights,val spendDelta:SpendDelta,val brandSpendShares:List<BrandSpendShare>,val ratingTrendText:String?,val habit:HabitSummary = period.habit,val trend:List<ComparisonPoint> = emptyList(),val coffeeTypeShares:List<ShareValue> = period.coffeeTypeShares,val brandShares:List<ShareValue> = period.brandShares,val topBrands:List<RankedValue> = period.topBrands,val topProducts:List<RankedValue> = period.topProducts,val best:HighlightRecord? = period.best,val worst:HighlightRecord? = period.worst)
data class YearlyInsights(val year:Int,val period:PeriodInsights,val averageMonthlySpendFen:Long,val monthlyPoints:List<TrendPoint>,val highestSpendMonths:List<String>,val lowestSpendMonths:List<String>,val topRatedRecordIds:List<String>,val ratingTrendText:String?,val topRatedRecords:List<RatedRecordSummary> = emptyList(),val highestRatedRecords:List<RatedRecordSummary> = emptyList(),val habit:HabitSummary = period.habit,val trend:List<ComparisonPoint> = emptyList(),val coffeeTypeShares:List<ShareValue> = period.coffeeTypeShares,val brandShares:List<ShareValue> = period.brandShares,val topBrands:List<RankedValue> = period.topBrands,val topProducts:List<RankedValue> = period.topProducts,val best:HighlightRecord? = period.best,val worst:HighlightRecord? = period.worst)

object InsightsCalculator {
 fun period(records:List<DrinkRecord>, points:List<TrendPoint> = emptyList()):PeriodInsights {
  val valid=records.filter { date(it)!=null }; val priced=valid.mapNotNull { it.actualPriceFen }; val rated=valid.mapNotNull { it.ratingHalfStars }
  val days=valid.mapNotNull(::date).distinct().sorted(); var run=0; var longest=0; var prev:LocalDate?=null; days.forEach { d->run=if(prev?.plusDays(1)==d)run+1 else 1; longest=maxOf(longest,run);prev=d }
  val rankedBrands=rank(valid){ it.snapshot.brandName.trim() }.take(3); val rankedProducts=rank(valid){ if(it.sourceItemId.isNotBlank()) it.sourceItemId else it.snapshot.brandName+"\u0000"+it.snapshot.itemName }.take(3)
  val types=CoffeeType.entries.map { type-> share(type.name,type.name,valid.count { it.snapshot.coffeeType==type },valid.size) }
  val grouped=valid.groupBy { it.snapshot.brandName.trim() }.map { (k,v)->share(k,k,v.size,valid.size) }.sortedWith(compareByDescending<ShareValue>{it.cups}.thenBy{it.key}); val brandShares=if(grouped.size<=4) grouped else grouped.take(4)+share("OTHER","OTHER",grouped.drop(4).sumOf{it.cups},valid.size)
  val best=highlight(valid,true); val worst=if(rated.minOrNull()==rated.maxOrNull()) null else highlight(valid,false)
  val habit=HabitSummary(valid.size,days.size,longest,rated.takeIf{it.isNotEmpty()}?.average()?.div(2),priced.takeIf{it.isNotEmpty()}?.sum(),priced.takeIf{it.isNotEmpty()}?.average()?.toLong(),null)
  return PeriodInsights(valid.size,priced.sum(),habit.averagePriceFen,habit.averageRating,rankedBrands,rankedProducts,emptyList(),emptyList(),emptyList(),emptyList(),points,habit=habit,coffeeTypeShares=types,brandShares=brandShares,best=best,worst=worst)
 }
 fun monthly(year:Int,month:Int,records:List<DrinkRecord>,previousMonthRecords:List<DrinkRecord>,today:String=LocalDate.now().toString()):MonthlyInsights {
  val now=LocalDate.parse(today); val current=records.filter { date(it)?.let { d->d.year==year&&d.monthValue==month }==true }; val prior=previousMonthRecords.filter { date(it)?.let { d->d==LocalDate.of(year,month,1).minusMonths(1).withDayOfMonth(d.dayOfMonth) }==true }
  val days=if(year==now.year&&month==now.monthValue) now.dayOfMonth else LocalDate.of(year,month,1).lengthOfMonth(); val priorDays=LocalDate.of(year,month,1).minusMonths(1).lengthOfMonth(); val trend=(1..days).map { i->ComparisonPoint(i,current.count{date(it)?.dayOfMonth==i},if(i<=priorDays) prior.count{date(it)?.dayOfMonth==i} else null) }.runningFold(ComparisonPoint(0,0,0)){a,b->ComparisonPoint(b.index,(a.current?:0)+(b.current?:0),b.previous?.let{(a.previous?:0)+it})}.drop(1)
  val p=period(current); return MonthlyInsights(year,month,p,SpendDelta(0,null,SpendDeltaBaseline.MISSING),emptyList(),null,trend=trend)
 }
 fun yearly(year:Int,records:List<DrinkRecord>,today:String=LocalDate.now().toString()):YearlyInsights { val now=LocalDate.parse(today); val current=records.filter{date(it)?.year==year}; val n=if(year==now.year)now.monthValue else 12; val trend=(1..n).map{i->ComparisonPoint(i,current.count{date(it)?.monthValue==i},records.count{date(it)?.let { d -> d.year==year-1 && d.monthValue==i }==true}.takeIf{it>0})}; val legacy=(1..12).map { i -> legacyPoint("${i}月", current.filter { date(it)?.monthValue == i }) }; val p=period(current); return YearlyInsights(year,p,0,legacy,emptyList(),emptyList(),emptyList(),null,trend=trend) }
 private fun legacyPoint(label:String, records:List<DrinkRecord>): TrendPoint { val priced=records.mapNotNull { it.actualPriceFen }; val rated=records.mapNotNull { it.ratingHalfStars }; return TrendPoint(label, priced.takeIf { it.isNotEmpty() }?.sum(), priced.size, rated.size, rated.takeIf { it.isNotEmpty() }?.average()?.div(2)) }
 private fun rank(records:List<DrinkRecord>,key:(DrinkRecord)->String)=records.groupBy(key).map{(k,v)->RankedValue(k,v.maxBy{it.occurredAtEpochMillis}.let{if(k.contains('\u0000'))it.snapshot.brandName+" · "+it.snapshot.itemName else it.snapshot.brandName},v.size,v.maxOf{it.occurredAtEpochMillis})}.sortedWith(compareByDescending<RankedValue>{it.cups}.thenByDescending{it.latestAt}.thenBy{it.key})
 private fun highlight(records:List<DrinkRecord>,best:Boolean):HighlightRecord? { val rated=records.filter{it.ratingHalfStars!=null}; val target=(if(best)rated.maxOfOrNull{it.ratingHalfStars!!} else rated.minOfOrNull{it.ratingHalfStars!!})?:return null; val groups=rated.filter{it.ratingHalfStars==target}.groupBy{if(it.sourceItemId.isNotBlank())it.sourceItemId else it.snapshot.brandName+"\u0000"+it.snapshot.itemName}; val chosen=groups.values.map{it.maxBy{r->r.occurredAtEpochMillis}}.sortedWith(compareByDescending<DrinkRecord>{it.occurredAtEpochMillis}.thenBy{it.id}).first(); return HighlightRecord(chosen.id,chosen.snapshot.brandName,chosen.snapshot.itemName,target,chosen.snapshot.imageAssetId,chosen.snapshot.brandLogoAssetId,groups.size-1) }
 private fun share(key:String,label:String,cups:Int,total:Int)=ShareValue(key,label,cups,if(total==0)0.0 else cups.toDouble()/total)
 private fun date(r:DrinkRecord)=runCatching{LocalDate.parse(r.localDate)}.getOrNull()
}
