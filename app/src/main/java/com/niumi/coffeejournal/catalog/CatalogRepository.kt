package com.niumi.coffeejournal.catalog

import com.niumi.coffeejournal.core.database.BrandDao
import com.niumi.coffeejournal.core.database.BrandEntity
import com.niumi.coffeejournal.core.database.BrandOverviewRow
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
import java.text.Normalizer
import java.util.Locale
import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface CatalogRepository {
    fun observeBrands(type: BrandType): Flow<List<Brand>>
    fun observeItems(brandId: String): Flow<List<CatalogItem>>
    fun observeBrandOverviews(type: BrandType): Flow<List<BrandOverview>> =
        observeBrands(type).map { brands -> brands.map { BrandOverview(it, 0, null) } }
    suspend fun getBrand(brandId: String): Brand
    suspend fun getItem(itemId: String): CatalogItem
    suspend fun upsertBrand(brand: Brand)
    suspend fun upsertItem(item: CatalogItem)
    suspend fun lastPriceFen(itemId: String): Long?
    suspend fun ensureSeedBrands() = Unit
}

data class BrandOverview(
    val brand: Brand,
    val itemCount: Int,
    val lastUpdatedAtEpochMillis: Long?,
)

class CatalogItemNotFoundException(itemId: String) :
    NoSuchElementException("Catalog item '$itemId' was not found")

class BrandNotFoundException(brandId: String) :
    NoSuchElementException("Catalog brand '$brandId' was not found")

class InvalidCatalogNameException(name: String) :
    IllegalArgumentException("Catalog item name must contain non-whitespace text: '$name'")

class DuplicateCatalogNameException(name: String) :
    IllegalArgumentException("同一分类下已存在同名条目：$name")

class RoomCatalogRepository(
    private val brandDao: BrandDao,
    private val catalogItemDao: CatalogItemDao,
    private val drinkDao: DrinkDao,
) : CatalogRepository {
    override fun observeBrands(type: BrandType): Flow<List<Brand>> =
        brandDao.observeByType(type.name).map { entities ->
            entities.map(BrandEntity::toDomain)
        }

    override fun observeItems(brandId: String): Flow<List<CatalogItem>> =
        catalogItemDao.observeByBrand(brandId).map { entities ->
            entities.map(CatalogItemEntity::toDomain)
        }

    override fun observeBrandOverviews(type: BrandType): Flow<List<BrandOverview>> =
        brandDao.observeOverviews(type.name).map { rows -> rows.map(BrandOverviewRow::toOverview) }

    override suspend fun getBrand(brandId: String): Brand =
        brandDao.get(brandId)?.toDomain()
            ?: throw BrandNotFoundException(brandId)

    override suspend fun getItem(itemId: String): CatalogItem =
        catalogItemDao.get(itemId)?.toDomain()
            ?: throw CatalogItemNotFoundException(itemId)

    override suspend fun upsertBrand(brand: Brand) {
        val normalized = normalizeCatalogName(brand.name)
        if (brandDao.existsNamedOther(brand.type.name, normalized, brand.id)) {
            throw DuplicateCatalogNameException(brand.name)
        }
        try {
            brandDao.upsert(brand.toEntity())
        } catch (_: SQLiteConstraintException) {
            throw DuplicateCatalogNameException(brand.name)
        }
    }

    override suspend fun upsertItem(item: CatalogItem) {
        val normalized = normalizeCatalogName(item.name)
        if (catalogItemDao.existsNamedOther(item.brandId, normalized, item.id)) {
            throw DuplicateCatalogNameException(item.name)
        }
        try {
            catalogItemDao.upsert(item.toEntity())
        } catch (_: SQLiteConstraintException) {
            throw DuplicateCatalogNameException(item.name)
        }
    }

    override suspend fun lastPriceFen(itemId: String): Long? =
        drinkDao.lastActualPriceFen(itemId)

    override suspend fun ensureSeedBrands() {
        brandDao.seedIgnoringExisting(seedBrands().map(Brand::toEntity))
    }
}

fun seedBrands(): List<Brand> = listOf(
    "seed-chain-luckin" to "瑞幸",
    "seed-chain-manner" to "Manner",
    "seed-chain-mstand" to "M Stand",
    "seed-chain-peets" to "Peet's",
    "seed-chain-arabica" to "% Arabica",
).map { (id, name) ->
    Brand(id, BrandType.CHAIN, name, null, MaintenanceMode.MANUAL_ONLY, null)
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
    normalizedName = normalizeCatalogName(name),
    logoAssetId = logoAssetId,
    maintenanceMode = maintenanceMode.name,
    publicSourceUrl = publicSourceUrl,
)

private fun BrandOverviewRow.toOverview() = BrandOverview(
    brand = Brand(
        id = id,
        type = enumValue("BrandOverviewRow.type", type),
        name = name,
        logoAssetId = logoAssetId,
        maintenanceMode = enumValue("BrandOverviewRow.maintenanceMode", maintenanceMode),
        publicSourceUrl = publicSourceUrl,
    ),
    itemCount = itemCount,
    lastUpdatedAtEpochMillis = lastUpdatedAtEpochMillis,
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
    normalizedName = normalizeCatalogName(name),
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

fun normalizeCatalogName(raw: String): String {
    val compatible = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val normalized = StringBuilder()
    var pendingSpace = false
    var index = 0
    while (index < compatible.length) {
        val codePoint = compatible.codePointAt(index)
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            pendingSpace = normalized.isNotEmpty()
        } else {
            if (pendingSpace) normalized.append(' ')
            normalized.appendCodePoint(codePoint)
            pendingSpace = false
        }
        index += Character.charCount(codePoint)
    }
    if (normalized.isEmpty()) throw InvalidCatalogNameException(raw)
    return normalized.toString()
}

private inline fun <reified T : Enum<T>> enumValue(field: String, value: String): T =
    try {
        enumValueOf<T>(value)
    } catch (_: IllegalArgumentException) {
        throw DataIntegrityException(field, value)
    }
