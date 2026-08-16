package com.niumi.coffeejournal.journal

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import com.niumi.coffeejournal.catalog.ManualProductEditorViewModel
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
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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
        val source = File(context.cacheDir, "quick-add.png").also { file -> FileOutputStream(file).use { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val image = images.importWhole(Uri.fromFile(source), ImageKind.PRODUCT)
        val editor = ManualProductEditorViewModel(catalog, images, CoroutineScope(Dispatchers.Unconfined), idGenerator = { "fruit" })
        editor.openNew(brand); editor.setName("果咖"); editor.setKind(ChainProductKind.FRUIT); editor.acceptImportedAsset(image.id); editor.save(); yield()
        val saved = withTimeout(5_000) { catalog.observeItems("brand").first { items -> items.any { it.id == "fruit" } }.single { it.id == "fruit" } }
        assertEquals(ChainProductKind.FRUIT, saved.chainProductKind)
        assertEquals(source.readBytes().toList(), File(image.localPath).readBytes().toList())

        val repository = DefaultJournalRepository(catalog, RoomDrinkStore(database), object : Clock { override fun read() = ClockReading(1000L, "1970-01-01") })
        val old = repository.newDraft(ItemType.CHAIN_PRODUCT, "fruit").copy(consumedAtEpochMillis = 1234, ratingHalfStars = 9, actualPriceFen = 990, brewMethod = "手冲", note = "备注")
        val replaced = repository.replaceDraftForItem(old, ItemType.CHAIN_PRODUCT, "fruit")
        assertEquals("fruit", replaced.sourceItemId)
        assertEquals(old.consumedAtEpochMillis, replaced.consumedAtEpochMillis)
        assertEquals(old.ratingHalfStars, replaced.ratingHalfStars)
        assertEquals(old.actualPriceFen, replaced.actualPriceFen)
        assertEquals(old.brewMethod, replaced.brewMethod)
        assertEquals(old.note, replaced.note)
        editor.completeSaved()
    }
}
