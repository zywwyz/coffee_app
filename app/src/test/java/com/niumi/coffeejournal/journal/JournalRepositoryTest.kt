package com.niumi.coffeejournal.journal

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.niumi.coffeejournal.catalog.CatalogItemNotFoundException
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ChainProductKind
import com.niumi.coffeejournal.core.model.CoffeeType
import com.niumi.coffeejournal.core.model.DrinkDraft
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.TimeZone

class JournalRepositoryTest {
    @Test
    fun `save snapshots personal beans as hand brew and chain products by catalog kind`() = runBlocking {
        val cases = listOf(
            ItemType.PERSONAL_BEAN to null to CoffeeType.HAND_BREW,
            ItemType.CHAIN_PRODUCT to ChainProductKind.BLACK to CoffeeType.BLACK,
            ItemType.CHAIN_PRODUCT to ChainProductKind.FRUIT to CoffeeType.FRUIT,
            ItemType.CHAIN_PRODUCT to ChainProductKind.MILK to CoffeeType.MILK,
        )
        cases.forEachIndexed { index, (typeAndKind, expectedType) ->
            val (type, kind) = typeAndKind
            val store = FakeDrinkStore()
            val repository = DefaultJournalRepository(
                FakeCatalogRepository(item().copy(type = type, chainProductKind = kind), null), store, FixedClock(),
            )

            repository.save(draft("save-$index").copy(itemType = type))

            assertEquals(expectedType, store.saved.single().snapshot.coffeeType)
        }
    }

