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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class JournalViewModel(
    private val journalRepository: JournalRepository,
    private val catalogRepository: CatalogRepository,
    initialYear: Int,
    initialMonth: Int,
    coroutineScope: CoroutineScope? = null,
    private val imagePathResolver: ImagePathResolver = ImagePathResolver { null },
) : ViewModel() {
    private val scope = coroutineScope ?: viewModelScope
    private val mutableState = MutableStateFlow(JournalUiState.empty(initialYear, initialMonth))
    val uiState: StateFlow<JournalUiState> = mutableState.asStateFlow()
    private val draftQueue = Channel<DrinkDraft>(Channel.UNLIMITED)
    private var currentDraft: DrinkDraft? = null
    private var monthJob: Job? = null
    private var brandJob: Job? = null
    private var itemJob: Job? = null

    init {
        scope.launch {
            for (draft in draftQueue) journalRepository.saveDraft(draft)
        }
        observeMonth()
        observeBrands(ItemType.CHAIN_PRODUCT)
    }

    fun previousMonth() = changeMonth(-1)
    fun nextMonth() = changeMonth(1)
    fun selectDate(localDate: String?) { mutableState.value = mutableState.value.copy(selectedDate = localDate) }

    fun setSourceType(type: ItemType) {
        currentDraft = null
        itemJob?.cancel()
        mutableState.value = mutableState.value.copy(
            editor = RecordEditorUi(sourceType = type),
            items = emptyList(),
        )
        observeBrands(type)
    }

    fun selectBrand(brandId: String) {
        currentDraft = null
        mutableState.value = mutableState.value.copy(
            editor = mutableState.value.editor.copy(selectedBrandId = brandId, selectedItemId = null),
            items = emptyList(),
        )
        itemJob?.cancel()
        itemJob = scope.launch {
            catalogRepository.observeItems(brandId).collect { items ->
                mutableState.value = mutableState.value.copy(
                    items = items.filter { it.type == mutableState.value.editor.sourceType },
                )
            }
        }
    }

    fun selectItem(type: ItemType, itemId: String) {
        scope.launch {
            try {
                val item = catalogRepository.getItem(itemId)
                val draft = journalRepository.newDraft(type, itemId)
                currentDraft = draft
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
                        needsImagePrompt = item.status == ItemStatus.NEEDS_IMAGE || item.imageAssetId == null,
                        errorMessage = null,
                    ),
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                setEditorError("无法选择该产品")
            }
        }
    }

    fun setRating(halfStars: Int?) {
        if (halfStars != null && halfStars !in 1..10) return
        editDraft { it.copy(ratingHalfStars = halfStars) }
    }

    fun setPriceInput(input: String) {
        val fen = input.takeIf { it.isNotBlank() }?.let(::parseYuanToFen)
        val valid = input.isBlank() || fen != null
        mutableState.value = mutableState.value.copy(
            editor = mutableState.value.editor.copy(priceInput = input, priceValid = valid, actualPriceFen = fen),
        )
        if (valid) editDraft(updateEditor = false) { it.copy(actualPriceFen = fen) }
    }

    fun setBrewMethod(value: String) = editDraft { it.copy(brewMethod = value.takeUnless(String::isBlank)) }
    fun setNote(value: String) = editDraft { it.copy(note = value) }
    fun skipImagePrompt() { mutableState.value = mutableState.value.copy(editor = mutableState.value.editor.copy(needsImagePrompt = false)) }

    fun save() {
        val editor = mutableState.value.editor
        val draft = currentDraft ?: return
        if (editor.saving || !editor.priceValid) return
        mutableState.value = mutableState.value.copy(editor = editor.copy(saving = true, errorMessage = null))
        scope.launch {
            try {
                journalRepository.save(draft)
                currentDraft = null
                mutableState.value = mutableState.value.copy(
                    editor = RecordEditorUi(sourceType = editor.sourceType),
                    saveCompletedToken = mutableState.value.saveCompletedToken + 1,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    editor = mutableState.value.editor.copy(saving = false, errorMessage = "保存失败，请重试"),
                )
            }
        }
    }

    private fun editDraft(
        updateEditor: Boolean = true,
        transform: (DrinkDraft) -> DrinkDraft,
    ) {
        val updated = currentDraft?.let(transform) ?: return
        currentDraft = updated
        if (updateEditor) {
            mutableState.value = mutableState.value.copy(
                editor = mutableState.value.editor.copy(
                    ratingHalfStars = updated.ratingHalfStars,
                    actualPriceFen = updated.actualPriceFen,
                    brewMethod = updated.brewMethod.orEmpty(),
                    note = updated.note,
                ),
            )
        }
        draftQueue.trySend(updated)
    }

    private fun observeMonth() {
        monthJob?.cancel()
        val year = mutableState.value.year
        val month = mutableState.value.month
        monthJob = scope.launch {
            journalRepository.observeMonth(year, month).collect { records ->
                val paths = records.associate { record ->
                    record.id to runCatching { imagePathResolver.resolve(record.snapshot.imageAssetId) }.getOrNull()
                }
                val logos = records.associate { record ->
                    record.id to runCatching {
                        val item = catalogRepository.getItem(record.sourceItemId)
                        val logoAssetId = catalogRepository.getBrand(item.brandId).logoAssetId
                        imagePathResolver.resolve(logoAssetId)
                    }.getOrNull()
                }
                mutableState.value = mutableState.value.copy(
                    records = records,
                    days = projectMonth(year, month, records, paths, logos),
                    summary = summarizeMonth(records),
                )
            }
        }
    }

    private fun observeBrands(type: ItemType) {
        brandJob?.cancel()
        val brandType = if (type == ItemType.CHAIN_PRODUCT) BrandType.CHAIN else BrandType.ROASTER
        brandJob = scope.launch {
            catalogRepository.observeBrands(brandType).collect { brands ->
                mutableState.value = mutableState.value.copy(brands = brands)
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
        )
        observeMonth()
    }

    private fun setEditorError(message: String) {
        mutableState.value = mutableState.value.copy(editor = mutableState.value.editor.copy(errorMessage = message))
    }

    companion object {
        fun factory(
            journalRepository: JournalRepository,
            catalogRepository: CatalogRepository,
            year: Int,
            month: Int,
            imagePathResolver: ImagePathResolver,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                JournalViewModel(
                    journalRepository,
                    catalogRepository,
                    year,
                    month,
                    imagePathResolver = imagePathResolver,
                ) as T
        }
    }
}

private fun formatFenInput(fen: Long): String =
    "${fen / 100}.${(fen % 100).toString().padStart(2, '0')}"
