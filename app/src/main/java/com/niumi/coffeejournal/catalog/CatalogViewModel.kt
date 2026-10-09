package com.niumi.coffeejournal.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.niumi.coffeejournal.core.image.ImageStore
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ChainProductKind
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.PersonalProductType
import com.niumi.coffeejournal.core.model.formatMilligrams
import com.niumi.coffeejournal.core.model.parseGramsToMilligrams
import com.niumi.coffeejournal.core.model.parsePositiveCount
import com.niumi.coffeejournal.journal.parseYuanToFen
import com.niumi.coffeejournal.core.model.MaintenanceMode
import com.niumi.coffeejournal.journal.Clock
import com.niumi.coffeejournal.journal.SystemClock
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class CatalogTab { CHAINS, BEANS }

data class BrandEditor(
    val type: BrandType,
    val name: String,
    val logoAssetId: String?,
    val maintenanceMode: MaintenanceMode = MaintenanceMode.MANUAL_ONLY,
    val publicSourceUrl: String? = null,
    val id: String? = null,
    val assetLeaseId: String? = null,
)

data class ItemEditor(
    val brandId: String,
    val type: ItemType,
    val name: String,
    val imageAssetId: String?,
    val origin: String?,
    val processing: String?,
    val roastLevel: String?,
    val flavorNotes: String?,
    val brewMethod: String?,
    val status: ItemStatus,
    val caffeineMg: Double? = null,
    val officialDescription: String? = null,
    val purchaseDate: String? = null,
    val roastDate: String? = null,
    val sourceUrl: String? = null,
    val id: String? = null,
    val category: String? = null,
    val specificationDescription: String? = null,
    val assetLeaseId: String? = null,
    val chainProductKind: ChainProductKind? = null,
    val personalProductType: PersonalProductType = PersonalProductType.BEANS,
    val purchasePriceFen: Long? = null,
    val packageWeightMg: Long? = null,
    val packageCount: Long? = null,
    val purchasePriceInput: String = purchasePriceFen?.let { "${it / 100}.${(it % 100).toString().padStart(2, '0')}" }.orEmpty(),
    val specificationInput: String = if (personalProductType == PersonalProductType.BEANS) packageWeightMg?.let(::formatMilligrams).orEmpty() else packageCount?.toString().orEmpty(),
)

data class CatalogUiState(
    val tab: CatalogTab = CatalogTab.CHAINS,
    val brandOverviews: List<BrandOverview> = emptyList(),
    val selectedBrandId: String? = null,
    val items: List<CatalogItem> = emptyList(),
    val allItems: List<CatalogItem> = emptyList(),
    val catalogQuery: String = "",
    val catalogBrandId: String? = null,
    val beanStatus: ItemStatus = ItemStatus.ACTIVE,
    val todayLocalDate: String = "",
    val personalBeanAverageRatings: Map<String, Double> = emptyMap(),
    val saving: Boolean = false,
    val errorMessage: String? = null,
    val saveCompletedToken: Long = 0,
    val editorSession: CatalogEditorSession? = null,
) {
    val visibleItems: List<CatalogItem>
        get() {
            val source = allItems
            return source.filter { item ->
                (tab != CatalogTab.BEANS || item.status == beanStatus || (beanStatus == ItemStatus.ACTIVE && item.status == ItemStatus.NEEDS_IMAGE)) &&
                    (catalogBrandId == null || item.brandId == catalogBrandId)
            }.let { items ->
                if (tab == CatalogTab.BEANS && beanStatus == ItemStatus.DISCONTINUED) items.sortedWith(compareByDescending<CatalogItem> { personalBeanAverageRatings[it.id] }.thenBy { it.name }.thenBy { it.id }) else items
            }
        }
}

sealed interface CatalogEditorSession {
    val leaseId: String
    val assetId: String?

    data class Brand(
        val initial: com.niumi.coffeejournal.core.model.Brand?,
        val type: BrandType,
        override val leaseId: String,
        override val assetId: String?,
        val draft: BrandEditor = BrandEditor(type, initial?.name.orEmpty(), assetId, id = initial?.id, assetLeaseId = leaseId),
    ) : CatalogEditorSession {
        val currentAssetId get() = draft.logoAssetId
    }

