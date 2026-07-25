package com.cryptomesh.frontend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cryptomesh.frontend.ui.components.ActionButton
import com.cryptomesh.frontend.ui.components.EmptyState
import com.cryptomesh.frontend.ui.components.SectionHeader

@Composable
fun PeersScreen() {
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
            SectionHeader(text = "Nearby Peers")
            ActionButton(
                label = "Start scan",
                icon = Icons.Default.Radar,
                onClick = {}
            )
            EmptyState(
                icon = Icons.Default.Radar,
                title = "No peers yet",
                description = "Peer scanning states and connection requests will be built in Phase 3."
            )
        }
    }
}
