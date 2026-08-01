package com.niumi.coffeejournal.catalog

import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogViewModelTest {
    @Test
    fun `seed metadata is exactly the five supported chain brands`() {
        assertEquals(
            listOf("瑞幸", "Manner", "M Stand", "Peet's", "% Arabica"),
            seedBrands().map { it.name },
        )
        assertTrue(seedBrands().all { it.logoAssetId == null && it.type == BrandType.CHAIN })
    }

    @Test
    fun `custom chain and personal bean can be created from complete editors`() = runBlocking {
        val repository = FakeCatalogRepository()
        val viewModel = viewModel(repository)

        viewModel.saveBrand(BrandEditor(BrandType.CHAIN, "新连锁", null, MaintenanceMode.MANUAL_ONLY, null))
        viewModel.saveBrand(BrandEditor(BrandType.ROASTER, "烘焙商", null, MaintenanceMode.MANUAL_ONLY, null))
        yield()
        val roaster = repository.brands.value.single { it.type == BrandType.ROASTER }
        viewModel.saveItem(
            ItemEditor(
                brandId = roaster.id, type = ItemType.PERSONAL_BEAN, name = "花魁",
                imageAssetId = "asset", origin = "埃塞俄比亚", processing = "日晒",
                roastLevel = "浅烘", flavorNotes = "莓果", brewMethod = "手冲",
                status = ItemStatus.ACTIVE, purchaseDate = "2026-08-01", roastDate = "2026-07-28",
            ),
        )
        yield()

        assertTrue(repository.brands.value.any { it.name == "新连锁" })
        assertEquals("埃塞俄比亚", repository.items.value.single().origin)
        assertEquals("asset", repository.items.value.single().imageAssetId)
    }

    @Test
    fun `duplicate name is shown as understandable error and saving blocks double tap`() = runBlocking {
        val repository = FakeCatalogRepository().apply { failDuplicate = true }
        val viewModel = viewModel(repository)

        viewModel.saveBrand(BrandEditor(BrandType.CHAIN, "Manner", null, MaintenanceMode.MANUAL_ONLY, null))
        yield()

        assertEquals("同一分类下已存在同名条目", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.saving)
    }

    @Test
    fun `archive and status filters never delete items`() = runBlocking {
        val repository = FakeCatalogRepository()
        val viewModel = viewModel(repository)
        val bean = item(status = ItemStatus.ACTIVE)
        repository.upsertItem(bean)
        viewModel.selectTab(CatalogTab.BEANS)
        viewModel.selectBrand("roaster")

        viewModel.setItemStatus(bean, ItemStatus.DISCONTINUED)
        yield()
        assertEquals(ItemStatus.DISCONTINUED, repository.items.value.single().status)
        viewModel.selectBeanStatus(ItemStatus.DISCONTINUED)
        assertEquals(listOf("bean"), viewModel.uiState.value.visibleItems.map { it.id })

        viewModel.setItemStatus(repository.items.value.single(), ItemStatus.ARCHIVED)
        yield()
        assertEquals(1, repository.items.value.size)
        viewModel.selectBeanStatus(ItemStatus.ARCHIVED)
        assertEquals(ItemStatus.ARCHIVED, viewModel.uiState.value.visibleItems.single().status)
    }

    @Test
    fun `editing an item preserves fetched metadata that is not user editable`() = runBlocking {
        val repository = FakeCatalogRepository()
        val existing = item(ItemStatus.ACTIVE).copy(
            type = ItemType.CHAIN_PRODUCT,
            sourceFetchedAt = 9988, informationCompleteness = 73,
            category = "咖啡", specificationDescription = "中杯 / 热", brewMethod = "浓缩",
        )
        repository.upsertItem(existing)
        val viewModel = viewModel(repository)

        viewModel.saveItem(
            ItemEditor(
                brandId = existing.brandId, type = existing.type, name = "新名称", imageAssetId = null,
                origin = null, processing = null, roastLevel = null, flavorNotes = null,
                brewMethod = null, status = existing.status, id = existing.id,
            ),
        )
        yield()

        assertEquals(9988L, repository.items.value.single().sourceFetchedAt)
        assertEquals(73, repository.items.value.single().informationCompleteness)
        assertEquals("咖啡", repository.items.value.single().category)
        assertEquals("中杯 / 热", repository.items.value.single().specificationDescription)
        assertEquals(null, repository.items.value.single().brewMethod)
    }

    @Test
    fun `manual chain editor saves category specification and brew independently`() = runBlocking {
        val repository = FakeCatalogRepository()
        val viewModel = viewModel(repository)

        viewModel.saveItem(
            ItemEditor(
                brandId = "chain", type = ItemType.CHAIN_PRODUCT, name = "澳白", imageAssetId = null,
                origin = null, processing = null, roastLevel = null, flavorNotes = null,
                brewMethod = "浓缩", status = ItemStatus.ACTIVE,
                category = "意式咖啡", specificationDescription = "大杯 / 冰",
            ),
        )
        yield()

        val saved = repository.items.value.single()
        assertEquals("意式咖啡", saved.category)
        assertEquals("大杯 / 冰", saved.specificationDescription)
        assertEquals("浓缩", saved.brewMethod)
    }

    @Test
    fun `chain editor can explicitly clear category and specification`() = runBlocking {
        val repository = FakeCatalogRepository()
        val existing = item(ItemStatus.ACTIVE).copy(
            type = ItemType.CHAIN_PRODUCT, category = "咖啡", specificationDescription = "中杯",
        )
        repository.upsertItem(existing)
        val viewModel = viewModel(repository)

        viewModel.saveItem(
            ItemEditor(
                brandId = existing.brandId, type = existing.type, name = existing.name,
                imageAssetId = null, origin = null, processing = null, roastLevel = null,
                flavorNotes = null, brewMethod = null, status = existing.status, id = existing.id,
                category = "", specificationDescription = "",
            ),
        )
        yield()

        assertEquals(null, repository.items.value.single().category)
        assertEquals(null, repository.items.value.single().specificationDescription)
    }

    @Test
    fun `second save tap is ignored while first write is suspended`() = runBlocking {
        val repository = FakeCatalogRepository().apply { saveGate = CompletableDeferred() }
        val viewModel = viewModel(repository)
        val editor = BrandEditor(BrandType.CHAIN, "新品牌", null, MaintenanceMode.MANUAL_ONLY, null)

        viewModel.saveBrand(editor)
        viewModel.saveBrand(editor)
        assertEquals(1, repository.brandSaveCalls)
        assertTrue(viewModel.uiState.value.saving)
        repository.saveGate?.complete(Unit)
        yield()
        assertFalse(viewModel.uiState.value.saving)
    }

    @Test
    fun `seed failure is contained and shown without cancelling catalog state`() = runBlocking {
        val repository = FakeCatalogRepository().apply { failSeed = true }
        val viewModel = viewModel(repository)
        yield()

        assertEquals("初始化品牌失败，请重试", viewModel.uiState.value.errorMessage)
    }

    private fun viewModel(repository: FakeCatalogRepository) = CatalogViewModel(
        repository = repository,
        coroutineScope = CoroutineScope(Job() + Dispatchers.Unconfined),
        idGenerator = { "generated-${repository.generated++}" },
    )

    private fun item(status: ItemStatus) = CatalogItem(
        id = "bean", brandId = "roaster", type = ItemType.PERSONAL_BEAN, name = "豆子",
        imageAssetId = null, origin = null, processing = null, roastLevel = null,
        flavorNotes = null, brewMethod = null, status = status,
    )

    private class FakeCatalogRepository : CatalogRepository {
        val brands = MutableStateFlow<List<Brand>>(emptyList())
        val items = MutableStateFlow<List<CatalogItem>>(emptyList())
        var generated = 0
        var failDuplicate = false
        var brandSaveCalls = 0
        var saveGate: CompletableDeferred<Unit>? = null
        var failSeed = false
        override fun observeBrands(type: BrandType): Flow<List<Brand>> = brands
        override fun observeItems(brandId: String): Flow<List<CatalogItem>> = items
        override fun observeBrandOverviews(type: BrandType): Flow<List<BrandOverview>> =
            MutableStateFlow(emptyList())
        override suspend fun getBrand(brandId: String) = brands.value.single { it.id == brandId }
        override suspend fun getItem(itemId: String) = items.value.single { it.id == itemId }
        override suspend fun upsertBrand(brand: Brand) {
            brandSaveCalls++
            saveGate?.await()
            if (failDuplicate) throw DuplicateCatalogNameException(brand.name)
            brands.value = brands.value.filterNot { it.id == brand.id } + brand
        }
        override suspend fun upsertItem(item: CatalogItem) {
            items.value = items.value.filterNot { it.id == item.id } + item
        }
        override suspend fun lastPriceFen(itemId: String): Long? = null
        override suspend fun ensureSeedBrands() {
            if (failSeed) error("seed failed")
        }
    }
}
