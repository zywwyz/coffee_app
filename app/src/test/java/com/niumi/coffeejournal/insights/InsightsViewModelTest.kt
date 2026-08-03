package com.niumi.coffeejournal.insights

import com.niumi.coffeejournal.core.model.DrinkRecord
import com.niumi.coffeejournal.core.model.DrinkSnapshot
import com.niumi.coffeejournal.core.model.ItemType
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
    fun `year switch emits twelve month facts for selected year`() = runBlocking {
        val repository = FakeInsightsRepository()
        val viewModel = InsightsViewModel(
            repository, 2026, 8, CoroutineScope(Job() + Dispatchers.Unconfined),
        )

        viewModel.showYearly()
        viewModel.previousYear()
        repository.flow(2025, 1).value = listOf(record("jan", "2025-01-01"))
        yield()

        val state = viewModel.uiState.value
        assertEquals(2025, state.year)
        assertEquals(12, state.yearly?.monthlyPoints?.size)
        assertEquals(1, state.yearly?.period?.cupCount)
        assertTrue(state.errorMessage == null)
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

    private class FakeInsightsRepository : InsightsRepository {
        private val flows = mutableMapOf<Pair<Int, Int>, MutableStateFlow<List<DrinkRecord>>>()
        fun flow(year: Int, month: Int) = flows.getOrPut(year to month) { MutableStateFlow(emptyList()) }
        override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> = flow(year, month)
    }

    private fun record(id: String, date: String) = DrinkRecord(
        id, 0, date, ItemType.CHAIN_PRODUCT, "source", null, null, 100, null,
        DrinkSnapshot("品牌", "产品", null, null, null),
    )
}
