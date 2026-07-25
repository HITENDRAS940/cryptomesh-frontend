package com.cryptomesh.frontend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cryptomesh.frontend.ui.components.ActionButton
import com.cryptomesh.frontend.ui.components.InfoRow
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
                    text = "Local Profile",
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            StatusPill(text = "Device identity")
            InfoRow(label = "Display name", value = identity?.displayName.orEmpty())
            InfoRow(label = "Device ID", value = identity?.deviceId.orEmpty())
            InfoRow(label = "Public key preview", value = identity?.publicKeyPreview.orEmpty())

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
