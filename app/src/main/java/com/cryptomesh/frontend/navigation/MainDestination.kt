package com.cryptomesh.frontend.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector

sealed class MainDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Dashboard : MainDestination("dashboard", "Home", Icons.Default.Home)
    data object Peers : MainDestination("peers", "Peers", Icons.Default.Devices)
    data object Chat : MainDestination("chat", "Chat", Icons.AutoMirrored.Filled.Chat)
}

val mainDestinations = listOf(
    MainDestination.Dashboard,
    MainDestination.Peers,
    MainDestination.Chat
)
