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
import com.niumi.coffeejournal.core.model.CoffeeType
import com.niumi.coffeejournal.core.model.ItemType
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface JournalRepository {
    fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>>
    suspend fun newDraft(type: ItemType, itemId: String): DrinkDraft
    suspend fun replaceDraftForItem(current: DrinkDraft, type: ItemType, itemId: String): DrinkDraft =
        newDraft(type, itemId)
    suspend fun currentDraft(): DrinkDraft? = restoreDraft()
    suspend fun restoreDraft(): DrinkDraft? = null
    suspend fun editDraft(recordId: String): DrinkDraft = error("Editing is not supported")
    suspend fun get(recordId: String): DrinkRecord? = null
    suspend fun save(draft: DrinkDraft): String
    suspend fun saveDraft(draft: DrinkDraft): Boolean
    suspend fun discardDraft(revisionId: String): Boolean = false
    suspend fun delete(recordId: String)
}

data class ClockReading(val epochMillis: Long, val localDate: String)

interface Clock {
    fun read(): ClockReading
}

object SystemClock : Clock {
    override fun read(): ClockReading {
        val epochMillis = System.currentTimeMillis()
        return ClockReading(epochMillis, localDateForEpoch(epochMillis))
    }
}

interface DrinkStore {
    fun observeRange(startLocalDate: String, endLocalDate: String): Flow<List<DrinkRecord>>
    suspend fun startDraft(draft: DrinkDraft)
    suspend fun startEditDraft(recordId: String, revisionId: String): DrinkDraft? = null
    suspend fun replaceDraft(expectedRevisionId: String, draft: DrinkDraft): Boolean = false
    suspend fun restoreDraft(): DrinkDraft? = null
    suspend fun get(recordId: String): DrinkRecord? = null
    suspend fun saveRecordAndClearDraft(record: DrinkRecord, revisionId: String)
    suspend fun update(record: DrinkRecord, expectedRevision: Int, draftRevisionId: String): Boolean = false
    suspend fun saveDraft(draft: DrinkDraft): Boolean
    suspend fun discardDraft(revisionId: String): Boolean = false
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

    override suspend fun startEditDraft(recordId: String, revisionId: String): DrinkDraft? = database.withTransaction {
        val record = database.drinkDao().get(recordId)?.toDomain() ?: return@withTransaction null
        val draft = DrinkDraft(
            revisionId = revisionId,
            itemType = record.itemType,
            sourceItemId = record.sourceItemId,
            brewMethod = record.brewMethod,
            ratingHalfStars = record.ratingHalfStars,
            actualPriceFen = record.actualPriceFen,
            note = record.note.orEmpty(),
            consumedAtEpochMillis = record.occurredAtEpochMillis,
            editingRecordId = record.id,
            expectedRecordRevision = record.revision,
        )
        database.draftDao().upsert(draft.toEntity(clock.read().epochMillis))
        draft
    }

    override suspend fun replaceDraft(expectedRevisionId: String, draft: DrinkDraft): Boolean = database.withTransaction {
        val current = database.draftDao().get(CURRENT_DRAFT_ID) ?: return@withTransaction false
        if (current.revisionId != expectedRevisionId) return@withTransaction false
        database.draftDao().upsert(draft.toEntity(clock.read().epochMillis))
        true
    }

    override suspend fun restoreDraft(): DrinkDraft? =
        database.draftDao().get(CURRENT_DRAFT_ID)?.toDomain()

    override suspend fun get(recordId: String): DrinkRecord? =
        database.drinkDao().get(recordId)?.toDomain()

    override suspend fun saveRecordAndClearDraft(record: DrinkRecord, revisionId: String) {
        database.withTransaction {
            database.drinkDao().insert(record.toEntity())
            database.draftDao().deleteIfRevision(CURRENT_DRAFT_ID, revisionId)
        }
    }

    override suspend fun update(
        record: DrinkRecord,
        expectedRevision: Int,
        draftRevisionId: String,
    ): Boolean = database.withTransaction {
        val changed = database.drinkDao().updateIfRevision(
            id = record.id,
            expectedRevision = expectedRevision,
            occurredAtEpochMillis = record.occurredAtEpochMillis,
            localDate = record.localDate,
            itemType = record.itemType.name,
            sourceItemId = record.sourceItemId,
            brewMethod = record.brewMethod,
            ratingHalfStars = record.ratingHalfStars,
            actualPriceFen = record.actualPriceFen,
            note = record.note,
            snapshotBrandName = record.snapshot.brandName,
            snapshotItemName = record.snapshot.itemName,
            snapshotOrigin = record.snapshot.origin,
            snapshotProcessing = record.snapshot.processing,
            snapshotImageAssetId = record.snapshot.imageAssetId,
            snapshotBrandLogoAssetId = record.snapshot.brandLogoAssetId,
            snapshotRoastLevel = record.snapshot.roastLevel,
            snapshotFlavorNotes = record.snapshot.flavorNotes,
            snapshotCoffeeType = record.snapshot.coffeeType.name,
            updatedAtEpochMillis = record.updatedAtEpochMillis,
            newRevision = record.revision,
        ) == 1
        if (changed) database.draftDao().deleteIfRevision(CURRENT_DRAFT_ID, draftRevisionId)
        changed
    }

