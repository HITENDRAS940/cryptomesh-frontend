package com.cryptomesh.frontend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cryptomesh.frontend.ui.components.ActionButton
import com.cryptomesh.frontend.ui.components.EmptyState
import com.cryptomesh.frontend.ui.components.SectionHeader
import com.cryptomesh.frontend.ui.components.StatusPill

@Composable
fun SyncScreen() {
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
            SectionHeader(text = "Synchronization")
            StatusPill(text = "Backend disconnected")
            ActionButton(
                label = "Manual sync",
                icon = Icons.Default.Sync,
                onClick = {}
            )
            EmptyState(
                icon = Icons.Default.CloudOff,
                title = "Nothing to sync",
                description = "Sync queue, last synced time, and server results will be built in Phase 7."
            )
        }
    }
}
