package com.cryptomesh.frontend.navigation

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.cryptomesh.frontend.ui.screens.WelcomeScreen
import com.cryptomesh.frontend.ui.state.CryptoMeshViewModel
import com.cryptomesh.frontend.ui.state.ChatViewModel
import com.cryptomesh.frontend.ui.state.PeerDiscoveryViewModel
import com.cryptomesh.frontend.CryptoMeshApplication
import com.cryptomesh.frontend.notification.MeshNotificationDestination
import com.cryptomesh.frontend.transport.TransportUnavailableReason
import com.cryptomesh.frontend.transport.hasRequiredBluetoothPermissions
import com.cryptomesh.frontend.transport.isBluetoothEnabled

@Composable
fun CryptoMeshApp(
    notificationDestination: MeshNotificationDestination? = null,
    onNotificationDestinationConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext
        as CryptoMeshApplication
    val viewModel: CryptoMeshViewModel = viewModel(
        factory = CryptoMeshViewModel.factory(
            application.container.identityRepository,
            application.container.securePacketRepository,
            application.container.mediaTransferRepository
        )
    )
    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(
            application.container.directMeshRepository
        )
    )
    val identity by viewModel.identity.collectAsState()
    val chatUiState by chatViewModel.uiState.collectAsState()
    val isIdentityLoaded by viewModel.isIdentityLoaded.collectAsState()
    val identityError by viewModel.identityError.collectAsState()
    val meshState by application.container.directMeshRepository.state
        .collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val isConversationOpen =
        currentRoute == MainDestination.Chat.route &&
            chatUiState.selectedConversationId != null
    val showBottomBar = currentRoute in mainDestinations.map { it.route } &&
        !isKeyboardVisible &&
        !isConversationOpen
    var bluetoothRequestCallback by remember {
        mutableStateOf<((Boolean) -> Unit)?>(null)
    }
    var hasAutomaticallyRequestedBluetooth by rememberSaveable(
        identity?.deviceId
    ) {
        mutableStateOf(false)
    }
    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val enabled = result.resultCode == Activity.RESULT_OK ||
            isBluetoothEnabled(application)
        if (enabled) {
            application.container.directMeshRepository.start()
        }
        bluetoothRequestCallback?.invoke(enabled)
        bluetoothRequestCallback = null
    }
    val requestBluetooth = { onResult: (Boolean) -> Unit ->
        bluetoothRequestCallback = onResult
        runCatching {
            bluetoothEnableLauncher.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            )
        }.onFailure {
            bluetoothRequestCallback = null
            onResult(false)
        }
        Unit
    }

    LaunchedEffect(identityError) {
        val message = identityError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissIdentityError()
    }

    LaunchedEffect(identity?.deviceId) {
        if (identity != null) {
            application.container.directMeshRepository.start()
        }
    }

    DisposableEffect(lifecycleOwner, identity?.deviceId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && identity != null) {
                application.container.directMeshRepository.start()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(
        identity?.deviceId,
        meshState.transportAvailable,
        meshState.transportUnavailableReason
    ) {
        if (meshState.transportAvailable) {
            hasAutomaticallyRequestedBluetooth = false
        }
        val shouldRequest =
            identity != null &&
                meshState.transportUnavailableReason ==
                TransportUnavailableReason.BluetoothDisabled &&
                hasRequiredBluetoothPermissions(application) &&
                !hasAutomaticallyRequestedBluetooth
        if (shouldRequest) {
            hasAutomaticallyRequestedBluetooth = true
            requestBluetooth {}
        }
    }

    if (!isIdentityLoaded) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    LaunchedEffect(notificationDestination, identity?.deviceId) {
        val destination = notificationDestination ?: return@LaunchedEffect
        if (identity == null) return@LaunchedEffect
        val route = when (destination) {
            MeshNotificationDestination.Chat ->
                MainDestination.Chat.route
            MeshNotificationDestination.Peers ->
                MainDestination.Peers.route
            MeshNotificationDestination.Dashboard ->
                MainDestination.Dashboard.route
        }
        navController.navigate(route) {
            launchSingleTop = true
        }
        onNotificationDestinationConsumed()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
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
                        viewModel.createIdentity(displayName) {
                            navController.navigate(
                                MainDestination.Dashboard.route
                            ) {
                                popUpTo(AppRoute.Welcome.route) {
                                    inclusive = true
                                }
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
                        viewModel.resetIdentity {
                            navController.navigate(AppRoute.Welcome.route) {
                                popUpTo(0)
                            }
                        }
                    }
                )
            }
            composable(AppRoute.Permissions.route) {
                PermissionsScreen(
                    onBack = { navController.popBackStack() },
                    onPermissionsGranted = {
                        application.container.directMeshRepository.start()
                    }
                )
            }
            composable(MainDestination.Dashboard.route) {
                DashboardScreen(
                    identity = identity,
                    meshState = meshState,
                    onOpenProfile = {
                        navController.navigate(AppRoute.Profile.route)
                    },
                    onOpenPermissions = {
                        navController.navigate(AppRoute.Permissions.route)
                    },
                    onEnableBluetooth = {
                        requestBluetooth {}
                    }
                )
            }
            composable(MainDestination.Peers.route) {
                val peerViewModel: PeerDiscoveryViewModel = viewModel(
                    factory = PeerDiscoveryViewModel.factory(
                        application.container.directMeshRepository
                    )
                )
                PeersScreen(
                    viewModel = peerViewModel,
                    onRequestBluetooth = requestBluetooth
                )
            }
            composable(MainDestination.Chat.route) {
                ChatScreen(viewModel = chatViewModel)
            }
        }
    }
}
