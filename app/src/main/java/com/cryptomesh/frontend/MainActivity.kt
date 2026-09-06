package com.cryptomesh.frontend

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cryptomesh.frontend.navigation.CryptoMeshApp
import com.cryptomesh.frontend.notification.MeshNotificationDestination
import com.cryptomesh.frontend.ui.theme.CryptoMeshTheme

class MainActivity : ComponentActivity() {
    private var notificationDestination by mutableStateOf(
        notificationDestination(intent)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationDestination = notificationDestination(intent)
        setContent {
            CryptoMeshTheme {
                CryptoMeshApp(
                    notificationDestination = notificationDestination,
                    onNotificationDestinationConsumed = {
                        notificationDestination = null
                        intent.removeExtra(EXTRA_NOTIFICATION_DESTINATION)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationDestination = notificationDestination(intent)
    }

    private fun notificationDestination(
        intent: Intent?
    ): MeshNotificationDestination? {
        val value = intent?.getStringExtra(
            EXTRA_NOTIFICATION_DESTINATION
        ) ?: return null
        return runCatching {
            MeshNotificationDestination.valueOf(value)
        }.getOrNull()
    }

    companion object {
        const val EXTRA_NOTIFICATION_DESTINATION =
            "com.cryptomesh.frontend.NOTIFICATION_DESTINATION"
    }
}
