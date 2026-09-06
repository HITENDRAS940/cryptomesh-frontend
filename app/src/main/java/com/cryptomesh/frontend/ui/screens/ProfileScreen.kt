package com.cryptomesh.frontend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cryptomesh.frontend.ui.components.ActionButton
import com.cryptomesh.frontend.ui.components.InfoRow
import com.cryptomesh.frontend.ui.components.ScreenHeader
import com.cryptomesh.frontend.ui.components.StatusPill
import com.cryptomesh.frontend.ui.state.LocalIdentity

@Composable
fun ProfileScreen(
    identity: LocalIdentity?,
    onBack: () -> Unit,
    onResetIdentity: () -> Unit
) {
    var showResetConfirmation by remember { mutableStateOf(false) }

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
                title = "Local Profile",
                supportingText = "Identity stored on this device",
                onBack = onBack
            )
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                StatusPill(text = "Device identity")
                InfoRow(
                    label = "Display name",
                    value = identity?.displayName.orEmpty()
                )
                InfoRow(label = "Device ID", value = identity?.deviceId.orEmpty())
                InfoRow(
                    label = "Public key preview",
                    value = identity?.publicKeyPreview.orEmpty()
                )

                ActionButton(
                    label = "Reset identity",
                    icon = Icons.Default.Refresh,
                    onClick = { showResetConfirmation = true },
                    modifier = Modifier.fillMaxWidth()
                )

                InfoRow(
                    label = "Key protection",
                    value =
                        "Signing key protected by Android Keystore. " +
                            "Only the public-key preview is displayed."
                )
            }
        }
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Reset local identity?") },
            text = {
                Text(
                    "This deletes the signing key, identity metadata, encrypted packets, and active peer sessions from this device."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation = false
                        onResetIdentity()
                    }
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetConfirmation = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
