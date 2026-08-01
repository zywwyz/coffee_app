package com.niumi.coffeejournal.core.model

import kotlinx.serialization.Serializable

@JvmInline
value class Money(val fen: Long) {
    init {
        require(fen >= 0) { "Money cannot be negative" }
    }

    fun formatCny(): String {
        val yuan = fen / FEN_PER_YUAN
        val remainderFen = fen % FEN_PER_YUAN
        return "¥$yuan.${remainderFen.toString().padStart(2, '0')}"
    }

    private companion object {
        const val FEN_PER_YUAN = 100
    }
}

@JvmInline
value class Rating(val halfStars: Int) {
    init {
        require(halfStars in 1..10) { "Rating must be between 0.5 and 5 stars" }
    }

    val stars: Double
        get() = halfStars / 2.0
}

@Serializable
enum class BrandType {
    CHAIN,
    ROASTER,
}

@Serializable
enum class ItemType {
    CHAIN_PRODUCT,
    PERSONAL_BEAN,
}

@Serializable
enum class ItemStatus {
    ACTIVE,
    NEEDS_IMAGE,
    DISCONTINUED,
    ARCHIVED,
}

@Serializable
enum class MaintenanceMode {
    PUBLIC_SOURCE,
    MANUAL_ONLY,
}

@Serializable
data class DrinkSnapshot(
    val brandName: String,
    val itemName: String,
    val origin: String?,
    val processing: String?,
    val imageAssetId: String?,
    val brandLogoAssetId: String? = null,
    val roastLevel: String? = null,
    val flavorNotes: String? = null,
)

@Serializable
data class DrinkRecord(
    val id: String,
    val occurredAtEpochMillis: Long,
    val localDate: String,
    val itemType: ItemType,
    val sourceItemId: String,
    val brewMethod: String?,
    val ratingHalfStars: Int?,
    val actualPriceFen: Long?,
    val note: String?,
    val snapshot: DrinkSnapshot,
) {
    init {
        ratingHalfStars?.let(::Rating)
        actualPriceFen?.let(::Money)
    }
}

data class Brand(
    val id: String,
    val type: BrandType,
    val name: String,
    val logoAssetId: String?,
    val maintenanceMode: MaintenanceMode,
    val publicSourceUrl: String?,
)

data class CatalogItem(
    val id: String,
    val brandId: String,
    val type: ItemType,
    val name: String,
    val imageAssetId: String?,
    val origin: String?,
    val processing: String?,
    val roastLevel: String?,
    val flavorNotes: String?,
    val brewMethod: String?,
    val status: ItemStatus,
    val caffeineMg: Double? = null,
    val officialDescription: String? = null,
    val purchaseDate: String? = null,
    val roastDate: String? = null,
    val sourceUrl: String? = null,
    val sourceFetchedAt: Long? = null,
    val informationCompleteness: Int = 0,
    val category: String? = null,
    val specificationDescription: String? = null,
) {
    init {
        require(informationCompleteness in 0..100) {
            "Information completeness must be between 0 and 100"
        }
    }
}

data class DrinkDraft(
    val revisionId: String,
    val itemType: ItemType,
    val sourceItemId: String,
    val brewMethod: String?,
    val ratingHalfStars: Int?,
    val actualPriceFen: Long?,
    val note: String,
) {
    init {
        require(revisionId.isNotBlank()) { "Draft revision id cannot be blank" }
        ratingHalfStars?.let(::Rating)
        actualPriceFen?.let(::Money)
    }
}
