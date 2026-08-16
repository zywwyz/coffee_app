package com.niumi.coffeejournal.catalog

import com.niumi.coffeejournal.core.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
    private fun brand() = Brand("brand", BrandType.CHAIN, "品牌", null, MaintenanceMode.MANUAL_ONLY, null)
    private fun item() = CatalogItem("item", "brand", ItemType.CHAIN_PRODUCT, "旧名称", null, null, null, null, null, null, ItemStatus.ACTIVE, chainProductKind=ChainProductKind.MILK)
    private class FakeRepository(initial: CatalogItem? = null) : CatalogRepository {
        val items = MutableStateFlow(listOfNotNull(initial)); override fun observeBrands(type: BrandType)=emptyFlow<List<Brand>>(); override fun observeItems(brandId:String)=items
        override suspend fun getBrand(brandId:String)=error("unused"); override suspend fun getItem(itemId:String)=items.value.single()
        override suspend fun upsertBrand(brand:Brand)=Unit; override suspend fun upsertItem(item:CatalogItem){items.value=items.value.filterNot{it.id==item.id}+item}; override suspend fun lastPriceFen(itemId:String)=null
    }
}
