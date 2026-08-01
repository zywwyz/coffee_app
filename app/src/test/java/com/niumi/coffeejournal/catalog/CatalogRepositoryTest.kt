package com.niumi.coffeejournal.catalog

import android.content.Context
import androidx.room.Room
import com.niumi.coffeejournal.core.database.BrandEntity
import com.niumi.coffeejournal.core.database.CatalogItemEntity
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.database.DataIntegrityException
import com.niumi.coffeejournal.core.database.DrinkRecordEntity
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogRepositoryTest {
    private lateinit var database: CoffeeDatabase
    private lateinit var repository: RoomCatalogRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        database = Room.inMemoryDatabaseBuilder(context, CoffeeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomCatalogRepository(
            database.brandDao(),
            database.catalogItemDao(),
            database.drinkDao(),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `upsert maps domains and normalizes item name with root rules`() = runBlocking {
        repository.upsertBrand(brand())
        repository.upsertItem(item(name = "  Flat\t WHITE  "))

        val entity = database.catalogItemDao().get(ITEM_ID)!!
        assertEquals("flat white", entity.normalizedName)
        assertEquals(brand(), repository.getBrand(BRAND_ID))
        assertEquals(item(name = "  Flat\t WHITE  "), repository.getItem(ITEM_ID))
        assertEquals(listOf(brand()), repository.observeBrands(BrandType.CHAIN).first())
        assertEquals(
            listOf(item(name = "  Flat\t WHITE  ")),
            repository.observeItems(BRAND_ID).first(),
        )
    }

    @Test
    fun `last price returns newest non-null actual price for item`() = runBlocking {
        database.drinkDao().insert(record("record-old", occurredAt = 1, priceFen = 990))
        database.drinkDao().insert(record("record-new-null", occurredAt = 3, priceFen = null))
        database.drinkDao().insert(record("record-new-price", occurredAt = 2, priceFen = 1_090))

        assertEquals(1_090L, repository.lastPriceFen(ITEM_ID))
        assertEquals(null, repository.lastPriceFen("never-recorded"))
    }

    @Test
    fun `missing item throws exception containing the id`() = runBlocking {
        try {
            repository.getItem("missing-item")
            fail("Expected CatalogItemNotFoundException")
        } catch (error: CatalogItemNotFoundException) {
            assertTrue(error.message.orEmpty().contains("missing-item"))
        }
    }

    @Test
    fun `missing brand throws exception containing the id`() = runBlocking {
        try {
            repository.getBrand("missing-brand")
            fail("Expected BrandNotFoundException")
        } catch (error: BrandNotFoundException) {
            assertTrue(error.message.orEmpty().contains("missing-brand"))
        }
    }

    @Test
    fun `unknown persisted enum reports field and value`() = runBlocking {
        database.brandDao().upsert(
            BrandEntity(
                id = BRAND_ID,
                type = "UNRECOGNIZED_TYPE",
                name = "Broken",
                logoAssetId = null,
                maintenanceMode = "MANUAL_ONLY",
                publicSourceUrl = null,
            ),
        )

        try {
            repository.observeBrands(BrandType.CHAIN).first()
            fail("Expected DataIntegrityException")
        } catch (error: DataIntegrityException) {
            assertTrue(error.message.orEmpty().contains("type"))
            assertTrue(error.message.orEmpty().contains("UNRECOGNIZED_TYPE"))
        }
    }

    private fun brand() = Brand(
        id = BRAND_ID,
        type = BrandType.CHAIN,
        name = "Example Coffee",
        logoAssetId = null,
        maintenanceMode = MaintenanceMode.MANUAL_ONLY,
        publicSourceUrl = null,
    )

    private fun item(name: String) = CatalogItem(
        id = ITEM_ID,
        brandId = BRAND_ID,
        type = ItemType.CHAIN_PRODUCT,
        name = name,
        imageAssetId = null,
        origin = null,
        processing = null,
        roastLevel = null,
        flavorNotes = null,
        brewMethod = null,
        status = ItemStatus.ACTIVE,
    )

    private fun record(id: String, occurredAt: Long, priceFen: Long?) = DrinkRecordEntity(
        id = id,
        occurredAtEpochMillis = occurredAt,
        localDate = "2026-08-01",
        itemType = "CHAIN_PRODUCT",
        sourceItemId = ITEM_ID,
        actualPriceFen = priceFen,
        snapshotBrandName = "Example Coffee",
        snapshotItemName = "Flat White",
    )

    private companion object {
        const val BRAND_ID = "brand-1"
        const val ITEM_ID = "item-1"
    }
}
