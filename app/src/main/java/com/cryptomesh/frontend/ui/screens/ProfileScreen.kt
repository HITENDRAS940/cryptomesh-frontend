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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
                    onClick = onResetIdentity,
                    modifier = Modifier.fillMaxWidth()
                )

                InfoRow(
                    label = "Next implementation",
                    value = "Replace this placeholder key preview with generated X25519 and Ed25519 public keys."
                )
            }
        }
    }
}
