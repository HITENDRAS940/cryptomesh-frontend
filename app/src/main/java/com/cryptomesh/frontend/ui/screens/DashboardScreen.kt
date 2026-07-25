package com.cryptomesh.frontend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cryptomesh.frontend.ui.components.ActionButton
import com.cryptomesh.frontend.ui.components.MetricCard
import com.cryptomesh.frontend.ui.components.SectionHeader
import com.cryptomesh.frontend.ui.components.StatusPill
import com.cryptomesh.frontend.ui.state.LocalIdentity

@Composable
fun DashboardScreen(
    identity: LocalIdentity?,
    onOpenProfile: () -> Unit,
    onOpenPermissions: () -> Unit
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "CryptoMesh",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Signed in as ${identity?.displayName ?: "Local user"}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionButton(
                    label = "Profile",
                    icon = Icons.Default.Badge,
                    onClick = onOpenProfile,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    label = "Permissions",
                    icon = Icons.Default.Lock,
                    onClick = onOpenPermissions,
                    modifier = Modifier.weight(1f)
                )
            }

            SectionHeader(text = "Current State")

            MetricCard(
                title = "Nearby peers",
                value = "0",
                supportingText = "Use Peers to scan for local devices."
            )
            MetricCard(
                title = "Pending packets",
                value = "0",
                supportingText = "Messages, files, and wallet packets will appear here."
            )
            MetricCard(
                title = "Wallet balance",
                value = "Not set",
                supportingText = "Wallet setup is not available yet."
            )
        }
    }
}
