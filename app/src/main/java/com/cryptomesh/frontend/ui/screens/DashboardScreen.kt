package com.cryptomesh.frontend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cryptomesh.frontend.ui.components.ActionButton
import com.cryptomesh.frontend.ui.components.MainTabHeader
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
        ) {
            MainTabHeader(
                title = "CryptoMesh",
                supportingText =
                    "Signed in as ${identity?.displayName ?: "Local user"}",
                trailingContent = {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
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
                }

                item {
                    SectionHeader(text = "Current State")
                }

                item {
                    MetricCard(
                        title = "Nearby peers",
                        value = "0",
                        supportingText = "Use Peers to scan for local devices."
                    )
                }
                item {
                    MetricCard(
                        title = "Pending packets",
                        value = "0",
                        supportingText =
                            "Messages, files, and wallet packets will appear here."
                    )
                }
                item {
                    MetricCard(
                        title = "Wallet balance",
                        value = "1,250.00 ₹",
                        supportingText =
                            "45.00 ₹ is pending backend settlement."
                    )
                }
            }
        }
    }
}
