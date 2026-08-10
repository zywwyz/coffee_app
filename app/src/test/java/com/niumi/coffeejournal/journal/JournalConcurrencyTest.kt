package com.niumi.coffeejournal.journal

import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.DrinkDraft
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalConcurrencyTest {
    @Test fun `cancelled committed selection reloads persisted draft before retrying B and C`() = runBlocking {
        val committedA = CompletableDeferred<Unit>()
        val journal = CommitThenCancelJournal(committedA)
        val viewModel = viewModel(journal, RaceCatalogRepository())
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "seed")
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "A")
        committedA.await()
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "B")
        yield()
        assertEquals("B", viewModel.uiState.value.editor.selectedItemId)
        assertEquals("revision-B", journal.persisted?.revisionId)
        assertEquals("B", journal.persisted?.sourceItemId)
        assertEquals(null, viewModel.uiState.value.editor.errorMessage)
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "C")
        yield()
        assertEquals("C", viewModel.uiState.value.editor.selectedItemId)
        assertEquals("revision-C", journal.persisted?.revisionId)
        assertEquals("C", journal.persisted?.sourceItemId)
    }
    @Test
    fun `save is rejected while latest product selection is loading`() = runBlocking {
        val selectionGate = CompletableDeferred<Unit>()
        val journal = RaceJournalRepository()
        val viewModel = viewModel(journal, RaceCatalogRepository(slowGetGate = selectionGate))
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "fast")

        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "slow")
        viewModel.save()

        assertTrue(viewModel.uiState.value.editor.selecting)
        assertTrue(journal.savedRecords.isEmpty())
        selectionGate.complete(Unit)
        yield()
        assertEquals("slow", viewModel.uiState.value.editor.selectedItemId)
        assertTrue(!viewModel.uiState.value.editor.selecting)

        viewModel.setNote("B")
        viewModel.save()
        yield()
        assertEquals("revision-slow", journal.savedRecords.single().revisionId)
    }

    @Test
    fun `source and brand changes invalidate loading selection without stuck state`() = runBlocking {
        val sourceGate = CompletableDeferred<Unit>()
        val sourceViewModel = viewModel(RaceJournalRepository(), RaceCatalogRepository(slowGetGate = sourceGate))
        sourceViewModel.selectItem(ItemType.CHAIN_PRODUCT, "slow")
        assertTrue(sourceViewModel.uiState.value.editor.selecting)

        sourceViewModel.setSourceType(ItemType.PERSONAL_BEAN)
        assertTrue(!sourceViewModel.uiState.value.editor.selecting)
        sourceGate.complete(Unit)
        yield()
        assertEquals(null, sourceViewModel.uiState.value.editor.selectedItemId)

        val brandGate = CompletableDeferred<Unit>()
        val brandViewModel = viewModel(RaceJournalRepository(), RaceCatalogRepository(slowGetGate = brandGate))
        brandViewModel.selectItem(ItemType.CHAIN_PRODUCT, "slow")
        brandViewModel.selectBrand("brand")
        assertTrue(!brandViewModel.uiState.value.editor.selecting)
        brandGate.complete(Unit)
        yield()
        assertEquals(null, brandViewModel.uiState.value.editor.selectedItemId)
    }

    @Test
    fun `latest selection failure clears loading state`() = runBlocking {
        val viewModel = viewModel(RaceJournalRepository(), RaceCatalogRepository())

        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "failure")
        yield()

        assertTrue(!viewModel.uiState.value.editor.selecting)
        assertTrue(!viewModel.uiState.value.editor.saving)
        assertTrue(viewModel.uiState.value.editor.errorMessage.orEmpty().isNotBlank())
    }

    @Test
    fun `out of order selection cannot replace the latest item`() = runBlocking {
        val slowGate = CompletableDeferred<Unit>()
        val catalog = RaceCatalogRepository(slowGetGate = slowGate)
        val journal = RaceJournalRepository()
        val viewModel = viewModel(journal, catalog)

        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "slow")
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "fast")
        yield()
        slowGate.complete(Unit)
        yield()

        assertEquals("fast", viewModel.uiState.value.editor.selectedItemId)
        assertEquals("revision-fast", journal.startedDrafts.last().revisionId)
    }

    @Test
    fun `stale item flow cannot populate products after source changes`() = runBlocking {
        val itemFlowGate = CompletableDeferred<Unit>()
        val viewModel = viewModel(
            RaceJournalRepository(),
            RaceCatalogRepository(itemFlowGate = itemFlowGate),
        )

        viewModel.selectBrand("brand")
        viewModel.setSourceType(ItemType.PERSONAL_BEAN)
        itemFlowGate.complete(Unit)
        yield()

        assertTrue(viewModel.uiState.value.items.isEmpty())
    }

    @Test
    fun `saving rejects editor and selection changes until captured revision completes`() = runBlocking {
        val journal = RaceJournalRepository().apply { saveGate = CompletableDeferred() }
        val viewModel = viewModel(journal, RaceCatalogRepository())
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "fast")
        viewModel.setNote("before save")

        viewModel.save()
        viewModel.setNote("must be ignored")
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "other")
        viewModel.setSourceType(ItemType.PERSONAL_BEAN)
        yield()

        assertTrue(viewModel.uiState.value.editor.saving)
        assertEquals("fast", viewModel.uiState.value.editor.selectedItemId)
        assertEquals("before save", viewModel.uiState.value.editor.note)
        assertEquals("revision-fast", journal.savedRecords.single().revisionId)
        journal.saveGate?.complete(Unit)
        yield()
        assertEquals(1, viewModel.uiState.value.saveCompletedToken)
    }

    @Test
    fun `autosave continues after failure and persists the next edit`() = runBlocking {
        val journal = RaceJournalRepository().apply { autosaveFailuresRemaining = 1 }
        val viewModel = viewModel(journal, RaceCatalogRepository())
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "fast")

        viewModel.setNote("first fails")
        yield()
        assertTrue(viewModel.uiState.value.editor.errorMessage.orEmpty().contains("草稿"))
        viewModel.setNote("next persists")
        yield()

        assertEquals("next persists", journal.persistedDrafts.last().note)
    }

    @Test
    fun `conflated autosave keeps the final edit while one save is blocked`() = runBlocking {
        val journal = RaceJournalRepository().apply { autosaveGate = CompletableDeferred() }
        val viewModel = viewModel(journal, RaceCatalogRepository())
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "fast")
        viewModel.setNote("one")
        yield()

        viewModel.setNote("two")
        viewModel.setNote("final")
        journal.autosaveGate?.complete(Unit)
        yield()

        assertEquals("final", journal.persistedDrafts.last().note)
        assertEquals(2, journal.autosaveCalls)
    }

    @Test
    fun `cancelled old month resolution cannot overwrite the new month`() = runBlocking {
        val oldResolverGate = CompletableDeferred<Unit>()
        val journal = MonthRaceJournalRepository()
        val resolver = ImagePathResolver { assetId ->
            if (assetId == "old-image") {
                withContext(NonCancellable) { oldResolverGate.await() }
                "/old.png"
            } else {
                "/new.png"
            }
        }
        val viewModel = JournalViewModel(
            journal,
            RaceCatalogRepository(),
            2026,
            8,
            CoroutineScope(Job() + Dispatchers.Unconfined),
            resolver,
        )

        viewModel.nextMonth()
        yield()
        oldResolverGate.complete(Unit)
        yield()

        assertEquals(9, viewModel.uiState.value.month)
        assertEquals(listOf("new"), viewModel.uiState.value.records.map { it.id })
    }

    @Test
    fun `month resolves only representative distinct snapshot assets`() = runBlocking {
        val journal = RepresentativeJournalRepository()
        val calls = mutableListOf<String?>()
        val resolver = ImagePathResolver { assetId -> calls += assetId; "/$assetId.png" }

        JournalViewModel(
            journal,
            RaceCatalogRepository(),
            2026,
            8,
            CoroutineScope(Job() + Dispatchers.Unconfined),
            resolver,
        )
        yield()

        assertEquals(listOf("shared-image"), calls)
    }

    @Test
    fun `month uses historical logo when product snapshot asset cannot resolve`() = runBlocking {
        val journal = object : JournalRepository by UnsupportedJournalRepository() {
            override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> = flowOf(
                listOf(
                    record("drink", "2026-08-01", "missing-product").copy(
                        snapshot = DrinkSnapshot(
                            "Brand", "Coffee", null, null, "missing-product", brandLogoAssetId = "historical-logo",
                        ),
                    ),
                ),
            )
        }
        val resolver = ImagePathResolver { assetId ->
            if (assetId == "historical-logo") "/historical-logo.png" else null
        }

        val viewModel = JournalViewModel(
            journal,
            RaceCatalogRepository(),
            2026,
            8,
            CoroutineScope(Job() + Dispatchers.Unconfined),
            resolver,
        )

        val day = viewModel.uiState.value.days.single { it.localDate == "2026-08-01" }
        assertEquals("/historical-logo.png", day.brandLogoPath)
    }

    private fun viewModel(journal: JournalRepository, catalog: CatalogRepository) = JournalViewModel(
        journal,
        catalog,
        2026,
        8,
        CoroutineScope(Job() + Dispatchers.Unconfined),
    )

    private class RaceJournalRepository : JournalRepository {
        val startedDrafts = mutableListOf<DrinkDraft>()
        val persistedDrafts = mutableListOf<DrinkDraft>()
        val savedRecords = mutableListOf<DrinkDraft>()
        var saveGate: CompletableDeferred<Unit>? = null
        var autosaveGate: CompletableDeferred<Unit>? = null
        var autosaveFailuresRemaining = 0
        var autosaveCalls = 0

        override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> = flowOf(emptyList())
        override suspend fun newDraft(type: ItemType, itemId: String): DrinkDraft =
            DrinkDraft("revision-$itemId", type, itemId, null, null, null, "").also(startedDrafts::add)

        override suspend fun save(draft: DrinkDraft): String {
            savedRecords += draft
            saveGate?.await()
            return "record"
        }

        override suspend fun saveDraft(draft: DrinkDraft): Boolean {
            autosaveCalls++
            autosaveGate?.await()
            autosaveGate = null
            if (autosaveFailuresRemaining-- > 0) error("disk unavailable")
            persistedDrafts += draft
            return true
        }

        override suspend fun delete(recordId: String) = Unit
    }

    private class CommitThenCancelJournal(private val committedA: CompletableDeferred<Unit>) : JournalRepository {
        var persisted: DrinkDraft? = null
        override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> = flowOf(emptyList())
        override suspend fun newDraft(type: ItemType, itemId: String): DrinkDraft =
            DrinkDraft("revision-$itemId", type, itemId, null, 9, 990, "note").also { persisted = it }
        override suspend fun replaceDraftForItem(current: DrinkDraft, type: ItemType, itemId: String): DrinkDraft {
            if (persisted?.revisionId != current.revisionId) throw DraftConflictException()
            val next = current.copy(revisionId = "revision-$itemId", itemType = type, sourceItemId = itemId)
            persisted = next
            if (itemId == "A") { committedA.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            return next
        }
        override suspend fun currentDraft(): DrinkDraft? = persisted
        override suspend fun save(draft: DrinkDraft) = "record"
        override suspend fun saveDraft(draft: DrinkDraft) = true
        override suspend fun delete(recordId: String) = Unit
    }

    private class RaceCatalogRepository(
        private val slowGetGate: CompletableDeferred<Unit>? = null,
        private val itemFlowGate: CompletableDeferred<Unit>? = null,
    ) : CatalogRepository {
        override fun observeBrands(type: BrandType): Flow<List<Brand>> = flowOf(listOf(brand()))
        override fun observeItems(brandId: String): Flow<List<CatalogItem>> = if (itemFlowGate == null) {
            flowOf(emptyList())
        } else {
            flow {
                withContext(NonCancellable) { itemFlowGate.await() }
                emit(listOf(item("stale").copy(type = ItemType.PERSONAL_BEAN)))
            }
        }
        override suspend fun getBrand(brandId: String): Brand = brand()
        override suspend fun getItem(itemId: String): CatalogItem {
            if (itemId == "failure") error("catalog unavailable")
            if (itemId == "slow") withContext(NonCancellable) { slowGetGate?.await() }
            return item(itemId)
        }
        override suspend fun upsertBrand(brand: Brand) = Unit
        override suspend fun upsertItem(item: CatalogItem) = Unit
        override suspend fun lastPriceFen(itemId: String): Long? = null

        private fun brand() = Brand("brand", BrandType.CHAIN, "Brand", "logo", MaintenanceMode.MANUAL_ONLY, null)
        private fun item(id: String) = CatalogItem(
            id, "brand", ItemType.CHAIN_PRODUCT, id, "image-$id", null, null, null, null, null, ItemStatus.ACTIVE,
        )
    }

    private class MonthRaceJournalRepository : JournalRepository by UnsupportedJournalRepository() {
        override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> =
            flowOf(listOf(record(if (month == 8) "old" else "new", "2026-${month.toString().padStart(2, '0')}-01", if (month == 8) "old-image" else "new-image")))
    }

    private class RepresentativeJournalRepository : JournalRepository by UnsupportedJournalRepository() {
        override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> = flowOf(
            listOf(
                record("old-same-day", "2026-08-01", "unused-image", timestamp = 1),
                record("latest-same-day", "2026-08-01", "shared-image", timestamp = 2),
                record("other-day", "2026-08-02", "shared-image", timestamp = 3),
            ),
        )
    }

    private open class UnsupportedJournalRepository : JournalRepository {
        override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> = flowOf(emptyList())
        override suspend fun newDraft(type: ItemType, itemId: String): DrinkDraft = error("unused")
        override suspend fun save(draft: DrinkDraft): String = error("unused")
        override suspend fun saveDraft(draft: DrinkDraft): Boolean = error("unused")
        override suspend fun delete(recordId: String) = error("unused")
    }

    companion object {
        private fun record(id: String, date: String, image: String, timestamp: Long = 1) = DrinkRecord(
            id, timestamp, date, ItemType.CHAIN_PRODUCT, "item", null, null, null, null,
            DrinkSnapshot("Brand", "Coffee", null, null, image),
        )
    }
}
