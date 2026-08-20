package com.niumi.coffeejournal.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BrandDao {
    @Transaction
    suspend fun upsert(brand: BrandEntity) {
        if (update(brand) == 0) insert(brand)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(brand: BrandEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(brand: BrandEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringExisting(brands: List<BrandEntity>): List<Long>

    @Transaction
    suspend fun seedIgnoringExisting(brands: List<BrandEntity>) {
        insertIgnoringExisting(brands)
    }

    @Query("UPDATE brands SET logoAssetId=:assetId WHERE id=:brandId AND logoAssetId IS NULL")
    suspend fun attachLogoIfMissing(brandId: String, assetId: String): Int

    @Query("SELECT * FROM brands ORDER BY name")
    fun observe(): Flow<List<BrandEntity>>

    @Query("SELECT * FROM brands WHERE type = :type ORDER BY name")
    fun observeByType(type: String): Flow<List<BrandEntity>>

    @Query("SELECT * FROM brands WHERE id = :id")
    suspend fun get(id: String): BrandEntity?

    @Query("SELECT * FROM brands WHERE type = :type AND normalizedName IN (:names)")
    suspend fun getByNormalizedNames(type: String, names: List<String>): List<BrandEntity>

    @Transaction
    suspend fun adoptAsBundledId(legacy: BrandEntity, bundledId: String) {
        if (legacy.id == bundledId || get(bundledId) != null) return
        renameId(legacy.id, bundledId)
    }

    @Query("UPDATE brands SET id = :toBrandId WHERE id = :fromBrandId")
    suspend fun renameId(fromBrandId: String, toBrandId: String)

    @Query("UPDATE catalog_items SET brandId = :toBrandId WHERE brandId = :fromBrandId")
    suspend fun moveCatalogItemsBrandId(fromBrandId: String, toBrandId: String)

    @Query("DELETE FROM brands WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM brands WHERE type = :type AND normalizedName = :name AND id != :id)",
    )
    suspend fun existsNamedOther(type: String, name: String, id: String): Boolean

    @Query(
        """
        SELECT b.*,
          (SELECT COUNT(*) FROM catalog_items i WHERE i.brandId = b.id) AS itemCount
        FROM brands b WHERE b.type = :type ORDER BY b.name
        """,
    )
    fun observeOverviews(type: String): Flow<List<BrandOverviewRow>>
}

data class BrandOverviewRow(
    val id: String,
    val type: String,
    val name: String,
    val normalizedName: String,
    val logoAssetId: String?,
    val maintenanceMode: String,
    val publicSourceUrl: String?,
    val itemCount: Int,
)

@Dao
interface CatalogItemDao {
    @Transaction
    suspend fun upsert(item: CatalogItemEntity) {
        if (update(item) == 0) {
            insert(item)
        }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: CatalogItemEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(item: CatalogItemEntity): Int

    @Query("SELECT * FROM catalog_items WHERE brandId = :brandId ORDER BY name")
    fun observeByBrand(brandId: String): Flow<List<CatalogItemEntity>>

    @Query("SELECT * FROM catalog_items WHERE id = :id")
    suspend fun get(id: String): CatalogItemEntity?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM catalog_items WHERE brandId = :brandId AND normalizedName = :name AND id != :id)",
    )
    suspend fun existsNamedOther(brandId: String, name: String, id: String): Boolean

    @Query(
        """
        SELECT * FROM catalog_items
        WHERE brandId = :brandId AND status = 'ACTIVE'
        ORDER BY COALESCE(sourceFetchedAt, 0) DESC, name
        LIMIT 1
        """,
    )
    suspend fun getLastActiveByBrand(brandId: String): CatalogItemEntity?
}

@Dao
interface DrinkDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: DrinkRecordEntity)

    @Update
    suspend fun update(record: DrinkRecordEntity)

    @Query(
        """
        UPDATE drink_records SET
          occurredAtEpochMillis=:occurredAtEpochMillis, localDate=:localDate,
          itemType=:itemType, sourceItemId=:sourceItemId, brewMethod=:brewMethod,
          ratingHalfStars=:ratingHalfStars, actualPriceFen=:actualPriceFen, note=:note,
          snapshotBrandName=:snapshotBrandName, snapshotItemName=:snapshotItemName,
          snapshotOrigin=:snapshotOrigin, snapshotProcessing=:snapshotProcessing,
          snapshotImageAssetId=:snapshotImageAssetId,
          snapshotBrandLogoAssetId=:snapshotBrandLogoAssetId,
          snapshotRoastLevel=:snapshotRoastLevel, snapshotFlavorNotes=:snapshotFlavorNotes,
          updatedAtEpochMillis=:updatedAtEpochMillis, revision=:newRevision
        WHERE id=:id AND revision=:expectedRevision
        """,
    )
    suspend fun updateIfRevision(
        id: String,
        expectedRevision: Int,
        occurredAtEpochMillis: Long,
        localDate: String,
        itemType: String,
        sourceItemId: String,
        brewMethod: String?,
        ratingHalfStars: Int?,
        actualPriceFen: Long?,
        note: String?,
        snapshotBrandName: String,
        snapshotItemName: String,
        snapshotOrigin: String?,
        snapshotProcessing: String?,
        snapshotImageAssetId: String?,
        snapshotBrandLogoAssetId: String?,
        snapshotRoastLevel: String?,
        snapshotFlavorNotes: String?,
        updatedAtEpochMillis: Long,
        newRevision: Int,
    ): Int

    @Delete
    suspend fun delete(record: DrinkRecordEntity)

    @Query("SELECT * FROM drink_records WHERE id = :id")
    suspend fun get(id: String): DrinkRecordEntity?

    @Query(
        """
        SELECT actualPriceFen FROM drink_records
        WHERE sourceItemId = :sourceItemId AND actualPriceFen IS NOT NULL
        ORDER BY occurredAtEpochMillis DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun lastActualPriceFen(sourceItemId: String): Long?

    @Query(
        """
        SELECT * FROM drink_records
        WHERE localDate BETWEEN :startLocalDate AND :endLocalDate
        ORDER BY occurredAtEpochMillis DESC
        """,
    )
    fun observeRange(
        startLocalDate: String,
        endLocalDate: String,
    ): Flow<List<DrinkRecordEntity>>
}

@Dao
interface ImageAssetDao {
    @Upsert
    suspend fun upsert(asset: ImageAssetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringExisting(asset: ImageAssetEntity): Long

    @Query("SELECT * FROM image_assets WHERE id = :id")
    suspend fun get(id: String): ImageAssetEntity?

    @Query("SELECT * FROM image_assets WHERE sha256 = :sha256 LIMIT 1")
    suspend fun getBySha256(sha256: String): ImageAssetEntity?

    @Query(
        """
        DELETE FROM image_assets
        WHERE id = :id
          AND NOT EXISTS (SELECT 1 FROM brands WHERE logoAssetId = :id)
          AND NOT EXISTS (SELECT 1 FROM catalog_items WHERE imageAssetId = :id)
          AND NOT EXISTS (
              SELECT 1 FROM drink_records WHERE snapshotImageAssetId = :id
          )
          AND NOT EXISTS (
              SELECT 1 FROM drink_records WHERE snapshotBrandLogoAssetId = :id
          )
        """,
    )
    suspend fun deleteIfUnreferenced(id: String): Int

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM brands WHERE logoAssetId = :id) +
            (SELECT COUNT(*) FROM catalog_items WHERE imageAssetId = :id) +
            (SELECT COUNT(*) FROM drink_records WHERE snapshotImageAssetId = :id) +
            (SELECT COUNT(*) FROM drink_records WHERE snapshotBrandLogoAssetId = :id)
        """,
    )
    suspend fun referenceCount(id: String): Int
}

@Dao
interface CatalogUpdateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(update: CatalogUpdateEntity)

    @Query(
        """
        SELECT * FROM catalog_updates
        WHERE brandId = :brandId
        ORDER BY fetchedAtEpochMillis DESC, id DESC
        LIMIT 1
        """,
    )
    fun observeLatest(brandId: String): Flow<CatalogUpdateEntity?>

    @Query(
        """
        SELECT * FROM catalog_updates
        WHERE brandId = :brandId
        ORDER BY fetchedAtEpochMillis DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun latest(brandId: String): CatalogUpdateEntity?
}

@Dao
interface DraftDao {
    @Upsert
    suspend fun upsert(draft: DraftRecordEntity)

    @Query("SELECT * FROM draft_records WHERE id = :id")
    suspend fun get(id: String): DraftRecordEntity?

    @Query("DELETE FROM draft_records WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM draft_records WHERE id = :id AND revisionId = :revisionId")
    suspend fun deleteIfRevision(id: String, revisionId: String): Int

    @Query("DELETE FROM draft_records WHERE id = :id AND editingRecordId = :recordId")
    suspend fun deleteIfEditingRecord(id: String, recordId: String): Int
}
