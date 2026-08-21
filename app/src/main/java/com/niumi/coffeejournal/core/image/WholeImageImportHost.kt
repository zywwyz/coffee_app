package com.niumi.coffeejournal.core.image

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportedAssetSelection(val assetId: String)

typealias AssetImportRequester = (
    ImageKind,
    String?,
    suspend (ImportedAssetSelection) -> Boolean,
) -> Unit

suspend fun associateImportedAsset(
    imageStore: ImageStore,
    selection: ImportedAssetSelection,
    previousAssetId: String? = null,
    association: suspend (ImportedAssetSelection) -> Boolean,
): Boolean = try {
    if (association(selection)) {
        previousAssetId?.takeIf { it != selection.assetId }?.let { imageStore.deleteIfUnreferenced(it) }
        true
    } else {
        imageStore.deleteIfUnreferenced(selection.assetId)
        false
    }
} catch (error: CancellationException) {
    withContext(NonCancellable) { runCatching { imageStore.deleteIfUnreferenced(selection.assetId) } }
    throw error
} catch (error: Exception) {
    runCatching { imageStore.deleteIfUnreferenced(selection.assetId) }
    throw error
}

@Composable
fun WholeImageImportHost(imageStore: ImageStore, content: @Composable (AssetImportRequester) -> Unit) {
    val coordinator: WholeImageImportCoordinatorViewModel = viewModel(
        key = "whole-image-import-${System.identityHashCode(imageStore)}",
        factory = WholeImageImportCoordinatorViewModel.factory(imageStore),
    )
    val error by coordinator.error.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        coordinator.consumePickerResult(uri)
    }
    content { kind, previousAssetId, association ->
        if (coordinator.request(kind, previousAssetId, association)) {
            picker.launch("image/*")
        }
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = coordinator::dismissError,
            title = { Text("图片导入失败") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = coordinator::dismissError) { Text("关闭") } },
            dismissButton = {
                TextButton(onClick = { if (coordinator.retry()) picker.launch("image/*") }) { Text("重试") }
            },
        )
    }
}

internal class WholeImageImportCoordinatorViewModel(
    private val imageStore: ImageStore,
    coroutineScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = coroutineScope ?: viewModelScope
    private val lock = Any()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private var pending: PendingImport? = null
    private var phase = ImportPhase.IDLE

    internal val hasPendingRequest: Boolean get() = synchronized(lock) { pending != null }
    internal val isAwaitingPicker: Boolean get() = synchronized(lock) { phase == ImportPhase.AWAITING_PICKER }

    fun request(kind: ImageKind, previousAssetId: String?, association: suspend (ImportedAssetSelection) -> Boolean): Boolean = synchronized(lock) {
        if (pending != null) return false
        pending = PendingImport(kind, previousAssetId, association)
        phase = ImportPhase.AWAITING_PICKER
        mutableError.value = null
        true
    }

    fun consumePickerResult(uri: Uri?): Boolean {
        val request = synchronized(lock) {
            if (phase != ImportPhase.AWAITING_PICKER) return false
            pending ?: return false
        }
        if (uri == null) {
            clear(request)
            return true
        }
        synchronized(lock) { if (pending !== request || phase != ImportPhase.AWAITING_PICKER) return false; phase = ImportPhase.IMPORTING }
        scope.launch {
            try {
                val asset = imageStore.importWhole(uri, request.kind)
                if (associateImportedAsset(imageStore, ImportedAssetSelection(asset.id), request.previousAssetId, request.association)) {
                    clear(request)
                } else {
                    fail(request, "图片未能保存，请重试")
                }
            } catch (error: CancellationException) {
                clear(request)
                throw error
            } catch (_: Exception) {
                fail(request, "图片导入失败，请重试")
            }
        }
        return true
    }

    fun retry(): Boolean = synchronized(lock) {
        if (pending == null || phase != ImportPhase.FAILED) return false
        phase = ImportPhase.AWAITING_PICKER
        mutableError.value = null
        true
    }

    fun dismissError() { synchronized(lock) { pending = null; phase = ImportPhase.IDLE; mutableError.value = null } }

    private fun fail(request: PendingImport, message: String) = synchronized(lock) {
        if (pending === request) { phase = ImportPhase.FAILED; mutableError.value = message }
    }

    private fun clear(request: PendingImport) = synchronized(lock) {
        if (pending === request) { pending = null; phase = ImportPhase.IDLE; mutableError.value = null }
    }

    override fun onCleared() { dismissError(); super.onCleared() }

    companion object {
        fun factory(imageStore: ImageStore): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WholeImageImportCoordinatorViewModel(imageStore) as T
        }
    }
}

private enum class ImportPhase { IDLE, AWAITING_PICKER, IMPORTING, FAILED }

private data class PendingImport(
    val kind: ImageKind,
    val previousAssetId: String?,
    val association: suspend (ImportedAssetSelection) -> Boolean,
)
