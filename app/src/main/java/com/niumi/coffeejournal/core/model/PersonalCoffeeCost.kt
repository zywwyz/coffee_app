package com.niumi.coffeejournal.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/** Calculate only the final amount in fen; no rounded unit price enters the calculation. */
fun personalCoffeeCost(
    type: PersonalProductType,
    purchasePriceFen: Long?,
    packageWeightMg: Long?,
    packageCount: Long?,
    consumedWeightMg: Long?,
    consumedCount: Long?,
): Long? {
    val price = purchasePriceFen?.takeIf { it >= 0 } ?: return null
    val total = (if (type == PersonalProductType.BEANS) packageWeightMg else packageCount)?.takeIf { it > 0 } ?: return null
    val used = (if (type == PersonalProductType.BEANS) consumedWeightMg else consumedCount)?.takeIf { it > 0 } ?: return null
    return try {
        BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(used))
            .divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP).longValueExact()
    } catch (_: ArithmeticException) { null }
}

fun parseGramsToMilligrams(input: String): Long? {
    if (!input.trim().matches(Regex("\\d+(?:\\.\\d{0,3})?"))) return null
    return try { BigDecimal(input.trim()).movePointRight(3).longValueExact().takeIf { it > 0 } }
    catch (_: ArithmeticException) { null }
    catch (_: NumberFormatException) { null }
}

fun parsePositiveCount(input: String): Long? = input.trim().takeIf { it.matches(Regex("\\d+")) }?.toLongOrNull()?.takeIf { it > 0 }
fun formatMilligrams(value: Long): String = BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString()

internal fun validatePersonalAmounts(price: Long?, weight: Long?, count: Long?, consumedWeight: Long? = null, consumedCount: Long? = null) {
    require(price == null || price >= 0) { "Purchase price cannot be negative" }
    listOf(weight, count, consumedWeight, consumedCount).forEach { require(it == null || it > 0) { "Quantity must be positive" } }
}

fun DrinkDraft.recalculatePersonalPrice(): DrinkDraft = if (itemType == ItemType.PERSONAL_BEAN && priceMode == PriceMode.AUTO) {
    copy(actualPriceFen = personalCoffeeCost(personalProductType, purchasePriceFen, packageWeightMg, packageCount, consumedWeightMg, consumedCount))
} else this
