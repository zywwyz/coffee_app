package com.niumi.coffeejournal.importer

import android.content.Context
import android.net.Uri
import com.niumi.coffeejournal.core.image.CropRect
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class TextBlock(val text: String, val bounds: CropRect)

fun interface ScreenshotTextRecognizer {
    suspend fun recognize(uri: Uri): List<TextBlock>
}

data class ScreenshotCandidate(
    val productName: String?,
    val actualPriceFen: Long?,
    val proposedCrop: CropRect?,
    val lowConfidenceFields: Set<String>,
)

data class ConfirmedScreenshotImport(
    val productName: String,
    val actualPriceFen: Long?,
    val imageAssetId: String,
)

data class ScreenshotReviewPreparation(
    val candidate: ScreenshotCandidate,
    val candidates: List<ScreenshotCandidate> = listOf(candidate),
)

class ScreenshotImportSession(
    private val recognizer: ScreenshotTextRecognizer,
    private val imageStore: ImageStore,
) {
    private var selectedSource: Uri? = null

    suspend fun prepare(source: Uri): ScreenshotReviewPreparation {
        selectedSource = source
        val blocks = recognizer.recognize(source)
        val primary = normalizeScreenshot(blocks)
        return ScreenshotReviewPreparation(primary, normalizeScreenshotCandidates(blocks).ifEmpty { listOf(primary) })
    }

    fun cancel() {
        selectedSource = null
    }

    suspend fun confirm(
        productName: String,
        actualPriceYuan: String,
        crop: CropRect,
        kind: ImageKind,
    ): ConfirmedScreenshotImport {
        val source = checkNotNull(selectedSource) { "No screenshot is being reviewed" }
        val name = productName.trim()
        require(name.isNotEmpty()) { "Product name is required" }
        val price = actualPriceYuan.trim().takeIf(String::isNotEmpty)?.let(::parseYuanAmountToFen)
        require(actualPriceYuan.isBlank() || price != null) { "Actual price is invalid" }
        val asset = imageStore.importCropped(source, crop, kind)
        selectedSource = null
        return ConfirmedScreenshotImport(name, price, asset.id)
    }

    override fun toString(): String = "ScreenshotImportSession(active=${selectedSource != null})"
}

class MlKitScreenshotTextRecognizer(context: Context) : ScreenshotTextRecognizer {
    private val applicationContext = context.applicationContext

    override suspend fun recognize(uri: Uri): List<TextBlock> {
        val input = InputImage.fromFilePath(applicationContext, uri)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return suspendCancellableCoroutine { continuation ->
            val closed = AtomicBoolean(false)
            fun closeOnce() {
                if (closed.compareAndSet(false, true)) recognizer.close()
            }
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    val blocks = result.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            TextBlock(line.text, CropRect(box.left, box.top, box.right, box.bottom))
                        }
                    }
                    if (continuation.isActive) continuation.resume(blocks)
                    closeOnce()
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                    closeOnce()
                }
                .addOnCanceledListener {
                    continuation.cancel()
                    closeOnce()
                }
            continuation.invokeOnCancellation { closeOnce() }
        }
    }
}

private val PRICE_PATTERN = Regex("(?:¥|￥)?\\s*([0-9]{1,7}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)")
private val ACTUAL_LABELS = listOf("实付", "支付", "到手", "成交价", "优惠后")
private val ORIGINAL_LABELS = listOf("原价", "划线价", "门市价", "建议价")

fun normalizeScreenshot(blocks: List<TextBlock>): ScreenshotCandidate {
    val priceCandidates = extractPriceCandidates(blocks)
    val selectedPrice = priceCandidates.maxWithOrNull(
        compareBy<PriceCandidate> { it.priority }.thenByDescending { it.block.bounds.top },
    )
    val nameBlock = extractNameBlocks(blocks).firstOrNull()
    val low = buildSet {
        add("productName")
        if (selectedPrice == null || selectedPrice.priority < 2) add("actualPriceFen")
        add("proposedCrop")
    }
    return ScreenshotCandidate(
        productName = nameBlock?.text?.trim(),
        actualPriceFen = selectedPrice?.fen,
        proposedCrop = proposedCrop(nameBlock, selectedPrice?.block),
        lowConfidenceFields = low,
    )
}

fun normalizeScreenshotCandidates(blocks: List<TextBlock>): List<ScreenshotCandidate> {
    val prices = extractPriceCandidates(blocks).filter { it.priority > 0 }
    return extractNameBlocks(blocks).map { name ->
        val center = (name.bounds.top + name.bounds.bottom) / 2
        val nearest = prices.minByOrNull {
            kotlin.math.abs(center - (it.block.bounds.top + it.block.bounds.bottom) / 2)
        }
        ScreenshotCandidate(
            productName = name.text.trim(),
            actualPriceFen = nearest?.fen,
            proposedCrop = proposedCrop(name, nearest?.block),
            lowConfidenceFields = buildSet {
                add("productName")
                add("proposedCrop")
                if (nearest == null || nearest.priority < 2) add("actualPriceFen")
            },
        )
    }
}

private fun extractPriceCandidates(blocks: List<TextBlock>) = blocks.mapNotNull { block ->
        val actualLabel = ACTUAL_LABELS.firstOrNull(block.text::contains)
        val originalLabel = ORIGINAL_LABELS.firstOrNull(block.text::contains)
        val searchText = when {
            actualLabel != null -> block.text.substringAfter(actualLabel)
            originalLabel != null -> block.text.substringAfter(originalLabel)
            else -> block.text
        }
        val fen = PRICE_PATTERN.find(searchText)?.groupValues?.get(1)?.let(::parseYuanAmountToFen)
            ?: return@mapNotNull null
        PriceCandidate(
            fen,
            priority = when {
                ACTUAL_LABELS.any(block.text::contains) -> 2
                ORIGINAL_LABELS.any(block.text::contains) -> 0
                else -> 1
            },
            block,
        )
    }

private fun extractNameBlocks(blocks: List<TextBlock>) = blocks.filter { block ->
        val value = block.text.trim()
        value.length in 2..40 && value.any(Char::isLetter) &&
            ACTUAL_LABELS.none(value::contains) && ORIGINAL_LABELS.none(value::contains) &&
            PRICE_PATTERN.find(value) == null
    }

fun parseYuanAmountToFen(raw: String): Long? = try {
    val normalized = raw.replace(",", "").trim()
    val yuan = BigDecimal(normalized)
    if (yuan.signum() < 0 || yuan.scale().coerceAtLeast(0) > 2) null
    else yuan.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
} catch (_: ArithmeticException) {
    null
} catch (_: NumberFormatException) {
    null
}

private data class PriceCandidate(val fen: Long, val priority: Int, val block: TextBlock)

private fun proposedCrop(name: TextBlock?, price: TextBlock?): CropRect? {
    val selected = listOfNotNull(name, price)
    if (selected.isEmpty()) return null
    val left = selected.minOf { it.bounds.left }
    val top = selected.minOf { it.bounds.top }
    val right = selected.maxOf { it.bounds.right }
    val bottom = selected.maxOf { it.bounds.bottom }
    val padding = selected.maxOf { it.bounds.height }.coerceAtLeast(1) * 2
    return CropRect(
        (left - padding).coerceAtLeast(0),
        (top - padding * 2).coerceAtLeast(0),
        right + padding,
        bottom + padding,
    )
}
