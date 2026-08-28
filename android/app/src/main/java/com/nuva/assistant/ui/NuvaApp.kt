package com.nuva.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nuva.assistant.MainActivity
import com.nuva.assistant.R
import com.nuva.assistant.automation.UserPresentContactWorkflow
import com.nuva.assistant.automation.UserPresentFileWorkflow
import com.nuva.assistant.core.NuvaContainer
import com.nuva.assistant.ui.history.HistoryScreen
import com.nuva.assistant.ui.home.HomeScreen
import com.nuva.assistant.ui.memory.MemoryScreen
import com.nuva.assistant.ui.onboarding.OnboardingScreen
import com.nuva.assistant.ui.settings.SettingsScreen
import com.nuva.assistant.ui.support.FeatureSupportScreen
import com.nuva.assistant.ui.theme.NuvaBackdrop
import com.nuva.assistant.ui.theme.NuvaGlassPanel

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", R.string.nav_home, Icons.Filled.Mic),
    Tab("history", R.string.nav_history, Icons.Filled.History),
    Tab("memory", R.string.nav_memory, Icons.Filled.Memory),
    Tab("settings", R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun NuvaApp(
    assistantInvocation: MainActivity.AssistantInvocation? = null,
    onAssistantInvocationConsumed: (Long) -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // First launch → permission onboarding; afterwards it stays reachable from
    // Settings ("setup" route) so permissions can be granted/re-checked any time.
    val onboardingDone by NuvaContainer.preferences.onboardingDone
        .collectAsState(initial = null as Boolean?)
    if (onboardingDone == null) return
    val startDestination = if (onboardingDone!!) "home" else "onboarding"
    val fileWorkflow by UserPresentFileWorkflow.state.collectAsState()
    val contactWorkflow by UserPresentContactWorkflow.state.collectAsState()
    LaunchedEffect(fileWorkflow, contactWorkflow, onboardingDone, assistantInvocation?.id) {
        val pickerPending = fileWorkflow is UserPresentFileWorkflow.State.Pending ||
            contactWorkflow is UserPresentContactWorkflow.State.Pending
        val assistantPending = assistantInvocation != null
        if (onboardingDone == true && (pickerPending || assistantPending) && currentDestination?.route != "home") {
            navController.navigate("home") { launchSingleTop = true }
        }
    }

    NuvaBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (currentDestination?.route != "onboarding") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvaGlassPanel(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 30.dp,
                            contentPadding = 3.dp,
                        ) {
                            NavigationBar(
                                containerColor = Color.Transparent,
                                tonalElevation = 0.dp,
                                windowInsets = WindowInsets(0, 0, 0, 0),
                            ) {
                                TABS.forEach { tab ->
                                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                                    val label = stringResource(tab.labelRes)
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            navController.navigate(tab.route) {
                                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = {
                                            if (selected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .shadow(10.dp, CircleShape, clip = false)
                                                        .background(
                                                            Brush.linearGradient(
                                                                listOf(
                                                                    MaterialTheme.colorScheme.primary,
                                                                    MaterialTheme.colorScheme.secondary,
                                                                ),
                                                                start = Offset.Zero,
                                                                end = Offset(80f, 80f),
                                                            ),
                                                            CircleShape,
                                                        )
                                                        .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(tab.icon, contentDescription = label, tint = Color.White)
                                                }
                                            } else {
                                                Icon(tab.icon, contentDescription = label)
                                            }
                                        },
                                        label = { Text(label) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Color.White,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = Color.Transparent,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable("onboarding") {
                    OnboardingScreen(
                        onFinished = {
                            navController.navigate("home") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        },
                        onShowFeatures = { navController.navigate("support") },
                    )
                }
                composable("home") {
                    HomeScreen(
                        assistantInvocation = assistantInvocation,
                        onAssistantInvocationConsumed = onAssistantInvocationConsumed,
                    )
                }
                composable("history") { HistoryScreen() }
                composable("memory") { MemoryScreen() }
                composable("settings") {
                    SettingsScreen(
                        onOpenSetup = { navController.navigate("onboarding") },
                        onOpenSupport = { navController.navigate("support") },
                        onOpenPrivacy = { navController.navigate("privacy") },
                    )
                }
                composable("support") { FeatureSupportScreen() }
                composable("privacy") { com.nuva.assistant.ui.privacy.PrivacyScreen() }
            }
        }
    }
}
