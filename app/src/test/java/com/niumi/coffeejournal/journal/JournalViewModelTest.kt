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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalViewModelTest {
    @Test
    fun `month projection is always six rows and keeps out of month dates`() {
        val days = projectMonth(2026, 8, emptyList())

        assertEquals(42, days.size)
        assertEquals("2026-07-27", days.first().localDate)
        assertFalse(days.first().inDisplayedMonth)
        assertEquals("2026-09-06", days.last().localDate)
    }

    @Test
    fun `same day uses last product image and count`() {
        val records = listOf(
            record("first", "2026-08-05", 100, "first.webp"),
            record("last", "2026-08-05", 200, "last.webp"),
        )

        val cell = projectMonth(
            2026,
            8,
            records,
            productImagePathsByRecordId = mapOf("first" to "first.webp", "last" to "last.webp"),
        ).single { it.localDate == "2026-08-05" }

        assertEquals("last.webp", cell.imagePath)
        assertEquals(2, cell.drinkCount)
    }

    @Test
    fun `calendar image uses immutable snapshot path or generic`() {
        assertEquals("snapshot.webp", calendarImage("snapshot.webp"))
        assertEquals(GENERIC_COFFEE_IMAGE, calendarImage(null))
    }

    @Test
    fun `month summary counts cups spend and rated average`() {
        val summary = summarizeMonth(
            listOf(
                record("one", "2026-08-01", 100, null, price = 990, rating = 9),
                record("two", "2026-08-02", 200, null, price = null, rating = null),
                record("three", "2026-08-03", 300, null, price = 1010, rating = 8),
            ),
        )

        assertEquals(3, summary.cupCount)
        assertEquals(2000L, summary.totalSpendFen)
        assertEquals(4.25, summary.averageRatingStars!!, 0.0)
    }

    @Test
    fun `month spend saturates instead of overflowing negative`() {
        val summary = summarizeMonth(
            listOf(
                record("max", "2026-08-01", 1, null, price = Long.MAX_VALUE),
                record("extra", "2026-08-02", 2, null, price = 1),
            ),
        )

        assertEquals(Long.MAX_VALUE, summary.totalSpendFen)
        assertTrue(summary.totalSpendFen >= 0)
    }

    @Test
    fun `yuan parser is exact safe and rejects invalid input`() {
        assertEquals(990L, parseYuanToFen("9.9"))
        assertEquals(9L, parseYuanToFen("0.09"))
        assertEquals(0L, parseYuanToFen("0"))
        assertNull(parseYuanToFen("1.999"))
        assertNull(parseYuanToFen("-1"))
        assertNull(parseYuanToFen("abc"))
        assertNull(parseYuanToFen("999999999999999999999"))
    }

    @Test
    fun `editor changes autosave latest draft`() = runBlocking {
        val journal = FakeJournalRepository()
        val catalog = FakeCatalogRepository()
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val viewModel = JournalViewModel(journal, catalog, 2026, 8, scope)

        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "item")
        viewModel.setRating(9)
        viewModel.setPriceInput("12.34")
        viewModel.setBrewMethod("冰")
        viewModel.setNote("顺滑")
        yield()

        assertEquals(9, journal.drafts.last().ratingHalfStars)
        assertEquals(1234L, journal.drafts.last().actualPriceFen)
        assertEquals("冰", journal.drafts.last().brewMethod)
        assertEquals("顺滑", journal.drafts.last().note)
    }

    @Test
    fun `duplicate save tap is rejected while save is running`() = runBlocking {
        val journal = FakeJournalRepository().apply { saveGate = CompletableDeferred() }
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val viewModel = JournalViewModel(journal, FakeCatalogRepository(), 2026, 8, scope)
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "item")

        viewModel.save()
        viewModel.save()
        yield()

        assertTrue(viewModel.uiState.value.editor.saving)
        assertEquals(1, journal.saveCalls)
        journal.saveGate?.complete(Unit)
        yield()
        assertFalse(viewModel.uiState.value.editor.saving)
    }

    @Test
    fun `record conflict tells the user to reopen instead of offering an invalid retry`() = runBlocking {
        val journal = FakeJournalRepository().apply { saveError = RecordConflictException("record") }
        val viewModel = JournalViewModel(
            journal, FakeCatalogRepository(), 2026, 8, CoroutineScope(Job() + Dispatchers.Unconfined),
        )
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "item")

        viewModel.save()
        yield()

        assertFalse(viewModel.uiState.value.editor.saving)
        assertEquals("记录已在其他位置修改，请重新打开", viewModel.uiState.value.editor.errorMessage)
    }

    @Test
    fun `deleting the record being edited clears the in memory editor`() = runBlocking {
        val journal = FakeJournalRepository().apply {
            editDraft = DrinkDraft(
                "edit-revision", ItemType.CHAIN_PRODUCT, "item", null, null, null, "未保存",
                editingRecordId = "record", expectedRecordRevision = 0,
            )
        }
        val viewModel = JournalViewModel(
            journal, FakeCatalogRepository(), 2026, 8, CoroutineScope(Job() + Dispatchers.Unconfined),
        )

        viewModel.editRecord("record")
        yield()
        viewModel.deleteRecord("record")
        yield()

        assertEquals(listOf("record"), journal.deletedRecords)
        assertNull(viewModel.uiState.value.editor.editingRecordId)
        assertNull(viewModel.uiState.value.editor.selectedItemId)
        assertFalse(viewModel.uiState.value.editor.saving)
    }

    @Test
    fun `discarding a draft clears the editor only after repository accepts its revision`() = runBlocking {
        val journal = FakeJournalRepository()
        val viewModel = JournalViewModel(
            journal, FakeCatalogRepository(), 2026, 8, CoroutineScope(Job() + Dispatchers.Unconfined),
        )
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "item")

        viewModel.discardDraft()
        yield()

        assertEquals(listOf("revision"), journal.discardedRevisions)
        assertNull(viewModel.uiState.value.editor.selectedItemId)
        assertNull(viewModel.uiState.value.editor.editingRecordId)
    }

    @Test
    fun `missing item image opens non blocking supplement prompt`() = runBlocking {
        val catalog = FakeCatalogRepository(item = item().copy(imageAssetId = null, status = ItemStatus.NEEDS_IMAGE))
        val viewModel = JournalViewModel(
            FakeJournalRepository(),
            catalog,
            2026,
            8,
            CoroutineScope(Job() + Dispatchers.Unconfined),
        )

        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "item")

        assertTrue(viewModel.uiState.value.editor.needsImagePrompt)
        viewModel.skipImagePrompt()
        assertFalse(viewModel.uiState.value.editor.needsImagePrompt)
    }

    @Test
    fun `confirmed screenshot image is saved on product and fills actual price`() = runBlocking {
        val catalog = FakeCatalogRepository(item = item().copy(imageAssetId = null, status = ItemStatus.NEEDS_IMAGE))
        val viewModel = JournalViewModel(
            FakeJournalRepository(), catalog, 2026, 8,
            CoroutineScope(Job() + Dispatchers.Unconfined),
        )
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "item")

        assertTrue(viewModel.attachImportedImage("new-asset", 1_288L))

        assertEquals("new-asset", catalog.savedItem?.imageAssetId)
        assertEquals(ItemStatus.ACTIVE, catalog.savedItem?.status)
        assertEquals("12.88", viewModel.uiState.value.editor.priceInput)
        assertFalse(viewModel.uiState.value.editor.needsImagePrompt)
    }

    @Test
    fun `save is blocked during delayed image attach then snapshot reads imported image`() = runBlocking {
        val catalog = DelayedCatalogRepository(item().copy(imageAssetId = null, status = ItemStatus.NEEDS_IMAGE))
        val journal = SnapshotJournalRepository(catalog)
        val viewModel = JournalViewModel(
            journal, catalog, 2026, 8, CoroutineScope(Job() + Dispatchers.Unconfined),
        )
        viewModel.selectItem(ItemType.CHAIN_PRODUCT, "item")

        val attaching = async { viewModel.attachImportedImage("new-asset", null) }
        catalog.upsertStarted.await()
        viewModel.save()
        assertEquals(0, journal.saveCalls)
        assertTrue(viewModel.uiState.value.editor.attachingImage)

        catalog.releaseUpsert.complete(Unit)
        assertTrue(attaching.await())
        viewModel.save()

        assertEquals(1, journal.saveCalls)
        assertEquals("new-asset", journal.savedSnapshotImage)
    }

    @Test
    fun `month resolves immutable snapshot image without current logo lookup`() = runBlocking {
        val journal = FakeJournalRepository().apply {
            month.value = listOf(record("drink", "2026-08-05", 100, "snapshot-logo"))
        }
        val resolver = ImagePathResolver { assetId ->
            when (assetId) {
                "snapshot-logo" -> "/private/logo-at-save.png"
                else -> null
            }
        }

        val viewModel = JournalViewModel(
            journal,
            FakeCatalogRepository(),
            2026,
            8,
            CoroutineScope(Job() + Dispatchers.Unconfined),
            resolver,
        )

        val day = viewModel.uiState.value.days.single { it.localDate == "2026-08-05" }
        assertEquals("/private/logo-at-save.png", day.imagePath)
    }

    @Test
    fun `calendar mode persists without changing projected month data`() = runBlocking {
        val journal = FakeJournalRepository().apply {
            month.value = listOf(
                DrinkRecord(
                    "drink", 100, "2026-08-05", ItemType.CHAIN_PRODUCT, "item", null, null, null, null,
                    DrinkSnapshot("瑞幸", "拿铁", null, null, "product-asset", "brand-asset"),
                ),
            )
        }
        val preference = FakeCalendarDisplayPreference()
        val resolver = ImagePathResolver { if (it == "product-asset") "product.webp" else "brand.webp" }
        val viewModel = JournalViewModel(
            journal, FakeCatalogRepository(), 2026, 8, CoroutineScope(Job() + Dispatchers.Unconfined),
            imagePathResolver = resolver,
            calendarDisplayPreference = preference,
        )
        val before = viewModel.uiState.value

        viewModel.setCalendarDisplayMode(CalendarDisplayMode.BRAND)

        val after = viewModel.uiState.value
        assertEquals(CalendarDisplayMode.BRAND, after.calendarDisplayMode)
        assertEquals(before.records, after.records)
        assertEquals(before.days, after.days)
        assertEquals(before.summary, after.summary)
        assertEquals(CalendarDisplayMode.BRAND, preference.value)

        viewModel.nextMonth()
        assertEquals(CalendarDisplayMode.BRAND, viewModel.uiState.value.calendarDisplayMode)
        assertEquals(0, journal.saveCalls)
    }

    @Test
    fun `fresh view model reads persisted calendar mode`() = runBlocking {
        val preference = FakeCalendarDisplayPreference()
        val first = JournalViewModel(
            FakeJournalRepository(), FakeCatalogRepository(), 2026, 8,
            CoroutineScope(Job() + Dispatchers.Unconfined), calendarDisplayPreference = preference,
        )
        first.setCalendarDisplayMode(CalendarDisplayMode.BRAND)
        val fresh = JournalViewModel(
            FakeJournalRepository(), FakeCatalogRepository(), 2026, 8,
            CoroutineScope(Job() + Dispatchers.Unconfined), calendarDisplayPreference = preference,
        )

        assertEquals(CalendarDisplayMode.BRAND, fresh.uiState.value.calendarDisplayMode)
    }

    private fun record(
        id: String,
        date: String,
        timestamp: Long,
        image: String?,
        price: Long? = null,
        rating: Int? = null,
    ) = DrinkRecord(
        id = id,
        occurredAtEpochMillis = timestamp,
        localDate = date,
        itemType = ItemType.CHAIN_PRODUCT,
        sourceItemId = "item",
        brewMethod = null,
        ratingHalfStars = rating,
        actualPriceFen = price,
        note = null,
        snapshot = DrinkSnapshot("瑞幸", "拿铁", null, null, image),
    )

    private fun item() = CatalogItem(
        id = "item",
        brandId = "brand",
        type = ItemType.CHAIN_PRODUCT,
        name = "拿铁",
        imageAssetId = "product.webp",
        origin = null,
        processing = null,
        roastLevel = null,
        flavorNotes = null,
        brewMethod = "热",
        status = ItemStatus.ACTIVE,
    )

    private class FakeJournalRepository : JournalRepository {
        val month = MutableStateFlow<List<DrinkRecord>>(emptyList())
        val drafts = mutableListOf<DrinkDraft>()
        var saveCalls = 0
        var saveGate: CompletableDeferred<Unit>? = null
        var saveError: Exception? = null
        var editDraft: DrinkDraft? = null
        val deletedRecords = mutableListOf<String>()
        val discardedRevisions = mutableListOf<String>()

        override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> = this.month
        override suspend fun newDraft(type: ItemType, itemId: String) = DrinkDraft(
            revisionId = "revision",
            itemType = type,
            sourceItemId = itemId,
            brewMethod = "热",
            ratingHalfStars = null,
            actualPriceFen = 990,
            note = "",
        )

        override suspend fun save(draft: DrinkDraft): String {
            saveCalls++
            saveGate?.await()
            saveError?.let { throw it }
            return "record"
        }

        override suspend fun saveDraft(draft: DrinkDraft): Boolean {
            drafts += draft
            return true
        }

        override suspend fun editDraft(recordId: String): DrinkDraft = requireNotNull(editDraft)
        override suspend fun discardDraft(revisionId: String): Boolean {
            discardedRevisions += revisionId
            return true
        }
        override suspend fun delete(recordId: String) { deletedRecords += recordId }
    }

    private inner class FakeCatalogRepository(
        item: CatalogItem = item(),
    ) : CatalogRepository {
        private var currentItem = item
        var savedItem: CatalogItem? = null
        private val brand = Brand("brand", BrandType.CHAIN, "瑞幸", "logo.webp", com.niumi.coffeejournal.core.model.MaintenanceMode.MANUAL_ONLY, null)
        override fun observeBrands(type: BrandType): Flow<List<Brand>> = flowOf(listOf(brand.copy(type = type)))
        override fun observeItems(brandId: String): Flow<List<CatalogItem>> = flowOf(listOf(currentItem))
        override suspend fun getBrand(brandId: String): Brand = brand
        override suspend fun getItem(itemId: String): CatalogItem = currentItem
        override suspend fun upsertBrand(brand: Brand) = Unit
        override suspend fun upsertItem(item: CatalogItem) { savedItem = item; currentItem = item }
        override suspend fun lastPriceFen(itemId: String): Long? = 990
    }

    private class FakeCalendarDisplayPreference(
        var value: CalendarDisplayMode = CalendarDisplayMode.COFFEE,
    ) : CalendarDisplayPreference {
        override fun read(): CalendarDisplayMode = value
        override fun write(mode: CalendarDisplayMode) { value = mode }
    }

    private inner class DelayedCatalogRepository(initial: CatalogItem) : CatalogRepository {
        private var current = initial
        val upsertStarted = CompletableDeferred<Unit>()
        val releaseUpsert = CompletableDeferred<Unit>()
        private val brand = Brand("brand", BrandType.CHAIN, "瑞幸", null, com.niumi.coffeejournal.core.model.MaintenanceMode.MANUAL_ONLY, null)
        override fun observeBrands(type: BrandType): Flow<List<Brand>> = flowOf(listOf(brand))
        override fun observeItems(brandId: String): Flow<List<CatalogItem>> = flowOf(listOf(current))
        override suspend fun getBrand(brandId: String): Brand = brand
        override suspend fun getItem(itemId: String): CatalogItem = current
        override suspend fun upsertBrand(brand: Brand) = Unit
        override suspend fun upsertItem(item: CatalogItem) {
            upsertStarted.complete(Unit)
            releaseUpsert.await()
            current = item
        }
        override suspend fun lastPriceFen(itemId: String): Long? = null
    }

    private class SnapshotJournalRepository(private val catalog: CatalogRepository) : JournalRepository {
        var saveCalls = 0
        var savedSnapshotImage: String? = null
        override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> = flowOf(emptyList())
        override suspend fun newDraft(type: ItemType, itemId: String) = DrinkDraft("revision", type, itemId, null, null, null, "")
        override suspend fun save(draft: DrinkDraft): String {
            saveCalls++
            savedSnapshotImage = catalog.getItem(draft.sourceItemId).imageAssetId
            return "record"
        }
        override suspend fun saveDraft(draft: DrinkDraft): Boolean = true
        override suspend fun delete(recordId: String) = Unit
    }
}
