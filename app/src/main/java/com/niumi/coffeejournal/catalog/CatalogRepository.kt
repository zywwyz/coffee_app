package com.niumi.coffeejournal.catalog

import android.net.Uri
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
import com.niumi.coffeejournal.core.model.ChainProductKind
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import java.text.Normalizer
import java.util.Locale
import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface CatalogRepository {
    fun observeBrands(type: BrandType): Flow<List<Brand>>
    fun observeItems(brandId: String): Flow<List<CatalogItem>>
    fun observeBrandOverviews(type: BrandType): Flow<List<BrandOverview>> =
        observeBrands(type).map { brands -> brands.map { BrandOverview(it, 0) } }
    suspend fun getBrand(brandId: String): Brand
    suspend fun getItem(itemId: String): CatalogItem
    suspend fun upsertBrand(brand: Brand)
    suspend fun upsertItem(item: CatalogItem)
    suspend fun deleteCustomBrand(brandId: String): CatalogDeleteResult = CatalogDeleteResult.NotFound
    suspend fun deleteCustomItem(itemId: String): CatalogDeleteResult = CatalogDeleteResult.NotFound
    suspend fun lastPriceFen(itemId: String): Long?
    suspend fun ensureSeedBrands() = Unit
}

sealed interface CatalogDeleteResult {
    data object Deleted : CatalogDeleteResult
    data object Protected : CatalogDeleteResult
    data object HasProducts : CatalogDeleteResult
    data object NotFound : CatalogDeleteResult
}

