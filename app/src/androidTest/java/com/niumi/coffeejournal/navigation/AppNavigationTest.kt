package com.niumi.coffeejournal.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bottom_bar_opens_three_roots() {
        compose.setContent { AppNavigation() }

        compose.onNodeWithText("日记").assertIsDisplayed()
        compose.onNodeWithText("豆库").performClick()
        compose.onNodeWithText("连锁品牌").assertIsDisplayed()
        compose.onNodeWithText("总结").performClick()
        compose.onNodeWithText("月度总结").assertIsDisplayed()
    }
}
