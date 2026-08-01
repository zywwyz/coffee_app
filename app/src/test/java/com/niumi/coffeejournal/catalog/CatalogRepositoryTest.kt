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
    fun `normalization applies nfkc and collapses unicode whitespace`() = runBlocking {
        repository.upsertBrand(brand())

        repository.upsertItem(item(name = "\u3000Ｆｌａｔ\u00a0\t  ＷＨＩＴＥ\u3000"))

        assertEquals("flat white", database.catalogItemDao().get(ITEM_ID)?.normalizedName)
    }

    @Test
    fun `canonically equivalent accents have the same normalized name`() = runBlocking {
        repository.upsertBrand(brand())
        repository.upsertItem(item(name = "Café"))
        val precomposed = database.catalogItemDao().get(ITEM_ID)?.normalizedName

        repository.upsertItem(item(name = "Cafe\u0301"))

        assertEquals(precomposed, database.catalogItemDao().get(ITEM_ID)?.normalizedName)
        assertEquals("café", precomposed)
    }

    @Test
    fun `empty and unicode-whitespace-only catalog names are rejected`() = runBlocking {
        repository.upsertBrand(brand())

        listOf("", " \t\n", "\u00a0\u3000").forEach { invalidName ->
            try {
                repository.upsertItem(item(name = invalidName))
                fail("Expected InvalidCatalogNameException for '$invalidName'")
            } catch (error: InvalidCatalogNameException) {
                assertTrue(error.message.orEmpty().contains("name", ignoreCase = true))
            }
        }
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
                type = "CHAIN",
                name = "Broken",
                logoAssetId = null,
                maintenanceMode = "UNRECOGNIZED_MODE",
                publicSourceUrl = null,
            ),
        )

        try {
            repository.observeBrands(BrandType.CHAIN).first()
            fail("Expected DataIntegrityException")
        } catch (error: DataIntegrityException) {
            assertTrue(error.message.orEmpty().contains("maintenanceMode"))
            assertTrue(error.message.orEmpty().contains("UNRECOGNIZED_MODE"))
        }
    }

    @Test
    fun `seed inserts exactly five chain brands and is idempotent without overwriting edits`() = runBlocking {
        repository.ensureSeedBrands()
        val expected = listOf("% Arabica", "M Stand", "Manner", "Peet's", "瑞幸")
        assertEquals(expected, repository.observeBrands(BrandType.CHAIN).first().map(Brand::name))

        val luckin = repository.observeBrands(BrandType.CHAIN).first().single { it.name == "瑞幸" }
        repository.upsertBrand(luckin.copy(name = "我的瑞幸", publicSourceUrl = "https://example.test"))
        repository.ensureSeedBrands()

        assertEquals(5, repository.observeBrands(BrandType.CHAIN).first().size)
        assertEquals(
            "https://example.test",
            repository.getBrand(luckin.id).publicSourceUrl,
        )
        assertEquals("我的瑞幸", repository.getBrand(luckin.id).name)
    }

    @Test
    fun `duplicate brand names use nfkc case and unicode whitespace rules`() = runBlocking {
        repository.upsertBrand(brand().copy(id = "one", name = "Ｍ Stand"))

        try {
            repository.upsertBrand(brand().copy(id = "two", name = "  m\u00a0 stand "))
            fail("Expected DuplicateCatalogNameException")
        } catch (error: DuplicateCatalogNameException) {
            assertTrue(error.message.orEmpty().contains("已存在"))
        }
    }

    @Test
    fun `database brand upsert cannot silently ignore a unique name race`() = runBlocking {
        val first = BrandEntity(
            id = "race-one", type = "CHAIN", name = "Manner", normalizedName = "manner",
            logoAssetId = null, maintenanceMode = "MANUAL_ONLY", publicSourceUrl = null,
        )
        database.brandDao().upsert(first)

        try {
            database.brandDao().upsert(first.copy(id = "race-two"))
            fail("Expected unique name constraint")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            // The repository translates this race into DuplicateCatalogNameException.
        }
    }

    @Test
    fun `brand overview reports item count and latest confirmed update`() = runBlocking {
        repository.upsertBrand(brand())
        repository.upsertItem(item(name = "拿铁"))
        database.catalogUpdateDao().insert(
            com.niumi.coffeejournal.core.database.CatalogUpdateEntity(
                id = "update", brandId = BRAND_ID, fetchedAtEpochMillis = 1234,
                status = "CONFIRMED", sourceUrl = null, errorMessage = null,
            ),
        )

        val overview = repository.observeBrandOverviews(BrandType.CHAIN).first().single()
        assertEquals(1, overview.itemCount)
        assertEquals(1234L, overview.lastUpdatedAtEpochMillis)
    }

    @Test
    fun `chain category and specification round trip independently from brew method`() = runBlocking {
        repository.upsertBrand(brand())
        val product = item(name = "澳白").copy(
            category = "意式咖啡",
            specificationDescription = "大杯 / 冰",
            brewMethod = "浓缩",
        )

        repository.upsertItem(product)

        assertEquals("意式咖啡", repository.getItem(ITEM_ID).category)
        assertEquals("大杯 / 冰", repository.getItem(ITEM_ID).specificationDescription)
        assertEquals("浓缩", repository.getItem(ITEM_ID).brewMethod)
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
