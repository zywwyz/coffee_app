package com.niumi.coffeejournal

import android.app.Application
import android.net.Uri
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.catalog.RoomCatalogRepository
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.image.RoomImagePathResolver
import com.niumi.coffeejournal.core.image.ImageStore
import com.niumi.coffeejournal.core.image.LocalImageStore
import com.niumi.coffeejournal.journal.DefaultJournalRepository
import com.niumi.coffeejournal.journal.JournalRepository
import com.niumi.coffeejournal.journal.RoomDrinkStore
import com.niumi.coffeejournal.journal.Clock
import com.niumi.coffeejournal.journal.SystemClock
import com.niumi.coffeejournal.journal.CalendarDisplayPreference
import com.niumi.coffeejournal.journal.SharedPreferencesCalendarDisplayPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import com.niumi.coffeejournal.backup.BackupManager
import com.niumi.coffeejournal.backup.LocalBackupManager

open class CoffeeJournalApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    open val database: CoffeeDatabase by lazy { CoffeeDatabase.create(this) }
    open val journalClock: Clock = SystemClock
    open val calendarDisplayPreference: CalendarDisplayPreference by lazy {
        SharedPreferencesCalendarDisplayPreference(this)
    }

    val catalogRepository: CatalogRepository by lazy {
        RoomCatalogRepository(
            brandDao = database.brandDao(),
            catalogItemDao = database.catalogItemDao(),
            drinkDao = database.drinkDao(),
            imageStore = imageStore,
            resourceUriFactory = { resourceId ->
                Uri.parse("android.resource://$packageName/$resourceId")
            },
        )
    }

    val journalRepository: JournalRepository by lazy {
        DefaultJournalRepository(
            catalogRepository = catalogRepository,
            drinkStore = RoomDrinkStore(database, journalClock),
            clock = journalClock,
        )
    }

    val imagePathResolver: ImagePathResolver by lazy {
        RoomImagePathResolver(database.imageAssetDao())
    }

    val imageStore: ImageStore by lazy {
        LocalImageStore(this, database.imageAssetDao())
    }

    val backupManager: BackupManager by lazy { LocalBackupManager(this, database) }

    /** Called by the real UI entry point, never merely by Application construction. */
    open fun initializeCatalogOnStartup(): Job = applicationScope.launch {
        runCatching { catalogRepository.ensureSeedBrands() }
    }
}
