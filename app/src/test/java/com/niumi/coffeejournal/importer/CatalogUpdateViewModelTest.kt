package com.niumi.coffeejournal.importer

import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogUpdateViewModelTest {
    @Test
    fun `construction never starts background network and manual request opens review`() {
        val source = FakeCatalogSource(SourceResult.Success(9, "https://official/menu", listOf(candidate("拿铁"))))
        val gateway = FakeCatalogUpdateGateway(review())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = CatalogUpdateViewModel(FakeSourceProvider(source), gateway, scope)

        assertEquals(0, source.fetchCalls)

        viewModel.requestUpdate(brand())

        assertEquals(1, source.fetchCalls)
        assertEquals(UpdatePhase.REVIEW, viewModel.uiState.value.phase)
        assertEquals(setOf("new", "missing"), viewModel.uiState.value.selectedKeys)
    }

    @Test
    fun `only checked review entries are applied after explicit confirmation`() {
        val source = FakeCatalogSource(SourceResult.Success(9, "https://official/menu", listOf(candidate("拿铁"))))
        val gateway = FakeCatalogUpdateGateway(review())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = CatalogUpdateViewModel(FakeSourceProvider(source), gateway, scope)
        viewModel.requestUpdate(brand())

        viewModel.toggleSelected("missing")
        viewModel.confirmSelected()

        assertEquals(setOf("new"), gateway.applied)
        assertEquals(UpdatePhase.IDLE, viewModel.uiState.value.phase)
    }

    @Test
    fun `classified failure exposes screenshot and manual fallback state`() {
        val source = FakeCatalogSource(SourceResult.Failure(FailureKind.PARSE_CHANGED, "结构变化"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = CatalogUpdateViewModel(FakeSourceProvider(source), FakeCatalogUpdateGateway(review()), scope)

        viewModel.requestUpdate(brand())

        assertEquals(UpdatePhase.FAILURE, viewModel.uiState.value.phase)
        assertEquals(FailureKind.PARSE_CHANGED, viewModel.uiState.value.failureKind)
        assertTrue(viewModel.uiState.value.showFallbackActions)
    }

    @Test
    fun `dismiss during loading promptly cancels fetch and returns idle`() {
        var cancelled = false
        val source = object : CatalogSource {
            override val brandKey = "brand"
            override suspend fun fetch(): SourceResult = try {
                awaitCancellation()
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = CatalogUpdateViewModel(FakeSourceProvider(source), FakeCatalogUpdateGateway(review()), scope)

        viewModel.requestUpdate(brand())
        assertEquals(UpdatePhase.LOADING, viewModel.uiState.value.phase)
        viewModel.dismiss()

        assertTrue(cancelled)
        assertEquals(UpdatePhase.IDLE, viewModel.uiState.value.phase)
    }

    private fun brand() = Brand(
        "brand", BrandType.CHAIN, "瑞幸", null, MaintenanceMode.PUBLIC_SOURCE,
        "https://www.luckincoffee.com/cn/menu/signature-lattes",
    )
    private fun candidate(name: String) = CatalogCandidate(name, null, null, null, "https://official/$name", null)
    private fun review() = CatalogReview(
        "brand", 9, "https://official/menu",
        listOf(
            CatalogChange("new", ChangeType.ADDED, "新品", null, candidate("新品"), emptyList()),
            CatalogChange("missing", ChangeType.POSSIBLY_DISCONTINUED, "旧品", null, null, emptyList()),
        ),
    )
}

private class FakeCatalogSource(private val result: SourceResult) : CatalogSource {
    override val brandKey = "brand"
    var fetchCalls = 0
    override suspend fun fetch(): SourceResult { fetchCalls++; return result }
}

private class FakeSourceProvider(private val source: CatalogSource) : CatalogSourceProvider {
    override fun sourceFor(brand: Brand): CatalogSource = source
}

private class FakeCatalogUpdateGateway(private val review: CatalogReview) : CatalogUpdateGateway {
    var applied: Set<String>? = null
    override suspend fun review(brandId: String, result: SourceResult.Success): CatalogReview = review
    override suspend fun applySelected(review: CatalogReview, selectedKeys: Set<String>): ApplyCatalogResult {
        applied = selectedKeys
        return ApplyCatalogResult(selectedKeys.size, 0)
    }
}