    @Test
    fun `save rejects pending chain product kind`() = runBlocking {
        val repository = DefaultJournalRepository(
            FakeCatalogRepository(item().copy(chainProductKind = ChainProductKind.PENDING), null), FakeDrinkStore(), FixedClock(),
        )

        try {
            repository.save(draft("pending"))
            fail("Expected pending product to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("PENDING"))
        }
    }

    @Test
    fun `edit without changing product preserves existing snapshot coffee type`() = runBlocking {
        val existing = savedRecord(CoffeeType.FRUIT)
        val store = FakeDrinkStore().apply { records[existing.id] = existing }
        val repository = DefaultJournalRepository(
            FakeCatalogRepository(item().copy(chainProductKind = ChainProductKind.MILK), null), store, FixedClock(),
        )

        repository.save(editDraft(existing))

        assertEquals(CoffeeType.FRUIT, store.updated.single().snapshot.coffeeType)
    }

    @Test
    fun `edit with changed product refreshes snapshot coffee type`() = runBlocking {
        val existing = savedRecord(CoffeeType.FRUIT)
        val store = FakeDrinkStore().apply { records[existing.id] = existing }
        val replacement = item().copy(id = "replacement", chainProductKind = ChainProductKind.MILK)
        val repository = DefaultJournalRepository(FakeCatalogRepository(replacement, null), store, FixedClock())

        repository.save(editDraft(existing).copy(sourceItemId = replacement.id))

        assertEquals(CoffeeType.MILK, store.updated.single().snapshot.coffeeType)
    }

    @Test
    fun `new draft carries catalog brew method and last price`() = runBlocking {
        val catalog = FakeCatalogRepository(item(), lastPriceFen = 990)
        val store = FakeDrinkStore()
        val repository = DefaultJournalRepository(catalog, store, FixedClock())

        val draft = repository.newDraft(ItemType.CHAIN_PRODUCT, ITEM_ID)

        assertEquals(ITEM_ID, draft.sourceItemId)
        assertEquals(ItemType.CHAIN_PRODUCT, draft.itemType)
        assertEquals("冰手冲", draft.brewMethod)
        assertEquals(990L, draft.actualPriceFen)
        assertNull(draft.ratingHalfStars)
        assertEquals("", draft.note)
        assertTrue(draft.revisionId.isNotBlank())
        assertEquals(localNoonEpoch("2025-08-01"), draft.consumedAtEpochMillis)
        assertEquals(listOf(draft), store.startedDrafts)
    }

    @Test
    fun `local noon uses strict canonical date in Shanghai`() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")

        val epoch = localNoonEpoch("2025-08-01", zone)

        assertEquals("2025-08-01", localDateForEpoch(epoch, zone))
        assertEquals(12, java.util.Calendar.getInstance(zone).apply { timeInMillis = epoch }
            .get(java.util.Calendar.HOUR_OF_DAY))
        try {
            localNoonEpoch("2025-8-1", zone)
            fail("Expected non-canonical date to fail")
        } catch (_: IllegalArgumentException) { }
        try {
            localNoonEpoch("2025-02-29", zone)
            fail("Expected invalid calendar date to fail")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `local noon is valid on New York DST transition day`() {
        val zone = TimeZone.getTimeZone("America/New_York")

        val epoch = localNoonEpoch("2025-03-09", zone)

        assertEquals("2025-03-09", localDateForEpoch(epoch, zone))
        assertEquals(12, java.util.Calendar.getInstance(zone).apply { timeInMillis = epoch }
            .get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `save accepts today noon when clock reads eight in the morning`() = runBlocking {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(zone)
        try {
            val clock = ClockReading(localNoonEpoch("2025-08-01", zone) - 4 * 60 * 60 * 1000, "2025-08-01")
            val store = FakeDrinkStore()
            val repository = DefaultJournalRepository(FakeCatalogRepository(item(), 990), store, object : Clock { override fun read() = clock })

            repository.save(draft("today").copy(consumedAtEpochMillis = localNoonEpoch("2025-08-01", zone)))

            assertEquals("2025-08-01", store.saved.single().localDate)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `save rejects tomorrow date`() = runBlocking {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(zone)
        try {
            val clock = ClockReading(localNoonEpoch("2025-08-01", zone), "2025-08-01")
            val repository = DefaultJournalRepository(FakeCatalogRepository(item(), 990), FakeDrinkStore(), object : Clock { override fun read() = clock })

            try {
                repository.save(draft("tomorrow").copy(consumedAtEpochMillis = localNoonEpoch("2025-08-02", zone)))
                fail("Expected tomorrow to be rejected")
            } catch (error: IllegalArgumentException) {
                assertEquals("Drink date cannot be after today", error.message)
            }
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `save rejects tomorrow date in Los Angeles default timezone`() = runBlocking {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(zone)
        try {
            val clock = ClockReading(localNoonEpoch("2025-08-01", zone), "2025-08-01")
            val repository = DefaultJournalRepository(FakeCatalogRepository(item(), 990), FakeDrinkStore(), object : Clock { override fun read() = clock })

            try {
                repository.save(draft("tomorrow-la").copy(consumedAtEpochMillis = localNoonEpoch("2025-08-02", zone)))
                fail("Expected tomorrow to be rejected")
            } catch (error: IllegalArgumentException) {
                assertEquals("Drink date cannot be after today", error.message)
            }
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `save snapshots the current catalog item and clears the draft`() = runBlocking {
        val original = item()
        val catalog = FakeCatalogRepository(original, lastPriceFen = 990)
        val store = FakeDrinkStore()
        val repository = DefaultJournalRepository(catalog, store, FixedClock())
        val draft = repository.newDraft(ItemType.CHAIN_PRODUCT, ITEM_ID)
        catalog.currentItem = original.copy(
            name = "生椰拿铁·夏日版",
            origin = "云南",
            processing = "日晒",
            roastLevel = "浅烘",
            flavorNotes = "椰香、柑橘",
            imageAssetId = "image-current",
        )

        val recordId = repository.save(draft.copy(ratingHalfStars = 9))

        val saved = store.saved.single()
        assertEquals(recordId, saved.id)
        assertEquals(localNoonEpoch("2025-08-01"), saved.occurredAtEpochMillis)
        assertEquals("2025-08-01", saved.localDate)
        assertEquals(ITEM_ID, saved.sourceItemId)
        assertEquals("生椰拿铁·夏日版", saved.snapshot.itemName)
        assertEquals("示例咖啡", saved.snapshot.brandName)
        assertEquals("云南", saved.snapshot.origin)
        assertEquals("日晒", saved.snapshot.processing)
        assertEquals("浅烘", saved.snapshot.roastLevel)
        assertEquals("椰香、柑橘", saved.snapshot.flavorNotes)
        assertEquals("image-current", saved.snapshot.imageAssetId)
        assertEquals(listOf(recordId), store.clearedBySave)
    }

    @Test
    fun `save snapshots product and historical brand logo separately`() = runBlocking {
        val catalog = FakeCatalogRepository(item().copy(imageAssetId = null), lastPriceFen = null).apply {
            currentBrand = currentBrand.copy(logoAssetId = "logo-at-save")
        }
        val store = FakeDrinkStore()
        val repository = DefaultJournalRepository(catalog, store, FixedClock())
        val draft = repository.newDraft(ItemType.CHAIN_PRODUCT, ITEM_ID)

        repository.save(draft)
        catalog.currentBrand = catalog.currentBrand.copy(logoAssetId = "logo-later")

        assertNull(store.saved.single().snapshot.imageAssetId)
        assertEquals("logo-at-save", store.saved.single().snapshot.brandLogoAssetId)
    }

    @Test
    fun `save fetches snapshot brand by id without waiting for brand observations`() = runBlocking {
        val catalog = FakeCatalogRepository(
            currentItem = item().copy(type = ItemType.PERSONAL_BEAN),
            lastPriceFen = 990,
            emitBrands = false,
        )
        val store = FakeDrinkStore()
        val repository = DefaultJournalRepository(catalog, store, FixedClock())

        withTimeout(250) {
            repository.save(
                DrinkDraft(
                    revisionId = "revision-1",
                    itemType = ItemType.PERSONAL_BEAN,
                    sourceItemId = ITEM_ID,
                    brewMethod = null,
                    ratingHalfStars = null,
                    actualPriceFen = null,
                    note = "",
                ),
            )
        }

        assertEquals("示例咖啡", store.saved.single().snapshot.brandName)
    }

    @Test
    fun `save accepts nullable rating price and brew and turns blank note into null`() = runBlocking {
        val store = FakeDrinkStore()
        val repository = DefaultJournalRepository(
            FakeCatalogRepository(item(), lastPriceFen = null),
            store,
            FixedClock(),
        )

        repository.save(
            DrinkDraft(
                revisionId = "revision-1",
                itemType = ItemType.CHAIN_PRODUCT,
                sourceItemId = ITEM_ID,
                brewMethod = null,
                ratingHalfStars = null,
                actualPriceFen = null,
                note = "",
            ),
        )

        val saved = store.saved.single()
        assertNull(saved.ratingHalfStars)
        assertNull(saved.actualPriceFen)
        assertNull(saved.brewMethod)
        assertNull(saved.note)
    }

    @Test
    fun `save timestamps record from one clock reading`() = runBlocking {
        val store = FakeDrinkStore()
        val repository = DefaultJournalRepository(
            FakeCatalogRepository(item(), lastPriceFen = null),
            store,
            CrossingClock(),
        )

        repository.save(
            DrinkDraft(
                revisionId = "revision-1",
                itemType = ItemType.CHAIN_PRODUCT,
                sourceItemId = ITEM_ID,
                brewMethod = null,
                ratingHalfStars = null,
                actualPriceFen = null,
                note = "",
            ),
        )

        assertEquals(localNoonEpoch("1970-01-01"), store.saved.single().occurredAtEpochMillis)
        assertEquals("1970-01-01", store.saved.single().localDate)
    }

    @Test
    fun `new draft fails clearly when catalog item does not exist`() = runBlocking {
        val repository = DefaultJournalRepository(
            FakeCatalogRepository(currentItem = null, lastPriceFen = null),
            FakeDrinkStore(),
            FixedClock(),
        )

        assertMissingItem { repository.newDraft(ItemType.CHAIN_PRODUCT, "missing") }
    }

    @Test
    fun `save rechecks item and fails without writing if it was removed`() = runBlocking {
        val catalog = FakeCatalogRepository(item(), lastPriceFen = 990)
        val store = FakeDrinkStore()
        val repository = DefaultJournalRepository(catalog, store, FixedClock())
        val draft = repository.newDraft(ItemType.CHAIN_PRODUCT, ITEM_ID)
        catalog.currentItem = null

        assertMissingItem { repository.save(draft) }
        assertTrue(store.saved.isEmpty())
        assertTrue(store.clearedBySave.isEmpty())
    }

    @Test
    fun `observe month uses inclusive leap February boundaries`() = runBlocking {
        val store = FakeDrinkStore()
        val repository = DefaultJournalRepository(
            FakeCatalogRepository(item(), null),
            store,
            FixedClock(),
        )

        repository.observeMonth(2024, 2).toList()

        assertEquals(listOf("2024-02-01" to "2024-02-29"), store.observedRanges)
    }

    @Test
    fun `save draft and delete delegate to the store`() = runBlocking {
        val store = FakeDrinkStore()
        val repository = DefaultJournalRepository(
            FakeCatalogRepository(item(), 990),
            store,
            FixedClock(),
        )
        val draft = repository.newDraft(ItemType.CHAIN_PRODUCT, ITEM_ID)

        assertTrue(repository.saveDraft(draft))
        repository.delete("record-1")

        assertEquals(listOf(draft), store.savedDrafts)
        assertEquals(listOf("record-1"), store.deletedIds)
    }

    private suspend fun assertMissingItem(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected CatalogItemNotFoundException")
        } catch (error: CatalogItemNotFoundException) {
            assertTrue(error.message.orEmpty().contains("item", ignoreCase = true))
        }
    }

    private fun item() = CatalogItem(
        id = ITEM_ID,
        brandId = BRAND_ID,
        type = ItemType.CHAIN_PRODUCT,
        name = "生椰拿铁",
        imageAssetId = "image-original",
        origin = "海南",
        processing = "水洗",
        roastLevel = "中烘",
        flavorNotes = "椰香",
        brewMethod = "冰手冲",
        status = ItemStatus.ACTIVE,
        chainProductKind = ChainProductKind.MILK,
    )

    private fun draft(revisionId: String) = DrinkDraft(
        revisionId = revisionId,
        itemType = ItemType.CHAIN_PRODUCT,
        sourceItemId = ITEM_ID,
        brewMethod = null,
        ratingHalfStars = null,
        actualPriceFen = null,
        note = "",
    )

    private fun savedRecord(coffeeType: CoffeeType) = DrinkRecord(
        id = "record-1", occurredAtEpochMillis = localNoonEpoch("2025-08-01"), localDate = "2025-08-01",
        itemType = ItemType.CHAIN_PRODUCT, sourceItemId = ITEM_ID, brewMethod = null, ratingHalfStars = null,
        actualPriceFen = null, note = null,
        snapshot = com.niumi.coffeejournal.core.model.DrinkSnapshot("品牌", "旧产品", null, null, null, coffeeType = coffeeType),
    )

    private fun editDraft(record: DrinkRecord) = DrinkDraft(
        revisionId = "edit", itemType = record.itemType, sourceItemId = record.sourceItemId, brewMethod = null,
        ratingHalfStars = null, actualPriceFen = null, note = "", editingRecordId = record.id,
        expectedRecordRevision = record.revision,
    )

    private class FixedClock : Clock {
        override fun read() = ClockReading(1_754_044_800_123, "2025-08-01")
    }

    private class CrossingClock : Clock {
        override fun read() = ClockReading(0L, "1970-01-01")
    }

    private class FakeCatalogRepository(
        var currentItem: CatalogItem?,
        private val lastPriceFen: Long?,
        private val emitBrands: Boolean = true,
    ) : CatalogRepository {
        var currentBrand = Brand(
            id = BRAND_ID,
            type = BrandType.CHAIN,
            name = "示例咖啡",
            logoAssetId = null,
            maintenanceMode = com.niumi.coffeejournal.core.model.MaintenanceMode.MANUAL_ONLY,
            publicSourceUrl = null,
        )

        override fun observeBrands(type: BrandType): Flow<List<Brand>> =
            if (emitBrands) {
                flowOf(listOf(currentBrand).filter { it.type == type })
            } else {
                emptyFlow()
            }
        override fun observeItems(brandId: String): Flow<List<CatalogItem>> = emptyFlow()

        override suspend fun getBrand(brandId: String): Brand =
            currentBrand.takeIf { it.id == brandId }
                ?: throw com.niumi.coffeejournal.catalog.BrandNotFoundException(brandId)

        override suspend fun getItem(itemId: String): CatalogItem =
            currentItem?.takeIf { it.id == itemId }
                ?: throw CatalogItemNotFoundException(itemId)

        override suspend fun upsertBrand(brand: Brand) = Unit
        override suspend fun upsertItem(item: CatalogItem) = Unit
        override suspend fun lastPriceFen(itemId: String): Long? = lastPriceFen

    }

    private class FakeDrinkStore : DrinkStore {
        val saved = mutableListOf<DrinkRecord>()
        val clearedBySave = mutableListOf<String>()
        val savedDrafts = mutableListOf<DrinkDraft>()
        val deletedIds = mutableListOf<String>()
        val observedRanges = mutableListOf<Pair<String, String>>()
        val startedDrafts = mutableListOf<DrinkDraft>()
        val records = mutableMapOf<String, DrinkRecord>()
        val updated = mutableListOf<DrinkRecord>()

        override suspend fun startDraft(draft: DrinkDraft) {
            startedDrafts += draft
        }

        override fun observeRange(startLocalDate: String, endLocalDate: String): Flow<List<DrinkRecord>> {
            observedRanges += startLocalDate to endLocalDate
            return flowOf(emptyList())
        }

        override suspend fun saveRecordAndClearDraft(record: DrinkRecord, revisionId: String) {
            saved += record
            clearedBySave += record.id
        }

        override suspend fun get(recordId: String): DrinkRecord? = records[recordId]

        override suspend fun update(record: DrinkRecord, expectedRevision: Int, draftRevisionId: String): Boolean {
            updated += record
            records[record.id] = record
            return true
        }

        override suspend fun saveDraft(draft: DrinkDraft): Boolean {
            savedDrafts += draft
            return true
        }

        override suspend fun delete(recordId: String) {
            deletedIds += recordId
        }
    }

    private companion object {
        const val BRAND_ID = "brand-1"
        const val ITEM_ID = "item-1"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomDrinkStoreTest {
    private lateinit var database: CoffeeDatabase
    private lateinit var store: RoomDrinkStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        database = Room.inMemoryDatabaseBuilder(context, CoffeeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomDrinkStore(database, FixedStoreClock)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `saving a record inserts it and clears current draft atomically`() = runBlocking {
        val draft = draft("revision-1")
        val record = record()
        store.startDraft(draft)

        store.saveRecordAndClearDraft(record, draft.revisionId)

        assertEquals("Flat White", database.drinkDao().get(record.id)?.snapshotItemName)
        assertNull(database.draftDao().get("current"))
    }

    @Test
    fun `late autosave after successful save cannot recreate draft`() = runBlocking {
        val oldDraft = draft("revision-old")
        store.startDraft(oldDraft)
        store.saveRecordAndClearDraft(record(), oldDraft.revisionId)

        assertFalse(store.saveDraft(oldDraft.copy(note = "late autosave")))

        assertNull(database.draftDao().get("current"))
    }

    @Test
    fun `saving old revision does not clear a newer draft`() = runBlocking {
        val oldDraft = draft("revision-old")
        val newDraft = draft("revision-new", note = "new")
        store.startDraft(oldDraft)
        store.startDraft(newDraft)

        store.saveRecordAndClearDraft(record(), oldDraft.revisionId)

        assertEquals("revision-new", database.draftDao().get("current")?.revisionId)
    }

    @Test
    fun `old autosave cannot overwrite newer draft`() = runBlocking {
        val oldDraft = draft("revision-old")
        val newDraft = draft("revision-new", note = "new")
        store.startDraft(oldDraft)
        store.startDraft(newDraft)

        assertFalse(store.saveDraft(oldDraft.copy(note = "stale")))

        val current = database.draftDao().get("current")
        assertEquals("revision-new", current?.revisionId)
        assertEquals("new", current?.note)
    }

    @Test
    fun `record insert failure rolls back and preserves matching draft`() = runBlocking {
        val draft = draft("revision-1")
        val record = record()
        store.startDraft(draft)
        database.drinkDao().insert(record.toEntityForTest())

        try {
            store.saveRecordAndClearDraft(record, draft.revisionId)
            fail("Expected duplicate record id to fail")
        } catch (_: SQLiteConstraintException) {
            // Expected.
        }

        assertNotNull(database.draftDao().get("current"))
        assertEquals("revision-1", database.draftDao().get("current")?.revisionId)
    }

    private fun draft(revisionId: String, note: String = "") = DrinkDraft(
        revisionId = revisionId,
        itemType = ItemType.CHAIN_PRODUCT,
        sourceItemId = "item-1",
        brewMethod = null,
        ratingHalfStars = null,
        actualPriceFen = null,
        note = note,
    )

    private fun record() = DrinkRecord(
        id = "record-1",
        occurredAtEpochMillis = 1,
        localDate = "2026-08-01",
        itemType = ItemType.CHAIN_PRODUCT,
        sourceItemId = "item-1",
        brewMethod = null,
        ratingHalfStars = null,
        actualPriceFen = null,
        note = null,
        snapshot = com.niumi.coffeejournal.core.model.DrinkSnapshot(
            brandName = "Example Coffee",
            itemName = "Flat White",
            origin = null,
            processing = null,
            imageAssetId = null,
        ),
    )

    private fun DrinkRecord.toEntityForTest() =
        com.niumi.coffeejournal.core.database.DrinkRecordEntity(
            id = id,
            occurredAtEpochMillis = occurredAtEpochMillis,
            localDate = localDate,
            itemType = itemType.name,
            sourceItemId = sourceItemId,
            brewMethod = brewMethod,
            ratingHalfStars = ratingHalfStars,
            actualPriceFen = actualPriceFen,
            note = note,
            snapshotBrandName = snapshot.brandName,
            snapshotItemName = snapshot.itemName,
            snapshotOrigin = snapshot.origin,
            snapshotProcessing = snapshot.processing,
            snapshotImageAssetId = snapshot.imageAssetId,
            snapshotBrandLogoAssetId = snapshot.brandLogoAssetId,
            snapshotRoastLevel = snapshot.roastLevel,
            snapshotFlavorNotes = snapshot.flavorNotes,
        )

    private object FixedStoreClock : Clock {
        override fun read() = ClockReading(1, "2026-08-01")
    }
}
