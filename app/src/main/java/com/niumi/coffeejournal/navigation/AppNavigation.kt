package com.niumi.coffeejournal.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

@Serializable
data object Journal : NavKey

@Serializable
data object Catalog : NavKey

@Serializable
data object Insights : NavKey

private data class RootDestination(
    val key: NavKey,
    val label: String,
    val iconLabel: String,
)

private val RootDestinations = listOf(
    RootDestination(Journal, "日记", "咖啡"),
    RootDestination(Catalog, "豆库", "豆"),
    RootDestination(Insights, "总结", "图"),
)

@Composable
fun AppNavigation() {
    val backStack = rememberNavBackStack(Journal)
    val selectedRoot = backStack.last()

    Scaffold(
        bottomBar = {
            NavigationBar {
                RootDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = selectedRoot == destination.key,
                        onClick = {
                            if (backStack.last() != destination.key) {
                                backStack.clear()
                                backStack.add(destination.key)
                            }
                        },
                        icon = { Text(destination.iconLabel) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.padding(contentPadding),
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryProvider = entryProvider {
                entry<Journal> { RootContent("咖啡日历", "记录今天的咖啡") }
                entry<Catalog> { RootContent("连锁品牌", "管理连锁产品与个人豆库") }
                entry<Insights> { RootContent("月度总结", "查看饮用、评分与消费趋势") }
            },
        )
    }
}

@Composable
private fun RootContent(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(text = subtitle, style = MaterialTheme.typography.bodyLarge)
    }
}
