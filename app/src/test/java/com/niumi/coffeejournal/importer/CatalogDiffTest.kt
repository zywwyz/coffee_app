package com.niumi.coffeejournal.importer

import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogDiffTest {
    @Test
    fun `reports deterministic add modify and missing changes without deleting`() {
        val old = listOf(
            item("a", "拿铁", imageAssetId = "old-local-image", imageSourceUrl = "https://img.luckincoffee.com/old.webp", description = "旧描述"),
            item("b", "澳白"),
        )
        val fresh = listOf(
            candidate("拿铁", imageUrl = "https://img.luckincoffee.com/latte.webp", description = "新描述"),
            candidate("桂花拿铁", imageUrl = "https://img.luckincoffee.com/osmanthus.webp"),
        )

        val changes = diffCatalog(old.reversed(), fresh.reversed())

        assertEquals(
            listOf(ChangeType.POSSIBLY_DISCONTINUED, ChangeType.ADDED, ChangeType.MODIFIED),
            changes.map { it.type },
        )
        assertEquals(listOf("澳白", "桂花拿铁", "拿铁"), changes.map { it.displayName })
        val modified = changes.single { it.type == ChangeType.MODIFIED }
        assertEquals(
            listOf(
                FieldChange("officialDescription", "旧描述", "新描述"),
                FieldChange("imageUrl", "https://img.luckincoffee.com/old.webp", "https://img.luckincoffee.com/latte.webp"),
            ),
            modified.fields,
        )
        assertEquals("old-local-image", modified.oldItem?.imageAssetId)
        assertNull(changes.single { it.type == ChangeType.ADDED }.oldItem)
    }

    @Test
    fun `normalizes compatible unicode and whitespace when matching names`() {
        val changes = diffCatalog(
            listOf(item("a", "Ｍ  Stand\u3000拿铁")),
            listOf(candidate("m stand 拿铁")),
        )

        assertEquals(emptyList<CatalogChange>(), changes)
    }

    @Test
    fun `absent official fields do not erase local enrichment and additions omit empty diffs`() {
        val enriched = item("a", "拿铁").copy(origin = "用户记录的埃塞俄比亚")

        assertEquals(emptyList<CatalogChange>(), diffCatalog(listOf(enriched), listOf(candidate("拿铁"))))
        val addition = diffCatalog(emptyList(), listOf(candidate("新品"))).single()
        assertTrue(addition.fields.all { it.newValue != null })
    }

    @Test
    fun `reappearing discontinued product is reviewable while archived and already missing rows stay untouched`() {
        val discontinued = item("a", "回归拿铁").copy(status = ItemStatus.DISCONTINUED)
        val archived = item("b", "用户归档").copy(status = ItemStatus.ARCHIVED)
        val stillMissing = item("c", "已下架").copy(status = ItemStatus.DISCONTINUED)

        val changes = diffCatalog(listOf(discontinued, archived, stillMissing), listOf(candidate("回归拿铁")))

        assertEquals(1, changes.size)
        assertEquals(ChangeType.MODIFIED, changes.single().type)
        assertEquals(listOf(FieldChange("status", "DISCONTINUED", "ACTIVE")), changes.single().fields)
    }

    private fun item(
        id: String,
        name: String,
        imageAssetId: String? = null,
        imageSourceUrl: String? = null,
        description: String? = null,
    ) = CatalogItem(
        id = id,
        brandId = "brand",
        type = ItemType.CHAIN_PRODUCT,
        name = name,
        imageAssetId = imageAssetId,
        origin = null,
        processing = null,
        roastLevel = null,
        flavorNotes = null,
        brewMethod = null,
        status = ItemStatus.ACTIVE,
        officialDescription = description,
        imageSourceUrl = imageSourceUrl,
    )

    private fun candidate(
        name: String,
        imageUrl: String? = null,
        description: String? = null,
    ) = CatalogCandidate(
        name = name,
        category = null,
        specificationDescription = null,
        officialDescription = description,
        sourceUrl = "https://www.luckincoffee.com/cn/menu/signature-lattes",
        imageUrl = imageUrl,
    )
}
