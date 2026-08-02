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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class ImageImportMode { ASK, SCREENSHOT, WHOLE_IMAGE }

data class ImportedAssetSelection(
    val assetId: String,
    val suggestedName: String? = null,
    val actualPriceFen: Long? = null,
)

typealias AssetImportRequester = (
    ImageKind,
    ImageImportMode,
    (ImportedAssetSelection) -> Unit,
) -> Unit

@Composable
fun ImageImportHost(
    imageStore: ImageStore,
    recognizer: ScreenshotTextRecognizer,
    content: @Composable (AssetImportRequester) -> Unit,
) {
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var selectedScreenshot by remember { mutableStateOf<Uri?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var importingWhole by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun clearRequest() {
        pending = null
        selectedScreenshot = null
        importingWhole = false
    }

    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) clearRequest() else selectedScreenshot = uri
    }
    val wholeImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val request = pending
        if (uri == null || request == null) {
            clearRequest()
        } else {
            importingWhole = true
            scope.launch {
                try {
                    val asset = imageStore.importWhole(uri, request.kind)
                    request.onSelected(ImportedAssetSelection(asset.id))
                    clearRequest()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    errorMessage = "图片导入失败，请重新选择"
                    clearRequest()
                }
            }
        }
    }

    val requester: AssetImportRequester = { kind, mode, onSelected ->
        if (pending == null) {
            pending = PendingImport(kind, mode, onSelected)
            when (mode) {
                ImageImportMode.SCREENSHOT -> screenshotPicker.launch("image/*")
                ImageImportMode.WHOLE_IMAGE -> wholeImagePicker.launch("image/*")
                ImageImportMode.ASK -> Unit
            }
        }
    }

    val screenshot = selectedScreenshot
    val request = pending
    if (screenshot != null && request != null) {
        ImportReviewScreen(
            source = screenshot,
            recognizer = recognizer,
            imageStore = imageStore,
            kind = request.kind,
            onConfirmed = { result ->
                request.onSelected(ImportedAssetSelection(result.imageAssetId, result.productName, result.actualPriceFen))
                clearRequest()
            },
            onCancel = ::clearRequest,
        )
    } else {
        content(requester)
    }

    if (request?.mode == ImageImportMode.ASK && selectedScreenshot == null && !importingWhole) {
        AlertDialog(
            onDismissRequest = ::clearRequest,
            title = { Text("补充一张真实图片") },
            text = { Text("可直接选择原始全屏截图，在 App 内裁剪；也可选择已经裁好的产品图片。") },
            confirmButton = {
                Button(onClick = { pending = request.copy(mode = ImageImportMode.SCREENSHOT); screenshotPicker.launch("image/*") }) {
                    Text("选择完整截图")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pending = request.copy(mode = ImageImportMode.WHOLE_IMAGE); wholeImagePicker.launch("image/*") }) {
                    Text("选择已裁图片")
                }
            },
        )
    }
    if (importingWhole) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("正在保存图片") },
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
    val onSelected: (ImportedAssetSelection) -> Unit,
)
