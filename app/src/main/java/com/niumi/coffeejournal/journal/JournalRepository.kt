package com.niumi.coffeejournal.journal

import androidx.room.withTransaction
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.database.DataIntegrityException
import com.niumi.coffeejournal.core.database.DraftRecordEntity
import com.niumi.coffeejournal.core.database.DrinkRecordEntity
import com.niumi.coffeejournal.core.model.DrinkDraft
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import com.niumi.coffeejournal.core.model.ItemType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface JournalRepository {
    fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>>
    suspend fun newDraft(type: ItemType, itemId: String): DrinkDraft
    suspend fun save(draft: DrinkDraft): String
    suspend fun saveDraft(draft: DrinkDraft): Boolean
    suspend fun delete(recordId: String)
}

data class ClockReading(val epochMillis: Long, val localDate: String)

interface Clock {
    fun read(): ClockReading
}

object SystemClock : Clock {
    override fun read(): ClockReading {
        val epochMillis = System.currentTimeMillis()
        val timeZone = TimeZone.getDefault()
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            this.timeZone = timeZone
        }
        return ClockReading(epochMillis, formatter.format(Date(epochMillis)))
    }
}

interface DrinkStore {
    fun observeRange(startLocalDate: String, endLocalDate: String): Flow<List<DrinkRecord>>
    suspend fun startDraft(draft: DrinkDraft)
    suspend fun saveRecordAndClearDraft(record: DrinkRecord, revisionId: String)
    suspend fun saveDraft(draft: DrinkDraft): Boolean
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

    override suspend fun startDraft(draft: DrinkDraft) {
        database.draftDao().upsert(draft.toEntity(clock.read().epochMillis))
    }

    override suspend fun saveRecordAndClearDraft(record: DrinkRecord, revisionId: String) {
        database.withTransaction {
            database.drinkDao().insert(record.toEntity())
            database.draftDao().deleteIfRevision(CURRENT_DRAFT_ID, revisionId)
        }
    }

    override suspend fun saveDraft(draft: DrinkDraft): Boolean = database.withTransaction {
        val current = database.draftDao().get(CURRENT_DRAFT_ID)
        if (current?.revisionId != draft.revisionId) return@withTransaction false
        database.draftDao().upsert(draft.toEntity(clock.read().epochMillis))
        true
    }

    override suspend fun delete(recordId: String) {
        database.drinkDao().get(recordId)?.let { database.drinkDao().delete(it) }
    }

    private companion object {
        const val CURRENT_DRAFT_ID = "current"
    }

    private fun DrinkDraft.toEntity(updatedAtEpochMillis: Long) = DraftRecordEntity(
        id = CURRENT_DRAFT_ID,
        revisionId = revisionId,
        itemType = itemType.name,
        sourceItemId = sourceItemId,
        brewMethod = brewMethod,
        ratingHalfStars = ratingHalfStars,
        actualPriceFen = actualPriceFen,
        note = note,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
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
        val draft = DrinkDraft(
            revisionId = UUID.randomUUID().toString(),
            itemType = item.type,
            sourceItemId = item.id,
            brewMethod = item.brewMethod,
            ratingHalfStars = null,
            actualPriceFen = catalogRepository.lastPriceFen(item.id),
            note = "",
        )
        drinkStore.startDraft(draft)
        return draft
    }

    override suspend fun save(draft: DrinkDraft): String {
        val item = catalogRepository.getItem(draft.sourceItemId)
        val brand = catalogRepository.getBrand(item.brandId)
        val id = UUID.randomUUID().toString()
        val reading = clock.read()
        val record = DrinkRecord(
            id = id,
            occurredAtEpochMillis = reading.epochMillis,
            localDate = reading.localDate,
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
                brandLogoAssetId = brand.logoAssetId,
                roastLevel = item.roastLevel,
                flavorNotes = item.flavorNotes,
            ),
        )
        drinkStore.saveRecordAndClearDraft(record, draft.revisionId)
        return id
    }

    override suspend fun saveDraft(draft: DrinkDraft): Boolean = drinkStore.saveDraft(draft)

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
        brandLogoAssetId = snapshotBrandLogoAssetId,
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
    snapshotBrandLogoAssetId = snapshot.brandLogoAssetId,
    snapshotRoastLevel = snapshot.roastLevel,
    snapshotFlavorNotes = snapshot.flavorNotes,
)

private inline fun <reified T : Enum<T>> enumValue(field: String, value: String): T =
    try {
        enumValueOf<T>(value)
    } catch (_: IllegalArgumentException) {
        throw DataIntegrityException(field, value)
    }
