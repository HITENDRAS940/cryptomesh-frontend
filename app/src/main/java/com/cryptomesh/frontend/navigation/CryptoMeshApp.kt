package com.cryptomesh.frontend.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cryptomesh.frontend.ui.screens.ChatScreen
import com.cryptomesh.frontend.ui.screens.CreateIdentityScreen
import com.cryptomesh.frontend.ui.screens.DashboardScreen
import com.cryptomesh.frontend.ui.screens.PeersScreen
import com.cryptomesh.frontend.ui.screens.PermissionsScreen
import com.cryptomesh.frontend.ui.screens.ProfileScreen
import com.cryptomesh.frontend.ui.screens.SyncScreen
import com.cryptomesh.frontend.ui.screens.WalletScreen
import com.cryptomesh.frontend.ui.screens.WelcomeScreen
import com.cryptomesh.frontend.ui.state.CryptoMeshViewModel

@Composable
fun CryptoMeshApp() {
    val navController = rememberNavController()
    val viewModel: CryptoMeshViewModel = viewModel()
    val identity by viewModel.identity.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val showBottomBar = currentRoute in mainDestinations.map { it.route } &&
        !isKeyboardVisible

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(
                    animationSpec = tween(220),
                    initialOffsetY = { it / 2 }
                ) + fadeIn(animationSpec = tween(180)),
                exit = slideOutVertically(
                    animationSpec = tween(200),
                    targetOffsetY = { it / 2 }
                ) + fadeOut(animationSpec = tween(150))
            ) {
                NavigationBar {
                    mainDestinations.forEach { destination ->
                        val selected = currentDestination
                            ?.hierarchy
                            ?.any { it.route == destination.route } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(MainDestination.Dashboard.route) {
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
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (identity == null) {
                AppRoute.Welcome.route
            } else {
                MainDestination.Dashboard.route
            },
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(AppRoute.Welcome.route) {
                WelcomeScreen(
                    onCreateIdentity = {
                        navController.navigate(AppRoute.CreateIdentity.route)
                    }
                )
            }
            composable(AppRoute.CreateIdentity.route) {
                CreateIdentityScreen(
                    onBack = { navController.popBackStack() },
                    onIdentityCreated = { displayName ->
                        viewModel.createIdentity(displayName)
                        navController.navigate(MainDestination.Dashboard.route) {
                            popUpTo(AppRoute.Welcome.route) {
                                inclusive = true
                            }
                        }
                    }
                )
            }
            composable(AppRoute.Profile.route) {
                ProfileScreen(
                    identity = identity,
                    onBack = { navController.popBackStack() },
                    onResetIdentity = {
                        viewModel.resetIdentity()
                        navController.navigate(AppRoute.Welcome.route) {
                            popUpTo(0)
                        }
                    }
                )
            }
            composable(AppRoute.Permissions.route) {
                PermissionsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable(MainDestination.Dashboard.route) {
                DashboardScreen(
                    identity = identity,
                    onOpenProfile = {
                        navController.navigate(AppRoute.Profile.route)
                    },
                    onOpenPermissions = {
                        navController.navigate(AppRoute.Permissions.route)
                    }
                )
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
