package com.niumi.coffeejournal

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.runner.AndroidJUnitRunner
import com.niumi.coffeejournal.core.database.CoffeeDatabase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AcceptanceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(classLoader: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(
            classLoader,
            AndroidTestCoffeeJournalApp::class.java.name,
            context,
        )
}

class AndroidTestCoffeeJournalApp : CoffeeJournalApp() {
    private val fixedReading = acceptanceClockReading()
    override val journalClock = object : com.niumi.coffeejournal.journal.Clock {
        override fun read() = fixedReading
    }
    val acceptanceYearMonth: Pair<Int, Int> = fixedReading.localDate
        .let { it.substring(0, 4).toInt() to it.substring(5, 7).toInt() }

    override val database: CoffeeDatabase by lazy {
        Room.inMemoryDatabaseBuilder(this, CoffeeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }
}

private fun acceptanceClockReading(): com.niumi.coffeejournal.journal.ClockReading {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return com.niumi.coffeejournal.journal.ClockReading(
        calendar.timeInMillis,
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(calendar.time),
    )
}
