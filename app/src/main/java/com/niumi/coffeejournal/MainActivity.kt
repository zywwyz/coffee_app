package com.niumi.coffeejournal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.niumi.coffeejournal.navigation.AppNavigation
import com.niumi.coffeejournal.ui.theme.CoffeeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CoffeeJournalApp
        app.initializeCatalogOnStartup()
        setContent {
            CoffeeTheme {
                AppNavigation(
                    journalRepository = app.journalRepository,
                    catalogRepository = app.catalogRepository,
                    journalClock = app.journalClock,
                    calendarDisplayPreference = app.calendarDisplayPreference,
                    imagePathResolver = app.imagePathResolver,
                    imageStore = app.imageStore,
                    backupManager = app.backupManager,
                )
            }
        }
    }
}
