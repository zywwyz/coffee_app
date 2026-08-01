package com.niumi.coffeejournal.journal

import androidx.room.withTransaction
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.database.DataIntegrityException
import com.niumi.coffeejournal.core.database.DraftRecordEntity
import com.niumi.coffeejournal.core.database.DrinkRecordEntity
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.DrinkDraft
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import com.niumi.coffeejournal.core.model.ItemType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface JournalRepository {
    fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>>
    suspend fun newDraft(type: ItemType, itemId: String): DrinkDraft
    suspend fun save(draft: DrinkDraft): String
    suspend fun saveDraft(draft: DrinkDraft)
    suspend fun delete(recordId: String)
}

interface Clock {
    fun nowEpochMillis(): Long
    fun todayLocalDate(): String
}

object SystemClock : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()

    override fun todayLocalDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
}

interface DrinkStore {
    fun observeRange(startLocalDate: String, endLocalDate: String): Flow<List<DrinkRecord>>
    suspend fun saveRecordAndClearDraft(record: DrinkRecord)
    suspend fun saveDraft(draft: DrinkDraft)
    suspend fun delete(recordId: String)
}

class RoomDrinkStore(
    private val database: CoffeeDatabase,
    private val clock: Clock = SystemClock,
) : DrinkStore {
    override fun observeRange(
        startLocalDate: String,
        endLocalDate: String,
    ): Flow<List<DrinkRecord>> =
        database.drinkDao().observeRange(startLocalDate, endLocalDate).map { entities ->
            entities.map(DrinkRecordEntity::toDomain)
        }

    override suspend fun saveRecordAndClearDraft(record: DrinkRecord) {
        database.withTransaction {
            database.drinkDao().insert(record.toEntity())
            database.draftDao().delete(CURRENT_DRAFT_ID)
        }
    }

    override suspend fun saveDraft(draft: DrinkDraft) {
        database.draftDao().upsert(
            DraftRecordEntity(
                id = CURRENT_DRAFT_ID,
                itemType = draft.itemType.name,
                sourceItemId = draft.sourceItemId,
                brewMethod = draft.brewMethod,
                ratingHalfStars = draft.ratingHalfStars,
                actualPriceFen = draft.actualPriceFen,
                note = draft.note,
                updatedAtEpochMillis = clock.nowEpochMillis(),
            ),
        )
    }

    override suspend fun delete(recordId: String) {
        database.drinkDao().get(recordId)?.let { database.drinkDao().delete(it) }
    }

    private companion object {
        const val CURRENT_DRAFT_ID = "current"
    }
}

class DefaultJournalRepository(
    private val catalogRepository: CatalogRepository,
    private val drinkStore: DrinkStore,
    private val clock: Clock = SystemClock,
) : JournalRepository {
    override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> {
        val (start, end) = monthRange(year, month)
        return drinkStore.observeRange(start, end)
    }

    override suspend fun newDraft(type: ItemType, itemId: String): DrinkDraft {
        val item = catalogRepository.getItem(itemId)
        require(item.type == type) {
            "Catalog item '$itemId' has type ${item.type}, not $type"
        }
        return DrinkDraft(
            itemType = item.type,
            sourceItemId = item.id,
            brewMethod = item.brewMethod,
            ratingHalfStars = null,
            actualPriceFen = catalogRepository.lastPriceFen(item.id),
            note = "",
        )
    }

    override suspend fun save(draft: DrinkDraft): String {
        val item = catalogRepository.getItem(draft.sourceItemId)
        val brandType = when (item.type) {
            ItemType.CHAIN_PRODUCT -> BrandType.CHAIN
            ItemType.PERSONAL_BEAN -> BrandType.ROASTER
        }
        val brand = catalogRepository.observeBrands(brandType)
            .first()
            .firstOrNull { it.id == item.brandId }
            ?: throw NoSuchElementException("Catalog brand '${item.brandId}' was not found")
        val id = UUID.randomUUID().toString()
        val record = DrinkRecord(
            id = id,
            occurredAtEpochMillis = clock.nowEpochMillis(),
            localDate = clock.todayLocalDate(),
            itemType = item.type,
            sourceItemId = item.id,
            brewMethod = draft.brewMethod,
            ratingHalfStars = draft.ratingHalfStars,
            actualPriceFen = draft.actualPriceFen,
            note = draft.note.takeUnless(String::isBlank),
            snapshot = DrinkSnapshot(
                brandName = brand.name,
                itemName = item.name,
                origin = item.origin,
                processing = item.processing,
                imageAssetId = item.imageAssetId,
                roastLevel = item.roastLevel,
                flavorNotes = item.flavorNotes,
            ),
        )
        drinkStore.saveRecordAndClearDraft(record)
        return id
    }

    override suspend fun saveDraft(draft: DrinkDraft) = drinkStore.saveDraft(draft)

    override suspend fun delete(recordId: String) = drinkStore.delete(recordId)
}

internal fun monthRange(year: Int, month: Int): Pair<String, String> {
    require(year >= 1) { "Year must be positive" }
    require(month in 1..12) { "Month must be between 1 and 12" }
    val calendar = GregorianCalendar(year, month - 1, 1).apply { isLenient = false }
    val lastDay = calendar.getActualMaximum(GregorianCalendar.DAY_OF_MONTH)
    val prefix = "%04d-%02d".format(Locale.ROOT, year, month)
    return "$prefix-01" to "$prefix-${lastDay.toString().padStart(2, '0')}"
}

private fun DrinkRecordEntity.toDomain() = DrinkRecord(
    id = id,
    occurredAtEpochMillis = occurredAtEpochMillis,
    localDate = localDate,
    itemType = enumValue("DrinkRecordEntity.itemType", itemType),
    sourceItemId = sourceItemId,
    brewMethod = brewMethod,
    ratingHalfStars = ratingHalfStars,
    actualPriceFen = actualPriceFen,
    note = note,
    snapshot = DrinkSnapshot(
        brandName = snapshotBrandName,
        itemName = snapshotItemName,
        origin = snapshotOrigin,
        processing = snapshotProcessing,
        imageAssetId = snapshotImageAssetId,
        roastLevel = snapshotRoastLevel,
        flavorNotes = snapshotFlavorNotes,
    ),
)

private fun DrinkRecord.toEntity() = DrinkRecordEntity(
    id = id,
    occurredAtEpochMillis = occurredAtEpochMillis,
    localDate = localDate,
    itemType = itemType.name,
    sourceItemId = sourceItemId,
    brewMethod = brewMethod,
    ratingHalfStars = ratingHalfStars,
    actualPriceFen = actualPriceFen,
    note = note,
    snapshotBrandName = snapshot.brandName,
    snapshotItemName = snapshot.itemName,
    snapshotOrigin = snapshot.origin,
    snapshotProcessing = snapshot.processing,
    snapshotImageAssetId = snapshot.imageAssetId,
    snapshotRoastLevel = snapshot.roastLevel,
    snapshotFlavorNotes = snapshot.flavorNotes,
)

private inline fun <reified T : Enum<T>> enumValue(field: String, value: String): T =
    try {
        enumValueOf<T>(value)
    } catch (_: IllegalArgumentException) {
        throw DataIntegrityException(field, value)
    }
