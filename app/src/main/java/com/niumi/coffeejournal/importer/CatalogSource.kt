package com.niumi.coffeejournal.importer

interface CatalogSource {
    val brandKey: String
    suspend fun fetch(): SourceResult
}

sealed interface SourceResult {
    data class Success(
        val fetchedAt: Long,
        val sourceUrl: String,
        val items: List<CatalogCandidate>,
    ) : SourceResult

    data class Failure(
        val kind: FailureKind,
        val message: String,
    ) : SourceResult
}

enum class FailureKind { OFFLINE, HTTP, PARSE_CHANGED, NO_PUBLIC_CATALOG }

data class CatalogCandidate(
    val name: String,
    val category: String?,
    val specificationDescription: String?,
    val officialDescription: String?,
    val sourceUrl: String,
    val imageUrl: String?,
    val origin: String? = null,
    val processing: String? = null,
    val roastLevel: String? = null,
    val flavorNotes: String? = null,
    val caffeineMg: Double? = null,
)
