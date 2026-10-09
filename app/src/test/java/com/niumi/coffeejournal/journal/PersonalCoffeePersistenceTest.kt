package com.niumi.coffeejournal.journal

import androidx.room.Room
import com.niumi.coffeejournal.catalog.RoomCatalogRepository
import com.niumi.coffeejournal.core.database.*
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalCoffeePersistenceTest {
    private lateinit var db: CoffeeDatabase
    private lateinit var catalog: RoomCatalogRepository
    private lateinit var repository: DefaultJournalRepository
    private val clock = object : Clock { override fun read() = ClockReading(localNoonEpoch("2026-10-07"), "2026-10-07") }
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CoffeeDatabase::class.java).allowMainThreadQueries().build()
        catalog = RoomCatalogRepository(db.brandDao(), db.catalogItemDao(), db.drinkDao())
        repository = DefaultJournalRepository(catalog, RoomDrinkStore(db, clock), clock)
        db.brandDao().upsert(BrandEntity("brand", "ROASTER", "烘焙商", "烘焙商", null, "MANUAL_ONLY", null))
        db.catalogItemDao().upsert(CatalogItemEntity("bean", "brand", "PERSONAL_BEAN", "豆", "豆", status = "ACTIVE", purchasePriceFen = 10000, packageWeightMg = 250000))
        db.catalogItemDao().upsert(CatalogItemEntity("bag", "brand", "PERSONAL_BEAN", "挂耳", "挂耳", status = "ACTIVE", personalProductType = "DRIP_BAG", purchasePriceFen = 5900, packageCount = 10))
    }
    @After fun close() { db.close() }

    @Test fun `new draft snapshots catalog base survives restore and edited catalog`() = runBlocking {
        val draft = repository.newDraft(ItemType.PERSONAL_BEAN, "bean").copy(consumedWeightMg = 15000).recalculatePersonalPrice()
        assertEquals(600L, draft.actualPriceFen)
        assertTrue(repository.saveDraft(draft))
        assertEquals(draft, repository.restoreDraft())
        catalog.upsertItem(catalog.getItem("bean").copy(purchasePriceFen = 20000))
        assertEquals(draft, repository.replaceDraftForItem(draft, ItemType.PERSONAL_BEAN, "bean"))
        val id = repository.save(draft)
        val edit = repository.editDraft(id)
        assertEquals(10000L, edit.purchasePriceFen)
        repository.save(edit.copy(note = "新备注", consumedWeightMg = 20000).recalculatePersonalPrice())
        assertEquals(800L, repository.get(id)?.actualPriceFen)
        assertEquals(10000L, repository.get(id)?.purchasePriceFen)
    }

    @Test fun `drip bag defaults one and product replacement clears bean usage`() = runBlocking {
        val bean = repository.newDraft(ItemType.PERSONAL_BEAN, "bean").copy(consumedWeightMg = 15000, priceMode = PriceMode.MANUAL, actualPriceFen = 999)
        repository.saveDraft(bean)
        val bag = repository.replaceDraftForItem(bean, ItemType.PERSONAL_BEAN, "bag")
        assertNull(bag.consumedWeightMg)
        assertNull(bag.packageWeightMg)
        assertEquals(1L, bag.consumedCount)
        assertEquals(590L, bag.actualPriceFen)
        assertEquals(PriceMode.AUTO, bag.priceMode)
        val id = repository.save(bag)
        assertEquals(CoffeeType.DRIP_BAG, repository.get(id)?.snapshot?.coffeeType)
    }

    @Test fun `legacy record edit preserves price without current catalog base`() = runBlocking {
        db.drinkDao().insert(DrinkRecordEntity("old", localNoonEpoch("2026-10-01"), "2026-10-01", "PERSONAL_BEAN", "bean", actualPriceFen = 888, snapshotBrandName = "旧品牌", snapshotItemName = "旧名称", snapshotCoffeeType = "HAND_BREW"))
        val edit = repository.editDraft("old")
        assertEquals(PriceMode.MANUAL, edit.priceMode)
        assertNull(edit.purchasePriceFen)
        repository.save(edit.copy(ratingHalfStars = 9, note = "只改评分"))
        val record = repository.get("old")!!
        assertEquals(888L, record.actualPriceFen)
        assertNull(record.purchasePriceFen)
        assertEquals("旧名称", record.snapshot.itemName)
        assertEquals(CoffeeType.HAND_BREW, record.snapshot.coffeeType)
    }

    @Test fun `photo only update preserves every cost snapshot field`() = runBlocking {
        val draft = repository.newDraft(ItemType.PERSONAL_BEAN, "bean").copy(brewMethod = "意式", consumedWeightMg = 18000).recalculatePersonalPrice()
        val id = repository.save(draft)
        val old = repository.get(id)!!
        db.imageAssetDao().upsert(ImageAssetEntity("photo", "/image.png", "a".repeat(64), "RECORD_SNAPSHOT", 1))
        assertTrue(repository.updateRecordSnapshotImage(id, "photo", old.revision, repository.photoUpdateToken()))
        val changed = repository.get(id)!!
        assertEquals(old.copy(snapshot = old.snapshot.copy(imageAssetId = "photo"), updatedAtEpochMillis = changed.updatedAtEpochMillis, revision = changed.revision), changed)
        assertEquals(CoffeeType.ESPRESSO, changed.snapshot.coffeeType)
    }

    @Test fun `viewmodel manual override quantity validation and automatic reset`() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val vm = JournalViewModel(repository, catalog, 2026, 10, coroutineScope = scope, clock = clock, imagePathResolver = ImagePathResolver { null })
        vm.selectItem(ItemType.PERSONAL_BEAN, "bean")
        await { vm.uiState.value.editor.selectedItemId == "bean" }
        vm.setConsumedWeightInput("15")
        assertEquals(600L, vm.uiState.value.editor.actualPriceFen)
        vm.setPriceInput("7.25")
        vm.setConsumedWeightInput("20")
        assertEquals(725L, vm.uiState.value.editor.actualPriceFen)
        vm.setRating(9)
        assertEquals("7.25", vm.uiState.value.editor.priceInput)
        vm.restoreAutomaticPrice()
        assertEquals(800L, vm.uiState.value.editor.actualPriceFen)
        assertEquals(PriceMode.AUTO, vm.uiState.value.editor.priceMode)
        vm.setConsumedWeightInput("1.0001")
        assertFalse(vm.uiState.value.editor.quantityValid)
        vm.setNote("暂存备注")
        assertEquals("1.0001", vm.uiState.value.editor.consumedWeightInput)
        vm.save()
        assertEquals(0, db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM drink_records").use { it.moveToFirst(); it.getInt(0) })
        vm.setConsumedWeightInput("18")
        vm.setBrewMethod("意式")
        vm.save()
        await { vm.uiState.value.saveCompletedToken > 0 }
        assertEquals("ESPRESSO", db.drinkDao().observeRange("2026-10-01", "2026-10-31").first().single().snapshotCoffeeType)
        job.cancel()
    }
    @Test fun `automatic overflow can recover as manual and mode survives draft restore`() = runBlocking {
        catalog.upsertItem(catalog.getItem("bag").copy(purchasePriceFen = Long.MAX_VALUE, packageCount = 1))
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val vm = JournalViewModel(repository, catalog, 2026, 10, coroutineScope = scope, clock = clock, imagePathResolver = ImagePathResolver { null })
        vm.selectItem(ItemType.PERSONAL_BEAN, "bag")
        await { vm.uiState.value.editor.selectedItemId == "bag" }
        vm.setConsumedCountInput("2")
        assertNull(vm.uiState.value.editor.actualPriceFen)
        assertEquals("金额超出支持范围，请改用手动金额", vm.uiState.value.editor.autoPriceMessage)
        vm.save()
        assertFalse(vm.uiState.value.editor.saving)
        vm.setPriceInput("7.50")
        vm.setConsumedCountInput("3")
        assertEquals(750L, vm.uiState.value.editor.actualPriceFen)
        withTimeout(5000) { while (repository.restoreDraft()?.let { it.priceMode == PriceMode.MANUAL && it.consumedCount == 3L } != true) { yield(); delay(1) } }
        val restored = repository.restoreDraft()!!
        assertEquals(750L, restored.actualPriceFen)
        assertEquals(Long.MAX_VALUE, restored.purchasePriceFen)
        vm.restoreAutomaticPrice()
        assertNull(vm.uiState.value.editor.actualPriceFen)
        vm.setPriceInput("7.50")
        vm.save()
        await { vm.uiState.value.saveCompletedToken > 0 }
        assertEquals(750L, db.drinkDao().observeRange("2026-10-01", "2026-10-31").first().single().actualPriceFen)
        job.cancel()
    }
    private suspend fun await(condition: () -> Boolean) { withTimeout(5000) { while (!condition()) { yield(); delay(1) } } }
}
