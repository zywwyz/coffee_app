package com.niumi.coffeejournal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = InMemoryCoffeeJournalApp::class)
class MainActivityWindowThemeTest {
    @Test
    fun main_activity_uses_chinese_label_and_no_action_bar_theme() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertEquals("咖啡日历", activity.applicationInfo.loadLabel(activity.packageManager))
        assertEquals(R.style.Theme_CoffeeJournal, activity.applicationInfo.theme)
        assertNull(activity.actionBar)
    }
}
