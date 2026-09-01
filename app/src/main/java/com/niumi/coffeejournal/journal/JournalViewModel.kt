package com.niumi.coffeejournal.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.DrinkDraft
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import java.util.Calendar
import java.util.GregorianCalendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class JournalViewModel(
    private val journalRepository: JournalRepository,
    private val catalogRepository: CatalogRepository,
    initialYear: Int,
    initialMonth: Int,
    coroutineScope: CoroutineScope? = null,
    private val imagePathResolver: ImagePathResolver = ImagePathResolver { null },
    private val clock: Clock = SystemClock,
    private val calendarDisplayPreference: CalendarDisplayPreference = DefaultCalendarDisplayPreference,
) : ViewModel() {
    private val scope = coroutineScope ?: viewModelScope
    private val mutableState = MutableStateFlow(
        JournalUiState.empty(initialYear, initialMonth).copy(calendarDisplayMode = calendarDisplayPreference.read()),
    )
    val uiState: StateFlow<JournalUiState> = mutableState.asStateFlow()
    private val draftQueue = Channel<DrinkDraft>(Channel.CONFLATED)
    private val selectionMutex = Mutex()
    private var currentDraft: DrinkDraft? = null
    private var monthJob: Job? = null
    private var brandJob: Job? = null
    private var itemJob: Job? = null
    private var selectionJob: Job? = null
    private var selectionGeneration = 0L
    private var monthGeneration = 0L
    private var brandGeneration = 0L
    private var itemGeneration = 0L

    init {
        scope.launch {
            for (draft in draftQueue) {
                try {
                    val saved = journalRepository.saveDraft(draft)
                    if (saved && currentDraft?.revisionId == draft.revisionId &&
                        mutableState.value.editor.errorMessage == AUTOSAVE_ERROR
                    ) {
                        mutableState.value = mutableState.value.copy(
                            editor = mutableState.value.editor.copy(errorMessage = null),
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (currentDraft?.revisionId == draft.revisionId) setEditorError(AUTOSAVE_ERROR)
                }
            }
        }
        observeMonth()
        observeBrands(ItemType.CHAIN_PRODUCT)
        restoreDraft()
    }

    fun previousMonth() = changeMonth(-1)
    fun nextMonth() = changeMonth(1)
    fun selectDate(localDate: String?) { mutableState.value = mutableState.value.copy(selectedDate = localDate) }

    fun setCalendarDisplayMode(mode: CalendarDisplayMode) {
        if (mutableState.value.calendarDisplayMode == mode) return
        mutableState.value = mutableState.value.copy(calendarDisplayMode = mode)
        calendarDisplayPreference.write(mode)
    }

    fun setSourceType(type: ItemType) {
        if (mutableState.value.editor.saving || mutableState.value.editor.attachingImage) return
        selectionGeneration++
        selectionJob?.cancel()
        itemJob?.cancel()
        val previous = mutableState.value.editor
        mutableState.value = mutableState.value.copy(
            editor = previous.copy(
                sourceType = type,
                selectedBrandId = null,
                selectedItemId = null,
                selecting = false,
                invalidItem = currentDraft != null,
                errorMessage = if (currentDraft != null) "请选择新的产品" else null,
            ),
            items = emptyList(),
        )
        observeBrands(type)
    }

    fun selectBrand(brandId: String) {
        if (mutableState.value.editor.saving || mutableState.value.editor.attachingImage) return
        selectionGeneration++
        selectionJob?.cancel()
        mutableState.value = mutableState.value.copy(
            editor = mutableState.value.editor.copy(
                selectedBrandId = brandId,
                selectedItemId = null,
                selecting = false,
            ),
            items = emptyList(),
        )
        observeItemsForBrand(brandId)
    }

    fun selectItem(type: ItemType, itemId: String, onCompleted: ((Boolean) -> Unit)? = null) {
        if (mutableState.value.editor.saving || mutableState.value.editor.attachingImage) return
        val generation = ++selectionGeneration
        selectionJob?.cancel()
        mutableState.value = mutableState.value.copy(
            editor = mutableState.value.editor.copy(selecting = true, errorMessage = null),
        )
        selectionJob = scope.launch {
            selectionMutex.withLock {
                if (generation != selectionGeneration) return@withLock
                try {
                    val item = catalogRepository.getItem(itemId)
                    if (generation != selectionGeneration) return@withLock
                    var attempts = 0
                    var draft: DrinkDraft
                    while (true) {
                        try {
                            draft = currentDraft?.let { journalRepository.replaceDraftForItem(it, type, itemId) }
                                ?: journalRepository.newDraft(type, itemId)
                            break
                        } catch (conflict: DraftConflictException) {
                            if (generation != selectionGeneration || attempts++ >= 1) throw conflict
                            currentDraft = journalRepository.currentDraft() ?: throw conflict
                        }
                    }
                    // The durable replacement may have completed just before cancellation.
                    // Retain it for the next generation before deciding whether to render it.
                    currentDraft = draft
                    if (generation != selectionGeneration) return@withLock
                    mutableState.value = mutableState.value.copy(
                        editor = mutableState.value.editor.copy(
                            sourceType = type,
                            selectedBrandId = item.brandId,
                            selectedItemId = itemId,
                            ratingHalfStars = draft.ratingHalfStars,
                            priceInput = draft.actualPriceFen?.let(::formatFenInput).orEmpty(),
                            priceValid = true,
                            actualPriceFen = draft.actualPriceFen,
                            brewMethod = draft.brewMethod.orEmpty(),
                            note = draft.note,
                            consumedAtEpochMillis = draft.consumedAtEpochMillis,
                            editingRecordId = draft.editingRecordId,
                            invalidItem = false,
                            needsImagePrompt = item.status == ItemStatus.NEEDS_IMAGE || item.imageAssetId == null,
                            selecting = false,
                            saving = false,
                            errorMessage = null,
                        ),
                    )
                    onCompleted?.invoke(true)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (generation == selectionGeneration) {
                        mutableState.value = mutableState.value.copy(
                            editor = mutableState.value.editor.copy(
                                selecting = false,
                                saving = false,
                                errorMessage = "无法选择该产品",
                            ),
                        )
                    }
                    onCompleted?.invoke(false)
                }
            }
        }
    }

    fun setRating(halfStars: Int?) {
        if (mutableState.value.editor.saving || mutableState.value.editor.selecting || mutableState.value.editor.attachingImage) return
        if (halfStars != null && halfStars !in 1..10) return
        editDraft { it.copy(ratingHalfStars = halfStars) }
    }

    fun setPriceInput(input: String) {
        if (mutableState.value.editor.saving || mutableState.value.editor.selecting || mutableState.value.editor.attachingImage) return
        val fen = input.takeIf { it.isNotBlank() }?.let(::parseYuanToFen)
        val valid = input.isBlank() || fen != null
        mutableState.value = mutableState.value.copy(
            editor = mutableState.value.editor.copy(priceInput = input, priceValid = valid, actualPriceFen = fen),
        )
        if (valid) editDraft(updateEditor = false) { it.copy(actualPriceFen = fen) }
    }

    fun setBrewMethod(value: String) = editDraft { it.copy(brewMethod = value.takeUnless(String::isBlank)) }
    fun setNote(value: String) = editDraft { it.copy(note = value) }
    fun setConsumedAt(epochMillis: Long) {
        val reading = clock.read()
        if (epochMillis <= 0 || localDateForEpoch(epochMillis) > reading.localDate) {
            setEditorError("饮用日期不能晚于今天")
            return
        }
        editDraft { it.copy(consumedAtEpochMillis = epochMillis) }
    }

    fun editRecord(recordId: String) {
        if (mutableState.value.editor.saving) return
        val generation = ++selectionGeneration
        selectionJob?.cancel()
        mutableState.value = mutableState.value.copy(editor = mutableState.value.editor.copy(selecting = true))
        selectionJob = scope.launch {
            try {
                val draft = journalRepository.editDraft(recordId)
                if (generation != selectionGeneration) return@launch
                applyRestoredDraft(draft, generation)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == selectionGeneration) setEditorError("无法打开这条记录")
            }
        }
    }

    fun deleteRecord(recordId: String) {
        selectionGeneration++
        selectionJob?.cancel()
        mutableState.value = mutableState.value.copy(
            editor = mutableState.value.editor.copy(selecting = false),
        )
        scope.launch {
            try {
                journalRepository.delete(recordId)
                if (currentDraft?.editingRecordId == recordId) clearCurrentDraft()
                if (mutableState.value.selectedDate != null) {
                    mutableState.value = mutableState.value.copy(selectedDate = mutableState.value.selectedDate)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                setEditorError("删除失败，请重试")
            }
        }
    }

    fun discardDraft() {
        val draft = currentDraft ?: return
        if (mutableState.value.editor.saving || mutableState.value.editor.selecting || mutableState.value.editor.attachingImage) return
        scope.launch {
            try {
                if (journalRepository.discardDraft(draft.revisionId) && currentDraft?.revisionId == draft.revisionId) {
                    clearCurrentDraft()
                } else if (currentDraft?.revisionId == draft.revisionId) {
                    setEditorError("草稿已变更，请重试")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (currentDraft?.revisionId == draft.revisionId) setEditorError("放弃草稿失败，请重试")
            }
        }
    }
    fun skipImagePrompt() {
        if (mutableState.value.editor.saving || mutableState.value.editor.selecting || mutableState.value.editor.attachingImage) return
        mutableState.value = mutableState.value.copy(editor = mutableState.value.editor.copy(needsImagePrompt = false))
    }

    suspend fun attachImportedImage(assetId: String, actualPriceFen: Long?): Boolean {
        val editor = mutableState.value.editor
        val selectedItemId = editor.selectedItemId ?: return false
        if (editor.saving || editor.selecting || editor.attachingImage) return false
        mutableState.value = mutableState.value.copy(
            editor = editor.copy(attachingImage = true, errorMessage = null),
        )
        return try {
            val item = catalogRepository.getItem(selectedItemId)
            catalogRepository.upsertItem(
                item.copy(
                    imageAssetId = assetId,
                    status = if (item.status == ItemStatus.NEEDS_IMAGE) ItemStatus.ACTIVE else item.status,
                ),
            )
            val associated = catalogRepository.getItem(selectedItemId)
            check(associated.imageAssetId == assetId) { "Catalog image association was not persisted" }
            check(mutableState.value.editor.selectedItemId == selectedItemId) { "Catalog selection changed" }
            val updatedDraft = currentDraft?.let { draft ->
                if (actualPriceFen == null) draft else draft.copy(actualPriceFen = actualPriceFen)
            }
            if (updatedDraft != null) {
                currentDraft = updatedDraft
                draftQueue.trySend(updatedDraft)
            }
            mutableState.value = mutableState.value.copy(
                editor = mutableState.value.editor.copy(
                    actualPriceFen = actualPriceFen ?: mutableState.value.editor.actualPriceFen,
                    priceInput = actualPriceFen?.let(::formatFenInput) ?: mutableState.value.editor.priceInput,
                    priceValid = true,
                    needsImagePrompt = false,
                    attachingImage = false,
                    errorMessage = null,
                ),
            )
            true
        } catch (error: CancellationException) {
            mutableState.value = mutableState.value.copy(
                editor = mutableState.value.editor.copy(attachingImage = false),
            )
            throw error
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                editor = mutableState.value.editor.copy(
                    attachingImage = false,
                    errorMessage = "图片关联产品失败，请重试",
                ),
            )
            false
        }
    }

    fun save() {
        val editor = mutableState.value.editor
        val draft = currentDraft ?: return
        if (editor.saving || editor.selecting || editor.attachingImage || !editor.priceValid) return
        mutableState.value = mutableState.value.copy(editor = editor.copy(saving = true, errorMessage = null))
        scope.launch {
            try {
                journalRepository.save(draft)
                if (currentDraft?.revisionId == draft.revisionId) {
                    clearCurrentDraft(saveCompleted = true)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentDraft?.revisionId == draft.revisionId) {
                    mutableState.value = mutableState.value.copy(
                        editor = mutableState.value.editor.copy(
                            saving = false,
                            errorMessage = if (error is RecordConflictException) {
                                "记录已在其他位置修改，请重新打开"
                            } else {
                                "保存失败，请重试"
                            },
                        ),
                    )
                }
            }
        }
    }

    private fun editDraft(
        updateEditor: Boolean = true,
        transform: (DrinkDraft) -> DrinkDraft,
    ) {
        if (mutableState.value.editor.saving || mutableState.value.editor.selecting || mutableState.value.editor.attachingImage) return
        val updated = currentDraft?.let(transform) ?: return
        currentDraft = updated
        if (updateEditor) {
            mutableState.value = mutableState.value.copy(
                editor = mutableState.value.editor.copy(
                    ratingHalfStars = updated.ratingHalfStars,
                    actualPriceFen = updated.actualPriceFen,
                    brewMethod = updated.brewMethod.orEmpty(),
                    note = updated.note,
                    consumedAtEpochMillis = updated.consumedAtEpochMillis,
                ),
            )
        }
        draftQueue.trySend(updated)
    }

    private fun clearCurrentDraft(saveCompleted: Boolean = false) {
        val sourceType = mutableState.value.editor.sourceType
        val reading = clock.read()
        currentDraft = null
        mutableState.value = mutableState.value.copy(
            editor = RecordEditorUi(sourceType = sourceType, consumedAtEpochMillis = localNoonEpoch(reading.localDate)),
            saveCompletedToken = mutableState.value.saveCompletedToken + if (saveCompleted) 1 else 0,
        )
    }

    private fun observeMonth() {
        monthJob?.cancel()
        val generation = ++monthGeneration
        val year = mutableState.value.year
        val month = mutableState.value.month
        monthJob = scope.launch {
            journalRepository.observeMonth(year, month).collect { records ->
                val representatives = representativeRecords(records)
                val assetIds = representatives.flatMap { record ->
                    listOfNotNull(record.snapshot.imageAssetId, record.snapshot.brandLogoAssetId)
                }.distinct()
                val pathsByAssetId = assetIds.associateWith { assetId ->
                    resultOrNullPreservingCancellation { imagePathResolver.resolve(assetId) }
                }
                currentCoroutineContext().ensureActive()
                if (generation != monthGeneration) return@collect
                val paths = representatives.associate { record ->
                    record.id to record.snapshot.imageAssetId?.let(pathsByAssetId::get)
                }
                val logoPaths = representatives.associate { record ->
                    record.id to record.snapshot.brandLogoAssetId?.let(pathsByAssetId::get)
                }
                mutableState.value = mutableState.value.copy(
                    records = records,
                    days = projectMonth(year, month, records, paths, logoPaths),
                    summary = summarizeMonth(records),
                )
            }
        }
    }

    private fun observeBrands(type: ItemType) {
        brandJob?.cancel()
        val generation = ++brandGeneration
        val brandType = if (type == ItemType.CHAIN_PRODUCT) BrandType.CHAIN else BrandType.ROASTER
        brandJob = scope.launch {
            catalogRepository.observeBrands(brandType).collect { brands ->
                if (generation != brandGeneration) return@collect
                mutableState.value = mutableState.value.copy(brands = brands)
            }
        }
    }

    private fun restoreDraft() {
        val generation = selectionGeneration
        scope.launch {
            val restored = try {
                journalRepository.restoreDraft()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (generation != selectionGeneration || currentDraft != null) return@launch
            applyRestoredDraft(restored, generation)
        }
    }

    private suspend fun applyRestoredDraft(draft: DrinkDraft, expectedGeneration: Long) {
        val item = try {
            catalogRepository.getItem(draft.sourceItemId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (expectedGeneration != selectionGeneration) return
        currentDraft = draft
        mutableState.value = mutableState.value.copy(
            editor = RecordEditorUi(
                sourceType = draft.itemType,
                selectedBrandId = item?.brandId,
                selectedItemId = item?.id,
                ratingHalfStars = draft.ratingHalfStars,
                priceInput = draft.actualPriceFen?.let(::formatFenInput).orEmpty(),
                actualPriceFen = draft.actualPriceFen,
                brewMethod = draft.brewMethod.orEmpty(),
                note = draft.note,
                consumedAtEpochMillis = draft.consumedAtEpochMillis,
                editingRecordId = draft.editingRecordId,
                invalidItem = item == null,
                selecting = false,
                needsImagePrompt = item?.let { it.status == ItemStatus.NEEDS_IMAGE || it.imageAssetId == null } == true,
                errorMessage = if (item == null) "原产品已不可用，请重新选择；其他内容已保留" else null,
            ),
        )
        observeBrands(draft.itemType)
        item?.let { observeItemsForBrand(it.brandId) }
    }

    private fun observeItemsForBrand(brandId: String) {
        itemJob?.cancel()
        val generation = ++itemGeneration
        itemJob = scope.launch {
            catalogRepository.observeItems(brandId).collect { items ->
                if (generation == itemGeneration) {
                    mutableState.value = mutableState.value.copy(
                        items = items.filter { it.type == mutableState.value.editor.sourceType },
                    )
                }
            }
        }
    }

    private fun changeMonth(delta: Int) {
        val calendar = GregorianCalendar(mutableState.value.year, mutableState.value.month - 1, 1)
        calendar.add(Calendar.MONTH, delta)
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        mutableState.value = JournalUiState.empty(year, month).copy(
            editor = mutableState.value.editor,
            brands = mutableState.value.brands,
            items = mutableState.value.items,
            saveCompletedToken = mutableState.value.saveCompletedToken,
            calendarDisplayMode = mutableState.value.calendarDisplayMode,
        )
        observeMonth()
    }

    private fun setEditorError(message: String) {
        mutableState.value = mutableState.value.copy(editor = mutableState.value.editor.copy(errorMessage = message))
    }

    override fun onCleared() {
        draftQueue.close()
        selectionJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val AUTOSAVE_ERROR = "草稿自动保存失败，请继续编辑重试"
        fun factory(
            journalRepository: JournalRepository,
            catalogRepository: CatalogRepository,
            year: Int,
            month: Int,
            imagePathResolver: ImagePathResolver,
            calendarDisplayPreference: CalendarDisplayPreference = DefaultCalendarDisplayPreference,
            clock: Clock = SystemClock,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                JournalViewModel(
                    journalRepository,
                    catalogRepository,
                    year,
                    month,
                    imagePathResolver = imagePathResolver,
                    clock = clock,
                    calendarDisplayPreference = calendarDisplayPreference,
                ) as T
        }
    }
}

private suspend fun <T> resultOrNullPreservingCancellation(block: suspend () -> T): T? =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

private fun formatFenInput(fen: Long): String =
    "${fen / 100}.${(fen % 100).toString().padStart(2, '0')}"
