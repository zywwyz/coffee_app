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

    suspend fun prepare(source: Uri, imageWidth: Int, imageHeight: Int): ScreenshotReviewPreparation {
        require(imageWidth > 0 && imageHeight > 0)
        selectedSource = source
        val blocks = recognizer.recognize(source)
        val candidates = normalizeScreenshotCandidates(blocks, imageWidth, imageHeight)
        val primary = candidates.firstOrNull() ?: normalizeScreenshot(blocks)
        return ScreenshotReviewPreparation(primary, candidates.ifEmpty { listOf(primary) })
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
        proposedCrop = null,
        lowConfidenceFields = low,
    )
}

fun normalizeScreenshotCandidates(
    blocks: List<TextBlock>,
    imageWidth: Int = blocks.maxOfOrNull { it.bounds.right }?.coerceAtLeast(1) ?: 1,
    imageHeight: Int = blocks.maxOfOrNull { it.bounds.bottom }?.coerceAtLeast(1) ?: 1,
): List<ScreenshotCandidate> {
    require(imageWidth > 0 && imageHeight > 0)
    val prices = extractPriceCandidates(blocks).filter { it.priority > 0 }
    val anchors = extractNameBlocks(blocks).map { name ->
        val centerX = (name.bounds.left + name.bounds.right) / 2
        val centerY = (name.bounds.top + name.bounds.bottom) / 2
        val nearest = prices.minByOrNull {
            val priceX = (it.block.bounds.left + it.block.bounds.right) / 2
            val priceY = (it.block.bounds.top + it.block.bounds.bottom) / 2
            val dx = centerX.toLong() - priceX
            val dy = centerY.toLong() - priceY
            dx * dx + dy * dy
        }
        CandidateAnchor(name, nearest)
    }
    return anchors.map { anchor ->
        ScreenshotCandidate(
            productName = anchor.name.text.trim(),
            actualPriceFen = anchor.price?.fen,
            proposedCrop = inferCardCrop(anchor, anchors, imageWidth, imageHeight),
            lowConfidenceFields = buildSet {
                add("productName")
                add("proposedCrop")
                if (anchor.price == null || anchor.price.priority < 2) add("actualPriceFen")
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

private data class CandidateAnchor(val name: TextBlock, val price: PriceCandidate?) {
    val bounds: CropRect = union(listOfNotNull(name, price?.block).map(TextBlock::bounds))
    val centerX: Int get() = (bounds.left + bounds.right) / 2
    val centerY: Int get() = (bounds.top + bounds.bottom) / 2
}

private fun inferCardCrop(
    current: CandidateAnchor,
    all: List<CandidateAnchor>,
    imageWidth: Int,
    imageHeight: Int,
): CropRect {
    val sameRow = all.filter { other ->
        other !== current && kotlin.math.abs(other.centerY - current.centerY) <=
            maxOf(other.bounds.height, current.bounds.height) * 2
    }
    val leftNeighbor = sameRow.filter { it.centerX < current.centerX }.maxByOrNull(CandidateAnchor::centerX)
    val rightNeighbor = sameRow.filter { it.centerX > current.centerX }.minByOrNull(CandidateAnchor::centerX)
    val halfCellWidth = when {
        leftNeighbor != null -> (current.centerX - leftNeighbor.centerX) / 2
        rightNeighbor != null -> (rightNeighbor.centerX - current.centerX) / 2
        else -> maxOf(current.bounds.width, imageWidth / 4)
    }.coerceAtLeast(current.bounds.width / 2)
    val cellLeft = leftNeighbor?.let { (it.centerX + current.centerX) / 2 }
        ?: (current.centerX - halfCellWidth).coerceAtLeast(0)
    val cellRight = rightNeighbor?.let { (it.centerX + current.centerX) / 2 }
        ?: (current.centerX + halfCellWidth).coerceAtMost(imageWidth)

    val sameColumn = all.filter { other ->
        other !== current && kotlin.math.abs(other.centerX - current.centerX) <=
            maxOf(other.bounds.width, current.bounds.width) * 3 / 2
    }
    val above = sameColumn.filter { it.centerY < current.centerY }.maxByOrNull(CandidateAnchor::centerY)
    val below = sameColumn.filter { it.centerY > current.centerY }.minByOrNull(CandidateAnchor::centerY)
    val verticalHalfCell = when {
        above != null -> (current.centerY - above.centerY) / 2
        below != null -> (below.centerY - current.centerY) / 2
        else -> maxOf(current.bounds.width, imageHeight / 5)
    }.coerceAtLeast(current.bounds.height)
    val cellTop = above?.let { (it.centerY + current.centerY) / 2 }
        ?: (current.centerY - verticalHalfCell).coerceAtLeast(0)
    val cellBottom = below?.let { (it.centerY + current.centerY) / 2 }
        ?: (current.centerY + verticalHalfCell).coerceAtMost(imageHeight)

    val gutterX = (imageWidth / 100).coerceAtLeast(2)
    val gutterY = (imageHeight / 200).coerceAtLeast(2)
    return CropRect(
        (cellLeft + gutterX).coerceAtMost(current.bounds.left),
        (cellTop + gutterY).coerceAtMost(current.bounds.top),
        (cellRight - gutterX).coerceAtLeast(current.bounds.right),
        (cellBottom - gutterY).coerceAtLeast(current.bounds.bottom),
    ).clampTo(imageWidth, imageHeight)
}

private fun union(rects: List<CropRect>): CropRect = CropRect(
    rects.minOf(CropRect::left),
    rects.minOf(CropRect::top),
    rects.maxOf(CropRect::right),
    rects.maxOf(CropRect::bottom),
)

private fun CropRect.clampTo(imageWidth: Int, imageHeight: Int): CropRect = CropRect(
    left.coerceIn(0, imageWidth - 1),
    top.coerceIn(0, imageHeight - 1),
    right.coerceIn(left.coerceIn(0, imageWidth - 1) + 1, imageWidth),
    bottom.coerceIn(top.coerceIn(0, imageHeight - 1) + 1, imageHeight),
)
