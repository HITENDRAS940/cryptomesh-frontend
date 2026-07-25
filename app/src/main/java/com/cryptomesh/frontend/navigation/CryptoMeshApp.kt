package com.cryptomesh.frontend.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cryptomesh.frontend.ui.screens.ChatScreen
import com.cryptomesh.frontend.ui.screens.DashboardScreen
import com.cryptomesh.frontend.ui.screens.PeersScreen
import com.cryptomesh.frontend.ui.screens.SyncScreen
import com.cryptomesh.frontend.ui.screens.WalletScreen

@Composable
fun CryptoMeshApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                mainDestinations.forEach { destination ->
                    val selected = currentDestination
                        ?.hierarchy
                        ?.any { it.route == destination.route } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label
                            )
                        },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = MainDestination.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(MainDestination.Dashboard.route) {
                DashboardScreen()
            }
            composable(MainDestination.Peers.route) {
                PeersScreen()
            }
            composable(MainDestination.Chat.route) {
                ChatScreen()
            }
            composable(MainDestination.Wallet.route) {
                WalletScreen()
            }
            composable(MainDestination.Sync.route) {
                SyncScreen()
            }
        }
    }
}
