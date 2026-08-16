package com.niumi.coffeejournal.importer

import androidx.room.withTransaction
import com.niumi.coffeejournal.catalog.normalizeCatalogName
import com.niumi.coffeejournal.core.database.CatalogItemEntity
import com.niumi.coffeejournal.core.database.CatalogUpdateEntity
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.ChainProductKind
import com.niumi.coffeejournal.core.model.legacyChainProductKind
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class CatalogReview(
    val brandId: String,
    val fetchedAt: Long,
    val sourceUrl: String,
    val changes: List<CatalogChange>,
)

data class ApplyCatalogResult(
    val appliedCount: Int,
    val imageFallbackCount: Int,
)

interface OfficialImageImporter {
    suspend fun importOfficialImage(brandId: String, imageUrl: String): String
    suspend fun cleanup(assetId: String)
}

class OfficialImageException(message: String) : IllegalArgumentException(message)

interface CatalogUpdateGateway {
    suspend fun review(brandId: String, result: SourceResult.Success): CatalogReview
    suspend fun applySelected(review: CatalogReview, selectedKeys: Set<String>): ApplyCatalogResult
}

class CatalogUpdateApplier(
    private val database: CoffeeDatabase,
    private val imageImporter: OfficialImageImporter,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val afterImageDelivered: suspend (String) -> Unit = {},
) : CatalogUpdateGateway {
    override suspend fun review(brandId: String, result: SourceResult.Success): CatalogReview {
        val current = database.catalogItemDao().observeByBrand(brandId).first().map(CatalogItemEntity::toDomain)
        return CatalogReview(brandId, result.fetchedAt, result.sourceUrl, diffCatalog(current, result.items))
    }

    override suspend fun applySelected(review: CatalogReview, selectedKeys: Set<String>): ApplyCatalogResult {
        require(selectedKeys.all { selected -> review.changes.any { it.key == selected } }) {
            "Selection contains a change outside this review"
        }
        val selected = review.changes.filter { it.key in selectedKeys }.filterNot { change ->
            change.type == ChangeType.ADDED && legacyChainProductKind(requireNotNull(change.candidate).name, change.candidate.category) == ChainProductKind.PENDING
        }
        val importedByKey = mutableMapOf<String, ImportedCandidateImage>()
        val deliveredAssets = mutableSetOf<String>()
        val oldAssetsToRelease = mutableSetOf<String>()
        var fallbackCount = 0
        try {
            for (change in selected) {
                val candidate = change.candidate ?: continue
                val imageUrl = candidate.imageUrl ?: continue
                if (imageUrl == change.oldItem?.imageSourceUrl) continue
                val imported = try {
                    imageImporter.importOfficialImage(review.brandId, imageUrl)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    fallbackCount++
                    null
                }
                imported?.let { assetId ->
                    deliveredAssets += assetId
                    afterImageDelivered(assetId)
                    importedByKey[change.key] = ImportedCandidateImage(assetId, imageUrl)
                }
            }
            database.withTransaction {
                selected.forEach { change ->
                    when (change.type) {
                        ChangeType.ADDED -> {
                            val candidate = requireNotNull(change.candidate)
                            val imported = importedByKey[change.key]
                            database.catalogItemDao().insert(
                                candidate.toEntity(
                                    id = idGenerator(),
                                    brandId = review.brandId,
                                    fetchedAt = review.fetchedAt,
                                    imported = imported,
                                    old = null,
                                ),
                            )
                        }
                        ChangeType.MODIFIED -> {
                            val old = requireNotNull(change.oldItem)
                            check(database.catalogItemDao().get(old.id)?.toDomain() == old) {
                                "Catalog item changed after review"
                            }
                            val imported = importedByKey[change.key]
                            database.catalogItemDao().update(
                                requireNotNull(change.candidate).toEntity(
                                    id = old.id,
                                    brandId = old.brandId,
                                    fetchedAt = review.fetchedAt,
                                    imported = imported,
                                    old = old,
                                ),
                            ).also { check(it == 1) { "Catalog item changed before confirmation" } }
                            if (imported != null) old.imageAssetId?.takeIf { it != imported.assetId }?.let(oldAssetsToRelease::add)
                        }
                        ChangeType.POSSIBLY_DISCONTINUED -> {
                            val old = requireNotNull(change.oldItem)
                            check(database.catalogItemDao().get(old.id)?.toDomain() == old) {
                                "Catalog item changed after review"
                            }
                            database.catalogItemDao().update(
                                old.copy(
                                    status = ItemStatus.DISCONTINUED,
                                    sourceUrl = review.sourceUrl,
                                    sourceFetchedAt = review.fetchedAt,
                                ).toEntity(),
                            )
                                .also { check(it == 1) { "Catalog item changed before confirmation" } }
                        }
                    }
                }
                database.catalogUpdateDao().insert(
                    CatalogUpdateEntity(
                        id = idGenerator(), brandId = review.brandId,
                        fetchedAtEpochMillis = review.fetchedAt, status = "CONFIRMED",
                        sourceUrl = review.sourceUrl, errorMessage = null,
                    ),
                )
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                deliveredAssets.forEach { assetId ->
                    runCatching { imageImporter.cleanup(assetId) }
                }
            }
            throw error
        }
        withContext(NonCancellable) {
            oldAssetsToRelease.forEach { assetId -> runCatching { imageImporter.cleanup(assetId) } }
        }
        return ApplyCatalogResult(selected.size, fallbackCount)
    }
}

