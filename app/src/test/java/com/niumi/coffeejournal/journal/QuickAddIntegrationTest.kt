package com.niumi.coffeejournal.journal

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import com.niumi.coffeejournal.catalog.ManualProductEditorViewModel
import com.niumi.coffeejournal.catalog.ManualProductEditorEvent
import com.niumi.coffeejournal.catalog.RoomCatalogRepository
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.image.ImageKind
import com.niumi.coffeejournal.core.image.LocalImageStore
import com.niumi.coffeejournal.core.model.Brand
import com.niumi.coffeejournal.core.model.BrandType
import com.niumi.coffeejournal.core.model.ChainProductKind
import com.niumi.coffeejournal.core.model.ItemStatus
import com.niumi.coffeejournal.core.model.ItemType
import com.niumi.coffeejournal.core.model.MaintenanceMode
import com.niumi.coffeejournal.core.model.CatalogItem
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickAddIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: CoffeeDatabase
    private lateinit var catalog: RoomCatalogRepository
    private lateinit var images: LocalImageStore

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "images").deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, CoffeeDatabase::class.java).allowMainThreadQueries().build()
        images = LocalImageStore(context, database.imageAssetDao())
        catalog = RoomCatalogRepository(database.brandDao(), database.catalogItemDao(), database.drinkDao(), images)
    }
    @After fun tearDown() { database.close(); File(context.filesDir, "images").deleteRecursively() }

    @Test fun `quick add fruit product stores original image and preserves draft fields`() = runBlocking {
        val brand = Brand("brand", BrandType.CHAIN, "品牌", null, MaintenanceMode.MANUAL_ONLY, null)
        catalog.upsertBrand(brand)
        catalog.upsertItem(CatalogItem("seed", "brand", ItemType.CHAIN_PRODUCT, "原产品", null, null, null, null, null, null, ItemStatus.ACTIVE, chainProductKind = ChainProductKind.BLACK))
        val repository = DefaultJournalRepository(catalog, RoomDrinkStore(database), object : Clock { override fun read() = ClockReading(1000L, "1970-01-01") })
        val journal = JournalViewModel(repository, catalog, 1970, 1, CoroutineScope(Dispatchers.Unconfined))
        journal.selectItem(ItemType.CHAIN_PRODUCT, "seed")
        withTimeout(5_000) { journal.uiState.first { it.editor.selectedItemId == "seed" } }
        journal.setConsumedAt(1234)
        withTimeout(5_000) { journal.uiState.first { it.editor.consumedAtEpochMillis == 1234L } }
        journal.setRating(9)
        withTimeout(5_000) { journal.uiState.first { it.editor.ratingHalfStars == 9 } }
        journal.setPriceInput("9.90")
        withTimeout(5_000) { journal.uiState.first { it.editor.priceInput == "9.90" } }
        journal.setBrewMethod("手冲")
        withTimeout(5_000) { journal.uiState.first { it.editor.brewMethod == "手冲" } }
        journal.setNote("备注")
        withTimeout(5_000) { journal.uiState.first { it.editor.note == "备注" } }
        val source = File(context.cacheDir, "quick-add.png").also { file -> FileOutputStream(file).use { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val image = images.importWhole(Uri.fromFile(source), ImageKind.PRODUCT)
        val editor = ManualProductEditorViewModel(catalog, images, CoroutineScope(Dispatchers.Unconfined), idGenerator = { "fruit" })
        val event = async { withTimeout(5_000) { editor.events.first() } }; yield()
        editor.openNew(brand); editor.setName("果咖"); editor.setKind(ChainProductKind.FRUIT); editor.acceptImportedAsset(image.id); editor.save(); yield()
        val saved = withTimeout(5_000) { catalog.observeItems("brand").first { items -> items.any { it.id == "fruit" } }.single { it.id == "fruit" } }
        assertEquals(ChainProductKind.FRUIT, saved.chainProductKind)
        assertEquals(source.readBytes().toList(), File(image.localPath).readBytes().toList())
        assertNotNull(database.imageAssetDao().get(image.id))

        val savedEvent = event.await() as ManualProductEditorEvent.Saved
        journal.selectItem(ItemType.CHAIN_PRODUCT, savedEvent.itemId)
        withTimeout(5_000) { journal.uiState.first { it.editor.selectedItemId == "fruit" } }
        val replaced = journal.uiState.value.editor
        assertEquals("fruit", replaced.selectedItemId)
        assertEquals(1234, replaced.consumedAtEpochMillis)
        assertEquals(9, replaced.ratingHalfStars)
        assertEquals("9.90", replaced.priceInput)
        assertEquals("手冲", replaced.brewMethod)
        assertEquals("备注", replaced.note)
        editor.completeSaved()
        assertFalse(editor.state.value.open)
    }
}