    data class Item(
        val initial: CatalogItem?,
        val brand: com.niumi.coffeejournal.core.model.Brand,
        override val leaseId: String,
        override val assetId: String?,
        val draft: ItemEditor = ItemEditor(
            brand.id, if (brand.type == BrandType.CHAIN) ItemType.CHAIN_PRODUCT else ItemType.PERSONAL_BEAN,
            initial?.name.orEmpty(), assetId, initial?.origin, initial?.processing, initial?.roastLevel,
            initial?.flavorNotes, initial?.brewMethod, initial?.status ?: ItemStatus.ACTIVE,
            initial?.caffeineMg, initial?.officialDescription, initial?.purchaseDate, initial?.roastDate,
            initial?.sourceUrl, initial?.id, initial?.category, initial?.specificationDescription, leaseId,
            initial?.chainProductKind,
            initial?.personalProductType ?: PersonalProductType.BEANS, initial?.purchasePriceFen, initial?.packageWeightMg, initial?.packageCount,
        ),
        val caffeineInput: String = initial?.caffeineMg?.toString().orEmpty(),
    ) : CatalogEditorSession {
        val currentAssetId get() = draft.imageAssetId
    }
}

class CatalogViewModel(
    private val repository: CatalogRepository,
    private val imageStore: ImageStore? = null,
    coroutineScope: CoroutineScope? = null,
    private val leaseCleanupScope: CoroutineScope = DEFAULT_LEASE_CLEANUP_SCOPE,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: Clock = SystemClock,
) : ViewModel() {
    private val scope = coroutineScope ?: viewModelScope
    private val mutableState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = mutableState.asStateFlow()
    private var brandsJob: Job? = null
    private var itemsJob: Job? = null
    private var catalogItemsJob: Job? = null
    private val leaseMutex = Mutex()
    private val assetLeases = mutableMapOf<String, StagedAssetLease>()

    init {
        scope.launch {
            try {
                repository.ensureSeedBrands()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(errorMessage = "初始化品牌失败，请重试")
            }
        }
        observeBrands(BrandType.CHAIN)
        observeCatalogItems(BrandType.CHAIN)
        scope.launch { repository.observePersonalBeanAverageRatings().collect { ratings -> mutableState.value = mutableState.value.copy(personalBeanAverageRatings = ratings) } }
        scope.launch { mutableState.value = mutableState.value.copy(todayLocalDate = clock.read().localDate) }
    }

    fun selectTab(tab: CatalogTab) {
        if (mutableState.value.saving || tab == mutableState.value.tab) return
        itemsJob?.cancel()
        mutableState.value = mutableState.value.copy(
            tab = tab, selectedBrandId = null, items = emptyList(), allItems = emptyList(),
            catalogQuery = "", catalogBrandId = null, errorMessage = null,
        )
        observeBrands(if (tab == CatalogTab.CHAINS) BrandType.CHAIN else BrandType.ROASTER)
        observeCatalogItems(if (tab == CatalogTab.CHAINS) BrandType.CHAIN else BrandType.ROASTER)
    }

    fun selectBrand(brandId: String) {
        itemsJob?.cancel()
        mutableState.value = mutableState.value.copy(selectedBrandId = brandId, items = emptyList())
        itemsJob = scope.launch {
            repository.observeItems(brandId).collect { items ->
                if (mutableState.value.selectedBrandId == brandId) {
                    mutableState.value = mutableState.value.copy(items = items)
                }
            }
        }
    }

    fun selectBeanStatus(status: ItemStatus) {
        if (status in BEAN_FILTERS) mutableState.value = mutableState.value.copy(beanStatus = status)
    }

    fun refreshToday() {
        mutableState.value = mutableState.value.copy(todayLocalDate = clock.read().localDate)
    }

    fun updateCatalogQuery(query: String) {
        mutableState.value = mutableState.value.copy(catalogQuery = query)
    }

    fun selectCatalogBrand(brandId: String?) {
        mutableState.value = mutableState.value.copy(catalogBrandId = brandId)
    }

    fun saveBrand(editor: BrandEditor) = saveAction {
        val name = editor.name.trim()
        if (name.isEmpty()) throw InvalidCatalogNameException(editor.name)
        beginAssetCommit(editor.assetLeaseId, editor.logoAssetId)
        try {
            repository.upsertBrand(
                Brand(
                    id = editor.id ?: idGenerator(), type = editor.type, name = name,
                    logoAssetId = editor.logoAssetId, maintenanceMode = editor.maintenanceMode,
                    publicSourceUrl = null,
                ),
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) { failAssetCommit(editor.assetLeaseId) }
            throw error
        }
        withContext(NonCancellable) {
            commitAssetLease(editor.assetLeaseId, editor.logoAssetId)
        }
        currentCoroutineContext().ensureActive()
    }

    fun saveItem(editor: ItemEditor) = saveAction {
        val name = editor.name.trim()
        if (name.isEmpty()) throw InvalidCatalogNameException(editor.name)
        val price = editor.purchasePriceInput.takeIf { it.isNotBlank() }?.let(::parseYuanToFen)
        val spec = editor.specificationInput.takeIf { it.isNotBlank() }?.let { if (editor.personalProductType == PersonalProductType.BEANS) parseGramsToMilligrams(it) else parsePositiveCount(it) }
        require(editor.type != ItemType.PERSONAL_BEAN || ((editor.purchasePriceInput.isBlank() || price != null) && (editor.specificationInput.isBlank() || spec != null))) { "采购价格或规格无效" }
        val existing = editor.id?.let { repository.getItem(it) }
        validateItemStatus(editor.type, editor.status, editor.roastDate, clock.read().localDate)
        val item = CatalogItem(
            id = editor.id ?: idGenerator(), brandId = editor.brandId, type = editor.type,
            name = name, imageAssetId = editor.imageAssetId, origin = editor.origin.clean(),
            processing = editor.processing.clean(), roastLevel = editor.roastLevel.clean(),
            flavorNotes = editor.flavorNotes.clean(), brewMethod = editor.brewMethod.clean(),
            status = editor.status, caffeineMg = editor.caffeineMg,
            officialDescription = editor.officialDescription.clean(),
            purchaseDate = editor.purchaseDate.clean(), roastDate = editor.roastDate.clean(),
            sourceUrl = editor.sourceUrl.clean(),
            sourceFetchedAt = existing?.sourceFetchedAt,
            informationCompleteness = existing?.informationCompleteness ?: 0,
            category = if (editor.type == ItemType.CHAIN_PRODUCT) {
                if (editor.category == null) existing?.category else editor.category.clean()
            } else null,
            specificationDescription = if (editor.type == ItemType.CHAIN_PRODUCT) {
                if (editor.specificationDescription == null) {
                    existing?.specificationDescription
                } else editor.specificationDescription.clean()
            } else null,
            imageSourceUrl = existing?.imageSourceUrl,
            chainProductKind = if (editor.type == ItemType.CHAIN_PRODUCT) editor.chainProductKind else null,
            personalProductType = if (editor.type == ItemType.PERSONAL_BEAN) editor.personalProductType else PersonalProductType.BEANS,
            purchasePriceFen = if (editor.type == ItemType.PERSONAL_BEAN) price else null,
            packageWeightMg = if (editor.type == ItemType.PERSONAL_BEAN && editor.personalProductType == PersonalProductType.BEANS) spec else null,
            packageCount = if (editor.type == ItemType.PERSONAL_BEAN && editor.personalProductType == PersonalProductType.DRIP_BAG) spec else null,
        )
        beginAssetCommit(editor.assetLeaseId, editor.imageAssetId)
        try {
            repository.upsertItem(item)
        } catch (error: Throwable) {
            withContext(NonCancellable) { failAssetCommit(editor.assetLeaseId) }
            throw error
        }
        withContext(NonCancellable) {
            commitAssetLease(editor.assetLeaseId, editor.imageAssetId)
        }
        currentCoroutineContext().ensureActive()
        selectBrand(editor.brandId)
    }

    fun setItemStatus(item: CatalogItem, status: ItemStatus) = saveAction {
        validateItemStatus(item.type, status, item.roastDate, clock.read().localDate)
        repository.upsertItem(item.copy(status = status))
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(errorMessage = null)
    }

    fun openBrandEditor(initial: Brand?, type: BrandType) {
        val current = mutableState.value.editorSession
        if (current is CatalogEditorSession.Brand && current.initial?.id == initial?.id && current.type == type) return
        openEditor(CatalogEditorSession.Brand(initial, type, idGenerator(), initial?.logoAssetId))
    }

    fun openItemEditor(initial: CatalogItem?, brand: Brand) {
        val current = mutableState.value.editorSession
        if (current is CatalogEditorSession.Item && current.initial?.id == initial?.id && current.brand.id == brand.id) return
        openEditor(CatalogEditorSession.Item(initial, brand, idGenerator(), initial?.imageAssetId))
    }

    fun closeEditor() {
        val session = mutableState.value.editorSession ?: return
        mutableState.value = mutableState.value.copy(editorSession = null)
        discardAssetLease(session.leaseId)
    }

    fun updateBrandDraft(update: (BrandEditor) -> BrandEditor) {
        val session = mutableState.value.editorSession as? CatalogEditorSession.Brand ?: return
        mutableState.value = mutableState.value.copy(editorSession = session.copy(draft = update(session.draft)))
    }

    fun updateItemDraft(update: (ItemEditor) -> ItemEditor) {
        val session = mutableState.value.editorSession as? CatalogEditorSession.Item ?: return
        mutableState.value = mutableState.value.copy(editorSession = session.copy(draft = update(session.draft)))
    }

    fun updateItemCaffeineInput(input: String) {
        val session = mutableState.value.editorSession as? CatalogEditorSession.Item ?: return
        mutableState.value = mutableState.value.copy(editorSession = session.copy(caffeineInput = input))
    }

    suspend fun stageAsset(
        leaseId: String,
        persistedAssetId: String?,
        stagedAssetId: String,
    ): Boolean {
        val store = imageStore ?: return false
        val replacedStaged = leaseMutex.withLock {
            val lease = assetLeases[leaseId] ?: mutableState.value.editorSession
                ?.takeIf { it.leaseId == leaseId }
                ?.let { StagedAssetLease(null, persistedAssetId).also { assetLeases[leaseId] = it } }
                ?: return false
            check(!lease.committing) { "Cannot replace an asset during save" }
            val previous = lease.stagedAssetId?.takeIf { it != stagedAssetId }
            lease.stagedAssetId = stagedAssetId
            previous
        }
        replacedStaged?.let { runCatching { store.deleteIfUnreferenced(it) } }
        mutableState.value.editorSession?.let { session ->
            if (session.leaseId == leaseId) {
                mutableState.value = mutableState.value.copy(editorSession = when (session) {
                    is CatalogEditorSession.Brand -> session.copy(assetId = stagedAssetId, draft = session.draft.copy(logoAssetId = stagedAssetId))
                    is CatalogEditorSession.Item -> session.copy(assetId = stagedAssetId, draft = session.draft.copy(imageAssetId = stagedAssetId))
                })
            }
        }
        return true
    }

    suspend fun retainAssetLease(leaseId: String, persistedAssetId: String?): Boolean {
        if (imageStore == null) return false
        return leaseMutex.withLock {
            if (assetLeases.containsKey(leaseId)) return@withLock true
            assetLeases[leaseId] = StagedAssetLease(null, persistedAssetId)
            true
        }
    }

    fun discardAssetLease(leaseId: String) {
        leaseCleanupScope.launch {
            val lease = leaseMutex.withLock {
                val current = assetLeases[leaseId] ?: return@withLock null
                if (current.committing) {
                    current.discardRequested = true
                    null
                } else {
                    assetLeases.remove(leaseId)
                }
            } ?: return@launch
            lease.stagedAssetId?.let { staged ->
                imageStore?.let { store -> runCatching { store.deleteIfUnreferenced(staged) } }
            }
        }
    }

    override fun onCleared() {
        leaseCleanupScope.launch {
            val abandoned = leaseMutex.withLock {
                val disposable = assetLeases.values.filterNot { it.committing }
                assetLeases.entries.removeAll { (_, lease) -> !lease.committing }
                assetLeases.values.forEach { it.discardRequested = true }
                disposable
            }
            imageStore?.let { store ->
                abandoned.forEach { lease ->
                    lease.stagedAssetId?.let { runCatching { store.deleteIfUnreferenced(it) } }
                }
            }
        }
        super.onCleared()
    }

    private suspend fun beginAssetCommit(leaseId: String?, committedAssetId: String?) {
        if (leaseId == null) return
        leaseMutex.withLock {
            val lease = assetLeases[leaseId] ?: return@withLock
            check(lease.stagedAssetId == committedAssetId || lease.stagedAssetId == null && committedAssetId == lease.persistedAssetId) {
                "Committed asset does not match staged lease"
            }
            lease.committing = true
        }
    }

    private suspend fun failAssetCommit(leaseId: String?) {
        if (leaseId == null) return
        val abandoned = leaseMutex.withLock {
            val lease = assetLeases[leaseId] ?: return@withLock null
            lease.committing = false
            if (lease.discardRequested) assetLeases.remove(leaseId) else null
        }
        abandoned?.let { lease ->
            lease.stagedAssetId?.let { staged ->
                imageStore?.let { store -> runCatching { store.deleteIfUnreferenced(staged) } }
            }
        }
    }

    private suspend fun commitAssetLease(leaseId: String?, committedAssetId: String?) {
        if (leaseId == null) return
        val lease = leaseMutex.withLock { assetLeases.remove(leaseId) }
        lease?.persistedAssetId?.takeIf { it != committedAssetId }?.let { oldAssetId ->
            imageStore?.let { store -> runCatching { store.deleteIfUnreferenced(oldAssetId) } }
        }
        if (mutableState.value.editorSession?.leaseId == leaseId) {
            mutableState.value = mutableState.value.copy(editorSession = null)
        }
    }

    private fun openEditor(session: CatalogEditorSession) {
        mutableState.value = mutableState.value.copy(editorSession = session, errorMessage = null)
        scope.launch { retainAssetLease(session.leaseId, session.assetId) }
    }

    private fun observeBrands(type: BrandType) {
        brandsJob?.cancel()
        brandsJob = scope.launch {
            repository.observeBrandOverviews(type).collect { overviews ->
                mutableState.value = mutableState.value.copy(brandOverviews = overviews)
            }
        }
    }

    private fun observeCatalogItems(type: BrandType) {
        catalogItemsJob?.cancel()
        catalogItemsJob = scope.launch {
            repository.observeCatalogItems(type).collect { items ->
                mutableState.value = mutableState.value.copy(allItems = items)
            }
        }
    }

    private fun saveAction(action: suspend () -> Unit) {
        if (mutableState.value.saving) return
        mutableState.value = mutableState.value.copy(saving = true, errorMessage = null)
        scope.launch {
            try {
                action()
                mutableState.value = mutableState.value.copy(
                    saving = false,
                    saveCompletedToken = mutableState.value.saveCompletedToken + 1,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: DuplicateCatalogNameException) {
                mutableState.value = mutableState.value.copy(
                    saving = false, errorMessage = "同一分类下已存在同名条目",
                )
            } catch (_: InvalidCatalogNameException) {
                mutableState.value = mutableState.value.copy(
                    saving = false, errorMessage = "名称不能为空",
                )
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    saving = false, errorMessage = "保存失败，请重试",
                )
            }
        }
    }

    companion object {
        val BEAN_FILTERS = listOf(ItemStatus.ACTIVE, ItemStatus.DISCONTINUED, ItemStatus.RESTING)

        private val DEFAULT_LEASE_CLEANUP_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun factory(repository: CatalogRepository, imageStore: ImageStore? = null): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CatalogViewModel(repository, imageStore) as T
            }
    }
}

