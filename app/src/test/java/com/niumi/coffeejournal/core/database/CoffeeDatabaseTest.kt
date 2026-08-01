package com.niumi.coffeejournal.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
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
}
