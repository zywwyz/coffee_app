package com.niumi.coffeejournal.importer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import com.niumi.coffeejournal.core.model.MaintenanceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class PublicPageRequest(
    val url: String,
    val policy: OfficialPagePolicy,
    val maxBytes: Int = MAX_PAGE_BYTES,
)

data class PublicPageResponse(
    val finalUrl: String,
    val body: String,
    val decodedBytes: Int = body.toByteArray(Charsets.UTF_8).size,
)

interface PublicPageClient {
    suspend fun getText(request: PublicPageRequest): PublicPageResponse
    suspend fun postForm(request: PublicPageRequest, fields: Map<String, String>): PublicPageResponse =
        throw PublicPageException.Http(message = "公开来源不支持分页请求")
}

sealed class PublicPageException(message: String) : IOException(message) {
    class Offline(message: String = "网络不可用") : PublicPageException(message)
    class Http(val status: Int? = null, message: String = status?.let { "HTTP $it" } ?: "请求失败") :
        PublicPageException(message)
    class UnsafeUrl(message: String = "来源地址不安全") : PublicPageException(message)
    class TooLarge : PublicPageException("页面超过大小限制")
}

enum class OfficialPagePolicy(
    private val hosts: Set<String>,
    private val allowedPath: (String) -> Boolean,
) {
    LUCKIN(setOf("www.luckincoffee.com"), { it.startsWith("/cn/menu/") || it == "/cn/menu" }),
    MSTAND(setOf("mstand.cn", "www.mstand.cn"), {
        it == "/ProductInfoCategory" || it.startsWith("/ProductInfo/") ||
            it.matches(Regex("/productinfo/[^/]+\\.html", RegexOption.IGNORE_CASE)) ||
            it == "/Designer/Common/GetData"
    }),
    CUSTOM(emptySet(), { true });

    fun accepts(rawUrl: String): Boolean {
        val uri = try { URI(rawUrl) } catch (_: Exception) { return false }
        if (!uri.isSafeHttpsAuthority()) return false
        val host = uri.host?.lowercase() ?: return false
        if (this != CUSTOM && host !in hosts) return false
        if (this == CUSTOM && host.isBlank()) return false
        return allowedPath(uri.path.orEmpty())
    }
}

class SafeOfficialHttpClient(
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 12_000,
    private val resolver: NetworkResolver = SystemNetworkResolver,
    private val transport: PinnedHttpTransport = OkHttpPinnedTransport(connectTimeoutMillis, readTimeoutMillis),
) : PublicPageClient {
    override suspend fun getText(request: PublicPageRequest): PublicPageResponse = execute(
        request, "GET", null, setOf("text/html", "application/xhtml+xml"),
    )

    override suspend fun postForm(
        request: PublicPageRequest,
        fields: Map<String, String>,
    ): PublicPageResponse {
        val encoded = fields.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8") }=${URLEncoder.encode(value, "UTF-8") }"
        }.toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_FORM_BYTES) throw PublicPageException.Http(message = "分页请求过大")
        return execute(request, "POST", encoded, setOf("application/json", "text/json"))
    }

    private suspend fun execute(
        request: PublicPageRequest,
        method: String,
        body: ByteArray?,
        acceptedTypes: Set<String>,
    ): PublicPageResponse {
        if (!request.policy.accepts(request.url)) throw PublicPageException.UnsafeUrl()
        var current = request.url
        val originalHost = URI(current).host.lowercase()
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!request.policy.accepts(current)) throw PublicPageException.UnsafeUrl()
            val uri = URI(current)
            if (!uri.host.equals(originalHost, true)) throw PublicPageException.UnsafeUrl("重定向离开官方域名")
            val addresses = if (transport is InternallyResolvingPinnedTransport) {
                emptyList()
            } else {
                resolveGlobalAddresses(uri, resolver)
            }
            val headers = mutableMapOf(
                "Accept" to if (method == "POST") "application/json" else "text/html,application/xhtml+xml",
                "User-Agent" to "CoffeeJournal/1.0 public-catalog",
            )
            if (body != null) headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
            val response = try {
                transport.execute(SafeHttpRequest(method, current, headers, body), addresses, request.maxBytes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) { throw error.toPublicNetworkException() }
            if (response.body.size > request.maxBytes) throw PublicPageException.TooLarge()
            if (response.status in REDIRECT_CODES) {
                if (method != "GET") throw PublicPageException.UnsafeUrl("分页请求不接受重定向")
                if (redirectCount == MAX_REDIRECTS) throw PublicPageException.Http(response.status, "重定向次数过多")
                val location = response.headers["location"] ?: throw PublicPageException.Http(response.status, "重定向缺少目标")
                current = uri.resolve(location).toString()
                return@repeat
            }
            if (response.status !in 200..299) throw PublicPageException.Http(response.status)
            val type = response.contentType.substringBefore(';').trim().lowercase()
            if (type !in acceptedTypes) throw PublicPageException.Http(response.status, "官网返回内容类型无效")
            return PublicPageResponse(current, response.body.toString(Charsets.UTF_8), response.body.size)
        }
        throw PublicPageException.Http(message = "重定向失败")
    }
}

