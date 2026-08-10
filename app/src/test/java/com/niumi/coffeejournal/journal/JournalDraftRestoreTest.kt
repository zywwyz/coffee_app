package com.niumi.coffeejournal.journal

import com.niumi.coffeejournal.catalog.CatalogItemNotFoundException
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.DrinkDraft
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalDraftRestoreTest {
    @Test fun `process restore repopulates every field and reports missing catalog item`() = runBlocking {
        val restored = draft("missing", note = "保留", consumedAt = 123_456)
        val viewModel = JournalViewModel(
            RestoreJournal(restored), MissingCatalog, 2026, 8,
            CoroutineScope(Job() + Dispatchers.Unconfined),
        )
        yield()

        val editor = viewModel.uiState.value.editor
        assertNull(editor.selectedItemId)
        assertTrue(editor.invalidItem)
        assertEquals(9, editor.ratingHalfStars)
        assertEquals("9.90", editor.priceInput)
        assertEquals("手冲", editor.brewMethod)
        assertEquals("保留", editor.note)
        assertEquals(123_456L, editor.consumedAtEpochMillis)
        assertEquals("record", editor.editingRecordId)
    }

    @Test fun `late async restore cannot overwrite item user selected after page opened`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val journal = RestoreJournal(draft("old", note = "old", consumedAt = 100), gate)
        val viewModel = JournalViewModel(
            journal, AvailableCatalog, 2026, 8,
            CoroutineScope(Job() + Dispatchers.Unconfined),
        )

        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "new")
        viewModel.setNote("user")
        gate.complete(Unit)
        yield()

        assertEquals("new", viewModel.uiState.value.editor.selectedItemId)
        assertEquals("user", viewModel.uiState.value.editor.note)
    }

    @Test fun `reselecting after a missing restored item preserves and persists the draft fields`() = runBlocking {
        val restored = draft("missing", note = "保留", consumedAt = 123_456)
        val journal = RestoreJournal(restored)
        val viewModel = JournalViewModel(journal, ReselectCatalog, 2026, 8, CoroutineScope(Job() + Dispatchers.Unconfined))
        yield()

        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "new")
        val saved = journal.savedDrafts.last()
        assertEquals("new", saved.sourceItemId)
        assertEquals("new-revision", saved.revisionId)
        assertEquals(restored.consumedAtEpochMillis, saved.consumedAtEpochMillis)
        assertEquals(restored.ratingHalfStars, saved.ratingHalfStars)
        assertEquals(restored.actualPriceFen, saved.actualPriceFen)
        assertEquals(restored.brewMethod, saved.brewMethod)
        assertEquals(restored.note, saved.note)
        assertEquals(restored.editingRecordId, saved.editingRecordId)
        assertEquals(restored.expectedRecordRevision, saved.expectedRecordRevision)
    }

    private fun draft(item: String, note: String, consumedAt: Long) = DrinkDraft(
        "revision", ItemType.CHAIN_PRODUCT, item, "手冲", 9, 990, note,
        consumedAtEpochMillis = consumedAt, editingRecordId = "record", expectedRecordRevision = 2,
    )

    private class RestoreJournal(
        private val restored: DrinkDraft,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : JournalRepository {
        val savedDrafts = mutableListOf<DrinkDraft>()
        override fun observeMonth(year: Int, month: Int) = flowOf(emptyList<com.niumi.coffeejournal.core.model.DrinkRecord>())
        override suspend fun restoreDraft(): DrinkDraft { gate?.await(); return restored }
        override suspend fun newDraft(type: ItemType, itemId: String) = DrinkDraft("new-revision", type, itemId, null, null, null, "")
        override suspend fun replaceDraftForItem(current: DrinkDraft, type: ItemType, itemId: String): DrinkDraft =
            current.copy(revisionId = "new-revision", itemType = type, sourceItemId = itemId).also(savedDrafts::add)
        override suspend fun save(draft: DrinkDraft) = "record"
        override suspend fun saveDraft(draft: DrinkDraft): Boolean { savedDrafts += draft; return true }
        override suspend fun delete(recordId: String) = Unit
    }

    private object AvailableCatalog : CatalogRepository {
        private val brand = Brand("brand", BrandType.CHAIN, "品牌", null, MaintenanceMode.MANUAL_ONLY, null)
        private val item = CatalogItem("new", "brand", ItemType.CHAIN_PRODUCT, "新产品", null, null, null, null, null, null, ItemStatus.ACTIVE)
        override fun observeBrands(type: BrandType): Flow<List<Brand>> = flowOf(listOf(brand))
        override fun observeItems(brandId: String): Flow<List<CatalogItem>> = flowOf(listOf(item))
        override suspend fun getBrand(brandId: String) = brand
        override suspend fun getItem(itemId: String) = item.takeIf { it.id == itemId } ?: throw CatalogItemNotFoundException(itemId)
        override suspend fun upsertBrand(brand: Brand) = Unit
        override suspend fun upsertItem(item: CatalogItem) = Unit
        override suspend fun lastPriceFen(itemId: String): Long? = null
    }

    private object MissingCatalog : CatalogRepository by AvailableCatalog {
        override suspend fun getItem(itemId: String): CatalogItem = throw CatalogItemNotFoundException(itemId)
    }

    private object ReselectCatalog : CatalogRepository by AvailableCatalog {
        override suspend fun getItem(itemId: String): CatalogItem =
            if (itemId == "new") AvailableCatalog.getItem(itemId) else throw CatalogItemNotFoundException(itemId)
    }
}