    override suspend fun saveDraft(draft: DrinkDraft): Boolean = database.withTransaction {
        val current = database.draftDao().get(CURRENT_DRAFT_ID)
        if (current?.revisionId != draft.revisionId) return@withTransaction false
        database.draftDao().upsert(draft.toEntity(clock.read().epochMillis))
        true
    }

    override suspend fun discardDraft(revisionId: String): Boolean = database.withTransaction {
        database.draftDao().deleteIfRevision(CURRENT_DRAFT_ID, revisionId) == 1
    }

    override suspend fun delete(recordId: String) {
        database.withTransaction {
            database.drinkDao().get(recordId)?.let { database.drinkDao().delete(it) }
            database.draftDao().deleteIfEditingRecord(CURRENT_DRAFT_ID, recordId)
        }
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
        consumedAtEpochMillis = consumedAtEpochMillis.takeIf { it > 0 } ?: updatedAtEpochMillis,
        editingRecordId = editingRecordId,
        expectedRecordRevision = expectedRecordRevision,
    )

    private fun DraftRecordEntity.toDomain() = DrinkDraft(
        revisionId = revisionId,
        itemType = itemType?.let { enumValue<ItemType>("DraftRecordEntity.itemType", it) }
            ?: throw DataIntegrityException("DraftRecordEntity.itemType", "null"),
        sourceItemId = sourceItemId ?: throw DataIntegrityException("DraftRecordEntity.sourceItemId", "null"),
        brewMethod = brewMethod,
        ratingHalfStars = ratingHalfStars,
        actualPriceFen = actualPriceFen,
        note = note,
        consumedAtEpochMillis = consumedAtEpochMillis,
        editingRecordId = editingRecordId,
        expectedRecordRevision = expectedRecordRevision,
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
            consumedAtEpochMillis = clock.read().let { reading -> localNoonEpoch(reading.localDate) },
        )
        drinkStore.startDraft(draft)
        return draft
    }

    override suspend fun replaceDraftForItem(current: DrinkDraft, type: ItemType, itemId: String): DrinkDraft {
        val item = catalogRepository.getItem(itemId)
        require(item.type == type) { "Catalog item '$itemId' has type ${item.type}, not $type" }
        val replacement = current.copy(
            revisionId = UUID.randomUUID().toString(), itemType = type, sourceItemId = itemId,
        )
        if (!drinkStore.replaceDraft(current.revisionId, replacement)) throw DraftConflictException()
        return replacement
    }

    override suspend fun restoreDraft(): DrinkDraft? = drinkStore.restoreDraft()

    override suspend fun currentDraft(): DrinkDraft? = drinkStore.restoreDraft()

    override suspend fun get(recordId: String): DrinkRecord? = drinkStore.get(recordId)

    override suspend fun editDraft(recordId: String): DrinkDraft {
        return drinkStore.startEditDraft(recordId, UUID.randomUUID().toString())
            ?: throw RecordNotFoundException(recordId)
    }

    override suspend fun save(draft: DrinkDraft): String {
        val reading = clock.read()
        val consumedAt = draft.consumedAtEpochMillis.takeIf { it > 0 }
            ?: localNoonEpoch(reading.localDate)
        val consumedLocalDate = localDateForEpoch(consumedAt)
        require(consumedLocalDate <= reading.localDate) {
            "Drink date cannot be after today"
        }
        val existing = draft.editingRecordId?.let { drinkStore.get(it) ?: throw RecordNotFoundException(it) }
        val productChanged = existing != null && existing.sourceItemId != draft.sourceItemId
        val item = if (existing == null || productChanged) catalogRepository.getItem(draft.sourceItemId) else null
        val brand = item?.let { catalogRepository.getBrand(it.brandId) }
        val snapshot = when {
            existing != null && !productChanged -> existing.snapshot
            else -> DrinkSnapshot(
                brandName = requireNotNull(brand).name,
                itemName = requireNotNull(item).name,
                origin = item.origin,
                processing = item.processing,
                imageAssetId = item.imageAssetId,
                brandLogoAssetId = brand.logoAssetId,
                roastLevel = item.roastLevel,
                flavorNotes = item.flavorNotes,
                coffeeType = item.coffeeTypeForSnapshot(),
            )
        }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val updatedAt = existing?.let { previous ->
            maxOf(reading.epochMillis, previous.updatedAtEpochMillis.saturatingIncrement())
        } ?: reading.epochMillis
        val record = DrinkRecord(
            id = id,
            occurredAtEpochMillis = consumedAt,
            localDate = consumedLocalDate,
            itemType = item?.type ?: requireNotNull(existing).itemType,
            sourceItemId = item?.id ?: requireNotNull(existing).sourceItemId,
            brewMethod = draft.brewMethod,
            ratingHalfStars = draft.ratingHalfStars,
            actualPriceFen = draft.actualPriceFen,
            note = draft.note.takeUnless(String::isBlank),
            snapshot = snapshot,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: reading.epochMillis,
            updatedAtEpochMillis = updatedAt,
            revision = existing?.revision?.let { Math.incrementExact(it) } ?: 0,
        )
        if (existing == null) {
            drinkStore.saveRecordAndClearDraft(record, draft.revisionId)
        } else {
            val expected = requireNotNull(draft.expectedRecordRevision)
            if (!drinkStore.update(record, expected, draft.revisionId)) throw RecordConflictException(record.id)
        }
        return id
    }

    override suspend fun saveDraft(draft: DrinkDraft): Boolean = drinkStore.saveDraft(draft)

    override suspend fun discardDraft(revisionId: String): Boolean = drinkStore.discardDraft(revisionId)

    override suspend fun delete(recordId: String) = drinkStore.delete(recordId)
}

