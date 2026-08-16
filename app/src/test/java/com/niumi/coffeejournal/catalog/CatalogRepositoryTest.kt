package com.niumi.coffeejournal.catalog

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.niumi.coffeejournal.core.database.BrandEntity
import com.niumi.coffeejournal.core.database.BrandDao
import com.niumi.coffeejournal.core.database.BrandOverviewRow
import com.niumi.coffeejournal.core.database.CatalogItemEntity
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.database.DataIntegrityException
import com.niumi.coffeejournal.core.database.DrinkRecordEntity
import com.niumi.coffeejournal.core.database.ImageAssetEntity
import com.niumi.coffeejournal.core.image.ImageAsset
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ChainProductKind
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    @Test fun `rejects invalid chain product kinds and personal bean kinds on save`() = runBlocking {
        val chain = item("类型").copy(type = ItemType.CHAIN_PRODUCT)
        repository.upsertBrand(brand())
        assertIllegalArgument { repository.upsertItem(chain.copy(chainProductKind = null)) }
        assertIllegalArgument { repository.upsertItem(chain.copy(chainProductKind = ChainProductKind.PENDING)) }
        listOf(ChainProductKind.BLACK, ChainProductKind.FRUIT, ChainProductKind.MILK).forEach { kind ->
            repository.upsertItem(chain.copy(id = kind.name, name = kind.name, chainProductKind = kind))
        }
        assertIllegalArgument { repository.upsertItem(item("个人豆").copy(type = ItemType.PERSONAL_BEAN, chainProductKind = ChainProductKind.MILK)) }
    }

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
    fun `seed inserts bundled chain brands in catalog order and preserves user edits`() = runBlocking {
        repository.ensureSeedBrands()
        val expected = BUNDLED_CHAIN_BRANDS.map { it.brand.name }
        assertEquals(expected, repository.observeBrands(BrandType.CHAIN).first().map(Brand::name))

        val luckin = repository.observeBrands(BrandType.CHAIN).first().single { it.name == "瑞幸" }
        repository.upsertBrand(luckin.copy(name = "我的瑞幸", publicSourceUrl = "https://example.test"))
        repository.ensureSeedBrands()

        assertEquals(12, repository.observeBrands(BrandType.CHAIN).first().size)
        assertEquals(
            "https://example.test",
            repository.getBrand(luckin.id).publicSourceUrl,
        )
        assertEquals("我的瑞幸", repository.getBrand(luckin.id).name)
    }

    @Test
    fun `seed imports every missing bundled logo once and keeps a user logo`() = runBlocking {
        val images = RecordingBrandLogoStore(database)
        val seeded = logoRepository(images)

        seeded.ensureSeedBrands()
        seeded.ensureSeedBrands()

        assertEquals(12, images.imported.size)
        val luckin = seeded.getBrand("seed-chain-luckin")
        val userLogo = images.persist("user-logo")
        seeded.upsertBrand(luckin.copy(logoAssetId = userLogo.id))
        seeded.ensureSeedBrands()

        assertEquals(userLogo.id, seeded.getBrand(luckin.id).logoAssetId)
        assertEquals(12, images.imported.size)
    }

    @Test
    fun `seed retries only logos left missing after a failed import`() = runBlocking {
        val images = RecordingBrandLogoStore(database, failAt = 4)
        val seeded = logoRepository(images)

        try {
            seeded.ensureSeedBrands()
            fail("Expected one import failure")
        } catch (_: IllegalStateException) {
        }
        seeded.ensureSeedBrands()

        assertEquals(13, images.imported.size)
        assertTrue(BUNDLED_CHAIN_BRANDS.all { seeded.getBrand(it.brand.id).logoAssetId != null })
    }

    @Test
    fun `seed cleans imported asset when CAS loses to a user logo`() = runBlocking {
        val images = RecordingBrandLogoStore(database) { imported ->
            if (imported.id != "import-1") return@RecordingBrandLogoStore
            val userLogo = ImageAsset("user-${imported.id}", "/unused/user-${imported.id}", "user-${imported.id}", ImageKind.BRAND_LOGO)
            database.imageAssetDao().upsert(
                ImageAssetEntity(userLogo.id, userLogo.localPath, userLogo.sha256, userLogo.kind.name, 1),
            )
            database.brandDao().get("seed-chain-luckin")?.let { brand ->
                database.brandDao().upsert(brand.copy(logoAssetId = userLogo.id))
            }
        }
        val seeded = logoRepository(images)

        seeded.ensureSeedBrands()

        assertEquals("user-import-1", seeded.getBrand("seed-chain-luckin").logoAssetId)
        assertEquals(listOf("import-1"), images.deleted)
    }

    @Test
    fun `custom chain brands sort after the fixed bundled catalog order`() = runBlocking {
        repository.ensureSeedBrands()
        repository.upsertBrand(brand().copy(id = "custom", name = "AAA Coffee"))

        assertEquals(
            BUNDLED_CHAIN_BRANDS.map { it.brand.id } + "custom",
            repository.observeBrands(BrandType.CHAIN).first().map { it.id },
        )
    }

    @Test
    fun `legacy alias adopts stable id and survives a later user rename`() = runBlocking {
        val images = RecordingBrandLogoStore(database)
        val userLogo = images.persist("legacy-logo")
        database.brandDao().upsert(
            BrandEntity("legacy-cotti", "CHAIN", "库迪咖啡", "库迪咖啡", userLogo.id, "MANUAL_ONLY", null),
        )
        repository.upsertItem(item("冷萃").copy(id = "legacy-item", brandId = "legacy-cotti"))
        database.catalogUpdateDao().insert(
            com.niumi.coffeejournal.core.database.CatalogUpdateEntity(
                "legacy-update", "legacy-cotti", 1, "CONFIRMED", null, null,
            ),
        )
        val seeded = logoRepository(images)

        seeded.ensureSeedBrands()

        val cotti = seeded.getBrand("seed-chain-cotti")
        assertEquals("库迪咖啡", cotti.name)
        assertEquals(userLogo.id, cotti.logoAssetId)
        assertEquals("seed-chain-cotti", database.catalogItemDao().get("legacy-item")?.brandId)
        assertEquals("seed-chain-cotti", database.catalogUpdateDao().latest("seed-chain-cotti")?.brandId)
        seeded.upsertBrand(cotti.copy(name = "我的库迪"))
        logoRepository(images).ensureSeedBrands()

        assertEquals(12, seeded.observeBrands(BrandType.CHAIN).first().size)
        assertEquals("我的库迪", seeded.getBrand("seed-chain-cotti").name)
        assertEquals(userLogo.id, seeded.getBrand("seed-chain-cotti").logoAssetId)
        assertEquals("seed-chain-cotti", database.catalogItemDao().get("legacy-item")?.brandId)
        assertEquals("seed-chain-cotti", database.catalogUpdateDao().latest("seed-chain-cotti")?.brandId)
        assertEquals(11, images.imported.size)
    }

    @Test
    fun `failed alias adoption rolls back brand items and updates together`() = runBlocking {
        database.brandDao().upsert(
            BrandEntity("legacy-cotti", "CHAIN", "库迪咖啡", "库迪咖啡", null, "MANUAL_ONLY", null),
        )
        repository.upsertItem(item("冷萃").copy(id = "legacy-item", brandId = "legacy-cotti"))
        database.catalogUpdateDao().insert(
            com.niumi.coffeejournal.core.database.CatalogUpdateEntity(
                "legacy-update", "legacy-cotti", 1, "CONFIRMED", null, null,
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_brand_adoption BEFORE UPDATE OF brandId ON catalog_updates
               WHEN OLD.brandId = 'legacy-cotti' BEGIN SELECT RAISE(ABORT, 'test rollback'); END""",
        )

        try {
            repository.ensureSeedBrands()
            fail("Expected the controlled migration failure")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
        }

        assertNotNull(database.brandDao().get("legacy-cotti"))
        assertNull(database.brandDao().get("seed-chain-cotti"))
        assertEquals("legacy-cotti", database.catalogItemDao().get("legacy-item")?.brandId)
        assertEquals("legacy-cotti", database.catalogUpdateDao().latest("legacy-cotti")?.brandId)
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
    fun `unique name inserted after precheck is still reported as duplicate`() = runBlocking {
        val winner = BrandEntity(
            id = "race-winner", type = "CHAIN", name = "Manner", normalizedName = "manner",
            logoAssetId = null, maintenanceMode = "MANUAL_ONLY", publicSourceUrl = null,
        )
        val racingRepository = RoomCatalogRepository(
            RacingBrandDao(database.brandDao(), winner), database.catalogItemDao(), database.drinkDao(),
        )

        try {
            racingRepository.upsertBrand(brand().copy(id = "race-loser", name = "Manner"))
            fail("Expected DuplicateCatalogNameException")
        } catch (error: DuplicateCatalogNameException) {
            assertTrue(error.message.orEmpty().contains("已存在"))
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

    @Test
    fun `missing brand logo foreign key is not reported as duplicate`() = runBlocking {
        try {
            repository.upsertBrand(brand().copy(logoAssetId = "missing-logo"))
            fail("Expected foreign key constraint")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
    }

    @Test
    fun `missing item image foreign key is not reported as duplicate`() = runBlocking {
        repository.upsertBrand(brand())
        try {
            repository.upsertItem(item("澳白").copy(imageAssetId = "missing-image"))
            fail("Expected foreign key constraint")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
    }

    @Test
    fun `missing item brand foreign key is not reported as duplicate`() = runBlocking {
        try {
            repository.upsertItem(item("澳白").copy(brandId = "missing-brand"))
            fail("Expected foreign key constraint")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
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
        chainProductKind = ChainProductKind.BLACK,
    )

    private fun assertIllegalArgument(block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

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

    private fun logoRepository(images: RecordingBrandLogoStore) = RoomCatalogRepository(
        database.brandDao(), database.catalogItemDao(), database.drinkDao(), images,
    ) { resourceId -> Uri.parse("android.resource://test/$resourceId") }

    private class RecordingBrandLogoStore(
        private val database: CoffeeDatabase,
        private val failAt: Int? = null,
        private val afterImport: (suspend (ImageAsset) -> Unit)? = null,
    ) : ImageStore {
        val imported = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        override suspend fun importCropped(source: Uri, crop: com.niumi.coffeejournal.core.image.CropRect, kind: ImageKind): ImageAsset =
            error("unexpected")

        override suspend fun importWhole(source: Uri, kind: ImageKind): ImageAsset {
            val id = "import-${imported.size + 1}"
            imported += id
            if (imported.size == failAt) error("expected import failure")
            val asset = persist(id)
            afterImport?.invoke(asset)
            return asset
        }

        suspend fun persist(id: String): ImageAsset {
            val asset = ImageAsset(id, "/unused/$id", id, ImageKind.BRAND_LOGO)
            database.imageAssetDao().upsert(
                ImageAssetEntity(id, asset.localPath, asset.sha256, asset.kind.name, 1),
            )
            return asset
        }

        override suspend fun deleteIfUnreferenced(assetId: String): Boolean {
            deleted += assetId
            return database.imageAssetDao().deleteIfUnreferenced(assetId) == 1
        }
    }

    private class RacingBrandDao(
        private val delegate: BrandDao,
        private val winner: BrandEntity,
    ) : BrandDao {
        private var injected = false
        override suspend fun insert(brand: BrandEntity) = delegate.insert(brand)
        override suspend fun update(brand: BrandEntity): Int = delegate.update(brand)
        override suspend fun insertIgnoringExisting(brands: List<BrandEntity>): List<Long> =
            delegate.insertIgnoringExisting(brands)
        override suspend fun attachLogoIfMissing(brandId: String, assetId: String): Int =
            delegate.attachLogoIfMissing(brandId, assetId)
        override fun observe() = delegate.observe()
        override fun observeByType(type: String) = delegate.observeByType(type)
        override suspend fun get(id: String) = delegate.get(id)
        override suspend fun getByNormalizedNames(type: String, names: List<String>) =
            delegate.getByNormalizedNames(type, names)
        override suspend fun adoptAsBundledId(legacy: BrandEntity, bundledId: String) =
            delegate.adoptAsBundledId(legacy, bundledId)
        override suspend fun renameId(fromBrandId: String, toBrandId: String) =
            delegate.renameId(fromBrandId, toBrandId)
        override suspend fun moveCatalogItemsBrandId(fromBrandId: String, toBrandId: String) =
            delegate.moveCatalogItemsBrandId(fromBrandId, toBrandId)
        override suspend fun moveCatalogUpdatesBrandId(fromBrandId: String, toBrandId: String) =
            delegate.moveCatalogUpdatesBrandId(fromBrandId, toBrandId)
        override suspend fun deleteById(id: String) = delegate.deleteById(id)
        override suspend fun existsNamedOther(type: String, name: String, id: String): Boolean {
            if (!injected) {
                injected = true
                delegate.insert(winner)
                return false
            }
            return delegate.existsNamedOther(type, name, id)
        }
        override fun observeOverviews(type: String): kotlinx.coroutines.flow.Flow<List<BrandOverviewRow>> =
            delegate.observeOverviews(type)
    }
}
