package com.niumi.coffeejournal

import android.app.Application
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.catalog.RoomCatalogRepository
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.image.RoomImagePathResolver
import com.niumi.coffeejournal.journal.DefaultJournalRepository
import com.niumi.coffeejournal.journal.JournalRepository
import com.niumi.coffeejournal.journal.RoomDrinkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class CoffeeJournalApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: CoffeeDatabase by lazy { CoffeeDatabase.create(this) }

    val catalogRepository: CatalogRepository by lazy {
        RoomCatalogRepository(
            brandDao = database.brandDao(),
            catalogItemDao = database.catalogItemDao(),
            drinkDao = database.drinkDao(),
        )
    }

    val journalRepository: JournalRepository by lazy {
        DefaultJournalRepository(
            catalogRepository = catalogRepository,
            drinkStore = RoomDrinkStore(database),
        )
    }

    val imagePathResolver: ImagePathResolver by lazy {
        RoomImagePathResolver(database.imageAssetDao())
    }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            try {
                catalogRepository.ensureSeedBrands()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // CatalogViewModel retries and presents a recoverable error when the user opens 豆库.
            }
        }
    }
}
