package com.niumi.coffeejournal.insights

import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.journal.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsViewModelTest {
    @Test
    fun `factory uses injected clock for initial selection and current period cutoff`() = runBlocking {
        val repository = FakeInsightsRepository()
        val clock = FixedClock("2026-08-20")
        val viewModel = InsightsViewModel.factory(
            repository, clock, CoroutineScope(Job() + Dispatchers.Unconfined),
        )
            .create(InsightsViewModel::class.java)
        repository.flow(2026, 8).value = listOf(
            record("today", "2026-08-20"),
            record("future", "2026-08-21"),
        )
        yield()

        assertEquals(2026 to 8, viewModel.uiState.value.year to viewModel.uiState.value.month)
        assertEquals(1, viewModel.uiState.value.monthly?.period?.cupCount)
        assertEquals(20, viewModel.uiState.value.monthly?.trend?.size)
    }

    @Test
    fun `monthly comparison contains real cumulative prior month statistics`() = runBlocking {
        val repository = FakeInsightsRepository()
        val viewModel = viewModel(repository)
        repository.flow(2026, 8).value = listOf(record("aug-1", "2026-08-01"), record("aug-3", "2026-08-03"))
        repository.flow(2026, 7).value = listOf(record("jul-1", "2026-07-01"), record("jul-2", "2026-07-02"), record("jul-3", "2026-07-03"))
        yield()

        val trend = viewModel.uiState.value.monthly!!.trend
        assertEquals(2, viewModel.uiState.value.monthly!!.period.cupCount)
        assertEquals(2, trend[2].current)
        assertEquals(3, trend[2].previous)
    }

    @Test
    fun `changing month cancels old collection so stale data cannot overwrite`() = runBlocking {
        val repository = FakeInsightsRepository()
        val viewModel = InsightsViewModel(
            repository, 2026, 8, CoroutineScope(Job() + Dispatchers.Unconfined),
        )
        repository.flow(2026, 8).value = listOf(record("aug", "2026-08-01"))
        yield()

        viewModel.previousMonth()
        repository.flow(2026, 7).value = listOf(record("jul", "2026-07-01"), record("jul2", "2026-07-02"))
        repository.flow(2026, 8).value = List(9) { record("stale-$it", "2026-08-01") }
        yield()

        assertEquals(7, viewModel.uiState.value.month)
        assertEquals(2, viewModel.uiState.value.monthly?.period?.cupCount)
    }

    @Test
    fun `yearly comparison observes two years and keeps real previous values`() = runBlocking {
        val repository = FakeInsightsRepository()
        val viewModel = viewModel(repository)

        viewModel.showYearly()
        repository.flow(2026, 1).value = listOf(record("current", "2026-01-01"))
        repository.flow(2026, 9).value = listOf(record("future", "2026-09-01"))
        repository.flow(2025, 1).value = listOf(record("previous", "2025-01-01"))
        yield()

        val state = viewModel.uiState.value
        assertEquals(2026, state.year)
        assertTrue((1..12).all { 2026 to it in repository.observed })
        assertTrue((1..12).all { 2025 to it in repository.observed })
        assertEquals(12, state.yearly?.monthlyPoints?.size)
        assertEquals(1, state.yearly?.period?.cupCount)
        assertEquals(8, state.yearly?.trend?.size)
        assertEquals(1, state.yearly?.trend?.first()?.previous)
        assertEquals(null, state.yearly?.trend?.get(1)?.previous)
        assertTrue(state.errorMessage == null)
    }

    @Test
    fun `historical yearly selection includes all twelve months and retains selected month`() = runBlocking {
        val repository = FakeInsightsRepository()
        val viewModel = viewModel(repository)
        viewModel.showYearly()
        viewModel.previousYear()
        repository.flow(2025, 12).value = listOf(record("dec", "2025-12-31"))
        yield()

        assertEquals(2025 to 8, viewModel.uiState.value.year to viewModel.uiState.value.month)
        assertEquals(1, viewModel.uiState.value.yearly?.period?.cupCount)
        assertEquals(12, viewModel.uiState.value.yearly?.trend?.size)

        viewModel.showMonthly()
        assertEquals(2025 to 8, viewModel.uiState.value.year to viewModel.uiState.value.month)
    }

    @Test
    fun `rapid mode switch cancels yearly streams before they can replace monthly statistics`() = runBlocking {
        val repository = FakeInsightsRepository()
        val viewModel = viewModel(repository)
        viewModel.showYearly()
        viewModel.showMonthly()
        repository.flow(2026, 8).value = listOf(record("monthly", "2026-08-01"))
        repository.flow(2026, 1).value = List(9) { record("stale-$it", "2026-01-01") }
        yield()

        assertEquals(InsightsMode.MONTHLY, viewModel.uiState.value.mode)
        assertEquals(1, viewModel.uiState.value.monthly?.period?.cupCount)
        assertEquals(null, viewModel.uiState.value.yearly)
    }

    @Test
    fun `date navigation stays inside supported four digit years`() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val upper = InsightsViewModel(FakeInsightsRepository(), 9999, 12, scope)
        val lower = InsightsViewModel(FakeInsightsRepository(), 1, 1, scope)

        upper.nextMonth()
        upper.nextYear()
        lower.previousMonth()
        lower.previousYear()

        assertEquals(9999 to 12, upper.uiState.value.year to upper.uiState.value.month)
        assertEquals(1 to 1, lower.uiState.value.year to lower.uiState.value.month)
    }

    @Test
    fun `first supported month never observes december as previous baseline`() = runBlocking {
        val repository = FakeInsightsRepository()
        repository.flow(1, 12).value = listOf(record("dec", "0001-12-01"))
        val viewModel = InsightsViewModel(
            repository, 1, 1, CoroutineScope(Job() + Dispatchers.Unconfined),
        )
        yield()

        assertTrue(1 to 12 !in repository.observed)
        assertEquals(SpendDeltaBaseline.MISSING, viewModel.uiState.value.monthly?.spendDelta?.baseline)
    }

    private class FakeInsightsRepository : InsightsRepository {
        private val flows = mutableMapOf<Pair<Int, Int>, MutableStateFlow<List<DrinkRecord>>>()
        val observed = mutableListOf<Pair<Int, Int>>()
        fun flow(year: Int, month: Int) = flows.getOrPut(year to month) { MutableStateFlow(emptyList()) }
        override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> {
            observed += year to month
            return flow(year, month)
        }
    }

    private fun viewModel(repository: FakeInsightsRepository) = InsightsViewModel.factory(
        repository, FixedClock("2026-08-20"), CoroutineScope(Job() + Dispatchers.Unconfined),
    ).create(InsightsViewModel::class.java)

    private class FixedClock(private val localDate: String) : Clock {
        override fun read() = com.niumi.coffeejournal.journal.ClockReading(0, localDate)
    }

    private fun record(id: String, date: String) = DrinkRecord(
        id, 0, date, ItemType.CHAIN_PRODUCT, "source", null, null, 100, null,
        DrinkSnapshot("品牌", "产品", null, null, null),
    )
}
