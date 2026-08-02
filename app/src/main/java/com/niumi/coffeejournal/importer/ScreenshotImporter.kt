package com.niumi.coffeejournal.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.niumi.coffeejournal.core.image.CropRect
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class TextBlock(val text: String, val bounds: CropRect)

fun interface ScreenshotTextRecognizer {
    suspend fun recognize(uri: Uri): List<TextBlock>
}

data class DecodedScreenshotBitmap(
    val bitmap: Bitmap,
    val orientedWidth: Int,
    val orientedHeight: Int,
)

fun interface ScreenshotBitmapDecoder {
    suspend fun decode(uri: Uri): DecodedScreenshotBitmap
}

interface BitmapTextRecognitionSession {
    suspend fun recognize(bitmap: Bitmap): List<TextBlock>
    fun close()
}

fun interface BitmapTextRecognitionSessionFactory {
    fun create(): BitmapTextRecognitionSession
}

class SampledBitmapScreenshotTextRecognizer(
    private val decoder: ScreenshotBitmapDecoder,
    private val sessionFactory: BitmapTextRecognitionSessionFactory,
) : ScreenshotTextRecognizer {
    override suspend fun recognize(uri: Uri): List<TextBlock> {
        val decoded = decoder.decode(uri)
        val session = try {
            sessionFactory.create()
        } catch (error: Throwable) {
            decoded.bitmap.recycle()
            throw error
        }
        return try {
            val scaleX = decoded.orientedWidth.toDouble() / decoded.bitmap.width
            val scaleY = decoded.orientedHeight.toDouble() / decoded.bitmap.height
            session.recognize(decoded.bitmap).map { block ->
                val left = (block.bounds.left * scaleX).roundToInt().coerceIn(0, decoded.orientedWidth - 1)
                val top = (block.bounds.top * scaleY).roundToInt().coerceIn(0, decoded.orientedHeight - 1)
                block.copy(
                    bounds = CropRect(
                        left,
                        top,
                        (block.bounds.right * scaleX).roundToInt().coerceIn(left + 1, decoded.orientedWidth),
                        (block.bounds.bottom * scaleY).roundToInt().coerceIn(top + 1, decoded.orientedHeight),
                    ),
                )
            }
        } finally {
            try {
                session.close()
            } finally {
                decoded.bitmap.recycle()
            }
        }
    }
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

class MlKitScreenshotTextRecognizer(
    context: Context,
    decoder: ScreenshotBitmapDecoder = AndroidScreenshotBitmapDecoder(context),
    sessionFactory: BitmapTextRecognitionSessionFactory = MlKitBitmapTextRecognitionSessionFactory,
) : ScreenshotTextRecognizer by SampledBitmapScreenshotTextRecognizer(decoder, sessionFactory)

class AndroidScreenshotBitmapDecoder(
    context: Context,
    private val maxDimension: Int = OCR_MAX_DECODE_DIMENSION,
    private val maxPixels: Int = OCR_MAX_DECODE_PIXELS,
) : ScreenshotBitmapDecoder {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun decode(uri: Uri): DecodedScreenshotBitmap = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalArgumentException("Screenshot cannot be opened")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Screenshot cannot be decoded" }
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val sample = ocrSampleSize(bounds.outWidth, bounds.outHeight, maxDimension, maxPixels)
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Screenshot cannot be decoded")
        val oriented = applyOcrOrientation(decoded, orientation)
        if (oriented !== decoded) decoded.recycle()
        val swapsAxes = orientation in setOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
        try {
            coroutineContext.ensureActive()
            DecodedScreenshotBitmap(
                oriented,
                if (swapsAxes) bounds.outHeight else bounds.outWidth,
                if (swapsAxes) bounds.outWidth else bounds.outHeight,
            )
        } catch (error: Throwable) {
            oriented.recycle()
            throw error
        }
    }
}

private object MlKitBitmapTextRecognitionSessionFactory : BitmapTextRecognitionSessionFactory {
    override fun create(): BitmapTextRecognitionSession = object : BitmapTextRecognitionSession {
        private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

        override suspend fun recognize(bitmap: Bitmap): List<TextBlock> {
            val input = InputImage.fromBitmap(bitmap, 0)
            return suspendCancellableCoroutine { continuation ->
                recognizer.process(input)
                    .addOnSuccessListener { result ->
                        val blocks = result.textBlocks.flatMap { block ->
                            block.lines.mapNotNull { line ->
                                val box = line.boundingBox ?: return@mapNotNull null
                                TextBlock(line.text, CropRect(box.left, box.top, box.right, box.bottom))
                            }
                        }
                        if (continuation.isActive) continuation.resume(blocks)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                    .addOnCanceledListener {
                        continuation.cancel()
                    }
            }
        }

        override fun close() = recognizer.close()
    }
}

const val OCR_MAX_DECODE_DIMENSION = 2048
const val OCR_MAX_DECODE_PIXELS = 4_000_000

fun ocrSampleSize(width: Int, height: Int, maxDimension: Int, maxPixels: Int): Int {
    require(width > 0 && height > 0 && maxDimension > 0 && maxPixels > 0)
    var sample = 1
    while (
        width / sample > maxDimension || height / sample > maxDimension ||
        (width.toLong() / sample) * (height.toLong() / sample) > maxPixels
    ) sample *= 2
    return sample
}

private fun applyOcrOrientation(source: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return source
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private val PRICE_PATTERN = Regex("(?:¥|￥)?\\s*([0-9]{1,7}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)")
private val ACTUAL_LABELS = listOf("实付", "到手", "成交价", "优惠后", "支付金额", "付款金额")
private const val BROAD_PAYMENT_LABEL = "支付"
private val PAYMENT_LABELS = ACTUAL_LABELS + BROAD_PAYMENT_LABEL
private val ORIGINAL_LABELS = listOf("原价", "划线价", "门市价", "建议价")
private val NON_PRICE_CONTEXTS = listOf("支付时间", "付款时间", "订单号", "订单编号", "取餐码", "日期", "时间")

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
        if (NON_PRICE_CONTEXTS.any(block.text::contains)) return@mapNotNull null
        val actualLabel = ACTUAL_LABELS.firstOrNull(block.text::contains)
        val originalLabel = ORIGINAL_LABELS.firstOrNull(block.text::contains)
        val broadPayment = actualLabel == null && block.text.contains(BROAD_PAYMENT_LABEL)
        val searchText = when {
            actualLabel != null -> block.text.substringAfter(actualLabel)
            originalLabel != null -> block.text.substringAfter(originalLabel)
            broadPayment -> block.text.substringAfter(BROAD_PAYMENT_LABEL)
            else -> block.text
        }
        val match = PRICE_PATTERN.find(searchText) ?: return@mapNotNull null
        val amount = match.groupValues[1]
        val hasCurrencyMark = match.value.contains('¥') || match.value.contains('￥')
        if (actualLabel == null && originalLabel == null && !hasCurrencyMark && '.' !in amount) {
            return@mapNotNull null
        }
        val fen = parseYuanAmountToFen(amount) ?: return@mapNotNull null
        PriceCandidate(
            fen,
            priority = when {
                actualLabel != null || broadPayment && hasCurrencyMark -> 2
                ORIGINAL_LABELS.any(block.text::contains) -> 0
                else -> 1
            },
            block,
        )
    }

private fun extractNameBlocks(blocks: List<TextBlock>) = blocks.filter { block ->
        val value = block.text.trim()
        value.length in 2..40 && value.any(Char::isLetter) &&
            PAYMENT_LABELS.none(value::contains) && ORIGINAL_LABELS.none(value::contains) &&
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
