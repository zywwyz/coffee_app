package com.niumi.coffeejournal.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarDisplayPreferenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `empty and corrupt preference default to coffee`() {
        context.getSharedPreferences("calendar_ui", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(CalendarDisplayMode.COFFEE, SharedPreferencesCalendarDisplayPreference(context).read())

        context.getSharedPreferences("calendar_ui", Context.MODE_PRIVATE)
            .edit().putString("display_mode", "not-a-mode").commit()

        assertEquals(CalendarDisplayMode.COFFEE, SharedPreferencesCalendarDisplayPreference(context).read())
    }

    @Test
    fun `brand selection persists to a fresh preference`() {
        context.getSharedPreferences("calendar_ui", Context.MODE_PRIVATE).edit().clear().commit()
        SharedPreferencesCalendarDisplayPreference(context).write(CalendarDisplayMode.BRAND)

        assertEquals(CalendarDisplayMode.BRAND, SharedPreferencesCalendarDisplayPreference(context).read())
    }
}
