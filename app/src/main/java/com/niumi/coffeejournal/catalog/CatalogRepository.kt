package com.niumi.coffeejournal.catalog

import com.niumi.coffeejournal.core.database.BrandDao
import com.niumi.coffeejournal.core.database.BrandEntity
import com.niumi.coffeejournal.core.database.CatalogItemDao
import com.niumi.coffeejournal.core.database.CatalogItemEntity
import com.niumi.coffeejournal.core.database.DataIntegrityException
import com.niumi.coffeejournal.core.database.DrinkDao
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface CatalogRepository {
    fun observeBrands(type: BrandType): Flow<List<Brand>>
    fun observeItems(brandId: String): Flow<List<CatalogItem>>
    suspend fun getItem(itemId: String): CatalogItem
    suspend fun upsertBrand(brand: Brand)
    suspend fun upsertItem(item: CatalogItem)
    suspend fun lastPriceFen(itemId: String): Long?
}

class CatalogItemNotFoundException(itemId: String) :
    NoSuchElementException("Catalog item '$itemId' was not found")

class RoomCatalogRepository(
    private val brandDao: BrandDao,
    private val catalogItemDao: CatalogItemDao,
    private val drinkDao: DrinkDao,
) : CatalogRepository {
    override fun observeBrands(type: BrandType): Flow<List<Brand>> =
        brandDao.observe().map { entities ->
            entities.map(BrandEntity::toDomain).filter { it.type == type }
        }

    override fun observeItems(brandId: String): Flow<List<CatalogItem>> =
        catalogItemDao.observeByBrand(brandId).map { entities ->
            entities.map(CatalogItemEntity::toDomain)
        }

    override suspend fun getItem(itemId: String): CatalogItem =
        catalogItemDao.get(itemId)?.toDomain()
            ?: throw CatalogItemNotFoundException(itemId)

    override suspend fun upsertBrand(brand: Brand) {
        brandDao.upsert(brand.toEntity())
    }

    override suspend fun upsertItem(item: CatalogItem) {
        catalogItemDao.upsert(item.toEntity())
    }

    override suspend fun lastPriceFen(itemId: String): Long? =
        drinkDao.lastActualPriceFen(itemId)
}

private fun BrandEntity.toDomain() = Brand(
    id = id,
    type = enumValue("BrandEntity.type", type),
    name = name,
    logoAssetId = logoAssetId,
    maintenanceMode = enumValue("BrandEntity.maintenanceMode", maintenanceMode),
    publicSourceUrl = publicSourceUrl,
)

private fun Brand.toEntity() = BrandEntity(
    id = id,
    type = type.name,
    name = name,
    logoAssetId = logoAssetId,
    maintenanceMode = maintenanceMode.name,
    publicSourceUrl = publicSourceUrl,
)

private fun CatalogItemEntity.toDomain() = CatalogItem(
    id = id,
    brandId = brandId,
    type = enumValue("CatalogItemEntity.type", type),
    name = name,
    imageAssetId = imageAssetId,
    origin = origin,
    processing = processing,
    roastLevel = roastLevel,
    flavorNotes = flavorNotes,
    brewMethod = brewMethod,
    status = enumValue("CatalogItemEntity.status", status),
    caffeineMg = caffeineMg,
    officialDescription = officialDescription,
    purchaseDate = purchaseDate,
    roastDate = roastDate,
    sourceUrl = sourceUrl,
    sourceFetchedAt = sourceFetchedAt,
    informationCompleteness = informationCompleteness,
)

private fun CatalogItem.toEntity() = CatalogItemEntity(
    id = id,
    brandId = brandId,
    type = type.name,
    name = name,
    normalizedName = name.trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " "),
    imageAssetId = imageAssetId,
    origin = origin,
    processing = processing,
    roastLevel = roastLevel,
    flavorNotes = flavorNotes,
    brewMethod = brewMethod,
    status = status.name,
    caffeineMg = caffeineMg,
    officialDescription = officialDescription,
    purchaseDate = purchaseDate,
    roastDate = roastDate,
    sourceUrl = sourceUrl,
    sourceFetchedAt = sourceFetchedAt,
    informationCompleteness = informationCompleteness,
)

private inline fun <reified T : Enum<T>> enumValue(field: String, value: String): T =
    try {
        enumValueOf<T>(value)
    } catch (_: IllegalArgumentException) {
        throw DataIntegrityException(field, value)
    }
