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
    @Upsert
    suspend fun upsert(brand: BrandEntity)

    @Query("SELECT * FROM brands ORDER BY name")
    fun observe(): Flow<List<BrandEntity>>

    @Query("SELECT * FROM brands WHERE id = :id")
    suspend fun get(id: String): BrandEntity?
}

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

    @Delete
    suspend fun delete(record: DrinkRecordEntity)

    @Query("SELECT * FROM drink_records WHERE id = :id")
    suspend fun get(id: String): DrinkRecordEntity?

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

    @Query("SELECT * FROM image_assets WHERE id = :id")
    suspend fun get(id: String): ImageAssetEntity?

    @Query(
        """
        DELETE FROM image_assets
        WHERE id = :id
          AND NOT EXISTS (SELECT 1 FROM brands WHERE logoAssetId = :id)
          AND NOT EXISTS (SELECT 1 FROM catalog_items WHERE imageAssetId = :id)
          AND NOT EXISTS (
              SELECT 1 FROM drink_records WHERE snapshotImageAssetId = :id
          )
        """,
    )
    suspend fun deleteIfUnreferenced(id: String): Int

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM brands WHERE logoAssetId = :id) +
            (SELECT COUNT(*) FROM catalog_items WHERE imageAssetId = :id) +
            (SELECT COUNT(*) FROM drink_records WHERE snapshotImageAssetId = :id)
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
}

@Dao
interface DraftDao {
    @Upsert
    suspend fun upsert(draft: DraftRecordEntity)

    @Query("SELECT * FROM draft_records WHERE id = :id")
    suspend fun get(id: String): DraftRecordEntity?

    @Query("DELETE FROM draft_records WHERE id = :id")
    suspend fun delete(id: String): Int
}
