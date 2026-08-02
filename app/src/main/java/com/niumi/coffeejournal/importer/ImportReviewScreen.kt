package com.niumi.coffeejournal.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.niumi.coffeejournal.core.image.CropRect
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportReviewUiState(
    val preview: ImageBitmap? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val productName: String = "",
    val actualPriceYuan: String = "",
    val crop: CropRect = CropRect(0, 0, 1, 1),
    val lowConfidenceFields: Set<String> = emptySet(),
    val detectedCandidates: List<DetectedCandidateUi> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val errorMessage: String? = null,
)

data class DetectedCandidateUi(
    val name: String,
    val actualPriceYuan: String,
    val proposedCrop: CropRect,
    val lowConfidenceFields: Set<String>,
)

@Composable
fun ImportReviewScreen(
    source: Uri,
    recognizer: ScreenshotTextRecognizer,
    imageStore: ImageStore,
    kind: ImageKind,
    onConfirmed: suspend (ConfirmedScreenshotImport) -> Boolean,
    onCancel: () -> Unit,
) {
    val session = remember(source, recognizer, imageStore) { ScreenshotImportSession(recognizer, imageStore) }
    var state by remember(source) { mutableStateOf(ImportReviewUiState(loading = true)) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(source) {
        try {
            val preview = loadTemporaryPreview(context, source)
            state = state.copy(
                preview = preview.bitmap,
                imageWidth = preview.width,
                imageHeight = preview.height,
                crop = CropRect(0, 0, preview.width, preview.height),
                lowConfidenceFields = setOf("productName", "actualPriceFen", "proposedCrop"),
            )
            val prepared = try {
                session.prepare(source, preview.width, preview.height)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                state = state.copy(
                    loading = false,
                    errorMessage = "文字识别失败，可手工填写并调整裁剪框",
                )
                return@LaunchedEffect
            }
            val candidate = prepared.candidate
            val proposed = candidate.proposedCrop?.runCatching {
                requireInside(preview.width, preview.height)
            }?.getOrNull() ?: CropRect(0, 0, preview.width, preview.height)
            state = state.copy(
                productName = candidate.productName.orEmpty(),
                actualPriceYuan = candidate.actualPriceFen?.let(::formatFen).orEmpty(),
                crop = proposed,
                lowConfidenceFields = candidate.lowConfidenceFields,
                detectedCandidates = prepared.candidates.mapNotNull { detected ->
                    detected.productName?.let { name ->
                        DetectedCandidateUi(
                            name = name,
                            actualPriceYuan = detected.actualPriceFen?.let(::formatFen).orEmpty(),
                            proposedCrop = detected.proposedCrop ?: CropRect(0, 0, preview.width, preview.height),
                            lowConfidenceFields = detected.lowConfidenceFields,
                        )
                    }
                }.distinctBy { it.name to it.actualPriceYuan },
                loading = false,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            state = state.copy(loading = false, errorMessage = "截图读取失败，请重新选择图片")
        }
    }
    DisposableEffect(session) { onDispose { session.cancel() } }

    ImportReviewContent(
        state = state,
        onNameChange = { state = state.copy(productName = it, lowConfidenceFields = state.lowConfidenceFields - "productName") },
        onPriceChange = { state = state.copy(actualPriceYuan = it, lowConfidenceFields = state.lowConfidenceFields - "actualPriceFen") },
        onCropChange = { state = state.copy(crop = it, lowConfidenceFields = state.lowConfidenceFields - "proposedCrop") },
        onCandidateSelect = { candidate ->
            state = state.copy(
                productName = candidate.name,
                actualPriceYuan = candidate.actualPriceYuan,
                crop = candidate.proposedCrop,
                lowConfidenceFields = candidate.lowConfidenceFields,
            )
        },
        onConfirm = {
            if (!state.saving && !state.loading) scope.launch {
                state = state.copy(saving = true, errorMessage = null)
                try {
                    val result = session.confirm(state.productName, state.actualPriceYuan, state.crop, kind)
                    if (onConfirmed(result)) {
                        state = ImportReviewUiState()
                    } else {
                        state = state.copy(
                            saving = false,
                            errorMessage = "图片未能关联到产品，请调整裁剪后重试",
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: IllegalArgumentException) {
                    state = state.copy(saving = false, errorMessage = "请确认产品名称、实付价格和裁剪范围")
                } catch (_: Exception) {
                    state = state.copy(saving = false, errorMessage = "保存裁剪图失败，请重试")
                }
            }
        },
        onCancel = { session.cancel(); state = ImportReviewUiState(); onCancel() },
    )
}

@Composable
fun ImportReviewContent(
    state: ImportReviewUiState,
    onNameChange: (String) -> Unit,
    onPriceChange: (String) -> Unit,
    onCropChange: (CropRect) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onCandidateSelect: (DetectedCandidateUi) -> Unit = {},
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel, enabled = !state.saving) { Text("取消") }
                Column(horizontalAlignment = Alignment.End) {
                    Text("裁一张产品图", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("原始截图不会被保存", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.loading) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.8f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                Text("正在本机识别截图…")
            } else {
                CropPreview(state, onCropChange)
                if (state.lowConfidenceFields.isNotEmpty()) {
                    Text(
                        "识别结果需要确认",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (state.detectedCandidates.size > 1) {
                    Text("检测到多个产品，请选择目标", style = MaterialTheme.typography.titleMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.detectedCandidates.forEach { candidate ->
                            FilterChip(
                                selected = state.productName == candidate.name,
                                onClick = { onCandidateSelect(candidate) },
                                label = { Text(listOf(candidate.name, candidate.actualPriceYuan.takeIf(String::isNotEmpty)?.let { "¥$it" }).filterNotNull().joinToString(" · ")) },
                                enabled = !state.saving,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = state.productName,
                    onValueChange = onNameChange,
                    label = { Text("产品名称") },
                    supportingText = { if ("productName" in state.lowConfidenceFields) Text("未可靠识别，请手工确认") },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "产品名称" },
                    singleLine = true,
                    enabled = !state.saving,
                )
                OutlinedTextField(
                    value = state.actualPriceYuan,
                    onValueChange = onPriceChange,
                    label = { Text("实付价格（元，可选）") },
                    supportingText = { if ("actualPriceFen" in state.lowConfidenceFields) Text("价格置信度较低") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.saving,
                )
                CropControls(state, onCropChange)
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = onConfirm,
                    enabled = !state.saving && state.productName.isNotBlank() && state.imageWidth > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.saving) "正在保存…" else "确认并保存裁剪图") }
            }
        }
    }
}

@Composable
private fun CropPreview(state: ImportReviewUiState, onCropChange: (CropRect) -> Unit = {}) {
    val ratio = if (state.imageWidth > 0 && state.imageHeight > 0) {
        (state.imageWidth.toFloat() / state.imageHeight).coerceIn(0.55f, 1.5f)
    } else 0.75f
    val cropColor = MaterialTheme.colorScheme.tertiary
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).background(MaterialTheme.colorScheme.surfaceContainer)
            .semantics { contentDescription = "截图预览 ${state.imageWidth}×${state.imageHeight}" },
    ) {
        state.preview?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        CropOverlay(state.imageWidth, state.imageHeight, state.crop, cropColor, onCropChange)
    }
}

@Composable
private fun CropOverlay(
    imageWidth: Int,
    imageHeight: Int,
    crop: CropRect,
    cropColor: Color,
    onCropChange: (CropRect) -> Unit,
) {
    val currentCrop by rememberUpdatedState(crop)
    val currentOnCropChange by rememberUpdatedState(onCropChange)
    Canvas(
        Modifier.fillMaxSize()
            .semantics { contentDescription = "可拖动裁剪框" }
            .pointerInput(imageWidth, imageHeight) {
                var handle: CropDragHandle? = null
                var gestureCrop = currentCrop
                detectDragGestures(
                    onDragStart = { position ->
                        if (imageWidth > 0 && imageHeight > 0) {
                            gestureCrop = currentCrop
                            handle = hitTestCropHandle(
                                position.x,
                                position.y,
                                gestureCrop,
                                PreviewCoordinateTransform(
                                    size.width.toFloat(), size.height.toFloat(), imageWidth, imageHeight,
                                ),
                                24.dp.toPx(),
                            )
                        }
                    },
                    onDragEnd = { handle = null },
                    onDragCancel = { handle = null },
                    onDrag = { change, amount ->
                        handle?.let { active ->
                            gestureCrop = applyCropDrag(
                                gestureCrop,
                                active,
                                amount.x,
                                amount.y,
                                PreviewCoordinateTransform(
                                    size.width.toFloat(), size.height.toFloat(), imageWidth, imageHeight,
                                ),
                                minimumSizePx = 48,
                            )
                            currentOnCropChange(gestureCrop)
                            change.consume()
                        }
                    },
                )
            },
    ) {
        if (imageWidth <= 0 || imageHeight <= 0) return@Canvas
        val transform = PreviewCoordinateTransform(size.width, size.height, imageWidth, imageHeight)
        val topLeft = transform.imageToDisplay(crop.left.toFloat(), crop.top.toFloat())
        val bottomRight = transform.imageToDisplay(crop.right.toFloat(), crop.bottom.toFloat())
        drawRect(
            Color(0x88000000),
            Offset(transform.offsetX, transform.offsetY),
            androidx.compose.ui.geometry.Size(imageWidth * transform.scale, (crop.top * transform.scale).coerceAtLeast(0f)),
        )
        drawRect(
            Color(0x88000000),
            Offset(transform.offsetX, bottomRight.y),
            androidx.compose.ui.geometry.Size(imageWidth * transform.scale, (transform.offsetY + imageHeight * transform.scale - bottomRight.y).coerceAtLeast(0f)),
        )
        drawRect(
            Color(0x88000000),
            Offset(transform.offsetX, topLeft.y),
            androidx.compose.ui.geometry.Size((topLeft.x - transform.offsetX).coerceAtLeast(0f), (bottomRight.y - topLeft.y).coerceAtLeast(0f)),
        )
        drawRect(
            Color(0x88000000),
            Offset(bottomRight.x, topLeft.y),
            androidx.compose.ui.geometry.Size((transform.offsetX + imageWidth * transform.scale - bottomRight.x).coerceAtLeast(0f), (bottomRight.y - topLeft.y).coerceAtLeast(0f)),
        )
        drawRect(
            cropColor,
            Offset(topLeft.x, topLeft.y),
            androidx.compose.ui.geometry.Size(
                (bottomRight.x - topLeft.x).coerceAtLeast(1f),
                (bottomRight.y - topLeft.y).coerceAtLeast(1f),
            ),
            style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()),
        )
    }
}

data class ImagePoint(val x: Float, val y: Float)

data class PreviewCoordinateTransform(
    val containerWidth: Float,
    val containerHeight: Float,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    init {
        require(containerWidth > 0f && containerHeight > 0f && imageWidth > 0 && imageHeight > 0)
    }

    val scale: Float = minOf(containerWidth / imageWidth, containerHeight / imageHeight)
    val offsetX: Float = (containerWidth - imageWidth * scale) / 2f
    val offsetY: Float = (containerHeight - imageHeight * scale) / 2f

    fun displayToImage(x: Float, y: Float) = ImagePoint(
        ((x - offsetX) / scale).coerceIn(0f, imageWidth.toFloat()),
        ((y - offsetY) / scale).coerceIn(0f, imageHeight.toFloat()),
    )

    fun imageToDisplay(x: Float, y: Float) = ImagePoint(offsetX + x * scale, offsetY + y * scale)
}

enum class CropDragHandle {
    MOVE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
}

fun hitTestCropHandle(
    displayX: Float,
    displayY: Float,
    crop: CropRect,
    transform: PreviewCoordinateTransform,
    edgeThresholdDisplayPx: Float,
): CropDragHandle? {
    val topLeft = transform.imageToDisplay(crop.left.toFloat(), crop.top.toFloat())
    val bottomRight = transform.imageToDisplay(crop.right.toFloat(), crop.bottom.toFloat())
    val nearLeft = kotlin.math.abs(displayX - topLeft.x) <= edgeThresholdDisplayPx
    val nearRight = kotlin.math.abs(displayX - bottomRight.x) <= edgeThresholdDisplayPx
    val nearTop = kotlin.math.abs(displayY - topLeft.y) <= edgeThresholdDisplayPx
    val nearBottom = kotlin.math.abs(displayY - bottomRight.y) <= edgeThresholdDisplayPx
    val withinX = displayX in (topLeft.x - edgeThresholdDisplayPx)..(bottomRight.x + edgeThresholdDisplayPx)
    val withinY = displayY in (topLeft.y - edgeThresholdDisplayPx)..(bottomRight.y + edgeThresholdDisplayPx)
    return when {
        nearLeft && nearTop -> CropDragHandle.TOP_LEFT
        nearRight && nearTop -> CropDragHandle.TOP_RIGHT
        nearLeft && nearBottom -> CropDragHandle.BOTTOM_LEFT
        nearRight && nearBottom -> CropDragHandle.BOTTOM_RIGHT
        nearLeft && withinY -> CropDragHandle.LEFT
        nearRight && withinY -> CropDragHandle.RIGHT
        nearTop && withinX -> CropDragHandle.TOP
        nearBottom && withinX -> CropDragHandle.BOTTOM
        displayX in topLeft.x..bottomRight.x && displayY in topLeft.y..bottomRight.y -> CropDragHandle.MOVE
        else -> null
    }
}

fun applyCropDrag(
    crop: CropRect,
    handle: CropDragHandle,
    displayDeltaX: Float,
    displayDeltaY: Float,
    transform: PreviewCoordinateTransform,
    minimumSizePx: Int,
): CropRect {
    require(minimumSizePx > 0)
    val normalized = normalizeCropForDrag(crop, handle, transform, minimumSizePx)
    val dx = (displayDeltaX / transform.scale).toInt()
    val dy = (displayDeltaY / transform.scale).toInt()
    if (handle == CropDragHandle.MOVE) {
        val left = (normalized.left + dx).coerceIn(0, transform.imageWidth - normalized.width)
        val top = (normalized.top + dy).coerceIn(0, transform.imageHeight - normalized.height)
        return CropRect(left, top, left + normalized.width, top + normalized.height)
    }
    val minimumWidth = minimumSizePx.coerceAtMost(transform.imageWidth)
    val minimumHeight = minimumSizePx.coerceAtMost(transform.imageHeight)
    var left = normalized.left
    var top = normalized.top
    var right = normalized.right
    var bottom = normalized.bottom
    if (handle in setOf(CropDragHandle.LEFT, CropDragHandle.TOP_LEFT, CropDragHandle.BOTTOM_LEFT)) {
        left = (left + dx).coerceIn(0, right - minimumWidth)
    }
    if (handle in setOf(CropDragHandle.RIGHT, CropDragHandle.TOP_RIGHT, CropDragHandle.BOTTOM_RIGHT)) {
        right = (right + dx).coerceIn(left + minimumWidth, transform.imageWidth)
    }
    if (handle in setOf(CropDragHandle.TOP, CropDragHandle.TOP_LEFT, CropDragHandle.TOP_RIGHT)) {
        top = (top + dy).coerceIn(0, bottom - minimumHeight)
    }
    if (handle in setOf(CropDragHandle.BOTTOM, CropDragHandle.BOTTOM_LEFT, CropDragHandle.BOTTOM_RIGHT)) {
        bottom = (bottom + dy).coerceIn(top + minimumHeight, transform.imageHeight)
    }
    return CropRect(left, top, right, bottom)
}

private fun normalizeCropForDrag(
    crop: CropRect,
    handle: CropDragHandle,
    transform: PreviewCoordinateTransform,
    minimumSizePx: Int,
): CropRect {
    val minimumWidth = minimumSizePx.coerceAtMost(transform.imageWidth)
    val minimumHeight = minimumSizePx.coerceAtMost(transform.imageHeight)
    var left = crop.left.coerceIn(0, transform.imageWidth - 1)
    var right = crop.right.coerceIn(left + 1, transform.imageWidth)
    var top = crop.top.coerceIn(0, transform.imageHeight - 1)
    var bottom = crop.bottom.coerceIn(top + 1, transform.imageHeight)
    if (right - left < minimumWidth) {
        if (handle in setOf(CropDragHandle.LEFT, CropDragHandle.TOP_LEFT, CropDragHandle.BOTTOM_LEFT)) {
            left = (right - minimumWidth).coerceAtLeast(0)
            right = (left + minimumWidth).coerceAtMost(transform.imageWidth)
        } else {
            right = (left + minimumWidth).coerceAtMost(transform.imageWidth)
            left = (right - minimumWidth).coerceAtLeast(0)
        }
    }
    if (bottom - top < minimumHeight) {
        if (handle in setOf(CropDragHandle.TOP, CropDragHandle.TOP_LEFT, CropDragHandle.TOP_RIGHT)) {
            top = (bottom - minimumHeight).coerceAtLeast(0)
            bottom = (top + minimumHeight).coerceAtMost(transform.imageHeight)
        } else {
            bottom = (top + minimumHeight).coerceAtMost(transform.imageHeight)
            top = (bottom - minimumHeight).coerceAtLeast(0)
        }
    }
    return CropRect(left, top, right, bottom)
}

@Composable
private fun CropControls(state: ImportReviewUiState, onChange: (CropRect) -> Unit) {
    if (state.imageWidth <= 1 || state.imageHeight <= 1) return
    Text("调整裁剪边界", style = MaterialTheme.typography.titleMedium)
    CropSlider("左", "调整裁剪左边界", state.crop.left, 0, state.crop.right - 1) {
        onChange(state.crop.copy(left = it))
    }
    CropSlider("右", "调整裁剪右边界", state.crop.right, state.crop.left + 1, state.imageWidth) {
        onChange(state.crop.copy(right = it))
    }
    CropSlider("上", "调整裁剪上边界", state.crop.top, 0, state.crop.bottom - 1) {
        onChange(state.crop.copy(top = it))
    }
    CropSlider("下", "调整裁剪下边界", state.crop.bottom, state.crop.top + 1, state.imageHeight) {
        onChange(state.crop.copy(bottom = it))
    }
}

@Composable
private fun CropSlider(label: String, description: String, value: Int, minimum: Int, maximum: Int, onValue: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(end = 8.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.toInt().coerceIn(minimum, maximum)) },
            valueRange = minimum.toFloat()..maximum.toFloat(),
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
        )
    }
}

private data class TemporaryPreview(val bitmap: ImageBitmap, val width: Int, val height: Int)

private suspend fun loadTemporaryPreview(context: Context, source: Uri): TemporaryPreview = withContext(Dispatchers.IO) {
    val resolver = context.applicationContext.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: error("Image cannot be opened")
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    val orientation = resolver.openInputStream(source)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
    val decoded = resolver.openInputStream(source)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("Image cannot be decoded")
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
    }
    val oriented = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { decoded.recycle() }
    val swapsAxes = orientation in setOf(5, 6, 7, 8)
    TemporaryPreview(oriented.asImageBitmap(), if (swapsAxes) bounds.outHeight else bounds.outWidth, if (swapsAxes) bounds.outWidth else bounds.outHeight)
}

private fun formatFen(fen: Long): String = String.format(Locale.CHINA, "%d.%02d", fen / 100, fen % 100)
