package com.niumi.coffeejournal.journal

import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.DrinkDraft
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalEditSnapshotTest {
    @Test fun `editing fields preserves historical snapshot after catalog changed`() = runBlocking {
        val store = EditingStore(record())
        val repository = DefaultJournalRepository(CurrentCatalog(), store, EditClock)

        val draft = repository.editDraft("record").copy(note = "修改备注", ratingHalfStars = null)
        repository.save(draft)

        assertEquals("历史产品名", store.record.snapshot.itemName)
        assertEquals("历史品牌名", store.record.snapshot.brandName)
        assertEquals("修改备注", store.record.note)
        assertEquals(null, store.record.ratingHalfStars)
        assertEquals(1, store.record.revision)
        assertEquals(100L, store.record.createdAtEpochMillis)
        assertEquals(EditClock.read().epochMillis, store.record.updatedAtEpochMillis)
    }

    @Test fun `explicitly changing product creates snapshot from selected catalog item`() = runBlocking {
        val store = EditingStore(record())
        val repository = DefaultJournalRepository(CurrentCatalog(), store, EditClock)

        repository.save(repository.editDraft("record").copy(sourceItemId = "new-item"))

        assertEquals("目录新产品", store.record.snapshot.itemName)
        assertEquals("当前品牌", store.record.snapshot.brandName)
        assertEquals("new-item", store.record.sourceItemId)
    }

    private fun record() = DrinkRecord(
        id = "record", occurredAtEpochMillis = 200, localDate = "1970-01-01",
        itemType = ItemType.CHAIN_PRODUCT, sourceItemId = "old-item", brewMethod = "热",
        ratingHalfStars = 9, actualPriceFen = 990, note = null,
        snapshot = DrinkSnapshot("历史品牌名", "历史产品名", "旧产地", null, "old-image"),
        createdAtEpochMillis = 100, updatedAtEpochMillis = 200, revision = 0,
    )

    private class EditingStore(initial: DrinkRecord) : DrinkStore {
        var record = initial
        override fun observeRange(startLocalDate: String, endLocalDate: String): Flow<List<DrinkRecord>> = flowOf(listOf(record))
        override suspend fun startDraft(draft: DrinkDraft) = Unit
        override suspend fun get(recordId: String): DrinkRecord? = record.takeIf { it.id == recordId }
        override suspend fun saveRecordAndClearDraft(record: DrinkRecord, revisionId: String) = error("not create")
        override suspend fun update(record: DrinkRecord, expectedRevision: Int, draftRevisionId: String): Boolean {
            if (this.record.revision != expectedRevision) return false
            this.record = record
            return true
        }
        override suspend fun saveDraft(draft: DrinkDraft) = true
        override suspend fun delete(recordId: String) = Unit
    }

    private class CurrentCatalog : CatalogRepository {
        private val brand = Brand("brand", BrandType.CHAIN, "当前品牌", null, MaintenanceMode.MANUAL_ONLY, null)
        private val item = CatalogItem(
            "new-item", "brand", ItemType.CHAIN_PRODUCT, "目录新产品", "new-image",
            "新产地", "水洗", null, null, "冰", ItemStatus.ACTIVE,
        )
        override fun observeBrands(type: BrandType): Flow<List<Brand>> = flowOf(listOf(brand))
        override fun observeItems(brandId: String): Flow<List<CatalogItem>> = flowOf(listOf(item))
        override suspend fun getBrand(brandId: String) = brand
        override suspend fun getItem(itemId: String) = item.also { require(itemId == it.id) }
        override suspend fun upsertBrand(brand: Brand) = Unit
        override suspend fun upsertItem(item: CatalogItem) = Unit
        override suspend fun lastPriceFen(itemId: String): Long? = null
    }

    private object EditClock : Clock {
        override fun read() = ClockReading(1_000, "1970-01-01")
    }
}
