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

class JournalRepositoryTest {
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
        assertEquals(listOf(draft), store.startedDrafts)
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
        assertEquals(1_754_044_800_123, saved.occurredAtEpochMillis)
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

        assertEquals(0L, store.saved.single().occurredAtEpochMillis)
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
