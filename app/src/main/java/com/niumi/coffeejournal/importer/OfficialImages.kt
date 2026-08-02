package com.niumi.coffeejournal.importer

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DownloadedOfficialImage(val bytes: ByteArray, val mimeType: String)
data class ImageBounds(val width: Int, val height: Int)

interface OfficialImageDownloader {
    suspend fun download(url: String): DownloadedOfficialImage
}

interface OfficialImageAssetStore {
    suspend fun import(bytes: ByteArray, mimeType: String): String
    suspend fun cleanup(assetId: String)
}

object OfficialImagePolicy {
    private val allowedHosts = mapOf(
        "seed-chain-luckin" to setOf(
            "img.luckincoffee.com", "www.luckincoffee.com",
            "ilucky-fe-outside-oss-prod.luckincdn.com",
        ),
        "seed-chain-mstand" to setOf("mstand.cn", "www.mstand.cn", "img.wanwang.xin"),
    )

    fun accepts(brandId: String, rawUrl: String): Boolean {
        val uri = try { URI(rawUrl) } catch (_: Exception) { return false }
        if (!uri.isSafeHttpsAuthority()) return false
        return uri.host?.lowercase() in allowedHosts[brandId].orEmpty()
    }
}

class ValidatingOfficialImageImporter(
    private val downloader: OfficialImageDownloader,
    private val assetStore: OfficialImageAssetStore,
    private val maxBytes: Int = 5 * 1024 * 1024,
    private val maxPixels: Long = 16_000_000,
    private val decodeBounds: (ByteArray) -> ImageBounds = ::androidImageBounds,
) : OfficialImageImporter {
    override suspend fun importOfficialImage(brandId: String, imageUrl: String): String {
        if (!OfficialImagePolicy.accepts(brandId, imageUrl)) throw OfficialImageException("官方图片地址不受信任")
        val downloaded = downloader.download(imageUrl)
        val mime = downloaded.mimeType.substringBefore(';').trim().lowercase()
        if (mime !in SUPPORTED_IMAGE_TYPES) throw OfficialImageException("官方地址返回的不是支持的图片")
        if (downloaded.bytes.isEmpty() || downloaded.bytes.size > maxBytes) throw OfficialImageException("官方图片大小无效")
        val bounds = decodeBounds(downloaded.bytes)
        if (bounds.width <= 0 || bounds.height <= 0 || bounds.width.toLong() * bounds.height > maxPixels) {
            throw OfficialImageException("官方图片像素尺寸无效")
        }
        return assetStore.import(downloaded.bytes, mime)
    }

    override suspend fun cleanup(assetId: String) = assetStore.cleanup(assetId)
}

class SafeOfficialImageDownloader(
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 12_000,
    private val maxBytes: Int = 5 * 1024 * 1024,
    private val resolver: NetworkResolver = SystemNetworkResolver,
    private val transport: PinnedHttpTransport = OkHttpPinnedTransport(connectTimeoutMillis, readTimeoutMillis),
) : OfficialImageDownloader {
    override suspend fun download(url: String): DownloadedOfficialImage {
        val originalHost = safeHttpsUri(url).host.lowercase()
        var current = url
        repeat(4) { redirectCount ->
            val uri = safeHttpsUri(current)
            if (!uri.host.equals(originalHost, true)) throw OfficialImageException("图片重定向离开官方域名")
            val addresses = try {
                if (transport is InternallyResolvingPinnedTransport) emptyList() else resolveGlobalAddresses(uri, resolver)
            }
            catch (error: Exception) { throw error.toOfficialImageException() }
            val response = try {
                transport.execute(
                    SafeHttpRequest(
                        "GET", current,
                        mapOf(
                            "Accept" to "image/avif,image/webp,image/png,image/jpeg",
                            "User-Agent" to "CoffeeJournal/1.0 official-image",
                        ),
                    ),
                    addresses,
                    maxBytes,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw error.toOfficialImageException()
            }
            try {
                val status = response.status
                if (status in IMAGE_REDIRECT_CODES) {
                    if (redirectCount == 3) throw OfficialImageException("图片重定向次数过多")
                    val target = response.headers["location"]
                        ?: throw OfficialImageException("图片重定向缺少目标")
                    current = URI(current).resolve(target).toString()
                    return@repeat
                }
                if (status !in 200..299) throw OfficialImageException("官方图片请求失败：HTTP $status")
                if (response.body.size > maxBytes) throw OfficialImageException("官方图片超过大小限制")
                val mime = response.contentType.substringBefore(';').trim().lowercase()
                if (mime !in SUPPORTED_IMAGE_TYPES) throw OfficialImageException("官方地址返回的不是支持的图片")
                return DownloadedOfficialImage(response.body, mime)
            } catch (error: OfficialImageException) {
                throw error
            } catch (error: Exception) {
                throw error.toOfficialImageException()
            }
        }
        throw OfficialImageException("官方图片请求失败")
    }
}

class LocalOfficialImageAssetStore(
    context: Context,
    private val imageStore: ImageStore,
    private val afterTempWritten: suspend (File) -> Unit = {},
    private val beforeAssetDelivery: suspend (String) -> Unit = {},
) : OfficialImageAssetStore {
    private val directory = File(context.applicationContext.cacheDir, "official-image-import")

    override suspend fun import(bytes: ByteArray, mimeType: String): String {
        var temporary: File? = null
        var assetId: String? = null
        return try {
            withContext(NonCancellable + Dispatchers.IO) {
                directory.mkdirs()
                temporary = File.createTempFile("official-", extensionFor(mimeType), directory)
                FileOutputStream(checkNotNull(temporary)).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
            }
            val ownedTemporary = checkNotNull(temporary)
            afterTempWritten(ownedTemporary)
            currentCoroutineContext().ensureActive()
            assetId = imageStore.importWhole(Uri.fromFile(ownedTemporary), ImageKind.PRODUCT).id
            val owned = checkNotNull(assetId)
            beforeAssetDelivery(owned)
            currentCoroutineContext().ensureActive()
            owned
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                assetId?.let { runCatching { imageStore.deleteIfUnreferenced(it) } }
            }
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { temporary?.let { runCatching { it.delete() } } }
        }
    }

    override suspend fun cleanup(assetId: String) {
        imageStore.deleteIfUnreferenced(assetId)
    }
}

private fun androidImageBounds(bytes: ByteArray): ImageBounds {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    return ImageBounds(options.outWidth, options.outHeight)
}

private fun safeHttpsUri(raw: String): URI {
    val uri = try { URI(raw) } catch (_: Exception) { throw OfficialImageException("图片地址无效") }
    if (!uri.isSafeHttpsAuthority()) {
        throw OfficialImageException("图片地址必须是 HTTPS")
    }
    return uri
}

private fun Exception.toOfficialImageException(): OfficialImageException = when (this) {
    is OfficialImageException -> this
    is PublicPageException -> OfficialImageException(message ?: "官方图片地址不安全")
    is UnknownHostException, is ConnectException, is SocketTimeoutException -> OfficialImageException("无法连接官方图片地址")
    else -> OfficialImageException(message ?: "官方图片请求失败")
}

private fun extensionFor(mimeType: String): String = when (mimeType) {
    "image/png" -> ".png"
    "image/webp" -> ".webp"
    else -> ".jpg"
}

private val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
private val IMAGE_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
