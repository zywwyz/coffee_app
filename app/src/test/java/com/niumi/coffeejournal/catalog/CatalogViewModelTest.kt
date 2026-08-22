package com.niumi.coffeejournal.catalog

import android.net.Uri
import com.niumi.coffeejournal.core.image.ImageAsset
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
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
    fun `seed metadata is exactly the bundled chain brands`() {
        assertEquals(
            listOf("瑞幸", "库迪", "NOWWA", "幸运咖", "星巴克", "肯悦咖啡", "MANNER", "沪咖", "Tims", "M Stand", "Peet's", "%Arabica"),
            seedBrands().map { it.name },
        )
        assertTrue(seedBrands().all { it.logoAssetId == null && it.type == BrandType.CHAIN })
        assertTrue(seedBrands().all { it.maintenanceMode == MaintenanceMode.MANUAL_ONLY })
        assertTrue(seedBrands().all { it.publicSourceUrl == null })
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
        assertEquals(3L, viewModel.uiState.value.saveCompletedToken)
    }

    @Test
    fun `duplicate name is shown as understandable error and saving blocks double tap`() = runBlocking {
        val repository = FakeCatalogRepository().apply { failDuplicate = true }
        val viewModel = viewModel(repository)

        viewModel.saveBrand(BrandEditor(BrandType.CHAIN, "Manner", null, MaintenanceMode.MANUAL_ONLY, null))
        yield()

        assertEquals("同一分类下已存在同名条目", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.saving)
        assertEquals(0L, viewModel.uiState.value.saveCompletedToken)
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

    @Test
    fun `caffeine parser accepts empty and finite nonnegative values only`() {
        assertEquals(CaffeineInput.Valid(null), validateCaffeineInput(""))
        assertEquals(CaffeineInput.Valid(0.0), validateCaffeineInput("0"))
        assertEquals(CaffeineInput.Valid(128.5), validateCaffeineInput("128.5"))
        listOf("abc", "NaN", "Infinity", "-0.1").forEach { input ->
            assertEquals(CaffeineInput.Invalid, validateCaffeineInput(input))
        }
    }

    @Test
    fun `cancelled editor lease deletes staged image without creating catalog row`() = runBlocking {
        val repository = FakeCatalogRepository()
        val images = RecordingImageStore()
        val viewModel = viewModel(repository, images)

        assertTrue(viewModel.retainAssetLease("editor", null))
        assertTrue(viewModel.stageAsset("editor", null, "new-image"))
        viewModel.discardAssetLease("editor")
        yield()

        assertTrue(repository.items.value.isEmpty())
        assertEquals(listOf("new-image"), images.deleteAttempts)
    }

    @Test
    fun `brand editor session survives recomposition with a stable lease and staged asset`() = runBlocking {
        val viewModel = viewModel(FakeCatalogRepository(), RecordingImageStore())

        viewModel.openBrandEditor(null, BrandType.CHAIN)
        val opened = viewModel.uiState.value.editorSession as CatalogEditorSession.Brand
        viewModel.openBrandEditor(null, BrandType.CHAIN)
        assertEquals(opened.leaseId, (viewModel.uiState.value.editorSession as CatalogEditorSession.Brand).leaseId)

        assertTrue(viewModel.stageAsset(opened.leaseId, null, "logo"))
        assertEquals("logo", (viewModel.uiState.value.editorSession as CatalogEditorSession.Brand).assetId)
    }

    @Test
    fun `item editor draft retains every edited field with its staged asset`() = runBlocking {
        val viewModel = viewModel(FakeCatalogRepository(), RecordingImageStore())
        val brand = Brand("roaster", BrandType.ROASTER, "烘焙商", null, MaintenanceMode.MANUAL_ONLY, null)
        viewModel.openItemEditor(null, brand)
        val leaseId = (viewModel.uiState.value.editorSession as CatalogEditorSession.Item).leaseId
        viewModel.updateItemDraft {
            it.copy(name = "花魁", origin = "埃塞", processing = "日晒", roastLevel = "浅烘", flavorNotes = "莓果", brewMethod = "手冲", purchaseDate = "2026-08-01")
        }
        assertTrue(viewModel.stageAsset(leaseId, null, "package"))

        val draft = (viewModel.uiState.value.editorSession as CatalogEditorSession.Item).draft
        assertEquals("花魁", draft.name)
        assertEquals("埃塞", draft.origin)
        assertEquals("手冲", draft.brewMethod)
        assertEquals("package", draft.imageAssetId)
        assertEquals("2026-08-01", draft.purchaseDate)
    }

    @Test
    fun `item editor retains raw caffeine input across session reads`() {
        val viewModel = viewModel(FakeCatalogRepository())
        viewModel.openItemEditor(null, Brand("chain", BrandType.CHAIN, "连锁", null, MaintenanceMode.MANUAL_ONLY, null))
        viewModel.updateItemCaffeineInput("1")
        viewModel.updateItemCaffeineInput("10")

        assertEquals("10", (viewModel.uiState.value.editorSession as CatalogEditorSession.Item).caffeineInput)
    }

    @Test
    fun `explicit editor close cleans session lease while configuration disposal does not`() = runBlocking {
        val images = RecordingImageStore()
        val viewModel = viewModel(FakeCatalogRepository(), images)
        viewModel.openItemEditor(item(ItemStatus.ACTIVE), Brand("roaster", BrandType.ROASTER, "烘焙商", null, MaintenanceMode.MANUAL_ONLY, null))
        val leaseId = (viewModel.uiState.value.editorSession as CatalogEditorSession.Item).leaseId
        assertTrue(viewModel.stageAsset(leaseId, null, "image"))

        assertEquals("image", (viewModel.uiState.value.editorSession as CatalogEditorSession.Item).assetId)
        assertTrue(images.deleteAttempts.isEmpty())
        viewModel.closeEditor()
        yield()

        assertEquals(null, viewModel.uiState.value.editorSession)
        assertEquals(listOf("image"), images.deleteAttempts)
    }

    @Test
    fun `successful save clears editor session while failed save retains it`() = runBlocking {
        val repository = FakeCatalogRepository().apply { failItemSave = true }
        val viewModel = viewModel(repository, RecordingImageStore())
        val brand = Brand("roaster", BrandType.ROASTER, "烘焙商", null, MaintenanceMode.MANUAL_ONLY, null)
        viewModel.openItemEditor(null, brand)
        val session = viewModel.uiState.value.editorSession as CatalogEditorSession.Item
        assertTrue(viewModel.stageAsset(session.leaseId, null, "image"))

        viewModel.saveItem(editor(imageAssetId = "image", assetLeaseId = session.leaseId))
        yield()
        assertEquals(session.leaseId, viewModel.uiState.value.editorSession?.leaseId)

        repository.failItemSave = false
        viewModel.saveItem(editor(imageAssetId = "image", assetLeaseId = session.leaseId))
        yield()
        assertEquals(null, viewModel.uiState.value.editorSession)
    }

    @Test
    fun `selection arriving after retained editor lease is discarded is rejected`() = runBlocking {
        val repository = FakeCatalogRepository()
        val images = RecordingImageStore()
        val viewModel = viewModel(repository, images)

        assertTrue(viewModel.retainAssetLease("picker", null))
        viewModel.discardAssetLease("picker")
        yield()

        assertFalse(viewModel.stageAsset("picker", null, "late-image"))
        assertTrue(images.deleteAttempts.isEmpty())
    }

    @Test
    fun `failed save retains staged lease then cancel cleans it`() = runBlocking {
        val repository = FakeCatalogRepository().apply { failItemSave = true }
        val images = RecordingImageStore()
        val viewModel = viewModel(repository, images)
        assertTrue(viewModel.retainAssetLease("editor", null))
        assertTrue(viewModel.stageAsset("editor", null, "new-image"))

        viewModel.saveItem(editor(imageAssetId = "new-image", assetLeaseId = "editor"))
        yield()
        assertEquals("保存失败，请重试", viewModel.uiState.value.errorMessage)
        assertTrue(images.deleteAttempts.isEmpty())

        viewModel.discardAssetLease("editor")
        yield()
        assertEquals(listOf("new-image"), images.deleteAttempts)
    }

    @Test
    fun `dispose during pending failed save defers cleanup until write terminates`() = runBlocking {
        val repository = FakeCatalogRepository().apply {
            itemSaveGate = CompletableDeferred()
            failItemSave = true
        }
        val images = RecordingImageStore()
        val viewModel = viewModel(repository, images)
        assertTrue(viewModel.retainAssetLease("editor", null))
        assertTrue(viewModel.stageAsset("editor", null, "new-image"))

        viewModel.saveItem(editor(imageAssetId = "new-image", assetLeaseId = "editor"))
        viewModel.discardAssetLease("editor")
        yield()
        assertTrue(images.deleteAttempts.isEmpty())

        repository.itemSaveGate?.complete(Unit)
        yield()
        assertEquals(listOf("new-image"), images.deleteAttempts)
    }

    @Test
    fun `dispose during pending successful save does not delete newly associated image`() = runBlocking {
        val repository = FakeCatalogRepository().apply { itemSaveGate = CompletableDeferred() }
        val images = RecordingImageStore()
        val viewModel = viewModel(repository, images)
        assertTrue(viewModel.retainAssetLease("editor", "old-image"))
        assertTrue(viewModel.stageAsset("editor", "old-image", "new-image"))

        viewModel.saveItem(editor(imageAssetId = "new-image", assetLeaseId = "editor"))
        viewModel.discardAssetLease("editor")
        yield()
        assertTrue(images.deleteAttempts.isEmpty())

        repository.itemSaveGate?.complete(Unit)
        yield()
        assertEquals("new-image", repository.items.value.single().imageAssetId)
        assertEquals(listOf("old-image"), images.deleteAttempts)
    }

    @Test
    fun `successful save commits staged image then deletes old persisted image`() = runBlocking {
        val repository = FakeCatalogRepository()
        val old = item(ItemStatus.ACTIVE).copy(imageAssetId = "old-image")
        repository.upsertItem(old)
        val images = RecordingImageStore()
        val viewModel = viewModel(repository, images)
        assertTrue(viewModel.retainAssetLease("editor", "old-image"))
        assertTrue(viewModel.stageAsset("editor", "old-image", "new-image"))
        assertTrue(images.deleteAttempts.isEmpty())

        viewModel.saveItem(
            editor(id = old.id, imageAssetId = "new-image", assetLeaseId = "editor"),
        )
        yield()

        assertEquals("new-image", repository.items.value.single().imageAssetId)
        assertEquals(listOf("old-image"), images.deleteAttempts)
        assertFalse("new-image" in images.deleteAttempts)
    }

    @Test
    fun `replacing staged image cleans prior staged asset and historical old reference is retained`() = runBlocking {
        val repository = FakeCatalogRepository()
        val old = item(ItemStatus.ACTIVE).copy(imageAssetId = "historical-old")
        repository.upsertItem(old)
        val images = RecordingImageStore(protectedAssets = setOf("historical-old"))
        val viewModel = viewModel(repository, images)

        assertTrue(viewModel.retainAssetLease("editor", "historical-old"))
        assertTrue(viewModel.stageAsset("editor", "historical-old", "first-stage"))
        assertTrue(viewModel.stageAsset("editor", "historical-old", "second-stage"))
        assertEquals(listOf("first-stage"), images.deleteAttempts)
        viewModel.saveItem(
            editor(id = old.id, imageAssetId = "second-stage", assetLeaseId = "editor"),
        )
        yield()

        assertEquals(listOf("first-stage", "historical-old"), images.deleteAttempts)
        assertTrue("historical-old" in images.retainedAssets)
        assertFalse("second-stage" in images.deleteAttempts)
    }

    private fun viewModel(
        repository: FakeCatalogRepository,
        imageStore: ImageStore? = null,
    ): CatalogViewModel {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        return CatalogViewModel(
            repository = repository, imageStore = imageStore,
            coroutineScope = scope, leaseCleanupScope = scope,
            idGenerator = { "generated-${repository.generated++}" },
        )
    }

    private fun editor(
        id: String? = null,
        imageAssetId: String?,
        assetLeaseId: String?,
    ) = ItemEditor(
        brandId = "roaster", type = ItemType.PERSONAL_BEAN, name = "豆子",
        imageAssetId = imageAssetId, origin = null, processing = null, roastLevel = null,
        flavorNotes = null, brewMethod = null, status = ItemStatus.ACTIVE, id = id,
        assetLeaseId = assetLeaseId,
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
        var failItemSave = false
        var itemSaveGate: CompletableDeferred<Unit>? = null
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
            itemSaveGate?.await()
            if (failItemSave) error("save failed")
            items.value = items.value.filterNot { it.id == item.id } + item
        }
        override suspend fun lastPriceFen(itemId: String): Long? = null
        override suspend fun ensureSeedBrands() {
            if (failSeed) error("seed failed")
        }
    }

    private class RecordingImageStore(
        protectedAssets: Set<String> = emptySet(),
    ) : ImageStore {
        val deleteAttempts = mutableListOf<String>()
        val retainedAssets = protectedAssets.toMutableSet()
        override suspend fun importWhole(source: Uri, kind: ImageKind): ImageAsset = error("unexpected")
        override suspend fun deleteIfUnreferenced(assetId: String): Boolean {
            deleteAttempts += assetId
            return if (assetId in retainedAssets) false else true
        }
    }
}