class RecordNotFoundException(id: String) : IllegalStateException("Drink record '$id' was not found")
class RecordConflictException(id: String) : IllegalStateException("Drink record '$id' was changed elsewhere")
class DraftConflictException : IllegalStateException("Draft was changed elsewhere")

private fun Long.saturatingIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1

internal fun localDateForEpoch(epochMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
    require(epochMillis >= 0)
    val calendar = GregorianCalendar(timeZone, Locale.ROOT).apply { timeInMillis = epochMillis }
    return "%04d-%02d-%02d".format(
        Locale.ROOT,
        calendar.get(GregorianCalendar.YEAR),
        calendar.get(GregorianCalendar.MONTH) + 1,
        calendar.get(GregorianCalendar.DAY_OF_MONTH),
    )
}

internal fun localNoonEpoch(localDate: String, timeZone: TimeZone = TimeZone.getDefault()): Long {
    require(localDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Date must be yyyy-MM-dd" }
    val year = localDate.substring(0, 4).toInt()
    val month = localDate.substring(5, 7).toInt() - 1
    val day = localDate.substring(8, 10).toInt()
    val calendar = GregorianCalendar(timeZone, Locale.ROOT).apply {
        isLenient = false
        clear()
        set(year, month, day, 12, 0, 0)
    }
    val epochMillis = calendar.timeInMillis
    require(localDateForEpoch(epochMillis, timeZone) == localDate) { "Date must be yyyy-MM-dd" }
    return epochMillis
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
        coffeeType = enumValue("DrinkRecordEntity.snapshotCoffeeType", snapshotCoffeeType),
    ),
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    revision = revision,
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
    snapshotCoffeeType = snapshot.coffeeType.name,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    revision = revision,
)

private inline fun <reified T : Enum<T>> enumValue(field: String, value: String): T =
    try {
        enumValueOf<T>(value)
    } catch (_: IllegalArgumentException) {
        throw DataIntegrityException(field, value)
    }

private fun com.niumi.coffeejournal.core.model.CatalogItem.coffeeTypeForSnapshot(): CoffeeType = when (type) {
    ItemType.PERSONAL_BEAN -> CoffeeType.HAND_BREW
    ItemType.CHAIN_PRODUCT -> when (chainProductKind) {
        com.niumi.coffeejournal.core.model.ChainProductKind.BLACK -> CoffeeType.BLACK
        com.niumi.coffeejournal.core.model.ChainProductKind.FRUIT -> CoffeeType.FRUIT
        com.niumi.coffeejournal.core.model.ChainProductKind.MILK -> CoffeeType.MILK
        com.niumi.coffeejournal.core.model.ChainProductKind.PENDING, null ->
            throw IllegalArgumentException("Cannot save CHAIN_PRODUCT with PENDING coffee type")
    }
}
