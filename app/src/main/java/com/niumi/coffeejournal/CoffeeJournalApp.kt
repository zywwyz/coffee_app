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

class CoffeeJournalApp : Application() {
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
}
