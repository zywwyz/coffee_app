package com.niumi.coffeejournal.journal

import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.ItemType
import java.math.BigDecimal
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

const val GENERIC_COFFEE_IMAGE = "coffee-placeholder"

data class CalendarDayUi(
    val localDate: String,
    val dayNumber: Int,
    val inDisplayedMonth: Boolean,
    val imagePath: String?,
    val brandLogoPath: String?,
    val drinkCount: Int,
)

data class MonthSummaryUi(
    val cupCount: Int,
    val totalSpendFen: Long,
    val averageRatingStars: Double?,
)

data class RecordEditorUi(
    val sourceType: ItemType = ItemType.CHAIN_PRODUCT,
    val selectedBrandId: String? = null,
    val selectedItemId: String? = null,
    val ratingHalfStars: Int? = null,
    val priceInput: String = "",
    val priceValid: Boolean = true,
    val actualPriceFen: Long? = null,
    val brewMethod: String = "",
    val note: String = "",
    val needsImagePrompt: Boolean = false,
    val saving: Boolean = false,
    val errorMessage: String? = null,
)

data class JournalUiState(
    val year: Int,
    val month: Int,
    val days: List<CalendarDayUi>,
    val records: List<DrinkRecord>,
    val selectedDate: String? = null,
    val summary: MonthSummaryUi,
    val editor: RecordEditorUi = RecordEditorUi(),
    val brands: List<Brand> = emptyList(),
    val items: List<CatalogItem> = emptyList(),
    val saveCompletedToken: Int = 0,
) {
    val selectedDayRecords: List<DrinkRecord>
        get() = records.filter { it.localDate == selectedDate }.sortedByDescending { it.occurredAtEpochMillis }

    companion object {
        fun empty(year: Int, month: Int) = JournalUiState(
            year = year,
            month = month,
            days = projectMonth(year, month, emptyList()),
            records = emptyList(),
            summary = summarizeMonth(emptyList()),
        )
    }
}

fun calendarImage(productImage: String?, brandLogo: String?): String =
    productImage ?: brandLogo ?: GENERIC_COFFEE_IMAGE

fun summarizeMonth(records: List<DrinkRecord>): MonthSummaryUi {
    val ratings = records.mapNotNull { it.ratingHalfStars }
    return MonthSummaryUi(
        cupCount = records.size,
        totalSpendFen = records.mapNotNull { it.actualPriceFen }.sum(),
        averageRatingStars = ratings.takeIf { it.isNotEmpty() }?.average()?.div(2.0),
    )
}

fun parseYuanToFen(input: String): Long? {
    val normalized = input.trim()
    if (!normalized.matches(Regex("\\d+(?:\\.\\d{0,2})?"))) return null
    return try {
        BigDecimal(normalized).movePointRight(2).longValueExact().takeIf { it >= 0 }
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}

fun projectMonth(
    year: Int,
    month: Int,
    records: List<DrinkRecord>,
    brandLogosByRecordId: Map<String, String?> = emptyMap(),
): List<CalendarDayUi> {
    require(year > 0 && month in 1..12)
    val first = GregorianCalendar(year, month - 1, 1).apply { isLenient = false }
    val mondayOffset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
    first.add(Calendar.DAY_OF_MONTH, -mondayOffset)
    val recordsByDate = records.groupBy { it.localDate }
    return List(42) { offset ->
        val date = first.clone() as Calendar
        date.add(Calendar.DAY_OF_MONTH, offset)
        val localDate = "%04d-%02d-%02d".format(
            Locale.ROOT,
            date.get(Calendar.YEAR),
            date.get(Calendar.MONTH) + 1,
            date.get(Calendar.DAY_OF_MONTH),
        )
        val dayRecords = recordsByDate[localDate].orEmpty()
        val latest = dayRecords.maxWithOrNull(compareBy<DrinkRecord> { it.occurredAtEpochMillis }.thenBy { it.id })
        val logo = latest?.let { brandLogosByRecordId[it.id] }
        CalendarDayUi(
            localDate = localDate,
            dayNumber = date.get(Calendar.DAY_OF_MONTH),
            inDisplayedMonth = date.get(Calendar.YEAR) == year && date.get(Calendar.MONTH) == month - 1,
            imagePath = latest?.let { calendarImage(it.snapshot.imageAssetId, logo) },
            brandLogoPath = logo,
            drinkCount = dayRecords.size,
        )
    }
}
