package com.niumi.coffeejournal.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.niumi.coffeejournal.core.image.ImageStore
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ChainProductKind
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ManualProductEditorState(
    val open: Boolean = false, val brand: Brand? = null, val editing: CatalogItem? = null,
    val name: String = "", val kind: ChainProductKind? = null, val imageAssetId: String? = null,
    val saving: Boolean = false, val errorMessage: String? = null,
)

sealed interface ManualProductEditorEvent { data class Saved(val itemId: String, val brandId: String) : ManualProductEditorEvent }

class ManualProductEditorViewModel(
    private val repository: CatalogRepository,
    private val imageStore: ImageStore? = null,
    coroutineScope: CoroutineScope? = null,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val scope = coroutineScope ?: viewModelScope
    private val mutableState = MutableStateFlow(ManualProductEditorState())
    val state: StateFlow<ManualProductEditorState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<ManualProductEditorEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ManualProductEditorEvent> = mutableEvents.asSharedFlow()
    private val operationMutex = Mutex()
    private var stagedAssetId: String? = null

    fun openNew(brand: Brand) { open(brand, null) }
    fun openEdit(brand: Brand, item: CatalogItem) { open(brand, item) }
    private fun open(brand: Brand, item: CatalogItem?) {
        mutableState.value = ManualProductEditorState(true, brand, item, item?.name.orEmpty(), item?.chainProductKind, item?.imageAssetId)
        stagedAssetId = null
    }
    fun setName(value: String) { mutableState.value = mutableState.value.copy(name = value, errorMessage = null) }
    fun setKind(value: ChainProductKind?) { mutableState.value = mutableState.value.copy(kind = value, errorMessage = null) }
    suspend fun acceptImportedAsset(assetId: String): Boolean = operationMutex.withLock {
        val previousStaged = stagedAssetId
        stagedAssetId = assetId.takeIf { it != mutableState.value.editing?.imageAssetId }
        mutableState.value = mutableState.value.copy(imageAssetId = assetId)
        previousStaged?.takeIf { it != assetId }?.let { deleteQuietly(it) }
        true
    }
    fun removePhoto() { scope.launch { operationMutex.withLock {
        stagedAssetId?.let { deleteQuietly(it) }; stagedAssetId = null
        mutableState.value = mutableState.value.copy(imageAssetId = null)
    } } }
    fun dismiss() { scope.launch { operationMutex.withLock { cleanupStaged(); mutableState.value = ManualProductEditorState() } } }
    fun save() {
        val snapshot = mutableState.value; val brand = snapshot.brand ?: return
        val name = snapshot.name.trim()
        if (name.isEmpty()) { mutableState.value = snapshot.copy(errorMessage = "名称不能为空"); return }
        if (snapshot.kind !in PUBLIC_KINDS) { mutableState.value = snapshot.copy(errorMessage = "请选择黑咖、果咖或奶咖"); return }
        if (snapshot.saving) return
        mutableState.value = snapshot.copy(saving = true, errorMessage = null)
        scope.launch {
            operationMutex.withLock {
                val current = mutableState.value
                val item = current.editing?.copy(name = name, imageAssetId = current.imageAssetId, chainProductKind = current.kind)
                    ?: CatalogItem(idGenerator(), brand.id, ItemType.CHAIN_PRODUCT, name, current.imageAssetId, null, null, null, null, null, ItemStatus.ACTIVE, chainProductKind = current.kind)
                try {
                    repository.upsertItem(item)
                    withContext(NonCancellable) {
                        current.editing?.imageAssetId?.takeIf { it != item.imageAssetId }?.let { deleteQuietly(it) }
                        stagedAssetId = null
                    }
                    mutableEvents.emit(ManualProductEditorEvent.Saved(item.id, brand.id))
                } catch (error: CancellationException) {
                    withContext(NonCancellable) { cleanupStaged() }; throw error
                } catch (_: DuplicateCatalogNameException) {
                    mutableState.value = current.copy(saving = false, errorMessage = "同一分类下已存在同名条目")
                } catch (_: Exception) {
                    withContext(NonCancellable) { cleanupStaged() }
                    mutableState.value = current.copy(
                        imageAssetId = current.editing?.imageAssetId,
                        saving = false,
                        errorMessage = "保存失败，请重试",
                    )
                }
            }
        }
    }
    /** Closes a saved editor after its caller has completed any follow-up action. */
    fun completeSaved() {
        if (mutableState.value.saving) mutableState.value = ManualProductEditorState()
    }
    private suspend fun cleanupStaged() { stagedAssetId?.let { deleteQuietly(it) }; stagedAssetId = null }
    private suspend fun deleteQuietly(assetId: String) { withContext(NonCancellable) { runCatching { imageStore?.deleteIfUnreferenced(assetId) } } }
    companion object {
        private val PUBLIC_KINDS = setOf(ChainProductKind.BLACK, ChainProductKind.FRUIT, ChainProductKind.MILK)
        fun factory(repository: CatalogRepository, imageStore: ImageStore? = null) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ManualProductEditorViewModel(repository, imageStore) as T
        }
    }
}
