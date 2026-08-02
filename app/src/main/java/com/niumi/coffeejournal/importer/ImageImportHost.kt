package com.niumi.coffeejournal.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImageImportMode { ASK, SCREENSHOT, WHOLE_IMAGE }

data class ImportedAssetSelection(
    val assetId: String,
    val suggestedName: String? = null,
    val actualPriceFen: Long? = null,
)

typealias AssetImportRequester = (
    ImageKind,
    ImageImportMode,
    String?,
    suspend (ImportedAssetSelection) -> Boolean,
) -> Unit

typealias ImagePicker = (ImageImportMode, (Uri?) -> Unit) -> Unit

data class ScreenshotReviewRequest(
    val source: Uri,
    val recognizer: ScreenshotTextRecognizer,
    val imageStore: ImageStore,
    val kind: ImageKind,
    val onConfirmed: suspend (ConfirmedScreenshotImport) -> Boolean,
    val onCancel: () -> Unit,
)

typealias ScreenshotReviewContent = @Composable (ScreenshotReviewRequest) -> Unit

suspend fun associateImportedAsset(
    imageStore: ImageStore,
    selection: ImportedAssetSelection,
    previousAssetId: String? = null,
    association: suspend (ImportedAssetSelection) -> Boolean,
): Boolean = try {
    if (association(selection)) {
        previousAssetId?.takeIf { it != selection.assetId }?.let { oldAssetId ->
            runCatching { imageStore.deleteIfUnreferenced(oldAssetId) }
        }
        true
    } else {
        runCatching { imageStore.deleteIfUnreferenced(selection.assetId) }
        false
    }
} catch (error: CancellationException) {
    withContext(NonCancellable) { runCatching { imageStore.deleteIfUnreferenced(selection.assetId) } }
    throw error
} catch (_: Exception) {
    runCatching { imageStore.deleteIfUnreferenced(selection.assetId) }
    false
}

@Composable
fun ImageImportHost(
    imageStore: ImageStore,
    recognizer: ScreenshotTextRecognizer,
    pickImage: ImagePicker? = null,
    showReviewInDialog: Boolean = true,
    reviewContent: ScreenshotReviewContent = { request ->
        ImportReviewScreen(
            source = request.source,
            recognizer = request.recognizer,
            imageStore = request.imageStore,
            kind = request.kind,
            onConfirmed = request.onConfirmed,
            onCancel = request.onCancel,
        )
    },
    content: @Composable (AssetImportRequester) -> Unit,
) {
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var selectedScreenshot by remember { mutableStateOf<Uri?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var importingWhole by remember { mutableStateOf(false) }
    var associating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun clearRequest() {
        pending = null
        selectedScreenshot = null
        importingWhole = false
        associating = false
    }

    val onScreenshotPicked: (Uri?) -> Unit = { uri ->
        if (uri == null || pending == null) clearRequest() else selectedScreenshot = uri
    }
    val onWholeImagePicked: (Uri?) -> Unit = { uri ->
        val request = pending
        if (uri == null || request == null) {
            clearRequest()
        } else {
            importingWhole = true
            scope.launch {
                try {
                    val asset = imageStore.importWhole(uri, request.kind)
                    val selection = ImportedAssetSelection(asset.id)
                    associating = true
                    if (associateImportedAsset(imageStore, selection, request.previousAssetId, request.onSelected)) {
                        clearRequest()
                    } else {
                        errorMessage = "图片未能关联到条目，已清理本次导入，请重试"
                        clearRequest()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    errorMessage = "图片导入失败，请重新选择"
                    clearRequest()
                }
            }
        }
    }
    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), onScreenshotPicked)
    val wholeImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), onWholeImagePicked)

    fun launchPicker(mode: ImageImportMode) {
        if (pickImage != null) {
            pickImage(mode, if (mode == ImageImportMode.SCREENSHOT) onScreenshotPicked else onWholeImagePicked)
        } else if (mode == ImageImportMode.SCREENSHOT) {
            screenshotPicker.launch("image/*")
        } else {
            wholeImagePicker.launch("image/*")
        }
    }

    val requester: AssetImportRequester = { kind, mode, previousAssetId, onSelected ->
        if (pending == null) {
            pending = PendingImport(kind, mode, previousAssetId, onSelected)
            when (mode) {
                ImageImportMode.SCREENSHOT -> launchPicker(mode)
                ImageImportMode.WHOLE_IMAGE -> launchPicker(mode)
                ImageImportMode.ASK -> Unit
            }
        }
    }

    val screenshot = selectedScreenshot
    val request = pending
    Box(Modifier.fillMaxSize()) {
        content(requester)
        if (screenshot != null && request != null) {
            val reviewRequest = ScreenshotReviewRequest(
                source = screenshot,
                recognizer = recognizer,
                imageStore = imageStore,
                kind = request.kind,
                onConfirmed = { result ->
                    associating = true
                    val selection = ImportedAssetSelection(result.imageAssetId, result.productName, result.actualPriceFen)
                    if (associateImportedAsset(imageStore, selection, request.previousAssetId, request.onSelected)) {
                            clearRequest()
                            true
                    } else {
                        associating = false
                        false
                    }
                },
                onCancel = ::clearRequest,
            )
            if (showReviewInDialog) {
                Dialog(
                    onDismissRequest = {},
                    properties = DialogProperties(
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
                ) { reviewContent(reviewRequest) }
            } else {
                reviewContent(reviewRequest)
            }
        }
    }

    if (request?.mode == ImageImportMode.ASK && selectedScreenshot == null && !importingWhole) {
        AlertDialog(
            onDismissRequest = ::clearRequest,
            title = { Text("补充一张真实图片") },
            text = { Text("可直接选择原始全屏截图，在 App 内裁剪；也可选择已经裁好的产品图片。") },
            confirmButton = {
                Button(onClick = { pending = request.copy(mode = ImageImportMode.SCREENSHOT); launchPicker(ImageImportMode.SCREENSHOT) }) {
                    Text("选择完整截图")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pending = request.copy(mode = ImageImportMode.WHOLE_IMAGE); launchPicker(ImageImportMode.WHOLE_IMAGE) }) {
                    Text("选择已裁图片")
                }
            },
        )
    }
    if (importingWhole || associating && selectedScreenshot == null) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(if (associating) "正在关联产品图片" else "正在保存图片") },
            text = { CircularProgressIndicator() },
        )
    }
    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("知道了") } },
            title = { Text("无法导入") },
            text = { Text(message) },
        )
    }
}

private data class PendingImport(
    val kind: ImageKind,
    val mode: ImageImportMode,
    val previousAssetId: String?,
    val onSelected: suspend (ImportedAssetSelection) -> Boolean,
)
