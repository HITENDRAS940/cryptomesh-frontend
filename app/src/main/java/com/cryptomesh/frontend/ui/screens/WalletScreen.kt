package com.cryptomesh.frontend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cryptomesh.frontend.ui.components.ActionButton
import com.cryptomesh.frontend.ui.components.EmptyState
import com.cryptomesh.frontend.ui.components.MetricCard
import com.cryptomesh.frontend.ui.components.SectionHeader

@Composable
fun WalletScreen() {
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
            SectionHeader(text = "Offline Wallet")
            MetricCard(
                title = "Available balance",
                value = "Not recharged",
                supportingText = "Prepaid balance state will connect to local wallet storage later."
            )
            ActionButton(
                label = "Send payment",
                icon = Icons.Default.Payments,
                onClick = {}
            )
            EmptyState(
                icon = Icons.Default.AccountBalanceWallet,
                title = "No transactions",
                description = "Balance and transaction history will appear here."
            )
        }
    }
}
