package com.cryptomesh.frontend.navigation

sealed class AppRoute(val route: String) {
    data object Welcome : AppRoute("welcome")
    data object CreateIdentity : AppRoute("create_identity")
    data object Profile : AppRoute("profile")
    data object Permissions : AppRoute("permissions")
}
