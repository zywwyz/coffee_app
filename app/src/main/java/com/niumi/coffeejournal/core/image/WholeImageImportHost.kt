package com.niumi.coffeejournal.core.image

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val request = pending
        pending = null
        if (uri != null && request != null) {
            scope.launch {
                try {
                    val asset = imageStore.importWhole(uri, request.kind)
                    associateImportedAsset(imageStore, ImportedAssetSelection(asset.id), request.previousAssetId, request.association)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The selection was not imported or was cleaned up by association.
                }
            }
        }
    }
    content { kind, previousAssetId, association ->
        if (pending == null) {
            pending = PendingImport(kind, previousAssetId, association)
            picker.launch("image/*")
        }
    }
}

private data class PendingImport(
    val kind: ImageKind,
    val previousAssetId: String?,
    val association: suspend (ImportedAssetSelection) -> Boolean,
)
