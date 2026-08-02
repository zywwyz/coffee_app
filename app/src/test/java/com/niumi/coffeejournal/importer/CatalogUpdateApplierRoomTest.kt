package com.niumi.coffeejournal.importer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.niumi.coffeejournal.catalog.normalizeCatalogName
import com.niumi.coffeejournal.core.database.BrandEntity
import com.niumi.coffeejournal.core.database.CatalogItemEntity
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.database.DrinkRecordEntity
import com.niumi.coffeejournal.core.database.ImageAssetEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogUpdateApplierRoomTest {
    private lateinit var database: CoffeeDatabase

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), CoffeeDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.brandDao().insert(brand())
        database.catalogItemDao().insert(item("old", "拿铁", description = "旧描述"))
    }

    @After fun tearDown() { database.close() }

    @Test
    fun `review causes zero writes until selected changes are explicitly confirmed`() = runBlocking {
        val importer = FakeOfficialImageImporter()
        val applier = CatalogUpdateApplier(database, importer, idGenerator = { "new-id" })
        val fetched = success(candidate("拿铁", description = "新描述"), candidate("新品"))

        val review = applier.review("brand", fetched)

        assertEquals(2, review.changes.size)
        assertEquals("旧描述", database.catalogItemDao().get("old")?.officialDescription)
        assertNull(database.catalogItemDao().get("new-id"))
        assertTrue(importer.imported.isEmpty())
        assertNull(database.catalogUpdateDao().latest("brand"))
    }

    @Test
    fun `confirmation applies only checked changes and records confirmed fetch time`() = runBlocking {
        database.imageAssetDao().upsert(image("asset-new", "1"))
        val importer = FakeOfficialImageImporter(mapOf("https://img.official/new.webp" to "asset-new"))
        val applier = CatalogUpdateApplier(database, importer, idGenerator = { "new-id" })
        val review = applier.review(
            "brand", success(
                candidate("拿铁", description = "新描述"),
                candidate("新品", imageUrl = "https://img.official/new.webp"),
            ),
        )

        val addition = review.changes.single { it.displayName == "新品" }
        applier.applySelected(review, setOf(addition.key))

        assertEquals("旧描述", database.catalogItemDao().get("old")?.officialDescription)
        val added = database.catalogItemDao().get("new-id")
        assertEquals("asset-new", added?.imageAssetId)
        assertEquals("https://img.official/new.webp", added?.imageSourceUrl)
        assertEquals(777L, added?.sourceFetchedAt)
        assertEquals("CONFIRMED", database.catalogUpdateDao().latest("brand")?.status)
        assertEquals(777L, database.catalogUpdateDao().latest("brand")?.fetchedAtEpochMillis)
    }

    @Test
    fun `failed image keeps valid old image while new image failure marks needs image`() = runBlocking {
        database.imageAssetDao().upsert(image("old-image", "2"))
        database.catalogItemDao().update(item("old", "拿铁", imageAssetId = "old-image"))
        val importer = FakeOfficialImageImporter(failing = true)
        val applier = CatalogUpdateApplier(database, importer, idGenerator = { "new-id" })
        val review = applier.review(
            "brand", success(
                candidate("拿铁", description = "新", imageUrl = "https://img.official/replacement.webp"),
                candidate("新品", imageUrl = "https://img.official/missing.webp"),
            ),
        )

        applier.applySelected(review, review.changes.map { it.key }.toSet())

        assertEquals("old-image", database.catalogItemDao().get("old")?.imageAssetId)
        assertEquals("ACTIVE", database.catalogItemDao().get("old")?.status)
        assertEquals("NEEDS_IMAGE", database.catalogItemDao().get("new-id")?.status)
    }

    @Test
    fun `official modification preserves locally enriched fields absent from page`() = runBlocking {
        database.catalogItemDao().update(item("old", "拿铁", description = "旧描述", origin = "用户记录产地"))
        val applier = CatalogUpdateApplier(database, FakeOfficialImageImporter())
        val review = applier.review("brand", success(candidate("拿铁", description = "官网新描述")))

        applier.applySelected(review, review.changes.map { it.key }.toSet())

        assertEquals("官网新描述", database.catalogItemDao().get("old")?.officialDescription)
        assertEquals("用户记录产地", database.catalogItemDao().get("old")?.origin)
        assertEquals(42, database.catalogItemDao().get("old")?.informationCompleteness)
    }

    @Test
    fun `confirmation refuses to overwrite a catalog item edited after review`() = runBlocking {
        val applier = CatalogUpdateApplier(database, FakeOfficialImageImporter())
        val review = applier.review("brand", success(candidate("拿铁", description = "官网新描述")))
        database.catalogItemDao().update(item("old", "拿铁", description = "用户刚刚编辑"))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { applier.applySelected(review, review.changes.map { it.key }.toSet()) }
        }

        assertEquals("用户刚刚编辑", database.catalogItemDao().get("old")?.officialDescription)
        assertNull(database.catalogUpdateDao().latest("brand"))
    }

    @Test
    fun `transaction failure rolls back rows and confirmed update and cleans imported assets`() = runBlocking {
        database.imageAssetDao().upsert(image("asset-a", "3"))
        database.imageAssetDao().upsert(image("asset-b", "4"))
        val importer = FakeOfficialImageImporter(
            mapOf(
                "https://img.official/a.webp" to "asset-a",
                "https://img.official/b.webp" to "asset-b",
            ),
        )
        val applier = CatalogUpdateApplier(database, importer, idGenerator = { "duplicate-id" })
        val review = applier.review(
            "brand", success(
                candidate("新品 A", imageUrl = "https://img.official/a.webp"),
                candidate("新品 B", imageUrl = "https://img.official/b.webp"),
            ),
        )

        assertThrows(Exception::class.java) {
            runBlocking { applier.applySelected(review, review.changes.map { it.key }.toSet()) }
        }

        assertNull(database.catalogItemDao().get("duplicate-id"))
        assertNull(database.catalogUpdateDao().latest("brand"))
        assertEquals(setOf("asset-a", "asset-b"), importer.cleaned.toSet())
    }

    @Test
    fun `cancellation during image preparation cleans assets already imported`() = runBlocking {
        database.imageAssetDao().upsert(image("asset-a", "5"))
        val importer = FakeOfficialImageImporter(
            assets = mapOf("https://img.official/a.webp" to "asset-a"),
            cancelOnUrl = "https://img.official/b.webp",
        )
        val applier = CatalogUpdateApplier(database, importer)
        val review = applier.review(
            "brand", success(
                candidate("新品 A", imageUrl = "https://img.official/a.webp"),
                candidate("新品 B", imageUrl = "https://img.official/b.webp"),
            ),
        )

        assertThrows(CancellationException::class.java) {
            runBlocking { applier.applySelected(review, review.changes.map { it.key }.toSet()) }
        }

        assertEquals(listOf("asset-a"), importer.cleaned)
        assertNull(database.catalogUpdateDao().latest("brand"))
    }

    @Test
    fun `cancellation at imported asset delivery boundary cleans delivered asset`() = runBlocking {
        database.imageAssetDao().upsert(image("asset-boundary", "6"))
        val importer = FakeOfficialImageImporter(
            assets = mapOf("https://img.official/a.webp" to "asset-boundary"),
        )
        val applier = CatalogUpdateApplier(
            database, importer,
            afterImageDelivered = {
                currentCoroutineContext().cancel()
                yield()
            },
        )
        val review = applier.review("brand", success(candidate("新品 A", imageUrl = "https://img.official/a.webp")))

        assertThrows(CancellationException::class.java) {
            runBlocking { applier.applySelected(review, review.changes.map { it.key }.toSet()) }
        }

        assertEquals(listOf("asset-boundary"), importer.cleaned)
        assertNull(database.catalogUpdateDao().latest("brand"))
    }

    @Test
    fun `discontinued selection marks without delete and leaves history snapshot unchanged`() = runBlocking {
        database.drinkDao().insert(
            DrinkRecordEntity(
                id = "record", occurredAtEpochMillis = 1, localDate = "2026-08-02",
                itemType = "CHAIN_PRODUCT", sourceItemId = "old", snapshotBrandName = "瑞幸",
                snapshotItemName = "历史拿铁", snapshotImageAssetId = null,
            ),
        )
        val applier = CatalogUpdateApplier(database, FakeOfficialImageImporter())
        val review = applier.review("brand", success(candidate("别的产品")))
        val missing = review.changes.single { it.type == ChangeType.POSSIBLY_DISCONTINUED }

        applier.applySelected(review, setOf(missing.key))

        assertNotNull(database.catalogItemDao().get("old"))
        assertEquals("DISCONTINUED", database.catalogItemDao().get("old")?.status)
        assertEquals(777L, database.catalogItemDao().get("old")?.sourceFetchedAt)
        assertEquals(
            "https://www.luckincoffee.com/cn/menu/signature-lattes",
            database.catalogItemDao().get("old")?.sourceUrl,
        )
        assertEquals("历史拿铁", database.drinkDao().get("record")?.snapshotItemName)
    }

    private fun success(vararg candidates: CatalogCandidate) = SourceResult.Success(
        fetchedAt = 777L,
        sourceUrl = "https://www.luckincoffee.com/cn/menu/signature-lattes",
        items = candidates.toList(),
    )

    private fun candidate(name: String, description: String? = null, imageUrl: String? = null) = CatalogCandidate(
        name, "咖啡", null, description,
        "https://www.luckincoffee.com/cn/menu/signature-lattes/${name.hashCode()}", imageUrl,
    )

    private fun brand() = BrandEntity(
        "brand", "CHAIN", "瑞幸", normalizeCatalogName("瑞幸"), null,
        "PUBLIC_SOURCE", "https://www.luckincoffee.com/cn/menu/signature-lattes",
    )

    private fun item(
        id: String,
        name: String,
        description: String? = null,
        imageAssetId: String? = null,
        origin: String? = null,
    ) = CatalogItemEntity(
        id = id, brandId = "brand", type = "CHAIN_PRODUCT", name = name,
        normalizedName = normalizeCatalogName(name), imageAssetId = imageAssetId,
        status = "ACTIVE", officialDescription = description, origin = origin,
    )

    private fun image(id: String, suffix: String) = ImageAssetEntity(
        id = id, localPath = "/tmp/$id.webp", sha256 = suffix.repeat(64),
        kind = "PRODUCT", createdAtEpochMillis = 1,
    )
}

private class FakeOfficialImageImporter(
    private val assets: Map<String, String> = emptyMap(),
    private val failing: Boolean = false,
    private val cancelOnUrl: String? = null,
) : OfficialImageImporter {
    val imported = mutableListOf<String>()
    val cleaned = mutableListOf<String>()
    override suspend fun importOfficialImage(brandId: String, imageUrl: String): String {
        imported += imageUrl
        if (imageUrl == cancelOnUrl) throw CancellationException("cancel")
        if (failing) throw OfficialImageException("download failed")
        return checkNotNull(assets[imageUrl])
    }
    override suspend fun cleanup(assetId: String) { cleaned += assetId }
}
