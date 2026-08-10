package com.niumi.coffeejournal.journal

import android.content.Context
import androidx.room.Room
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.model.DrinkDraft
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import com.niumi.coffeejournal.core.model.ItemType
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun DrinkRecord.toEntityForLifecycleTest() =
        com.niumi.coffeejournal.core.database.DrinkRecordEntity(
            id, occurredAtEpochMillis, localDate, itemType.name, sourceItemId,
            brewMethod, ratingHalfStars, actualPriceFen, note,
            snapshot.brandName, snapshot.itemName, snapshot.origin, snapshot.processing,
            snapshot.imageAssetId, snapshot.brandLogoAssetId, snapshot.roastLevel,
            snapshot.flavorNotes, createdAtEpochMillis, updatedAtEpochMillis, revision,
        )
}
