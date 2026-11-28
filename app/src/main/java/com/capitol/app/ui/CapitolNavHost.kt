package com.capitol.app.ui

import android.app.Activity
import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.capitol.app.AppContainer
import com.capitol.app.data.prefs.ThemeMode
import com.capitol.app.update.UpdateManager
import com.capitol.app.ui.agent.AgentScreen
import com.capitol.app.ui.agent.AgentViewModel
import com.capitol.app.ui.analysis.AnalysisScreen
import com.capitol.app.ui.analysis.AnalysisViewModel
import com.capitol.app.ui.home.HomeScreen
import com.capitol.app.ui.home.HomeViewModel
import com.capitol.app.ui.portfolio.PortfolioScreen
import com.capitol.app.ui.portfolio.PortfolioViewModel
import com.capitol.app.ui.settings.SettingsScreen
import com.capitol.app.ui.watchlist.WatchlistScreen
import com.capitol.app.ui.watchlist.WatchlistViewModel
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("analyze", "Analyze", Icons.AutoMirrored.Filled.ShowChart),
    Tab("watchlist", "Watchlist", Icons.Default.Star),
    Tab("agent", "AI Agent", Icons.Default.AutoAwesome),
    Tab("portfolio", "Portfolio", Icons.Default.AccountBalanceWallet),
)

@Composable
fun CapitolNavHost(c: AppContainer, isDark: Boolean, nav: NavHostController = rememberNavController()) {
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route?.substringBefore('?')
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as Activity

    fun go(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = !route.contains('?')
    }
    val openSymbol: (String) -> Unit = { go("analyze?symbol=${Uri.encode(it)}") }
    val askAi: (String) -> Unit = { go("agent?prompt=${Uri.encode(it)}") }

    Scaffold(
        bottomBar = {
            if (current !in setOf("settings", "compare", "calendar", "screener")) Column {
                // Flat bar on the canvas colour with a hairline on top; the accent marks only the active tab.
                androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    TABS.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = { go(t.route) },
                            icon = { Icon(t.icon, t.label) },
                            label = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
            NavHost(nav, startDestination = "home",
                enterTransition = { fadeIn(tween(250)) }, exitTransition = { fadeOut(tween(200)) }) {
                composable("home") {
                    val vm: HomeViewModel = viewModel { HomeViewModel(c) }
                    HomeScreen(
                        vm, isDark,
                        onToggleTheme = { scope.launch { c.settings.setTheme(if (isDark) ThemeMode.LIGHT else ThemeMode.DARK) } },
                        onOpenSymbol = openSymbol, onOpenAgent = { go("agent") }, onOpenPortfolio = { go("portfolio") },
                        onOpenScreener = { nav.navigate("screener") },
                        onOpenSettings = { nav.navigate("settings") },
                    )
                }
                composable("analyze?symbol={symbol}", arguments = listOf(navArgument("symbol") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                    val vm: AnalysisViewModel = viewModel { AnalysisViewModel(c) }
                    AnalysisScreen(vm, e.arguments?.getString("symbol"),
                        onCompare = { s -> nav.navigate("compare?symbols=${Uri.encode(s)}") }, onAskAi = askAi)
                }
                composable("watchlist") {
                    val vm: WatchlistViewModel = viewModel { WatchlistViewModel(c) }
                    WatchlistScreen(vm, onOpen = openSymbol, onCalendar = { nav.navigate("calendar") })
                }
                composable("agent?prompt={prompt}", arguments = listOf(navArgument("prompt") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                    val vm: AgentViewModel = viewModel { AgentViewModel(c) }
                    AgentScreen(vm, e.arguments?.getString("prompt"), onOpenSettings = { nav.navigate("settings") })
                }
                composable("portfolio") {
                    val vm: PortfolioViewModel = viewModel { PortfolioViewModel(c) }
                    PortfolioScreen(vm, onAnalyze = openSymbol, onAskAi = askAi)
                }
                composable("compare?symbols={symbols}", arguments = listOf(navArgument("symbols") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                    val vm: com.capitol.app.ui.compare.CompareViewModel = viewModel { com.capitol.app.ui.compare.CompareViewModel(c) }
                    com.capitol.app.ui.compare.CompareScreen(vm, e.arguments?.getString("symbols")?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
                        onBack = { nav.popBackStack() }, onOpen = openSymbol)
                }
                composable("screener") {
                    val vm: com.capitol.app.ui.screener.ScreenerViewModel = viewModel { com.capitol.app.ui.screener.ScreenerViewModel(c) }
                    com.capitol.app.ui.screener.ScreenerScreen(vm, onBack = { nav.popBackStack() }, onOpen = openSymbol)
                }
                composable("calendar") {
                    val vm: com.capitol.app.ui.calendar.CalendarViewModel = viewModel { com.capitol.app.ui.calendar.CalendarViewModel(c) }
                    com.capitol.app.ui.calendar.CalendarScreen(vm, onBack = { nav.popBackStack() }, onOpen = openSymbol)
                }
                composable("settings") {
                    SettingsScreen(c, onBack = { nav.popBackStack() }, onStartUpdate = {
                        when (c.updates.state.value) {
                            UpdateManager.State.ReadyToInstall -> c.updates.install(activity)
                            else -> c.updates.start(activity)
                        }
                    })
                }
            }
        }
    }
    UpdatePrompt(c.updates, activity)
}

/** Global dialog shown when an update is found, downloading or ready. */
@Composable
private fun UpdatePrompt(updates: UpdateManager, activity: Activity) {
    val st by updates.state.collectAsStateWithLifecycle()
    when (val s = st) {
        is UpdateManager.State.Available -> AlertDialog(
            onDismissRequest = updates::dismiss,
            title = { Text("Update available · v${s.version}") },
            text = { Text(s.notes.ifBlank { "A new version of Capitol is ready." }.take(800), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton({ updates.start(activity) }) { Text(if (s.viaPlay) "Update" else "Download") } },
            dismissButton = { TextButton(updates::dismiss) { Text("Later") } },
        )
        is UpdateManager.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update") },
            text = { Column { LinearProgressIndicator({ s.progress }); Text("${(s.progress * 100).toInt()}%") } },
            confirmButton = {},
        )
        UpdateManager.State.ReadyToInstall -> AlertDialog(
            onDismissRequest = updates::dismiss,
            title = { Text("Update downloaded") },
            text = { Text("Install now? Android will ask you to confirm.") },
            confirmButton = { TextButton({ updates.install(activity) }) { Text("Install") } },
            dismissButton = { TextButton(updates::dismiss) { Text("Later") } },
        )
        else -> {}
    }
}
