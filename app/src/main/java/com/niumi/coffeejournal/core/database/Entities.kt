package com.niumi.coffeejournal.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.niumi.coffeejournal.core.model.Money
import com.niumi.coffeejournal.core.model.Rating

@Entity(
    tableName = "brands",
    foreignKeys = [
        ForeignKey(
            entity = ImageAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["logoAssetId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["logoAssetId"])],
)
data class BrandEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val logoAssetId: String?,
    val maintenanceMode: String,
    val publicSourceUrl: String?,
)

@Entity(
    tableName = "catalog_items",
    foreignKeys = [
        ForeignKey(
            entity = BrandEntity::class,
            parentColumns = ["id"],
            childColumns = ["brandId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ImageAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["imageAssetId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["brandId", "normalizedName"], unique = true),
        Index(value = ["imageAssetId"]),
    ],
)
data class CatalogItemEntity(
    @PrimaryKey val id: String,
    val brandId: String,
    val type: String,
    val name: String,
    val normalizedName: String,
    val imageAssetId: String? = null,
    val origin: String? = null,
    val processing: String? = null,
    val roastLevel: String? = null,
    val flavorNotes: String? = null,
    val brewMethod: String? = null,
    val status: String,
    val caffeineMg: Double? = null,
    val officialDescription: String? = null,
    val purchaseDate: String? = null,
    val roastDate: String? = null,
    val sourceUrl: String? = null,
    val sourceFetchedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val informationCompleteness: Int = 0,
) {
    init {
        require(informationCompleteness in 0..100) {
            "Information completeness must be between 0 and 100"
        }
    }
}

@Entity(
    tableName = "drink_records",
    foreignKeys = [
        ForeignKey(
            entity = ImageAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotImageAssetId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["localDate", "occurredAtEpochMillis"]),
        Index(value = ["snapshotImageAssetId"]),
    ],
)
data class DrinkRecordEntity(
    @PrimaryKey val id: String,
    val occurredAtEpochMillis: Long,
    val localDate: String,
    val itemType: String,
    val sourceItemId: String,
    val brewMethod: String? = null,
    val ratingHalfStars: Int? = null,
    val actualPriceFen: Long? = null,
    val note: String? = null,
    val snapshotBrandName: String,
    val snapshotItemName: String,
    val snapshotOrigin: String? = null,
    val snapshotProcessing: String? = null,
    val snapshotImageAssetId: String? = null,
    val snapshotRoastLevel: String? = null,
    val snapshotFlavorNotes: String? = null,
) {
    init {
        ratingHalfStars?.let(::Rating)
        actualPriceFen?.let(::Money)
    }
}

@Entity(
    tableName = "image_assets",
    indices = [Index(value = ["sha256"], unique = true)],
)
data class ImageAssetEntity(
    @PrimaryKey val id: String,
    val localPath: String,
    val sha256: String,
    val kind: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "catalog_updates",
    indices = [Index(value = ["brandId", "fetchedAtEpochMillis"])],
)
data class CatalogUpdateEntity(
    @PrimaryKey val id: String,
    val brandId: String,
    val fetchedAtEpochMillis: Long,
    val status: String,
    val sourceUrl: String?,
    val errorMessage: String?,
)

@Entity(tableName = "draft_records")
data class DraftRecordEntity(
    @PrimaryKey val id: String,
    val itemType: String?,
    val sourceItemId: String?,
    val brewMethod: String?,
    val ratingHalfStars: Int?,
    val actualPriceFen: Long?,
    val note: String,
    val updatedAtEpochMillis: Long,
) {
    init {
        ratingHalfStars?.let(::Rating)
        actualPriceFen?.let(::Money)
    }
}
