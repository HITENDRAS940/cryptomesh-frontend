package com.cryptomesh.frontend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cryptomesh.frontend.ui.components.MetricCard
import com.cryptomesh.frontend.ui.components.SectionHeader
import com.cryptomesh.frontend.ui.components.StatusPill

@Composable
fun DashboardScreen() {
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
                    text = "Secure offline communication and wallet transactions.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionHeader(text = "Current State")

            MetricCard(
                title = "Nearby peers",
                value = "0",
                supportingText = "Peer discovery UI arrives in Phase 3."
            )
            MetricCard(
                title = "Pending packets",
                value = "0",
                supportingText = "Messages, files, and wallet packets will appear here."
            )
            MetricCard(
                title = "Wallet balance",
                value = "Not set",
                supportingText = "Wallet UI arrives in Phase 6."
            )
        }
    }
}