data class BrandOverview(
    val brand: Brand,
    val itemCount: Int,
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
    private val imageStore: ImageStore? = null,
    private val resourceUriFactory: ((Int) -> Uri)? = null,
) : CatalogRepository {
    override fun observeBrands(type: BrandType): Flow<List<Brand>> =
        brandDao.observeByType(type.name).map { entities ->
            entities.map(BrandEntity::toDomain).sortedForCatalog(type)
        }

    override fun observeItems(brandId: String): Flow<List<CatalogItem>> =
        catalogItemDao.observeByBrand(brandId).map { entities ->
            entities.map(CatalogItemEntity::toDomain)
        }

    override fun observeBrandOverviews(type: BrandType): Flow<List<BrandOverview>> =
        brandDao.observeOverviews(type.name).map { rows ->
            rows.map(BrandOverviewRow::toOverview).sortedWith(
                compareBy<BrandOverview> { BUNDLED_CHAIN_BRANDS.firstOrNull { seed -> seed.brand.id == it.brand.id }?.order ?: Int.MAX_VALUE }
                    .thenBy { normalizeCatalogName(it.brand.name) },
            )
        }

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
        } catch (error: SQLiteConstraintException) {
            if (brandDao.existsNamedOther(brand.type.name, normalized, brand.id)) {
                throw DuplicateCatalogNameException(brand.name)
            }
            throw error
        }
    }

    override suspend fun upsertItem(item: CatalogItem) {
        require(item.type != ItemType.CHAIN_PRODUCT || item.chainProductKind in setOf(ChainProductKind.BLACK, ChainProductKind.FRUIT, ChainProductKind.MILK)) {
            "Chain products require a public product kind"
        }
        require(item.type != ItemType.PERSONAL_BEAN || item.chainProductKind == null) {
            "Personal beans cannot have a chain product kind"
        }
        val normalized = normalizeCatalogName(item.name)
        if (catalogItemDao.existsNamedOther(item.brandId, normalized, item.id)) {
            throw DuplicateCatalogNameException(item.name)
        }
        try {
            catalogItemDao.upsert(item.toEntity())
        } catch (error: SQLiteConstraintException) {
            if (catalogItemDao.existsNamedOther(item.brandId, normalized, item.id)) {
                throw DuplicateCatalogNameException(item.name)
            }
            throw error
        }
    }

    override suspend fun deleteCustomBrand(brandId: String): CatalogDeleteResult {
        val brand = brandDao.get(brandId) ?: return CatalogDeleteResult.NotFound
        if (brand.type != BrandType.CHAIN.name || brandId in BUNDLED_CHAIN_BRANDS.map { it.brand.id }) return CatalogDeleteResult.Protected
        if (catalogItemDao.observeByBrand(brandId).first().isNotEmpty()) return CatalogDeleteResult.HasProducts
        return try {
            brandDao.deleteById(brandId)
            runCatching { imageStore?.deleteIfUnreferenced(brand.logoAssetId ?: return@runCatching) }
            CatalogDeleteResult.Deleted
        } catch (_: SQLiteConstraintException) {
            CatalogDeleteResult.HasProducts
        }
    }

    override suspend fun deleteCustomItem(itemId: String): CatalogDeleteResult {
        val item = catalogItemDao.get(itemId) ?: return CatalogDeleteResult.NotFound
        val brand = brandDao.get(item.brandId) ?: return CatalogDeleteResult.NotFound
        if (item.type != ItemType.CHAIN_PRODUCT.name || brand.id in BUNDLED_CHAIN_BRANDS.map { it.brand.id }) return CatalogDeleteResult.Protected
        catalogItemDao.deleteById(itemId)
        item.imageAssetId?.let { assetId -> runCatching { imageStore?.deleteIfUnreferenced(assetId) } }
        return CatalogDeleteResult.Deleted
    }

    override suspend fun lastPriceFen(itemId: String): Long? =
        drinkDao.lastActualPriceFen(itemId)

    override suspend fun ensureSeedBrands() {
        seedMutex.withLock {
            val existing = brandDao.getByNormalizedNames(
                BrandType.CHAIN.name,
                BUNDLED_CHAIN_BRANDS.flatMap { definition -> definition.catalogNames() }.distinct(),
            ).associateBy { it.normalizedName }
            BUNDLED_CHAIN_BRANDS.forEach { definition ->
                val legacy = definition.catalogNames().asSequence()
                    .mapNotNull(existing::get)
                    .firstOrNull { it.id != definition.brand.id }
                if (legacy != null) brandDao.adoptAsBundledId(legacy, definition.brand.id)
            }
            brandDao.seedIgnoringExisting(BUNDLED_CHAIN_BRANDS.filter { definition ->
                definition.catalogNames().none(existing::containsKey)
            }.map { it.brand.toEntity() })
            val store = imageStore ?: return@withLock
            val resourceUri = resourceUriFactory ?: return@withLock
            BUNDLED_CHAIN_BRANDS.forEach { definition ->
                val target = brandDao.get(definition.brand.id)
                    ?: brandDao.getByNormalizedNames(BrandType.CHAIN.name, definition.catalogNames().toList()).firstOrNull()
                    ?: return@forEach
                if (target.logoAssetId != null) return@forEach
                val asset = store.importWhole(resourceUri(definition.logoRes), ImageKind.BRAND_LOGO)
                if (brandDao.attachLogoIfMissing(target.id, asset.id) == 0) {
                    store.deleteIfUnreferenced(asset.id)
                }
            }
        }
    }
}

fun seedBrands(): List<Brand> = BUNDLED_CHAIN_BRANDS.map(BundledBrandDefinition::brand)

private val seedMutex = Mutex()

private fun List<Brand>.sortedForCatalog(type: BrandType): List<Brand> {
    if (type != BrandType.CHAIN) return this
    return sortedWith(compareBy<Brand> { brand ->
        BUNDLED_CHAIN_BRANDS.firstOrNull { definition ->
            brand.id == definition.brand.id || normalizeCatalogName(brand.name) in definition.catalogNames()
        }?.order ?: Int.MAX_VALUE
    }.thenBy { normalizeCatalogName(it.name) })
}

private fun BundledBrandDefinition.catalogNames(): Set<String> =
    (aliases + brand.name).mapTo(linkedSetOf(), ::normalizeCatalogName)

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
    category = category,
    specificationDescription = specificationDescription,
    imageSourceUrl = imageSourceUrl,
    chainProductKind = chainProductKind?.let { enumValue<ChainProductKind>("CatalogItemEntity.chainProductKind", it) },
).also { item ->
    require((item.type == ItemType.CHAIN_PRODUCT) == (item.chainProductKind != null)) {
        "Catalog item type and chain product kind disagree"
    }
}

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
    category = category,
    specificationDescription = specificationDescription,
    imageSourceUrl = imageSourceUrl,
    chainProductKind = chainProductKind?.name,
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
