package com.cryptomesh.frontend.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.graphics.vector.ImageVector

sealed class MainDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Dashboard : MainDestination("dashboard", "Home", Icons.Default.Home)
    data object Peers : MainDestination("peers", "Peers", Icons.Default.Devices)
    data object Chat : MainDestination("chat", "Chat", Icons.AutoMirrored.Filled.Chat)
    data object Wallet : MainDestination("wallet", "Wallet", Icons.Default.AccountBalanceWallet)
    data object Sync : MainDestination("sync", "Sync", Icons.Default.Sync)
}

val mainDestinations = listOf(
    MainDestination.Dashboard,
    MainDestination.Peers,
    MainDestination.Chat,
    MainDestination.Wallet,
    MainDestination.Sync
)
