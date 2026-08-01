package com.niumi.coffeejournal.core.database

import org.junit.Assert.assertThrows
import org.junit.Test

class EntitiesTest {
    @Test
    fun `catalog item rejects completeness below zero`() {
        assertThrows(IllegalArgumentException::class.java) {
            catalogItem(informationCompleteness = -1)
        }
    }

    @Test
    fun `catalog item rejects completeness above one hundred`() {
        assertThrows(IllegalArgumentException::class.java) {
            catalogItem(informationCompleteness = 101)
        }
    }

    @Test
    fun `drink record rejects ratings outside half-star range`() {
        assertThrows(IllegalArgumentException::class.java) {
            drinkRecord(ratingHalfStars = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            drinkRecord(ratingHalfStars = 11)
        }
    }

    @Test
    fun `drink record rejects negative price`() {
        assertThrows(IllegalArgumentException::class.java) {
            drinkRecord(actualPriceFen = -1)
        }
    }

    @Test
    fun `draft record rejects ratings outside half-star range`() {
        assertThrows(IllegalArgumentException::class.java) {
            draftRecord(ratingHalfStars = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            draftRecord(ratingHalfStars = 11)
        }
    }

    @Test
    fun `draft record rejects negative price`() {
        assertThrows(IllegalArgumentException::class.java) {
            draftRecord(actualPriceFen = -1)
        }
    }

    private fun catalogItem(informationCompleteness: Int) = CatalogItemEntity(
        id = "item-1",
        brandId = "brand-1",
        type = "CHAIN_PRODUCT",
        name = "Flat White",
        normalizedName = "flat white",
        status = "ACTIVE",
        informationCompleteness = informationCompleteness,
    )

    private fun drinkRecord(
        ratingHalfStars: Int? = null,
        actualPriceFen: Long? = null,
    ) = DrinkRecordEntity(
        id = "record-1",
        occurredAtEpochMillis = 1,
        localDate = "2026-08-01",
        itemType = "CHAIN_PRODUCT",
        sourceItemId = "item-1",
        ratingHalfStars = ratingHalfStars,
        actualPriceFen = actualPriceFen,
        snapshotBrandName = "Example Coffee",
        snapshotItemName = "Flat White",
    )

    private fun draftRecord(
        ratingHalfStars: Int? = null,
        actualPriceFen: Long? = null,
    ) = DraftRecordEntity(
        id = "draft-1",
        revisionId = "revision-1",
        itemType = null,
        sourceItemId = null,
        brewMethod = null,
        ratingHalfStars = ratingHalfStars,
        actualPriceFen = actualPriceFen,
        note = "",
        updatedAtEpochMillis = 1,
    )
}
