package com.niumi.coffeejournal.journal

import android.content.Context
import androidx.room.Room
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.model.DrinkDraft
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.MaintenanceMode
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class JournalRecordLifecycleTest {
    private lateinit var database: CoffeeDatabase
    private lateinit var store: RoomDrinkStore

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication() as Context,
            CoffeeDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = RoomDrinkStore(database, FixedClock)
    }

    @After fun tearDown() = database.close()

    @Test fun `draft date time survives store recreation and successful save clears it`() = runBlocking {
        val draft = draft(consumedAt = 1_754_006_460_000L, note = "补记")
        store.startDraft(draft)

        assertEquals(draft, RoomDrinkStore(database, FixedClock).restoreDraft())

        store.saveRecordAndClearDraft(record(draft.consumedAtEpochMillis), draft.revisionId)
        assertNull(store.restoreDraft())
    }

    @Test fun `atomic draft replacement preserves fields and leaves the old draft on CAS conflict`() = runBlocking {
        val original = draft(1_754_006_460_000L, "原备注").copy(editingRecordId = "record", expectedRecordRevision = 2)
        store.startDraft(original)
        val replacement = original.copy(
            revisionId = "replacement", itemType = ItemType.PERSONAL_BEAN, sourceItemId = "bean",
        )

        assertTrue(store.replaceDraft(original.revisionId, replacement))
        assertEquals(replacement, RoomDrinkStore(database, FixedClock).restoreDraft())
        assertFalse(store.replaceDraft(original.revisionId, replacement.copy(note = "stale")))
        assertEquals(replacement, store.restoreDraft())
    }

    @Test fun `conditional update preserves id and created time and advances revision`() = runBlocking {
        val original = record(1_754_006_460_000L)
        database.drinkDao().insert(original.toEntityForLifecycleTest())

        val changed = original.copy(
            occurredAtEpochMillis = 1_750_000_000_000L,
            localDate = "2025-06-15",
            note = "edited",
            updatedAtEpochMillis = 1_760_000_000_000L,
            revision = 1,
        )

        assertTrue(store.update(changed, expectedRevision = 0, draftRevisionId = "draft-revision"))
        assertFalse(store.update(changed.copy(note = "stale"), expectedRevision = 0, draftRevisionId = "draft-revision"))
        val saved = store.get(original.id)!!
        assertEquals(original.id, saved.id)
        assertEquals(original.createdAtEpochMillis, saved.createdAtEpochMillis)
        assertEquals("edited", saved.note)
        assertEquals(1, saved.revision)
    }

    @Test fun `deleting record immediately invalidates observed month without deleting image assets`() = runBlocking {
        database.imageAssetDao().upsert(
            com.niumi.coffeejournal.core.database.ImageAssetEntity(
                "asset", "/managed/asset.webp", "a".repeat(64), "RECORD_SNAPSHOT", 1,
            ),
        )
        database.drinkDao().insert(
            record(1_754_006_460_000L).copy(
                snapshot = DrinkSnapshot("历史品牌", "历史产品", null, null, "asset"),
            ).toEntityForLifecycleTest(),
        )
        assertEquals(1, store.observeRange("2025-08-01", "2025-08-31").first().size)

        store.delete("record")

        assertTrue(store.observeRange("2025-08-01", "2025-08-31").first().isEmpty())
        assertEquals("asset", database.imageAssetDao().get("asset")?.id)
    }

    @Test fun `deleting an edited record clears its persisted draft atomically`() = runBlocking {
        database.drinkDao().insert(record(1_754_006_460_000L).toEntityForLifecycleTest())
        store.startDraft(draft(1_754_006_460_000L, "待保存修改").copy(
            editingRecordId = "record", expectedRecordRevision = 0,
        ))

        store.delete("record")

        assertNull(store.get("record"))
        assertNull(RoomDrinkStore(database, FixedClock).restoreDraft())
    }

    @Test fun `starting an edit after delete creates no draft`() = runBlocking {
        database.drinkDao().insert(record(1_754_006_460_000L).toEntityForLifecycleTest())
        store.delete("record")

        assertNull(store.startEditDraft("record", "edit-revision"))
        assertNull(store.restoreDraft())
    }

    @Test fun `starting an edit then deleting clears both record and draft`() = runBlocking {
        database.drinkDao().insert(record(1_754_006_460_000L).toEntityForLifecycleTest())

        assertEquals("record", store.startEditDraft("record", "edit-revision")?.editingRecordId)
        store.delete("record")

        assertNull(store.get("record"))
        assertNull(store.restoreDraft())
    }

    @Test fun `deleting another record preserves the current edit draft`() = runBlocking {
        database.drinkDao().insert(record(1_754_006_460_000L).toEntityForLifecycleTest())
        database.drinkDao().insert(record(1_754_006_460_001L).copy(id = "other").toEntityForLifecycleTest())
        val editingDraft = draft(1_754_006_460_000L, "待保存修改").copy(
            editingRecordId = "record", expectedRecordRevision = 0,
        )
        store.startDraft(editingDraft)

        store.delete("other")

        assertNull(store.get("other"))
        assertEquals(editingDraft, store.restoreDraft())
    }

    @Test fun `delete transaction failure leaves both record and matching draft intact`() = runBlocking {
        val saved = record(1_754_006_460_000L)
        database.drinkDao().insert(saved.toEntityForLifecycleTest())
        val editingDraft = draft(1_754_006_460_000L, "待保存修改").copy(
            editingRecordId = "record", expectedRecordRevision = 0,
        )
        store.startDraft(editingDraft)
        database.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER fail_draft_clear BEFORE DELETE ON draft_records
               WHEN OLD.editingRecordId = 'record' BEGIN SELECT RAISE(ABORT, 'forced failure'); END""",
        )

        try {
            store.delete("record")
            fail("Expected transaction failure")
        } catch (_: Exception) {
            // The trigger aborts draft deletion; Room must roll back the preceding record deletion too.
        }

        assertEquals(saved, store.get("record"))
        assertEquals(editingDraft, store.restoreDraft())
    }

    @Test fun `discarding the current draft leaves saved records intact`() = runBlocking {
        val saved = record(1_754_006_460_000L)
        database.drinkDao().insert(saved.toEntityForLifecycleTest())
        val draft = draft(1_754_006_460_000L, "放弃的输入")
        store.startDraft(draft)

        assertTrue(store.discardDraft(draft.revisionId))

        assertNull(store.restoreDraft())
        assertEquals(saved, store.get(saved.id))
    }

    @Test fun `deleting an edited record does not poison a recreated repository new save`() = runBlocking {
        database.drinkDao().insert(record(1_754_006_460_000L).toEntityForLifecycleTest())
        val repository = DefaultJournalRepository(LifecycleCatalog, store, FixedClock)
        repository.editDraft("record")

        repository.delete("record")

        val recreated = DefaultJournalRepository(LifecycleCatalog, RoomDrinkStore(database, FixedClock), FixedClock)
        assertNull(recreated.restoreDraft())
        val savedId = recreated.save(recreated.newDraft(ItemType.CHAIN_PRODUCT, "item"))
        assertEquals("item", recreated.get(savedId)?.sourceItemId)
    }

    @Test fun `local date follows selected instant in mainland timezone`() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        assertEquals("2025-07-31", localDateForEpoch(1_753_974_000_000L, zone))
        assertEquals("2025-08-01", localDateForEpoch(1_753_977_600_000L, zone))
    }

    private fun draft(consumedAt: Long, note: String) = DrinkDraft(
        revisionId = "draft-revision",
        itemType = ItemType.CHAIN_PRODUCT,
        sourceItemId = "item",
        brewMethod = "冰",
        ratingHalfStars = 9,
        actualPriceFen = 990,
        note = note,
        consumedAtEpochMillis = consumedAt,
    )

    private fun record(consumedAt: Long) = DrinkRecord(
        id = "record",
        occurredAtEpochMillis = consumedAt,
        localDate = "2025-08-01",
        itemType = ItemType.CHAIN_PRODUCT,
        sourceItemId = "item",
        brewMethod = null,
        ratingHalfStars = null,
        actualPriceFen = null,
        note = null,
        snapshot = DrinkSnapshot("历史品牌", "历史产品", null, null, null),
        createdAtEpochMillis = 1_754_006_460_000L,
        updatedAtEpochMillis = 1_754_006_460_000L,
        revision = 0,
    )

    private object FixedClock : Clock {
        override fun read() = ClockReading(1_760_000_000_000L, "2025-10-07")
    }

    private object LifecycleCatalog : CatalogRepository {
        private val brand = Brand("brand", BrandType.CHAIN, "测试品牌", null, MaintenanceMode.MANUAL_ONLY, null)
        private val item = CatalogItem(
            "item", "brand", ItemType.CHAIN_PRODUCT, "测试产品", null, null, null, null, null, "热", ItemStatus.ACTIVE,
        )
        override fun observeBrands(type: BrandType): Flow<List<Brand>> = flowOf(listOf(brand))
        override fun observeItems(brandId: String): Flow<List<CatalogItem>> = flowOf(listOf(item))
        override suspend fun getBrand(brandId: String): Brand = brand
        override suspend fun getItem(itemId: String): CatalogItem = item
        override suspend fun upsertBrand(brand: Brand) = Unit
        override suspend fun upsertItem(item: CatalogItem) = Unit
        override suspend fun lastPriceFen(itemId: String): Long? = null
    }

    private fun DrinkRecord.toEntityForLifecycleTest() =
        com.niumi.coffeejournal.core.database.DrinkRecordEntity(
            id, occurredAtEpochMillis, localDate, itemType.name, sourceItemId,
            brewMethod, ratingHalfStars, actualPriceFen, note,
            snapshot.brandName, snapshot.itemName, snapshot.origin, snapshot.processing,
            snapshot.imageAssetId, snapshot.brandLogoAssetId, snapshot.roastLevel,
            snapshot.flavorNotes, createdAtEpochMillis, updatedAtEpochMillis, revision,
        )
}
