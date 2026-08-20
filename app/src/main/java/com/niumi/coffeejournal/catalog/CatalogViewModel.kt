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
import com.niumi.coffeejournal.core.model.MaintenanceMode
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
)

data class CatalogUiState(
    val tab: CatalogTab = CatalogTab.CHAINS,
    val brandOverviews: List<BrandOverview> = emptyList(),
    val selectedBrandId: String? = null,
    val items: List<CatalogItem> = emptyList(),
    val beanStatus: ItemStatus = ItemStatus.ACTIVE,
    val saving: Boolean = false,
    val errorMessage: String? = null,
    val saveCompletedToken: Long = 0,
) {
    val visibleItems: List<CatalogItem>
        get() = if (tab == CatalogTab.BEANS) items.filter { it.status == beanStatus } else items
}

class CatalogViewModel(
    private val repository: CatalogRepository,
    private val imageStore: ImageStore? = null,
    coroutineScope: CoroutineScope? = null,
    private val leaseCleanupScope: CoroutineScope = DEFAULT_LEASE_CLEANUP_SCOPE,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val scope = coroutineScope ?: viewModelScope
    private val mutableState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = mutableState.asStateFlow()
    private var brandsJob: Job? = null
    private var itemsJob: Job? = null
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
    }

    fun selectTab(tab: CatalogTab) {
        if (mutableState.value.saving || tab == mutableState.value.tab) return
        itemsJob?.cancel()
        mutableState.value = mutableState.value.copy(
            tab = tab, selectedBrandId = null, items = emptyList(), errorMessage = null,
        )
        observeBrands(if (tab == CatalogTab.CHAINS) BrandType.CHAIN else BrandType.ROASTER)
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
        val existing = editor.id?.let { repository.getItem(it) }
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
        repository.upsertItem(item.copy(status = status))
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(errorMessage = null)
    }

    suspend fun stageAsset(
        leaseId: String,
        persistedAssetId: String?,
        stagedAssetId: String,
    ): Boolean {
        val store = imageStore ?: return false
        val replacedStaged = leaseMutex.withLock {
            val lease = assetLeases[leaseId] ?: return false
            check(!lease.committing) { "Cannot replace an asset during save" }
            val previous = lease.stagedAssetId?.takeIf { it != stagedAssetId }
            lease.stagedAssetId = stagedAssetId
            previous
        }
        replacedStaged?.let { runCatching { store.deleteIfUnreferenced(it) } }
        return true
    }

    suspend fun retainAssetLease(leaseId: String, persistedAssetId: String?): Boolean {
        if (imageStore == null) return false
        return leaseMutex.withLock {
            if (assetLeases.containsKey(leaseId)) return@withLock false
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
        val lease = leaseMutex.withLock { assetLeases.remove(leaseId) } ?: return
        lease.persistedAssetId?.takeIf { it != committedAssetId }?.let { oldAssetId ->
            imageStore?.let { store -> runCatching { store.deleteIfUnreferenced(oldAssetId) } }
        }
    }

    private fun observeBrands(type: BrandType) {
        brandsJob?.cancel()
        brandsJob = scope.launch {
            repository.observeBrandOverviews(type).collect { overviews ->
                mutableState.value = mutableState.value.copy(brandOverviews = overviews)
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
        val BEAN_FILTERS = listOf(ItemStatus.ACTIVE, ItemStatus.DISCONTINUED, ItemStatus.ARCHIVED)

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
