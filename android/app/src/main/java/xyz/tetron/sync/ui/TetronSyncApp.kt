// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import xyz.tetron.sync.AppContainer
import xyz.tetron.sync.ui.history.HistoryScreen
import xyz.tetron.sync.ui.history.HistoryViewModel
import xyz.tetron.sync.ui.home.HomeScreen
import xyz.tetron.sync.ui.home.HomeViewModel
import xyz.tetron.sync.ui.progress.ProgressScreen
import xyz.tetron.sync.ui.settings.SettingsScreen
import xyz.tetron.sync.ui.settings.SettingsViewModel

private sealed class Destination(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Destination("home", "Home", Icons.Filled.Home)
    data object Progress : Destination("progress", "Progress", Icons.Filled.CloudUpload)
    data object History : Destination("history", "History", Icons.Filled.History)
    data object Settings : Destination("settings", "Settings", Icons.Filled.Settings)

    companion object {
        val all = listOf(Home, Progress, History, Settings)
    }
}

/**
 * SYNC-009: the app shell -- bottom navigation across Home/Progress/
 * History (Settings joins in a later slice of this requirement), all
 * hosted under one [TetronSyncTheme]. [HomeViewModel] is requested scoped to
 * the hosting Activity ([LocalContext.current] here is always
 * `MainActivity`, this app's only Activity) so Home and Progress observe
 * the exact same in-flight [xyz.tetron.sync.ui.home.RunPhase] -- a run
 * started from Home must be visible on Progress without either screen
 * owning the other.
 */
@Composable
fun TetronSyncApp(container: AppContainer) {
    val factory = remember(container) { AppViewModelFactory(container) }
    val activity = LocalContext.current as ComponentActivity
    val homeViewModel: HomeViewModel = viewModel(activity, factory = factory)
    val navController = rememberNavController()

    TetronSyncTheme {
        Scaffold(
            bottomBar = {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                NavigationBar {
                    Destination.all.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    launchSingleTop = true
                                    restoreState = true
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Destination.Home.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(Destination.Home.route) { HomeScreen(homeViewModel) }
                composable(Destination.Progress.route) { ProgressScreen(homeViewModel) }
                composable(Destination.History.route) {
                    val historyViewModel: HistoryViewModel = viewModel(factory = factory)
                    HistoryScreen(historyViewModel)
                }
                composable(Destination.Settings.route) {
                    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
                    SettingsScreen(settingsViewModel)
                }
            }
        }
    }
}