internal fun InputStream.readBounded(maxBytes: Int): ByteArray {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw PublicPageException.TooLarge()
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

class LuckinCatalogSource(
    private val client: PublicPageClient,
    private val now: () -> Long = System::currentTimeMillis,
) : CatalogSource {
    override val brandKey = "seed-chain-luckin"
    override suspend fun fetch(): SourceResult = fetchOfficial(
        client, LUCKIN_MENU_URL, OfficialPagePolicy.LUCKIN, now, ::parseLuckin,
    )
}

class MStandCatalogSource(
    private val client: PublicPageClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxWallMillis: Long = 30_000,
    private val maxTotalBytes: Int = 6 * 1024 * 1024,
    private val maxPages: Int = 10,
    private val maxCandidates: Int = 500,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : CatalogSource {
    override val brandKey = "seed-chain-mstand"
    override suspend fun fetch(): SourceResult = try {
        withTimeout(maxWallMillis) {
            val startedAt = monotonicMillis()
            fun checkWallBudget() {
                if (monotonicMillis() - startedAt > maxWallMillis) {
                    throw PublicPageException.Http(message = "M Stand 官网更新超过总时间限制")
                }
            }
            val first = client.getText(PublicPageRequest(MSTAND_CATEGORY_URL, OfficialPagePolicy.MSTAND))
            checkWallBudget()
            val page = parseMStand(first.body)
            if (page.totalPages > maxPages) throw PublicPageException.TooLarge()
            var totalBytes = first.decodedBytes
            if (totalBytes > maxTotalBytes) throw PublicPageException.TooLarge()
            val items = page.items.toMutableList()
            if (items.size > maxCandidates) throw PublicPageException.TooLarge()
            for (pageIndex in 1 until page.totalPages) {
                val response = client.postForm(
                    PublicPageRequest(MSTAND_DATA_URL, OfficialPagePolicy.MSTAND),
                    mstandPageFields(pageIndex),
                )
                checkWallBudget()
                totalBytes += response.decodedBytes
                if (totalBytes > maxTotalBytes) throw PublicPageException.TooLarge()
                items += parseMStandData(response.body)
                if (items.size > maxCandidates) throw PublicPageException.TooLarge()
            }
            ensureUniqueCandidates(items)
            SourceResult.Success(now(), first.finalUrl, items)
        }
    } catch (_: TimeoutCancellationException) {
        SourceResult.Failure(FailureKind.HTTP, "M Stand 官网更新超过总时间限制，请稍后重试。")
    } catch (error: CancellationException) {
        throw error
    } catch (error: CatalogParseException) {
        SourceResult.Failure(FailureKind.PARSE_CHANGED, error.message ?: "M Stand 页面结构已变化")
    } catch (error: PublicPageException) {
        error.toFailure()
    }
}

object MannerCatalogSource : NoPublicCatalogSource("seed-chain-manner", "Manner")
object PeetsChinaCatalogSource : NoPublicCatalogSource("seed-chain-peets", "Peet's 中国")
object ArabicaCatalogSource : NoPublicCatalogSource("seed-chain-arabica", "% Arabica")

open class NoPublicCatalogSource(
    override val brandKey: String,
    private val brandName: String,
) : CatalogSource {
    override suspend fun fetch(): SourceResult = SourceResult.Failure(
        FailureKind.NO_PUBLIC_CATALOG,
        "$brandName 暂无稳定公开产品目录，请使用截图导入或手工录入。",
    )
}

class CustomCatalogSource(
    override val brandKey: String,
    private val url: String,
    private val client: PublicPageClient,
) : CatalogSource {
    override suspend fun fetch(): SourceResult {
        if (!OfficialPagePolicy.CUSTOM.accepts(url)) {
            return SourceResult.Failure(FailureKind.HTTP, "自定义来源必须是明确配置的 HTTPS 地址")
        }
        return SourceResult.Failure(
            FailureKind.NO_PUBLIC_CATALOG,
            "自定义网页没有专用解析器，未发起网络请求；请使用截图导入或手工录入。",
        )
    }
}

fun validatePublicSourceConfiguration(mode: MaintenanceMode, rawUrl: String?): String? {
    if (mode == MaintenanceMode.MANUAL_ONLY) return null
    val url = rawUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("公开网页更新需要填写 HTTPS 产品页地址")
    if (!OfficialPagePolicy.CUSTOM.accepts(url)) {
        throw IllegalArgumentException("公开产品页必须是安全的 HTTPS 地址")
    }
    return url
}

private suspend fun fetchOfficial(
    client: PublicPageClient,
    url: String,
    policy: OfficialPagePolicy,
    now: () -> Long,
    parser: (String) -> List<CatalogCandidate>,
): SourceResult = try {
    val response = client.getText(PublicPageRequest(url, policy))
    val candidates = parser(response.body)
    if (candidates.isEmpty()) SourceResult.Failure(FailureKind.PARSE_CHANGED, "官网页面结构已变化，请改用截图或手工录入。")
    else SourceResult.Success(now(), response.finalUrl, candidates)
} catch (error: CatalogParseException) {
    SourceResult.Failure(FailureKind.PARSE_CHANGED, error.message ?: "页面结构已变化")
} catch (error: PublicPageException) {
    error.toFailure()
}

private fun parseLuckin(html: String): List<CatalogCandidate> {
    val encoded = Regex("window\\._INIT_DATA_='([^']+)'", RegexOption.DOT_MATCHES_ALL)
        .find(html)?.groupValues?.get(1) ?: throw CatalogParseException("瑞幸初始化菜单数据缺失")
    val root = try {
        Json.parseToJsonElement(encoded.decodeBase64Utf8()).jsonObject
    } catch (_: Exception) {
        throw CatalogParseException("瑞幸菜单数据无法解析")
    }
    val menu = root["menuData"] as? JsonObject ?: throw CatalogParseException("瑞幸菜单字段缺失")
    val category = menu.string("title") ?: throw CatalogParseException("瑞幸菜单分类缺失")
    val products = menu["products"] as? JsonArray ?: throw CatalogParseException("瑞幸产品列表缺失")
    val items = products.map { element ->
        val product = element as? JsonObject ?: throw CatalogParseException("瑞幸产品结构无效")
        val name = product.string("title") ?: throw CatalogParseException("瑞幸产品名称缺失")
        val image = product.string("imgUrl") ?: throw CatalogParseException("瑞幸产品图片缺失")
        CatalogCandidate(
            name = name, category = category, specificationDescription = null,
            officialDescription = product.string("desc")?.stripHtml(),
            sourceUrl = LUCKIN_MENU_URL, imageUrl = image,
        )
    }
    ensureUniqueCandidates(items)
    return items
}

private data class MStandPage(val items: List<CatalogCandidate>, val totalPages: Int)

private fun parseMStand(html: String): MStandPage {
    val blocks = html.findBlocks("li", "w-list-item")
    if (blocks.isEmpty()) throw CatalogParseException("未找到 M Stand 产品信息")
    val items = blocks.map { block ->
        val link = block.requiredAttribute("a", "w-list-link", "href").absoluteUrl("https://mstand.cn")
        if (!OfficialPagePolicy.MSTAND.accepts(link)) throw CatalogParseException("M Stand 产品链接不在允许范围")
        CatalogCandidate(
            name = block.requiredText("w-list-title"), category = "饮品",
            specificationDescription = null,
            officialDescription = block.optionalText("w-list-desc"), sourceUrl = link,
            imageUrl = block.requiredAttribute("img", "w-listpic-in", "src").absoluteUrl("https://mstand.cn"),
        )
    }
    val totalPages = Regex("jp-totalpages=[\"'](\\d+)[\"']", RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.get(1)?.toIntOrNull()
        ?: throw CatalogParseException("M Stand 分页信息缺失")
    if (totalPages !in 1..MAX_MSTAND_PAGES) throw CatalogParseException("M Stand 分页数量异常")
    ensureUniqueCandidates(items)
    return MStandPage(items, totalPages)
}

private fun parseMStandData(raw: String): List<CatalogCandidate> {
    val root = try { Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) {
        throw CatalogParseException("M Stand 分页数据无法解析")
    }
    if (root.string("IsSuccess") != "true") {
        throw CatalogParseException("M Stand 分页请求未成功")
    }
    val data = root["Data"] as? JsonArray ?: throw CatalogParseException("M Stand 分页产品缺失")
    return data.map { element ->
        val product = element as? JsonObject ?: throw CatalogParseException("M Stand 分页产品结构无效")
        val name = product.string("Name") ?: throw CatalogParseException("M Stand 产品名称缺失")
        val link = product.string("LinkUrl")?.absoluteUrl("https://mstand.cn")
            ?: throw CatalogParseException("M Stand 产品链接缺失")
        if (!OfficialPagePolicy.MSTAND.accepts(link)) throw CatalogParseException("M Stand 产品链接不在允许范围")
        CatalogCandidate(
            name, "饮品", null, product.string("Short")?.takeIf { it.isNotBlank() }, link,
            product.string("PicUrl")?.absoluteUrl("https://mstand.cn")
                ?: throw CatalogParseException("M Stand 产品图片缺失"),
        )
    }
}

private class CatalogParseException(message: String) : IllegalArgumentException(message)

private fun String.findBlocks(tag: String, cssClass: String): List<String> = Regex(
    "<$tag\\b[^>]*class=\"[^\"]*\\b${Regex.escape(cssClass)}\\b[^\"]*\"[^>]*>.*?</$tag>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
).findAll(this).map { it.value }.toList()

private fun String.requiredText(cssClass: String): String = optionalText(cssClass)
    ?: throw CatalogParseException("必填字段 $cssClass 缺失")

private fun String.optionalText(cssClass: String): String? {
    val raw = Regex(
        "<[^>]+class=\"[^\"]*\\b${Regex.escape(cssClass)}\\b[^\"]*\"[^>]*>(.*?)</[^>]+>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(this)?.groupValues?.get(1) ?: return null
    return raw.replace(Regex("<[^>]+>"), " ").decodeHtml().trim().takeIf { it.isNotEmpty() }
}

private fun String.requiredAttribute(tag: String, cssClass: String?, attribute: String): String {
    val classPart = cssClass?.let { "(?=[^>]*class=\"[^\"]*\\b${Regex.escape(it)}\\b[^\"]*\")" }.orEmpty()
    val match = Regex(
        "<$tag\\b$classPart[^>]*\\b${Regex.escape(attribute)}=\"([^\"]+)\"[^>]*>",
        RegexOption.IGNORE_CASE,
    ).find(this) ?: throw CatalogParseException("必填属性 $attribute 缺失")
    return match.groupValues[1].decodeHtml()
}

private fun String.absoluteUrl(base: String): String = URI(base).resolve(this).toString()
private fun String.stripHtml(): String = replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("<[^>]+>"), " ").decodeHtml().trim()
private fun String.decodeHtml(): String = replace("&amp;", "&").replace("&quot;", "\"")
    .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
    .replace(Regex("\\s+"), " ")

private fun PublicPageException.toFailure(): SourceResult.Failure = when (this) {
    is PublicPageException.Offline -> SourceResult.Failure(FailureKind.OFFLINE, message ?: "网络不可用")
    else -> SourceResult.Failure(FailureKind.HTTP, message ?: "官网请求失败")
}

private fun JsonObject.string(key: String): String? = try {
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
} catch (_: IllegalArgumentException) {
    throw CatalogParseException("字段 $key 类型无效")
}

private fun ensureUniqueCandidates(items: List<CatalogCandidate>) {
    if (items.isEmpty()) throw CatalogParseException("官网产品列表为空")
    if (items.map { com.niumi.coffeejournal.catalog.normalizeCatalogName(it.name) }.toSet().size != items.size) {
        throw CatalogParseException("官网产品名称重复")
    }
}

private fun mstandPageFields(pageIndex: Int): Map<String, String> = linkedMapOf(
    "dataType" to "product", "key" to "", "pageIndex" to pageIndex.toString(),
    "pageSize" to "8", "selectCategory" to "575736", "selectId" to "",
    "dateFormater" to "yyyy-MM-dd", "orderByField" to "createtime",
    "orderByType" to "desc", "templateId" to "0", "postData" to "",
    "es" to "false", "setTop" to "true",
)

private fun String.decodeBase64Utf8(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val output = ByteArrayOutputStream(length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (character in this) {
        if (character == '=') break
        if (character.isWhitespace()) continue
        val value = alphabet.indexOf(character)
        if (value < 0) throw CatalogParseException("Base64 字符无效")
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            output.write((buffer shr bits) and 0xff)
        }
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}

const val LUCKIN_MENU_URL = "https://www.luckincoffee.com/cn/menu/signature-lattes"
const val MSTAND_CATEGORY_URL = "https://mstand.cn/ProductInfoCategory?categoryId=575736"
const val MSTAND_DATA_URL = "https://mstand.cn/Designer/Common/GetData"
private const val MAX_PAGE_BYTES = 2 * 1024 * 1024
private const val MAX_FORM_BYTES = 16 * 1024
private const val MAX_MSTAND_PAGES = 20
private const val MAX_REDIRECTS = 3
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
