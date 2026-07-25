package com.cryptomesh.frontend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cryptomesh.frontend.ui.components.ActionButton
import com.cryptomesh.frontend.ui.components.EmptyState
import com.cryptomesh.frontend.ui.components.SectionHeader

@Composable
fun ChatScreen() {
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
            SectionHeader(text = "Secure Chat")
            ActionButton(
                label = "New message",
                icon = Icons.AutoMirrored.Filled.Send,
                onClick = {}
            )
            EmptyState(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "No conversations",
                description = "Conversation list, message bubbles, and delivery states will be built in Phase 4."
            )
        }
    }
}
