package com.niumi.coffeejournal.importer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import com.niumi.coffeejournal.core.model.MaintenanceMode

class OfficialSourcesTest {
    @Test
    fun `luckin fixture yields only explicitly published fields`() = runBlocking {
        val source = LuckinCatalogSource(
            client = FakePageClient(fixture("luckin-products.html")),
            now = { 1234L },
        )

        val result = source.fetch() as SourceResult.Success

        assertEquals(1234L, result.fetchedAt)
        assertEquals(listOf("Coconut Latte", "Velvet Latte"), result.items.map { it.name })
        assertEquals("Signature Lattes", result.items.first().category)
        assertEquals(null, result.items.first().specificationDescription)
        assertEquals("https://www.luckincoffee.com/cn/menu/signature-lattes", result.items.first().sourceUrl)
        assertEquals("https://ilucky-fe-outside-oss-prod.luckincdn.com/iadmin/coconut.jpg", result.items.first().imageUrl)
        assertTrue(result.items.all { it.origin == null && it.processing == null })
    }

    @Test
    fun `mstand fixture yields official detail page`() = runBlocking {
        val result = MStandCatalogSource(
            client = FakePageClient(fixture("mstand-product.html")),
            now = { 9L },
        ).fetch() as SourceResult.Success

        assertEquals(listOf("鲜椰冰咖"), result.items.map { it.name })
        assertEquals("https://mstand.cn/productinfo/756215.html", result.items.single().sourceUrl)
        assertEquals("饮品", result.items.single().category)
    }

    @Test
    fun `mstand follows official public pagination without treating later products as missing`() = runBlocking {
        val firstPage = fixture("mstand-product.html").replace("jp-totalpages=\"1\"", "jp-totalpages=\"2\"")
        val client = FakePageClient(firstPage, postBody = fixture("mstand-page2.json"))

        val result = MStandCatalogSource(client, now = { 9L }).fetch() as SourceResult.Success

        assertEquals(listOf("鲜椰冰咖", "海盐芝士黑咖"), result.items.map { it.name })
        assertEquals(1, client.postCalls)
    }

    @Test
    fun `missing required product structure is parse changed`() = runBlocking {
        val result = LuckinCatalogSource(FakePageClient("<html>新版页面</html>"), now = { 1L }).fetch()

        assertEquals(FailureKind.PARSE_CHANGED, (result as SourceResult.Failure).kind)
    }

    @Test
    fun `offline and http failures remain distinct`() = runBlocking {
        val offline = LuckinCatalogSource(FakePageClient(error = PublicPageException.Offline()), now = { 1L }).fetch()
        val http = LuckinCatalogSource(FakePageClient(error = PublicPageException.Http(503)), now = { 1L }).fetch()

        assertEquals(FailureKind.OFFLINE, (offline as SourceResult.Failure).kind)
        assertEquals(FailureKind.HTTP, (http as SourceResult.Failure).kind)
    }

    @Test
    fun `brands without stable public catalogs explicitly fall back`() = runBlocking {
        listOf(MannerCatalogSource, PeetsChinaCatalogSource, ArabicaCatalogSource).forEach { source ->
            val result = source.fetch() as SourceResult.Failure
            assertEquals(FailureKind.NO_PUBLIC_CATALOG, result.kind)
            assertTrue(result.message.contains("截图") && result.message.contains("手工"))
        }
    }

    @Test
    fun `official request policy permits only exact https hosts and paths`() {
        assertTrue(OfficialPagePolicy.LUCKIN.accepts("https://www.luckincoffee.com/cn/menu/signature-lattes"))
        assertFalse(OfficialPagePolicy.LUCKIN.accepts("http://www.luckincoffee.com/cn/menu/signature-lattes"))
        assertFalse(OfficialPagePolicy.LUCKIN.accepts("https://evil.example/?next=https://www.luckincoffee.com/cn/menu/a"))
        assertFalse(OfficialPagePolicy.LUCKIN.accepts("https://www.luckincoffee.com.evil.example/cn/menu/a"))
        assertFalse(OfficialPagePolicy.LUCKIN.accepts("https://www.luckincoffee.com/login"))
        assertTrue(OfficialPagePolicy.MSTAND.accepts("https://mstand.cn/ProductInfoCategory?categoryId=575736"))
    }

    @Test
    fun `custom public source only accepts explicit https url and never guesses parser`() = runBlocking {
        assertEquals(FailureKind.HTTP, (CustomCatalogSource("custom", "http://example.com/menu", FakePageClient("x")).fetch() as SourceResult.Failure).kind)
        assertEquals(FailureKind.PARSE_CHANGED, (CustomCatalogSource("custom", "https://example.com/menu", FakePageClient("<html></html>")).fetch() as SourceResult.Failure).kind)
    }

    @Test
    fun `public maintenance configuration requires an explicit safe https url`() {
        assertEquals(null, validatePublicSourceConfiguration(MaintenanceMode.MANUAL_ONLY, "http://example.com"))
        assertEquals("https://example.com/menu", validatePublicSourceConfiguration(MaintenanceMode.PUBLIC_SOURCE, " https://example.com/menu "))
        assertThrows(IllegalArgumentException::class.java) {
            validatePublicSourceConfiguration(MaintenanceMode.PUBLIC_SOURCE, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatePublicSourceConfiguration(MaintenanceMode.PUBLIC_SOURCE, "https://user:pass@example.com/menu")
        }
    }

    private fun fixture(name: String): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("sources/$name"),
    ).bufferedReader().use { it.readText() }
}

private class FakePageClient(
    private val body: String = "",
    private val error: PublicPageException? = null,
    private val postBody: String = "",
) : PublicPageClient {
    var postCalls = 0
    override suspend fun getText(request: PublicPageRequest): PublicPageResponse {
        error?.let { throw it }
        return PublicPageResponse(request.url, body)
    }
    override suspend fun postForm(request: PublicPageRequest, fields: Map<String, String>): PublicPageResponse {
        postCalls++
        return PublicPageResponse(request.url, postBody)
    }
}
