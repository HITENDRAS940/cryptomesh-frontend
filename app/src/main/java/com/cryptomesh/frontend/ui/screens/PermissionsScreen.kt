package com.cryptomesh.frontend.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
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
import com.cryptomesh.frontend.ui.components.ScreenHeader
import com.cryptomesh.frontend.ui.components.StatusPill
import com.cryptomesh.frontend.notification.requiredNotificationPermissions
import com.cryptomesh.frontend.transport.requiredBluetoothPermissions

@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    onPermissionsGranted: () -> Unit
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
        if (results.isNotEmpty() && results.values.all { it }) {
            onPermissionsGranted()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            ScreenHeader(
                title = "App Permissions",
                supportingText = "Nearby communication access",
                onBack = onBack
            )
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                StatusPill(
                    text = if (requested) {
                        "Request completed"
                    } else {
                        "Required for offline discovery"
                    }
                )
                PermissionItem(
                    icon = Icons.Default.Bluetooth,
                    title = "Nearby devices",
                    description =
                        "Allows BLE discovery, advertising, and direct encrypted communication."
                )
                PermissionItem(
                    icon = Icons.Default.Notifications,
                    title = "Notifications",
                    description =
                        "Alerts you about nearby peers, secure connections, messages, and delivery updates."
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
    return requiredBluetoothPermissions() +
        requiredNotificationPermissions()
}
