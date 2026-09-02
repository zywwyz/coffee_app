package com.niumi.coffeejournal.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.journal.JournalRepository
import com.niumi.coffeejournal.journal.Clock
import com.niumi.coffeejournal.journal.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

interface InsightsRepository {
    fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>>
    fun observeRange(startLocalDate: String, endLocalDate: String): Flow<List<DrinkRecord>>
}

class JournalInsightsRepository(private val journalRepository: JournalRepository) : InsightsRepository {
    override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> =
        journalRepository.observeMonth(year, month)
    override fun observeRange(startLocalDate: String, endLocalDate: String): Flow<List<DrinkRecord>> =
        journalRepository.observeRange(startLocalDate, endLocalDate)
}

enum class InsightsMode { MONTHLY, YEARLY }

data class InsightsUiState(
    val year: Int,
    val month: Int,
    val mode: InsightsMode = InsightsMode.MONTHLY,
    val loading: Boolean = true,
    val monthly: MonthlyInsights? = null,
    val yearly: YearlyInsights? = null,
    val errorMessage: String? = null,
)

class InsightsViewModel(
    private val repository: InsightsRepository,
    initialYear: Int,
    initialMonth: Int,
    coroutineScope: CoroutineScope? = null,
    private val clock: Clock = SystemClock,
) : ViewModel() {
    private val scope = coroutineScope ?: viewModelScope
    private val mutableState = MutableStateFlow(InsightsUiState(initialYear, initialMonth))
    val uiState: StateFlow<InsightsUiState> = mutableState.asStateFlow()
    private var observation: Job? = null
    private var generation = 0L

    init {
        require(initialYear in 1..9999)
        require(initialMonth in 1..12)
        observeSelection()
    }

    fun showMonthly() = changeMode(InsightsMode.MONTHLY)
    fun showYearly() = changeMode(InsightsMode.YEARLY)

    fun previousMonth() = moveMonth(-1)
    fun nextMonth() = moveMonth(1)
    fun previousYear() = moveYear(-1)
    fun nextYear() = moveYear(1)

    private fun changeMode(mode: InsightsMode) {
        if (mutableState.value.mode == mode) return
        mutableState.value = mutableState.value.copy(mode = mode, loading = true, errorMessage = null)
        observeSelection()
    }

    private fun moveMonth(amount: Int) {
        val state = mutableState.value
        val currentIndex = (state.year - 1L) * 12L + state.month - 1L
        val targetIndex = (currentIndex + amount).coerceIn(0L, 9999L * 12L - 1L)
        val targetYear = (targetIndex / 12L + 1L).toInt()
        val targetMonth = (targetIndex % 12L + 1L).toInt()
        if (targetYear == state.year && targetMonth == state.month) return
        mutableState.value = state.copy(
            year = targetYear,
            month = targetMonth,
            loading = true,
            monthly = null,
            errorMessage = null,
        )
        observeSelection()
    }

    private fun moveYear(amount: Int) {
        val target = (mutableState.value.year + amount).coerceIn(1, 9999)
        if (target == mutableState.value.year) return
        mutableState.value = mutableState.value.copy(
            year = target, loading = true, yearly = null, monthly = null, errorMessage = null,
        )
        observeSelection()
    }

    private fun observeSelection() {
        observation?.cancel()
        val selected = mutableState.value
        val selectedGeneration = ++generation
        observation = scope.launch {
            try {
                when (selected.mode) {
                    InsightsMode.MONTHLY -> observeMonthly(selected, selectedGeneration)
                    InsightsMode.YEARLY -> observeYearly(selected, selectedGeneration)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == selectedGeneration) {
                    mutableState.value = mutableState.value.copy(loading = false, errorMessage = "总结暂时无法计算")
                }
            }
        }
    }

    companion object {
        fun factory(
            repository: InsightsRepository,
            clock: Clock = SystemClock,
            coroutineScope: CoroutineScope? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val localDate = clock.read().localDate
                    return InsightsViewModel(
                        repository,
                        localDate.substring(0, 4).toInt(),
                        localDate.substring(5, 7).toInt(),
                        coroutineScope,
                        clock,
                    ) as T
                }
            }
    }

    private suspend fun observeMonthly(selected: InsightsUiState, selectedGeneration: Long) {
        val previous = when {
            selected.year == 1 && selected.month == 1 -> null
            selected.month == 1 -> selected.year - 1 to 12
            else -> selected.year to selected.month - 1
        }
        val previousRecords = previous?.let { (year, month) ->
            repository.observeMonth(year, month)
        } ?: flowOf(emptyList())
        combine(
            repository.observeMonth(selected.year, selected.month),
            previousRecords,
        ) { current, prior -> current to prior }.collect { (current, prior) ->
            if (generation != selectedGeneration) return@collect
            mutableState.value = mutableState.value.copy(
                loading = false,
                monthly = InsightsCalculator.monthly(
                    selected.year, selected.month, current, prior, clock.read().localDate,
                ),
                yearly = null,
                errorMessage = null,
            )
        }
    }

    private suspend fun observeYearly(selected: InsightsUiState, selectedGeneration: Long) {
        val startYear = if (selected.year == 1) 1 else selected.year - 1
        repository.observeRange(
            "%04d-01-01".format(startYear), "%04d-12-31".format(selected.year),
        ).collect { records ->
            if (generation != selectedGeneration) return@collect
            mutableState.value = mutableState.value.copy(
                loading = false,
                yearly = InsightsCalculator.yearly(selected.year, records, clock.read().localDate),
                monthly = null,
                errorMessage = null,
            )
        }
    }
}