private data class StagedAssetLease(
    var stagedAssetId: String?,
    val persistedAssetId: String?,
    var committing: Boolean = false,
    var discardRequested: Boolean = false,
)

private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)

fun ItemStatus.beanStatusLabel(): String = when (this) {
    ItemStatus.ACTIVE, ItemStatus.NEEDS_IMAGE -> "正在喝"
    ItemStatus.DISCONTINUED -> "已喝完"
    ItemStatus.ARCHIVED -> "归档"
    ItemStatus.RESTING -> "养豆中"
}

internal fun isValidRestingRoastDate(value: String?, today: String): Boolean =
    value != null && Regex("\\d{4}-\\d{2}-\\d{2}").matches(value) && value <= today && runCatching {
        val parts = value.split('-').map(String::toInt)
        require(parts.size == 3)
        Calendar.getInstance().apply { isLenient = false; set(parts[0], parts[1] - 1, parts[2]); timeInMillis }.timeInMillis
    }.isSuccess

private fun validateItemStatus(type: ItemType, status: ItemStatus, roastDate: String?, today: String) {
    require(!(type == ItemType.CHAIN_PRODUCT && status == ItemStatus.RESTING)) { "连锁产品不支持养豆状态" }
    require(!(type == ItemType.PERSONAL_BEAN && status == ItemStatus.ARCHIVED)) { "个人豆不支持归档状态" }
    require(status != ItemStatus.RESTING || isValidRestingRoastDate(roastDate, today)) { "养豆中需要有效且不晚于今天的烘焙日期" }
}

sealed interface CaffeineInput {
    data class Valid(val milligrams: Double?) : CaffeineInput
    data object Invalid : CaffeineInput
}

fun validateCaffeineInput(input: String): CaffeineInput {
    if (input.isBlank()) return CaffeineInput.Valid(null)
    val value = input.toDoubleOrNull() ?: return CaffeineInput.Invalid
    return if (value.isFinite() && value >= 0.0) CaffeineInput.Valid(value) else CaffeineInput.Invalid
}
