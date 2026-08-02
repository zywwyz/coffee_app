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
        setContent {
            CoffeeTheme {
                AppNavigation(
                    journalRepository = app.journalRepository,
                    catalogRepository = app.catalogRepository,
                    imagePathResolver = app.imagePathResolver,
                    imageStore = app.imageStore,
                    screenshotTextRecognizer = app.screenshotTextRecognizer,
                    catalogUpdateSources = app.catalogUpdateSources,
                    catalogUpdateGateway = app.catalogUpdateGateway,
                )
            }
        }
    }
}
