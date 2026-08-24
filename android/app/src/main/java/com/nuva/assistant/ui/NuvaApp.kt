package com.nuva.assistant.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nuva.assistant.R
import com.nuva.assistant.ui.history.HistoryScreen
import com.nuva.assistant.ui.home.HomeScreen
import com.nuva.assistant.ui.memory.MemoryScreen
import com.nuva.assistant.ui.settings.SettingsScreen

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", R.string.nav_home, Icons.Filled.Mic),
    Tab("history", R.string.nav_history, Icons.Filled.History),
    Tab("memory", R.string.nav_memory, Icons.Filled.Memory),
    Tab("settings", R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun NuvaApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("home") { HomeScreen() }
            composable("history") { HistoryScreen() }
            composable("memory") { MemoryScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
