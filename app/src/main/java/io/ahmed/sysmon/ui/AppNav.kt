package io.ahmed.sysmon.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.ui.screens.AlertsScreen
import io.ahmed.sysmon.ui.screens.DashboardScreen
import io.ahmed.sysmon.ui.screens.DeviceProfileScreen
import io.ahmed.sysmon.ui.screens.DevicesScreen
import io.ahmed.sysmon.ui.screens.JobsScreen
import io.ahmed.sysmon.ui.screens.LoginScreen
import io.ahmed.sysmon.ui.screens.LogsScreen
import io.ahmed.sysmon.ui.screens.SettingsScreen
import io.ahmed.sysmon.ui.screens.UsageScreen

private data class TabSpec(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabSpec("home", "Home", Icons.Filled.Home),
    TabSpec("usage", "Usage", Icons.Filled.BarChart),
    TabSpec("devices", "Devices", Icons.Filled.Devices),
    TabSpec("alerts", "Alerts", Icons.Filled.Notifications),
    TabSpec("jobs", "Jobs", Icons.Filled.Work),
    TabSpec("logs", "Logs", Icons.Filled.Article),
    TabSpec("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun AppNav() {
    val context = LocalContext.current
    val repo = remember { Repository(context) }

    // Observe the login flag so the navigation updates when sign-in/out happens.
    var loggedIn by remember { mutableStateOf(repo.prefs.loggedIn) }

    if (!loggedIn) {
        LoginScreen(onLoggedIn = { loggedIn = true })
        return
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                for (tab in TABS) {
                    NavigationBarItem(
                        selected = currentRoute == tab.route ||
                            backStack?.destination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            nav.navigate(tab.route) {
                                launchSingleTop = true
                                popUpTo("home") { saveState = true }
                                restoreState = true
                            }
                        },
                        // Label sits right below the icon, so icon gets null a11y
                        // to avoid double-announcement by TalkBack.
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            NavHost(
                navController = nav,
                startDestination = "home",
                enterTransition = {
                    fadeIn(tween(200)) + slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.Start, tween(220)
                    )
                },
                exitTransition = {
                    fadeOut(tween(120)) + slideOutOfContainer(
                        AnimatedContentTransitionScope.SlideDirection.Start, tween(220)
                    )
                },
                popEnterTransition = {
                    fadeIn(tween(200)) + slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.End, tween(220)
                    )
                },
                popExitTransition = {
                    fadeOut(tween(120)) + slideOutOfContainer(
                        AnimatedContentTransitionScope.SlideDirection.End, tween(220)
                    )
                }
            ) {
                composable("home") { DashboardScreen() }
                composable("usage") { UsageScreen() }
                composable("devices") {
                    DevicesScreen(onOpenDevice = { mac ->
                        val encoded = java.net.URLEncoder.encode(mac, "utf-8")
                        nav.navigate("device/$encoded")
                    })
                }
                composable(
                    route = "device/{mac}",
                    arguments = listOf(androidx.navigation.navArgument("mac") {
                        type = androidx.navigation.NavType.StringType
                    })
                ) { backEntry ->
                    val raw = backEntry.arguments?.getString("mac") ?: ""
                    val mac = java.net.URLDecoder.decode(raw, "utf-8")
                    DeviceProfileScreen(mac = mac, onBack = { nav.popBackStack() })
                }
                composable("alerts") { AlertsScreen() }
                composable("jobs") { JobsScreen() }
                composable("logs") { LogsScreen() }
                composable("settings") {
                    SettingsScreen(onLoggedOut = { loggedIn = false })
                }
            }
        }
    }
}
