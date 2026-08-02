package com.niumi.coffeejournal.importer

import com.niumi.coffeejournal.catalog.normalizeCatalogName
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemStatus

enum class ChangeType { ADDED, MODIFIED, POSSIBLY_DISCONTINUED }

data class FieldChange(val field: String, val oldValue: String?, val newValue: String?)

data class CatalogChange(
    val key: String,
    val type: ChangeType,
    val displayName: String,
    val oldItem: CatalogItem?,
    val candidate: CatalogCandidate?,
    val fields: List<FieldChange>,
)

fun diffCatalog(old: List<CatalogItem>, fresh: List<CatalogCandidate>): List<CatalogChange> {
    val oldByName = old.associateBy { normalizeCatalogName(it.name) }
    val freshByName = fresh.associateBy { normalizeCatalogName(it.name) }
    val keys = (oldByName.keys + freshByName.keys).sorted()
    return keys.mapNotNull { key ->
        val previous = oldByName[key]
        val candidate = freshByName[key]
        when {
            previous == null && candidate != null -> CatalogChange(
                key, ChangeType.ADDED, candidate.name, null, candidate,
                candidate.fieldValues().mapNotNull { (field, value) ->
                    value?.let { FieldChange(field, null, it) }
                },
            )
            previous != null && candidate == null -> previous
                .takeIf { it.status == ItemStatus.ACTIVE || it.status == ItemStatus.NEEDS_IMAGE }
                ?.let {
                    CatalogChange(
                        key, ChangeType.POSSIBLY_DISCONTINUED, previous.name, previous, null,
                        listOf(FieldChange("status", previous.status.name, "DISCONTINUED")),
                    )
                }
            previous != null && candidate != null -> {
                val oldFields = previous.fieldValues()
                val newFields = candidate.fieldValues()
                val fieldChanges = FIELD_ORDER.mapNotNull { field ->
                    val before = oldFields[field]
                    val after = newFields[field]
                    if (after == null) return@mapNotNull null
                    val equivalent = if (field == "name" && before != null) {
                        normalizeCatalogName(before) == normalizeCatalogName(after)
                    } else before == after
                    FieldChange(field, before, after).takeUnless { equivalent }
                }
                val changes = fieldChanges + if (previous.status == ItemStatus.DISCONTINUED) {
                    listOf(FieldChange("status", "DISCONTINUED", "ACTIVE"))
                } else emptyList()
                changes.takeIf { it.isNotEmpty() }?.let {
                    CatalogChange(key, ChangeType.MODIFIED, candidate.name, previous, candidate, it)
                }
            }
            else -> null
        }
    }.sortedWith(compareBy<CatalogChange>({ it.type.reviewOrder }, { normalizeCatalogName(it.displayName) }))
}

private val ChangeType.reviewOrder: Int
    get() = when (this) {
        ChangeType.POSSIBLY_DISCONTINUED -> 0
        ChangeType.ADDED -> 1
        ChangeType.MODIFIED -> 2
    }

private val FIELD_ORDER = listOf(
    "name", "category", "specificationDescription", "officialDescription", "origin",
    "processing", "roastLevel", "flavorNotes", "caffeineMg", "imageUrl",
)

private fun CatalogItem.fieldValues(): Map<String, String?> = linkedMapOf(
    "name" to name,
    "category" to category,
    "specificationDescription" to specificationDescription,
    "officialDescription" to officialDescription,
    "origin" to origin,
    "processing" to processing,
    "roastLevel" to roastLevel,
    "flavorNotes" to flavorNotes,
    "caffeineMg" to caffeineMg?.toString(),
    "imageUrl" to imageSourceUrl,
)

private fun CatalogCandidate.fieldValues(): Map<String, String?> = linkedMapOf(
    "name" to name,
    "category" to category,
    "specificationDescription" to specificationDescription,
    "officialDescription" to officialDescription,
    "origin" to origin,
    "processing" to processing,
    "roastLevel" to roastLevel,
    "flavorNotes" to flavorNotes,
    "caffeineMg" to caffeineMg?.toString(),
    "imageUrl" to imageUrl,
)
