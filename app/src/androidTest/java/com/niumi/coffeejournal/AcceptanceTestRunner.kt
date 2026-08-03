package com.niumi.coffeejournal

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.runner.AndroidJUnitRunner
import com.niumi.coffeejournal.core.database.CoffeeDatabase

class AcceptanceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(classLoader: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(
            classLoader,
            AndroidTestCoffeeJournalApp::class.java.name,
            context,
        )
}

class AndroidTestCoffeeJournalApp : CoffeeJournalApp() {
    override val database: CoffeeDatabase by lazy {
        Room.inMemoryDatabaseBuilder(this, CoffeeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }
}
