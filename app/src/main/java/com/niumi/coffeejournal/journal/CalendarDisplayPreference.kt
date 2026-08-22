package com.niumi.coffeejournal.journal

import android.content.Context

enum class CalendarDisplayMode { BRAND, COFFEE }

interface CalendarDisplayPreference {
    fun read(): CalendarDisplayMode
    fun write(mode: CalendarDisplayMode)
}

internal object DefaultCalendarDisplayPreference : CalendarDisplayPreference {
    override fun read() = CalendarDisplayMode.COFFEE
    override fun write(mode: CalendarDisplayMode) = Unit
}

class SharedPreferencesCalendarDisplayPreference(context: Context) : CalendarDisplayPreference {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): CalendarDisplayMode {
        val raw = preferences.getString(DISPLAY_MODE_KEY, null) ?: return CalendarDisplayMode.COFFEE
        return runCatching { CalendarDisplayMode.valueOf(raw) }.getOrDefault(CalendarDisplayMode.COFFEE)
    }

    override fun write(mode: CalendarDisplayMode) {
        preferences.edit().putString(DISPLAY_MODE_KEY, mode.name).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "calendar_ui"
        const val DISPLAY_MODE_KEY = "display_mode"
    }
}
