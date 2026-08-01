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
    fun `calendar image falls back from product to brand logo to generic`() {
        assertEquals("product.webp", calendarImage("product.webp", "logo.webp"))
        assertEquals("logo.webp", calendarImage(null, "logo.webp"))
        assertEquals(GENERIC_COFFEE_IMAGE, calendarImage(null, null))
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
    fun `month resolves asset ids to paths and falls back to valid brand logo`() = runBlocking {
        val journal = FakeJournalRepository().apply {
            month.value = listOf(record("drink", "2026-08-05", 100, "product-asset"))
        }
        val resolver = ImagePathResolver { assetId ->
            when (assetId) {
                "product-asset" -> null
                "logo.webp" -> "/private/logo.png"
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
        assertEquals("/private/logo.png", day.imagePath)
        assertEquals("/private/logo.png", day.brandLogoPath)
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
            return "record"
        }

        override suspend fun saveDraft(draft: DrinkDraft): Boolean {
            drafts += draft
            return true
        }

        override suspend fun delete(recordId: String) = Unit
    }

    private inner class FakeCatalogRepository(
        private val item: CatalogItem = item(),
    ) : CatalogRepository {
        private val brand = Brand("brand", BrandType.CHAIN, "瑞幸", "logo.webp", com.niumi.coffeejournal.core.model.MaintenanceMode.MANUAL_ONLY, null)
        override fun observeBrands(type: BrandType): Flow<List<Brand>> = flowOf(listOf(brand.copy(type = type)))
        override fun observeItems(brandId: String): Flow<List<CatalogItem>> = flowOf(listOf(item))
        override suspend fun getBrand(brandId: String): Brand = brand
        override suspend fun getItem(itemId: String): CatalogItem = item
        override suspend fun upsertBrand(brand: Brand) = Unit
        override suspend fun upsertItem(item: CatalogItem) = Unit
        override suspend fun lastPriceFen(itemId: String): Long? = 990
    }
}
