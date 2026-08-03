package com.niumi.coffeejournal.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.journal.JournalRepository
import java.util.GregorianCalendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

interface InsightsRepository {
    fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>>
}

class JournalInsightsRepository(private val journalRepository: JournalRepository) : InsightsRepository {
    override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> =
        journalRepository.observeMonth(year, month)
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

    private suspend fun observeMonthly(selected: InsightsUiState, selectedGeneration: Long) {
        val previous = GregorianCalendar(selected.year, selected.month - 1, 1).apply {
            isLenient = false
            add(GregorianCalendar.MONTH, -1)
        }
        combine(
            repository.observeMonth(selected.year, selected.month),
            repository.observeMonth(
                previous.get(GregorianCalendar.YEAR), previous.get(GregorianCalendar.MONTH) + 1,
            ),
        ) { current, prior -> current to prior }.collect { (current, prior) ->
            if (generation != selectedGeneration) return@collect
            mutableState.value = mutableState.value.copy(
                loading = false,
                monthly = InsightsCalculator.monthly(selected.year, selected.month, current, prior),
                yearly = null,
                errorMessage = null,
            )
        }
    }

    private suspend fun observeYearly(selected: InsightsUiState, selectedGeneration: Long) {
        val months = (1..12).map { repository.observeMonth(selected.year, it) }
        combine(months) { emissions -> emissions.flatMap { it } }.collect { records ->
            if (generation != selectedGeneration) return@collect
            mutableState.value = mutableState.value.copy(
                loading = false,
                yearly = InsightsCalculator.yearly(selected.year, records),
                monthly = null,
                errorMessage = null,
            )
        }
    }
}
