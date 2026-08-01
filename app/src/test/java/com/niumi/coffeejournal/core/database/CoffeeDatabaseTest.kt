package com.niumi.coffeejournal.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoffeeDatabaseTest {
    private lateinit var database: CoffeeDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        database = Room.inMemoryDatabaseBuilder(context, CoffeeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `catalog rename does not change an existing drink snapshot name`() = runBlocking {
        val brand = BrandEntity(
            id = "brand-1",
            type = "CHAIN",
            name = "Example Coffee",
            logoAssetId = null,
            maintenanceMode = "MANUAL_ONLY",
            publicSourceUrl = null,
        )
        val originalItem = CatalogItemEntity(
            id = "item-1",
            brandId = brand.id,
            type = "CHAIN_PRODUCT",
            name = "Old Name",
            normalizedName = "old name",
            status = "ACTIVE",
        )
        val record = DrinkRecordEntity(
            id = "record-1",
            occurredAtEpochMillis = 1_754_044_800_000,
            localDate = "2025-08-01",
            itemType = "CHAIN_PRODUCT",
            sourceItemId = originalItem.id,
            snapshotBrandName = brand.name,
            snapshotItemName = originalItem.name,
        )

        database.brandDao().upsert(brand)
        database.catalogItemDao().upsert(originalItem)
        database.drinkDao().insert(record)

        database.catalogItemDao().upsert(
            originalItem.copy(name = "New Name", normalizedName = "new name"),
        )

        assertEquals("Old Name", database.drinkDao().get(record.id)?.snapshotItemName)
    }

    @Test
    fun `same brand cannot contain duplicate normalized catalog names`() = runBlocking {
        val brand = brand(id = "brand-1")
        database.brandDao().upsert(brand)
        database.catalogItemDao().upsert(
            catalogItem(id = "item-1", brandId = brand.id, normalizedName = "flat white"),
        )

        try {
            database.catalogItemDao().upsert(
                catalogItem(id = "item-2", brandId = brand.id, normalizedName = "flat white"),
            )
            fail("Expected the unique brand/name index to reject the duplicate")
        } catch (_: SQLiteConstraintException) {
            // Expected: the unique index protects names within a brand.
        }
    }

    @Test
    fun `different brands can use the same normalized catalog name`() = runBlocking {
        val firstBrand = brand(id = "brand-1")
        val secondBrand = brand(id = "brand-2")
        database.brandDao().upsert(firstBrand)
        database.brandDao().upsert(secondBrand)

        val firstItem = catalogItem(
            id = "item-1",
            brandId = firstBrand.id,
            normalizedName = "flat white",
        )
        val secondItem = catalogItem(
            id = "item-2",
            brandId = secondBrand.id,
            normalizedName = "flat white",
        )
        database.catalogItemDao().upsert(firstItem)
        database.catalogItemDao().upsert(secondItem)

        assertNotNull(database.catalogItemDao().get(firstItem.id))
        assertNotNull(database.catalogItemDao().get(secondItem.id))
    }

    @Test
    fun `image referenced by a drink snapshot cannot be deleted`() = runBlocking {
        val asset = imageAsset(id = "image-1")
        database.imageAssetDao().upsert(asset)
        val record = DrinkRecordEntity(
            id = "record-1",
            occurredAtEpochMillis = 1,
            localDate = "2026-08-01",
            itemType = "CHAIN_PRODUCT",
            sourceItemId = "item-1",
            snapshotBrandName = "Example Coffee",
            snapshotItemName = "Flat White",
            snapshotImageAssetId = asset.id,
        )
        database.drinkDao().insert(record)

        assertEquals(0, database.imageAssetDao().deleteIfUnreferenced(asset.id))
        assertNotNull(database.imageAssetDao().get(asset.id))
        assertNotNull(database.drinkDao().get(record.id))
    }

    @Test
    fun `historical brand logo referenced by snapshot cannot be deleted`() = runBlocking {
        val logo = imageAsset(id = "historical-logo")
        database.imageAssetDao().upsert(logo)
        val record = DrinkRecordEntity(
            id = "record-logo",
            occurredAtEpochMillis = 1,
            localDate = "2026-08-01",
            itemType = "CHAIN_PRODUCT",
            sourceItemId = "item-1",
            snapshotBrandName = "Example Coffee",
            snapshotItemName = "Flat White",
            snapshotBrandLogoAssetId = logo.id,
        )
        database.drinkDao().insert(record)

        assertEquals(1, database.imageAssetDao().referenceCount(logo.id))
        assertEquals(0, database.imageAssetDao().deleteIfUnreferenced(logo.id))
        assertEquals(logo.id, database.drinkDao().get(record.id)?.snapshotBrandLogoAssetId)
    }

    @Test
    fun `unreferenced image can be deleted`() = runBlocking {
        val asset = imageAsset(id = "image-1")
        database.imageAssetDao().upsert(asset)

        assertEquals(1, database.imageAssetDao().deleteIfUnreferenced(asset.id))
        assertEquals(null, database.imageAssetDao().get(asset.id))
    }

    @Test
    fun `catalog update to another items normalized name rolls back`() = runBlocking {
        val brand = brand(id = "brand-1")
        database.brandDao().upsert(brand)
        val original = catalogItem(
            id = "item-1",
            brandId = brand.id,
            normalizedName = "flat white",
        )
        val conflicting = catalogItem(
            id = "item-2",
            brandId = brand.id,
            normalizedName = "latte",
        )
        database.catalogItemDao().upsert(original)
        database.catalogItemDao().upsert(conflicting)

        try {
            database.catalogItemDao().upsert(
                original.copy(name = "Latte", normalizedName = "latte"),
            )
            fail("Expected the unique brand/name index to reject the update")
        } catch (_: SQLiteConstraintException) {
            // Expected: the failed update must leave both rows unchanged.
        }

        assertEquals(original, database.catalogItemDao().get(original.id))
        assertEquals(conflicting, database.catalogItemDao().get(conflicting.id))
    }

    @Test
    fun `latest catalog update uses id as stable timestamp tie breaker`() = runBlocking {
        val first = catalogUpdate(id = "update-a")
        val second = catalogUpdate(id = "update-b")
        database.catalogUpdateDao().insert(first)
        database.catalogUpdateDao().insert(second)

        assertEquals(second, database.catalogUpdateDao().observeLatest(first.brandId).first())
    }

    @Test
    fun `range and latest queries have their composite indices`() {
        assertEquals(
            listOf("localDate", "occurredAtEpochMillis"),
            indexColumns("index_drink_records_localDate_occurredAtEpochMillis"),
        )
        assertEquals(
            listOf("brandId", "fetchedAtEpochMillis"),
            indexColumns("index_catalog_updates_brandId_fetchedAtEpochMillis"),
        )
    }

    private fun brand(id: String) = BrandEntity(
        id = id,
        type = "CHAIN",
        name = "Brand $id",
        logoAssetId = null,
        maintenanceMode = "MANUAL_ONLY",
        publicSourceUrl = null,
    )

    private fun catalogItem(
        id: String,
        brandId: String,
        normalizedName: String,
    ) = CatalogItemEntity(
        id = id,
        brandId = brandId,
        type = "CHAIN_PRODUCT",
        name = "Flat White",
        normalizedName = normalizedName,
        status = "ACTIVE",
    )

    private fun imageAsset(id: String) = ImageAssetEntity(
        id = id,
        localPath = "images/$id.jpg",
        sha256 = "sha-$id",
        kind = "PRODUCT",
        createdAtEpochMillis = 1,
    )

    private fun catalogUpdate(id: String) = CatalogUpdateEntity(
        id = id,
        brandId = "brand-1",
        fetchedAtEpochMillis = 1,
        status = "SUCCESS",
        sourceUrl = null,
        errorMessage = null,
    )

    private fun indexColumns(indexName: String): List<String> = buildList {
        database.openHelper.readableDatabase
            .query("PRAGMA index_info(`$indexName`)")
            .use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    add(cursor.getString(nameColumn))
                }
            }
    }
}
