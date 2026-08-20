package com.niumi.coffeejournal.catalog

import com.niumi.coffeejournal.core.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import com.niumi.coffeejournal.core.image.*
import android.net.Uri
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class ManualProductEditorViewModelTest {
    @Test fun `new editor trims name and saves active public product`() = runBlocking {
        val repo = FakeRepository(); val vm = ManualProductEditorViewModel(repo, coroutineScope = CoroutineScope(Dispatchers.Unconfined), idGenerator = { "new" })
        vm.openNew(brand()); vm.setName("  澳白  "); vm.setKind(ChainProductKind.MILK); vm.save(); yield()
        assertEquals("澳白", repo.items.value.single().name); assertEquals(ItemStatus.ACTIVE, repo.items.value.single().status)
    }
    @Test fun `pending kind is refused`() = runBlocking {
        val vm = ManualProductEditorViewModel(FakeRepository(), coroutineScope = CoroutineScope(Dispatchers.Unconfined)); vm.openNew(brand()); vm.setName("美式"); vm.setKind(ChainProductKind.PENDING); vm.save(); yield()
        assertEquals("请选择黑咖、果咖或奶咖", vm.state.value.errorMessage)
    }
    @Test fun `edit preserves hidden fields`() = runBlocking {
        val old = item().copy(origin="旧产地", sourceFetchedAt=9, informationCompleteness=70, category="旧分类")
        val repo = FakeRepository(old); val vm = ManualProductEditorViewModel(repo, coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        vm.openEdit(brand(), old); vm.setName("新名称"); vm.setKind(ChainProductKind.BLACK); vm.save(); yield()
        assertEquals(old.copy(name="新名称", chainProductKind=ChainProductKind.BLACK), repo.items.value.single())
    }
    @Test fun `duplicate name reports same brand error`() = runBlocking {
        val vm = ManualProductEditorViewModel(FakeRepository(duplicate = true), coroutineScope = CoroutineScope(Dispatchers.Unconfined)); vm.openNew(brand()); vm.setName("美式"); vm.setKind(ChainProductKind.BLACK); vm.save(); yield()
        assertEquals("同一分类下已存在同名条目", vm.state.value.errorMessage)
    }
    @Test fun `optional photo saves null`() = runBlocking {
        val repo = FakeRepository(); val vm = ManualProductEditorViewModel(repo, coroutineScope = CoroutineScope(Dispatchers.Unconfined)); vm.openNew(brand()); vm.setName("美式"); vm.setKind(ChainProductKind.BLACK); vm.save(); yield()
        assertNull(repo.items.value.single().imageAssetId)
    }
    @Test fun `replace remove and dismiss clean staged assets`() = runBlocking {
        val images = Images(); val repo = FakeRepository(); val vm = ManualProductEditorViewModel(repo, images, CoroutineScope(Dispatchers.Unconfined)); vm.openNew(brand())
        vm.acceptImportedAsset("one"); vm.acceptImportedAsset("two"); vm.removePhoto(); vm.dismiss(); yield()
        assertEquals(listOf("one", "two"), images.deleted)
        assertTrue(repo.items.value.isEmpty())
    }
    @Test fun `failed save cleans staged image`() = runBlocking {
        val images = Images(); val repo = FakeRepository(fail = true); val vm = ManualProductEditorViewModel(repo, images, CoroutineScope(Dispatchers.Unconfined)); vm.openNew(brand()); vm.acceptImportedAsset("new"); vm.setName("美式"); vm.setKind(ChainProductKind.BLACK); vm.save(); yield()
        assertEquals(listOf("new"), images.deleted)
        assertNull(vm.state.value.imageAssetId)
        vm.acceptImportedAsset("retry"); vm.save(); yield()
        assertEquals("retry", repo.items.value.single().imageAssetId)
    }
    @Test fun `failed catalog save emits no saved event and retains no item`() = runBlocking {
        val repo = FakeRepository(fail = true); val vm = ManualProductEditorViewModel(repo, coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        vm.openNew(brand()); vm.setName("美式"); vm.setKind(ChainProductKind.BLACK); vm.save(); yield()
        assertTrue(repo.items.value.isEmpty())
        assertEquals("保存失败，请重试", vm.state.value.errorMessage)
        assertNull(withTimeoutOrNull(50) { vm.events.first() })
    }
    @Test fun `successful save retains staged image`() = runBlocking {
        val images = Images(); val vm = ManualProductEditorViewModel(FakeRepository(), images, CoroutineScope(Dispatchers.Unconfined)); vm.openNew(brand()); vm.acceptImportedAsset("new"); vm.setName("美式"); vm.setKind(ChainProductKind.BLACK); vm.save(); yield()
        assertTrue(images.deleted.isEmpty())
    }
    @Test fun `selection failure keeps saved product dialog actionable`() = runBlocking {
        val vm = ManualProductEditorViewModel(FakeRepository(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        vm.openNew(brand()); vm.setName("美式"); vm.setKind(ChainProductKind.BLACK); vm.save(); yield()

        vm.selectionFailed()

        assertTrue(vm.state.value.open)
        assertFalse(vm.state.value.saving)
        assertEquals("美式", vm.state.value.name)
        assertEquals("产品已保存，但无法选中，请重试或取消", vm.state.value.errorMessage)
        val retried = async { withTimeoutOrNull(1_000) { vm.events.first() } }
        yield()
        vm.retrySelection()
        assertTrue(retried.await() is ManualProductEditorEvent.Saved)
    }
    @Test fun `saved action is delivered to a collector that starts after save`() = runBlocking {
        val vm = ManualProductEditorViewModel(FakeRepository(), coroutineScope = CoroutineScope(Dispatchers.Unconfined), idGenerator = { "new" })
        vm.openNew(brand()); vm.setName("美式"); vm.setKind(ChainProductKind.BLACK); vm.save(); yield()

        assertEquals(ManualProductEditorEvent.Saved("new", "brand"), withTimeoutOrNull(1_000) { vm.events.first() })
    }
    @Test fun `unacknowledged saved action survives collector recreation`() = runBlocking {
        val vm = ManualProductEditorViewModel(FakeRepository(), coroutineScope = CoroutineScope(Dispatchers.Unconfined), idGenerator = { "new" })
        vm.openNew(brand()); vm.setName("美式"); vm.setKind(ChainProductKind.BLACK); vm.save(); yield()

        val received = mutableListOf<ManualProductEditorEvent>()
        val firstCollector = launch { vm.events.collect { received += it } }
        yield()
        assertEquals(1, received.size)
        firstCollector.cancelAndJoin()
        assertTrue(withTimeoutOrNull(1_000) { vm.events.first() } is ManualProductEditorEvent.Saved)
    }
    @Test fun `acknowledging saved action prevents delivery to recreated collector`() = runBlocking {
        val vm = ManualProductEditorViewModel(FakeRepository(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        vm.openNew(brand()); vm.setName("美式"); vm.setKind(ChainProductKind.BLACK); vm.save(); yield(); vm.completeSaved()

        assertNull(withTimeoutOrNull(50) { vm.events.first() })
    }
    @Test fun `selection retry emits one action after a failure`() = runBlocking {
        val vm = ManualProductEditorViewModel(FakeRepository(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val received = mutableListOf<ManualProductEditorEvent>()
        val collector = launch { vm.events.collect { received += it } }
        vm.openNew(brand()); vm.setName("美式"); vm.setKind(ChainProductKind.BLACK); vm.save(); yield(); vm.selectionFailed(); vm.retrySelection(); yield()

        assertEquals(2, received.size)
        yield()
        assertEquals(2, received.size)
        collector.cancelAndJoin()
    }
    @Test fun `cancelled save cleans staged image non cancellably`() = runBlocking {
        val images = Images(); val gate = CompletableDeferred<Unit>(); val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val vm = ManualProductEditorViewModel(FakeRepository(gate = gate), images, scope); vm.openNew(brand()); vm.acceptImportedAsset("new"); vm.setName("美式"); vm.setKind(ChainProductKind.BLACK); vm.save(); scope.cancel(); yield()
        assertEquals(listOf("new"), images.deleted)
    }
    private fun brand() = Brand("brand", BrandType.CHAIN, "品牌", null, MaintenanceMode.MANUAL_ONLY, null)
    private fun item() = CatalogItem("item", "brand", ItemType.CHAIN_PRODUCT, "旧名称", null, null, null, null, null, null, ItemStatus.ACTIVE, chainProductKind=ChainProductKind.MILK)
    private class FakeRepository(initial: CatalogItem? = null, private val duplicate:Boolean=false, private var fail:Boolean=false, private val gate:CompletableDeferred<Unit>?=null) : CatalogRepository {
        val items = MutableStateFlow(listOfNotNull(initial)); override fun observeBrands(type: BrandType)=emptyFlow<List<Brand>>(); override fun observeItems(brandId:String)=items
        override suspend fun getBrand(brandId:String)=error("unused"); override suspend fun getItem(itemId:String)=items.value.single()
        override suspend fun upsertBrand(brand:Brand)=Unit; override suspend fun upsertItem(item:CatalogItem){gate?.await(); if(duplicate) throw DuplicateCatalogNameException(item.name); if(fail) { fail=false; error("fail") }; items.value=items.value.filterNot{it.id==item.id}+item}; override suspend fun lastPriceFen(itemId:String)=null
    }
    private class Images : ImageStore { val deleted=mutableListOf<String>(); override suspend fun deleteIfUnreferenced(assetId:String):Boolean { deleted += assetId; return true }; override suspend fun importWhole(source:Uri,kind:ImageKind)=error("unused") }
}
