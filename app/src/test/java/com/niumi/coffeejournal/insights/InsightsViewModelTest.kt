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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
        repository.range("2025-01-01", "2026-12-31").value = listOf(
            record("current", "2026-01-01"), record("future", "2026-09-01"), record("previous", "2025-01-01"),
        )
        yield()

        val state = viewModel.uiState.value
        assertEquals(2026, state.year)
        assertEquals(1, repository.rangeSubscriptions.size)
        assertEquals(12, state.yearly?.monthlyPoints?.size)
        assertEquals(1, state.yearly?.period?.cupCount)
        assertEquals(8, state.yearly?.trend?.size)
        assertEquals(1, state.yearly?.trend?.first()?.previous)
        assertEquals(0, state.yearly?.trend?.get(1)?.previous)
        assertTrue(state.errorMessage == null)
    }

    @Test
    fun `historical yearly selection includes all twelve months and retains selected month`() = runBlocking {
        val repository = FakeInsightsRepository()
        val viewModel = viewModel(repository)
        viewModel.showYearly()
        viewModel.previousYear()
        repository.range("2024-01-01", "2025-12-31").value = listOf(record("dec", "2025-12-31"))
        yield()

        assertEquals(2025 to 8, viewModel.uiState.value.year to viewModel.uiState.value.month)
        assertEquals(1, viewModel.uiState.value.yearly?.period?.cupCount)
        assertEquals(12, viewModel.uiState.value.yearly?.trend?.size)

        viewModel.showMonthly()
        assertEquals(2025 to 8, viewModel.uiState.value.year to viewModel.uiState.value.month)
    }

    @Test
    fun `rapid mode switch cancels single yearly range before queued emission can replace monthly statistics`() = runTest {
        val repository = FakeInsightsRepository()
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val viewModel = viewModel(repository, scope)
        testScheduler.runCurrent()
        viewModel.showYearly()
        testScheduler.runCurrent()
        val yearlySubscription = repository.rangeSubscriptions.single { it.active }
        viewModel.showMonthly()
        repository.flow(2026, 8).value = listOf(record("monthly", "2026-08-01"))
        repository.range("2025-01-01", "2026-12-31").value = List(9) { record("stale-$it", "2026-01-01") }
        testScheduler.runCurrent()

        assertTrue(!yearlySubscription.active)
        assertEquals(1, repository.rangeSubscriptions.size)
        assertEquals(2, repository.subscriptions.count { it.active })
        assertEquals(setOf(2026 to 8, 2026 to 7), repository.subscriptions.filter { it.active }.map { it.month }.toSet())
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
        data class Subscription(val month: Pair<Int, Int>, var active: Boolean = false)

        private val flows = mutableMapOf<Pair<Int, Int>, MutableStateFlow<List<DrinkRecord>>>()
        private val ranges = mutableMapOf<Pair<String, String>, MutableStateFlow<List<DrinkRecord>>>()
        val observed = mutableListOf<Pair<Int, Int>>()
        val subscriptions = mutableListOf<Subscription>()
        val rangeSubscriptions = mutableListOf<Subscription>()
        fun flow(year: Int, month: Int) = flows.getOrPut(year to month) { MutableStateFlow(emptyList()) }
        fun range(start: String, end: String) = ranges.getOrPut(start to end) { MutableStateFlow(emptyList()) }
        override fun observeMonth(year: Int, month: Int): Flow<List<DrinkRecord>> {
            observed += year to month
            val subscription = Subscription(year to month)
            subscriptions += subscription
            return flow(year, month)
                .onStart { subscription.active = true }
                .onCompletion { subscription.active = false }
        }
        override fun observeRange(startLocalDate: String, endLocalDate: String): Flow<List<DrinkRecord>> {
            val subscription = Subscription(0 to 0)
            rangeSubscriptions += subscription
            return range(startLocalDate, endLocalDate)
                .onStart { subscription.active = true }
                .onCompletion { subscription.active = false }
        }
    }

    private fun viewModel(repository: FakeInsightsRepository, scope: CoroutineScope = CoroutineScope(Job() + Dispatchers.Unconfined)) = InsightsViewModel.factory(
        repository, FixedClock("2026-08-20"), scope,
    ).create(InsightsViewModel::class.java)

    private class FixedClock(private val localDate: String) : Clock {
        override fun read() = com.niumi.coffeejournal.journal.ClockReading(0, localDate)
    }

    private fun record(id: String, date: String) = DrinkRecord(
        id, 0, date, ItemType.CHAIN_PRODUCT, "source", null, null, 100, null,
        DrinkSnapshot("品牌", "产品", null, null, null),
    )
}
