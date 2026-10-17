package ai.retrobot.ui.navigation

import ai.retrobot.di.AppContainer
import ai.retrobot.feature.battle.BattleScreen
import ai.retrobot.feature.chat.ChatScreen
import ai.retrobot.feature.keys.KeysScreen
import ai.retrobot.feature.leaderboard.LeaderboardScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private enum class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    BATTLE("battle", "Battle", Icons.Filled.Bolt),
    CHAT("chat", "Chat", Icons.Filled.Chat),
    LEADERBOARD("leaderboard", "Ranks", Icons.Filled.Leaderboard),
    PROVIDERS("providers", "Providers", Icons.Filled.Key),
}

@Composable
fun RetroBotNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.BATTLE.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.BATTLE.route) {
                BattleScreen(container = container)
            }
            composable(Tab.CHAT.route) {
                ChatScreen(container = container)
            }
            composable(Tab.LEADERBOARD.route) {
                LeaderboardScreen(container = container)
            }
            composable(Tab.PROVIDERS.route) {
                KeysScreen(container = container)
            }
        }
    }
}
