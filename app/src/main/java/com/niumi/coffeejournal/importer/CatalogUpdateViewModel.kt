package com.niumi.coffeejournal.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.MaintenanceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class UpdatePhase { IDLE, LOADING, REVIEW, APPLYING, FAILURE }

data class CatalogUpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val brandId: String? = null,
    val brandName: String? = null,
    val review: CatalogReview? = null,
    val selectedKeys: Set<String> = emptySet(),
    val failureKind: FailureKind? = null,
    val message: String? = null,
    val imageFallbackCount: Int = 0,
) {
    val showFallbackActions: Boolean
        get() = phase == UpdatePhase.FAILURE
}

fun interface CatalogSourceProvider {
    fun sourceFor(brand: Brand): CatalogSource
}

class DefaultCatalogSourceProvider(private val client: PublicPageClient) : CatalogSourceProvider {
    override fun sourceFor(brand: Brand): CatalogSource {
        if (brand.maintenanceMode == MaintenanceMode.MANUAL_ONLY) {
            return NoPublicCatalogSource(brand.id, brand.name)
        }
        return when (brand.id) {
            "seed-chain-luckin" -> LuckinCatalogSource(client)
            "seed-chain-mstand" -> MStandCatalogSource(client)
            "seed-chain-manner" -> MannerCatalogSource
            "seed-chain-peets" -> PeetsChinaCatalogSource
            "seed-chain-arabica" -> ArabicaCatalogSource
            else -> brand.publicSourceUrl?.let { CustomCatalogSource(brand.id, it, client) }
                ?: NoPublicCatalogSource(brand.id, brand.name)
        }
    }
}

class CatalogUpdateViewModel(
    private val sources: CatalogSourceProvider,
    private val gateway: CatalogUpdateGateway,
    coroutineScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = coroutineScope ?: viewModelScope
    private val mutableState = MutableStateFlow(CatalogUpdateUiState())
    val uiState: StateFlow<CatalogUpdateUiState> = mutableState.asStateFlow()
    private var activeJob: Job? = null

    fun requestUpdate(brand: Brand) {
        if (mutableState.value.phase in setOf(UpdatePhase.LOADING, UpdatePhase.APPLYING)) return
        activeJob?.cancel()
        mutableState.value = CatalogUpdateUiState(
            phase = UpdatePhase.LOADING, brandId = brand.id, brandName = brand.name,
        )
        activeJob = scope.launch {
            try {
                when (val result = sources.sourceFor(brand).fetch()) {
                    is SourceResult.Failure -> mutableState.value = CatalogUpdateUiState(
                        phase = UpdatePhase.FAILURE, brandId = brand.id, brandName = brand.name,
                        failureKind = result.kind, message = result.message,
                    )
                    is SourceResult.Success -> {
                        val review = gateway.review(brand.id, result)
                        mutableState.value = CatalogUpdateUiState(
                            phase = UpdatePhase.REVIEW, brandId = brand.id, brandName = brand.name,
                            review = review, selectedKeys = review.changes.map { it.key }.toSet(),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = CatalogUpdateUiState(
                    phase = UpdatePhase.FAILURE, brandId = brand.id, brandName = brand.name,
                    failureKind = FailureKind.HTTP, message = error.message ?: "更新失败，本地数据未改变。",
                )
            }
        }
    }

    fun toggleSelected(key: String) {
        val state = mutableState.value
        if (state.phase != UpdatePhase.REVIEW || state.review?.changes?.none { it.key == key } != false) return
        mutableState.value = state.copy(
            selectedKeys = if (key in state.selectedKeys) state.selectedKeys - key else state.selectedKeys + key,
        )
    }

    fun confirmSelected() {
        val state = mutableState.value
        val review = state.review ?: return
        if (state.phase != UpdatePhase.REVIEW) return
        mutableState.value = state.copy(phase = UpdatePhase.APPLYING)
        activeJob = scope.launch {
            try {
                val result = gateway.applySelected(review, state.selectedKeys)
                mutableState.value = CatalogUpdateUiState(imageFallbackCount = result.imageFallbackCount)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = state.copy(
                    phase = UpdatePhase.FAILURE, failureKind = FailureKind.HTTP,
                    message = "应用更新失败，本地数据已回滚：${error.message.orEmpty()}",
                )
            }
        }
    }

    fun dismiss() {
        if (mutableState.value.phase == UpdatePhase.APPLYING) return
        activeJob?.cancel()
        mutableState.value = CatalogUpdateUiState()
    }

    companion object {
        fun factory(sources: CatalogSourceProvider, gateway: CatalogUpdateGateway): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CatalogUpdateViewModel(sources, gateway) as T
            }
    }
}
