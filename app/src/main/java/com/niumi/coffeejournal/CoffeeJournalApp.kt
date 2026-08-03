package com.niumi.coffeejournal

import android.app.Application
import com.niumi.coffeejournal.catalog.CatalogRepository
import com.niumi.coffeejournal.catalog.RoomCatalogRepository
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import com.niumi.coffeejournal.core.image.ImagePathResolver
import com.niumi.coffeejournal.core.image.RoomImagePathResolver
import com.niumi.coffeejournal.core.image.ImageStore
import com.niumi.coffeejournal.core.image.LocalImageStore
import com.niumi.coffeejournal.importer.MlKitScreenshotTextRecognizer
import com.niumi.coffeejournal.importer.ScreenshotTextRecognizer
import com.niumi.coffeejournal.importer.CatalogSourceProvider
import com.niumi.coffeejournal.importer.CatalogUpdateApplier
import com.niumi.coffeejournal.importer.CatalogUpdateGateway
import com.niumi.coffeejournal.importer.DefaultCatalogSourceProvider
import com.niumi.coffeejournal.importer.LocalOfficialImageAssetStore
import com.niumi.coffeejournal.importer.SafeOfficialHttpClient
import com.niumi.coffeejournal.importer.SafeOfficialImageDownloader
import com.niumi.coffeejournal.importer.ValidatingOfficialImageImporter
import com.niumi.coffeejournal.journal.DefaultJournalRepository
import com.niumi.coffeejournal.journal.JournalRepository
import com.niumi.coffeejournal.journal.RoomDrinkStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.niumi.coffeejournal.backup.BackupManager
import com.niumi.coffeejournal.backup.LocalBackupManager

open class CoffeeJournalApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    open val database: CoffeeDatabase by lazy { CoffeeDatabase.create(this) }

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

    val imageStore: ImageStore by lazy {
        LocalImageStore(this, database.imageAssetDao())
    }

    val backupManager: BackupManager by lazy { LocalBackupManager(this, database) }

    val screenshotTextRecognizer: ScreenshotTextRecognizer by lazy {
        MlKitScreenshotTextRecognizer(this)
    }

    val catalogUpdateSources: CatalogSourceProvider by lazy {
        DefaultCatalogSourceProvider(SafeOfficialHttpClient())
    }

    val catalogUpdateGateway: CatalogUpdateGateway by lazy {
        val officialImages = ValidatingOfficialImageImporter(
            downloader = SafeOfficialImageDownloader(),
            assetStore = LocalOfficialImageAssetStore(this, imageStore),
        )
        CatalogUpdateApplier(database, officialImages)
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
