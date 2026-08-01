package com.niumi.coffeejournal.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelsTest {
    @Test
    fun money_formats_integer_fen_as_cny() {
        assertEquals("¥9.90", Money(990).formatCny())
    }

    @Test
    fun money_rejects_negative_fen() {
        assertThrows(IllegalArgumentException::class.java) { Money(-1) }
    }

    @Test
    fun money_formats_zero_fen() {
        assertEquals("¥0.00", Money(0).formatCny())
    }

    @Test
    fun money_formats_single_digit_fen_with_leading_zero() {
        assertEquals("¥0.05", Money(5).formatCny())
    }

    @Test
    fun money_formats_long_max_value_without_precision_loss() {
        assertEquals("¥92233720368547758.07", Money(Long.MAX_VALUE).formatCny())
    }

    @Test
    fun rating_exposes_half_star_value() {
        assertEquals(4.5, Rating(9).stars, 0.0)
    }

    @Test
    fun rating_rejects_values_above_five_stars() {
        assertThrows(IllegalArgumentException::class.java) { Rating(11) }
    }

    @Test
    fun rating_accepts_both_inclusive_boundaries() {
        assertEquals(0.5, Rating(1).stars, 0.0)
        assertEquals(5.0, Rating(10).stars, 0.0)
    }

    @Test
    fun rating_rejects_zero() {
        assertThrows(IllegalArgumentException::class.java) { Rating(0) }
    }

    @Test
    fun snapshot_keeps_display_name() {
        val snapshot = DrinkSnapshot(
            brandName = "瑞幸",
            itemName = "生椰拿铁",
            origin = null,
            processing = null,
            imageAssetId = "img-1",
        )

        assertEquals("生椰拿铁", snapshot.itemName)
    }

    @Test
    fun catalog_item_rejects_information_completeness_outside_percentage_range() {
        assertThrows(IllegalArgumentException::class.java) {
            catalogItem(informationCompleteness = 101)
        }
    }

    @Test
    fun catalog_item_accepts_both_information_completeness_boundaries() {
        assertEquals(0, catalogItem(informationCompleteness = 0).informationCompleteness)
        assertEquals(100, catalogItem(informationCompleteness = 100).informationCompleteness)
    }

    @Test
    fun catalog_item_rejects_negative_information_completeness() {
        assertThrows(IllegalArgumentException::class.java) {
            catalogItem(informationCompleteness = -1)
        }
    }

    @Test
    fun record_reuses_rating_invariant_when_rating_is_present() {
        assertThrows(IllegalArgumentException::class.java) {
            drinkRecord(ratingHalfStars = 0)
        }
    }

    @Test
    fun record_reuses_money_invariant_when_price_is_present() {
        assertThrows(IllegalArgumentException::class.java) {
            drinkRecord(actualPriceFen = -1)
        }
    }

    @Test
    fun draft_reuses_rating_invariant_when_rating_is_present() {
        assertThrows(IllegalArgumentException::class.java) {
            drinkDraft(ratingHalfStars = 0)
        }
    }

    @Test
    fun draft_reuses_money_invariant_when_price_is_present() {
        assertThrows(IllegalArgumentException::class.java) {
            drinkDraft(actualPriceFen = -1)
        }
    }

    private fun catalogItem(informationCompleteness: Int) = CatalogItem(
        id = "item-1",
        brandId = "brand-1",
        type = ItemType.CHAIN_PRODUCT,
        name = "生椰拿铁",
        imageAssetId = null,
        origin = null,
        processing = null,
        roastLevel = null,
        flavorNotes = null,
        brewMethod = null,
        status = ItemStatus.ACTIVE,
        informationCompleteness = informationCompleteness,
    )

    private fun drinkDraft(
        ratingHalfStars: Int? = null,
        actualPriceFen: Long? = null,
    ) = DrinkDraft(
        itemType = ItemType.CHAIN_PRODUCT,
        sourceItemId = "item-1",
        brewMethod = null,
        ratingHalfStars = ratingHalfStars,
        actualPriceFen = actualPriceFen,
        note = "",
    )

    private fun drinkRecord(
        ratingHalfStars: Int? = null,
        actualPriceFen: Long? = null,
    ) = DrinkRecord(
        id = "record-1",
        occurredAtEpochMillis = 0,
        localDate = "2026-08-01",
        itemType = ItemType.CHAIN_PRODUCT,
        sourceItemId = "item-1",
        brewMethod = null,
        ratingHalfStars = ratingHalfStars,
        actualPriceFen = actualPriceFen,
        note = null,
        snapshot = DrinkSnapshot(
            brandName = "瑞幸",
            itemName = "生椰拿铁",
            origin = null,
            processing = null,
            imageAssetId = "img-1",
        ),
    )
}
