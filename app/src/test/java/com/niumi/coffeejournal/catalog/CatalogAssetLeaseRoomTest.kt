package com.niumi.coffeejournal.catalog

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.database.ImageAssetEntity
import com.niumi.coffeejournal.core.image.CropRect
import com.niumi.coffeejournal.core.image.ImageAsset
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.ImageStore
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.CatalogItem
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogAssetLeaseRoomTest {
    private lateinit var database: CoffeeDatabase
    private lateinit var repository: RoomCatalogRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        database = Room.inMemoryDatabaseBuilder(context, CoffeeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomCatalogRepository(
            database.brandDao(), database.catalogItemDao(), database.drinkDao(),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `old image cleanup starts only after room commits the new association`() = runBlocking {
        database.imageAssetDao().upsert(asset("old-image", "old-sha"))
        database.imageAssetDao().upsert(asset("new-image", "new-sha"))
        repository.upsertBrand(
            Brand(
                id = "roaster", type = BrandType.ROASTER, name = "烘焙商",
                logoAssetId = null, maintenanceMode = MaintenanceMode.MANUAL_ONLY,
                publicSourceUrl = null,
            ),
        )
        repository.upsertItem(item("old-image"))
        val images = DatabaseAwareImageStore(database)
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val viewModel = CatalogViewModel(
            repository = repository, imageStore = images,
            coroutineScope = scope, leaseCleanupScope = scope,
        )

        assertTrue(viewModel.retainAssetLease("editor", "old-image"))
        assertTrue(viewModel.stageAsset("editor", "old-image", "new-image"))
        assertTrue(images.deleteAttempts.isEmpty())
        viewModel.saveItem(
            ItemEditor(
                id = "bean", brandId = "roaster", type = ItemType.PERSONAL_BEAN,
                name = "豆子", imageAssetId = "new-image", origin = null,
                processing = null, roastLevel = null, flavorNotes = null,
                brewMethod = null, status = ItemStatus.ACTIVE, assetLeaseId = "editor",
            ),
        )
        withTimeout(5_000) {
            viewModel.uiState.first { it.saveCompletedToken == 1L }
        }

        assertEquals("new-image", repository.getItem("bean").imageAssetId)
        assertEquals(listOf("old-image"), images.deleteAttempts)
        assertEquals(listOf("new-image"), images.associationsAtDelete)
        assertFalse("new-image" in images.deleteAttempts)
    }

    private fun asset(id: String, sha: String) = ImageAssetEntity(
        id = id, localPath = "/unused/$id", sha256 = sha,
        kind = "PRODUCT", createdAtEpochMillis = 1,
    )

    private fun item(imageAssetId: String) = CatalogItem(
        id = "bean", brandId = "roaster", type = ItemType.PERSONAL_BEAN, name = "豆子",
        imageAssetId = imageAssetId, origin = null, processing = null, roastLevel = null,
        flavorNotes = null, brewMethod = null, status = ItemStatus.ACTIVE,
    )

    private class DatabaseAwareImageStore(
        private val database: CoffeeDatabase,
    ) : ImageStore {
        val deleteAttempts = mutableListOf<String>()
        val associationsAtDelete = mutableListOf<String?>()

        override suspend fun importCropped(
            source: Uri,
            crop: CropRect,
            kind: ImageKind,
        ): ImageAsset = error("unexpected")

        override suspend fun importWhole(source: Uri, kind: ImageKind): ImageAsset =
            error("unexpected")

        override suspend fun deleteIfUnreferenced(assetId: String): Boolean {
            deleteAttempts += assetId
            associationsAtDelete += database.catalogItemDao().get("bean")?.imageAssetId
            return true
        }
    }
}