private data class ImportedCandidateImage(val assetId: String, val sourceUrl: String)

private fun CatalogCandidate.toEntity(
    id: String,
    brandId: String,
    fetchedAt: Long,
    imported: ImportedCandidateImage?,
    old: CatalogItem?,
): CatalogItemEntity {
    val retainedImage = imported?.assetId ?: old?.imageAssetId
    val retainedImageSource = imported?.sourceUrl ?: old?.imageSourceUrl
    val status = when {
        retainedImage == null -> ItemStatus.NEEDS_IMAGE
        old?.status == ItemStatus.ARCHIVED -> ItemStatus.ARCHIVED
        else -> ItemStatus.ACTIVE
    }
    return CatalogItemEntity(
        id = id, brandId = brandId, type = ItemType.CHAIN_PRODUCT.name,
        name = name, normalizedName = normalizeCatalogName(name), imageAssetId = retainedImage,
        origin = origin ?: old?.origin, processing = processing ?: old?.processing,
        roastLevel = roastLevel ?: old?.roastLevel,
        flavorNotes = flavorNotes ?: old?.flavorNotes, brewMethod = old?.brewMethod, status = status.name,
        caffeineMg = caffeineMg ?: old?.caffeineMg,
        officialDescription = officialDescription ?: old?.officialDescription,
        sourceUrl = sourceUrl, sourceFetchedAt = fetchedAt,
        informationCompleteness = calculateCompleteness(old), category = category ?: old?.category,
        specificationDescription = specificationDescription ?: old?.specificationDescription,
        imageSourceUrl = retainedImageSource,
        chainProductKind = old?.chainProductKind?.name ?: legacyChainProductKind(name, category).name,
    )
}

private fun CatalogCandidate.calculateCompleteness(old: CatalogItem?): Int {
    val fields = listOf(
        category ?: old?.category,
        specificationDescription ?: old?.specificationDescription,
        officialDescription ?: old?.officialDescription,
        origin ?: old?.origin,
        processing ?: old?.processing,
        roastLevel ?: old?.roastLevel,
        flavorNotes ?: old?.flavorNotes,
    )
    return (fields.count { !it.isNullOrBlank() } * 100 / fields.size)
}

private fun CatalogItemEntity.toDomain() = CatalogItem(
    id, brandId, ItemType.valueOf(type), name, imageAssetId, origin, processing, roastLevel,
    flavorNotes, brewMethod, ItemStatus.valueOf(status), caffeineMg, officialDescription,
    purchaseDate, roastDate, sourceUrl, sourceFetchedAt, informationCompleteness,
    category, specificationDescription, imageSourceUrl,
    chainProductKind?.let { ChainProductKind.valueOf(it) },
)

private fun CatalogItem.toEntity() = CatalogItemEntity(
    id, brandId, type.name, name, normalizeCatalogName(name), imageAssetId, origin, processing,
    roastLevel, flavorNotes, brewMethod, status.name, caffeineMg, officialDescription,
    purchaseDate, roastDate, sourceUrl, sourceFetchedAt, informationCompleteness,
    category, specificationDescription, imageSourceUrl,
    chainProductKind = chainProductKind?.name,
)
