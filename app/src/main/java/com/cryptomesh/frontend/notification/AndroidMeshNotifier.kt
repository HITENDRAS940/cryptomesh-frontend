package com.cryptomesh.frontend.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cryptomesh.frontend.MainActivity
import com.cryptomesh.frontend.R

class AndroidMeshNotifier(
    private val context: Context
) : MeshNotifier {
    private val notificationManager =
        NotificationManagerCompat.from(context)

    init {
        createNotificationChannels()
    }

    @SuppressLint("MissingPermission")
    override fun show(event: MeshNotificationEvent) {
        if (!canPostAppNotifications(context)) return

        val notification = NotificationCompat.Builder(
            context,
            event.channel.channelId
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title)
            .setContentText(event.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
            .setPriority(event.channel.priority)
            .setCategory(event.channel.category)
            .setAutoCancel(true)
            .setGroup(NOTIFICATION_GROUP)
            .setContentIntent(contentIntent(event))
            .build()

        notificationManager.notify(
            event.key.hashCode() and Int.MAX_VALUE,
            notification
        )
    }

    private fun createNotificationChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(
                MeshNotificationChannel.Messages.channelId,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming messages and delivery updates"
            },
            NotificationChannel(
                MeshNotificationChannel.Connections.channelId,
                "Peer connections",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Nearby peers and secure connection activity"
            },
            NotificationChannel(
                MeshNotificationChannel.System.channelId,
                "Mesh status",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Bluetooth availability and scan errors"
            }
        )
        manager.createNotificationChannels(channels)
    }

    private fun contentIntent(event: MeshNotificationEvent): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(
                MainActivity.EXTRA_NOTIFICATION_DESTINATION,
                event.destination.name
            )
        }
        return PendingIntent.getActivity(
            context,
            event.destination.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

fun requiredNotificationPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }
}

fun canPostAppNotifications(context: Context): Boolean {
    val permissionGranted =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    return permissionGranted &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private val MeshNotificationChannel.channelId: String
    get() = when (this) {
        MeshNotificationChannel.Messages -> "cryptomesh_messages"
        MeshNotificationChannel.Connections -> "cryptomesh_connections"
        MeshNotificationChannel.System -> "cryptomesh_status"
    }

private val MeshNotificationChannel.priority: Int
    get() = when (this) {
        MeshNotificationChannel.Messages -> NotificationCompat.PRIORITY_HIGH
        MeshNotificationChannel.Connections ->
            NotificationCompat.PRIORITY_DEFAULT
        MeshNotificationChannel.System -> NotificationCompat.PRIORITY_DEFAULT
    }

private val MeshNotificationChannel.category: String
    get() = when (this) {
        MeshNotificationChannel.Messages -> NotificationCompat.CATEGORY_MESSAGE
        MeshNotificationChannel.Connections ->
            NotificationCompat.CATEGORY_SOCIAL
        MeshNotificationChannel.System -> NotificationCompat.CATEGORY_STATUS
    }

private const val NOTIFICATION_GROUP = "cryptomesh_activity"
