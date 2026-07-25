package com.cryptomesh.frontend.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cryptomesh.frontend.ui.components.ActionButton
import com.cryptomesh.frontend.ui.components.InfoRow
import com.cryptomesh.frontend.ui.components.StatusPill

@Composable
fun PermissionsScreen(
    onBack: () -> Unit
) {
    var requested by remember { mutableStateOf(false) }
    var resultSummary by remember { mutableStateOf("Permissions not requested in this session.") }
    val permissions = remember { requiredPermissions() }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        requested = true
        val granted = results.count { it.value }
        resultSummary = "$granted of ${results.size} requested permissions granted."
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "App Permissions",
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            StatusPill(text = if (requested) "Request completed" else "Required for offline discovery")
            PermissionItem(
                icon = Icons.Default.Bluetooth,
                title = "Bluetooth nearby devices",
                description = "Used for peer discovery and short-range device connection."
            )
            PermissionItem(
                icon = Icons.Default.Wifi,
                title = "Wi-Fi Direct",
                description = "Used for higher-speed local transfer without Internet."
            )
            PermissionItem(
                icon = Icons.Default.LocationOn,
                title = "Location",
                description = "Required by Android for nearby wireless scanning on supported versions."
            )
            PermissionItem(
                icon = Icons.Default.Notifications,
                title = "Notifications",
                description = "Used later for transfer, sync, and wallet transaction status."
            )
            ActionButton(
                label = "Request permissions",
                icon = Icons.Default.CheckCircle,
                onClick = { launcher.launch(permissions.toTypedArray()) },
                modifier = Modifier.fillMaxWidth()
            )
            InfoRow(label = "Last request", value = resultSummary)
        }
    }
}

@Composable
private fun PermissionItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun requiredPermissions(): List<String> {
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions += Manifest.permission.BLUETOOTH_SCAN
        permissions += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        permissions += Manifest.permission.BLUETOOTH
        permissions += Manifest.permission.BLUETOOTH_ADMIN
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        permissions += Manifest.permission.POST_NOTIFICATIONS
    }

    return permissions
}
