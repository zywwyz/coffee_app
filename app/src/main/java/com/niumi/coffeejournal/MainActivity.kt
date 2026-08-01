package com.niumi.coffeejournal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.niumi.coffeejournal.navigation.AppNavigation
import com.niumi.coffeejournal.ui.theme.CoffeeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CoffeeTheme {
                AppNavigation()
            }
        }
    }
}
