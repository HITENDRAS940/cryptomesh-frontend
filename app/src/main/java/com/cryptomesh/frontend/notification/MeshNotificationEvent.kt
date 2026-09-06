package com.cryptomesh.frontend.notification

import com.cryptomesh.frontend.data.repository.DirectMeshState
import com.cryptomesh.frontend.data.repository.DirectMediaTransfer
import com.cryptomesh.frontend.data.repository.DirectMessage
import com.cryptomesh.frontend.data.repository.DirectMessageStatus
import com.cryptomesh.frontend.data.repository.DirectPeer
import com.cryptomesh.frontend.data.repository.DirectPeerStatus
import com.cryptomesh.frontend.data.repository.MediaTransferStatus

enum class MeshNotificationChannel {
    Messages,
    Connections,
    System
}

enum class MeshNotificationDestination {
    Chat,
    Peers,
    Dashboard
}

enum class MeshNotificationKind {
    PeerFound,
    IncomingConnection,
    ConnectionProgress,
    PeerConnected,
    PeerDisconnected,
    ConnectionFailed,
    IncomingMessage,
    MessageAcknowledged,
    MessageFailed,
    IncomingMedia,
    MediaCompleted,
    MediaFailed,
    TransportUnavailable,
    ScanFailed
}

data class MeshNotificationEvent(
    val key: String,
    val kind: MeshNotificationKind,
    val channel: MeshNotificationChannel,
    val destination: MeshNotificationDestination,
    val title: String,
    val body: String
)

class MeshNotificationEventDetector {
    fun detect(
        previous: DirectMeshState,
        current: DirectMeshState
    ): List<MeshNotificationEvent> {
        return buildList {
            addPeerEvents(previous, current)
            addMessageEvents(previous, current)
            addMediaEvents(previous, current)
            addSystemEvents(previous, current)
        }
    }

    private fun MutableList<MeshNotificationEvent>.addPeerEvents(
        previous: DirectMeshState,
        current: DirectMeshState
    ) {
        val previousPeers = previous.peers.associateBy(DirectPeer::linkId)
        val currentPeers = current.peers.associateBy(DirectPeer::linkId)

        currentPeers.values.forEach { peer ->
            val oldPeer = previousPeers[peer.linkId]
            peerEvent(oldPeer, peer)?.let(::add)
        }
        previousPeers.values
            .filter {
                it.status == DirectPeerStatus.Connected &&
                    it.linkId !in currentPeers
            }
            .forEach { peer ->
                add(peerDisconnectedEvent(peer))
            }
    }

    private fun peerEvent(
        previous: DirectPeer?,
        current: DirectPeer
    ): MeshNotificationEvent? {
        if (previous?.status == current.status) return null
        val name = current.notificationName()
        val eventKey = "peer:${current.linkId}"

        return when {
            current.status == DirectPeerStatus.Connected ->
                MeshNotificationEvent(
                    key = eventKey,
                    kind = MeshNotificationKind.PeerConnected,
                    channel = MeshNotificationChannel.Connections,
                    destination = MeshNotificationDestination.Chat,
                    title = "Peer connected",
                    body = "Encrypted session with $name is ready."
                )

            current.status == DirectPeerStatus.Failed ->
                MeshNotificationEvent(
                    key = eventKey,
                    kind = MeshNotificationKind.ConnectionFailed,
                    channel = MeshNotificationChannel.Connections,
                    destination = MeshNotificationDestination.Peers,
                    title = "Connection failed",
                    body = current.failureMessage
                        ?: "Could not connect securely to $name."
                )

            previous?.status == DirectPeerStatus.Connected ->
                peerDisconnectedEvent(current)

            current.status == DirectPeerStatus.Authenticating &&
                !current.connectionInitiatedByLocal ->
                MeshNotificationEvent(
                    key = eventKey,
                    kind = MeshNotificationKind.IncomingConnection,
                    channel = MeshNotificationChannel.Connections,
                    destination = MeshNotificationDestination.Peers,
                    title = "Incoming connection",
                    body = "$name is requesting an encrypted session."
                )

            current.status == DirectPeerStatus.Authenticating ->
                MeshNotificationEvent(
                    key = eventKey,
                    kind = MeshNotificationKind.ConnectionProgress,
                    channel = MeshNotificationChannel.Connections,
                    destination = MeshNotificationDestination.Peers,
                    title = "Securing connection",
                    body = "Authenticating the session with $name."
                )

            current.status == DirectPeerStatus.Connecting ->
                MeshNotificationEvent(
                    key = eventKey,
                    kind = MeshNotificationKind.ConnectionProgress,
                    channel = MeshNotificationChannel.Connections,
                    destination = MeshNotificationDestination.Peers,
                    title = "Connecting to peer",
                    body = "Opening a secure link with $name."
                )

            previous == null &&
                current.status == DirectPeerStatus.Discovered ->
                MeshNotificationEvent(
                    key = eventKey,
                    kind = MeshNotificationKind.PeerFound,
                    channel = MeshNotificationChannel.Connections,
                    destination = MeshNotificationDestination.Peers,
                    title = "Peer found",
                    body = "$name is nearby."
                )

            else -> null
        }
    }

    private fun MutableList<MeshNotificationEvent>.addMessageEvents(
        previous: DirectMeshState,
        current: DirectMeshState
    ) {
        val previousMessages = previous.messages
            .associateBy(DirectMessage::packetId)

        current.messages.forEach { message ->
            val oldMessage = previousMessages[message.packetId]
            val peerName = current.peerNameFor(message.peerDeviceId)
            when {
                oldMessage == null && !message.isOutgoing ->
                    add(
                        MeshNotificationEvent(
                            key = "message:${message.packetId}",
                            kind = MeshNotificationKind.IncomingMessage,
                            channel = MeshNotificationChannel.Messages,
                            destination = MeshNotificationDestination.Chat,
                            title = "New message from $peerName",
                            body = message.text.notificationPreview()
                        )
                    )

                message.isOutgoing &&
                    message.status == DirectMessageStatus.Acknowledged &&
                    oldMessage?.status != DirectMessageStatus.Acknowledged ->
                    add(
                        MeshNotificationEvent(
                            key = "message:${message.packetId}",
                            kind = MeshNotificationKind.MessageAcknowledged,
                            channel = MeshNotificationChannel.Messages,
                            destination = MeshNotificationDestination.Chat,
                            title = "Message delivered",
                            body = "$peerName acknowledged your message."
                        )
                    )

                message.isOutgoing &&
                    message.status == DirectMessageStatus.Failed &&
                    oldMessage?.status != DirectMessageStatus.Failed ->
                    add(
                        MeshNotificationEvent(
                            key = "message:${message.packetId}",
                            kind = MeshNotificationKind.MessageFailed,
                            channel = MeshNotificationChannel.Messages,
                            destination = MeshNotificationDestination.Chat,
                            title = "Message not sent",
                            body = "Delivery to $peerName failed. Open Chat to retry."
                        )
                    )
            }
        }
    }

    private fun MutableList<MeshNotificationEvent>.addMediaEvents(
        previous: DirectMeshState,
        current: DirectMeshState
    ) {
        val previousTransfers = previous.mediaTransfers
            .associateBy(DirectMediaTransfer::transferId)
        current.mediaTransfers.forEach { transfer ->
            val oldTransfer = previousTransfers[transfer.transferId]
            val peerName = current.peerNameFor(transfer.peerDeviceId)
            when {
                oldTransfer == null && !transfer.isOutgoing ->
                    add(
                        MeshNotificationEvent(
                            key = "media:${transfer.transferId}",
                            kind = MeshNotificationKind.IncomingMedia,
                            channel = MeshNotificationChannel.Messages,
                            destination = MeshNotificationDestination.Chat,
                            title = "Incoming ${transfer.mediaKind.name.lowercase()}",
                            body = "${transfer.fileName} from $peerName"
                        )
                    )

                transfer.status == MediaTransferStatus.Completed &&
                    oldTransfer?.status != MediaTransferStatus.Completed ->
                    add(
                        MeshNotificationEvent(
                            key = "media:${transfer.transferId}:complete",
                            kind = MeshNotificationKind.MediaCompleted,
                            channel = MeshNotificationChannel.Messages,
                            destination = MeshNotificationDestination.Chat,
                            title = "Media transfer complete",
                            body = "${transfer.fileName} is available in Chat."
                        )
                    )

                transfer.status == MediaTransferStatus.Failed &&
                    oldTransfer?.status != MediaTransferStatus.Failed ->
                    add(
                        MeshNotificationEvent(
                            key = "media:${transfer.transferId}:failed",
                            kind = MeshNotificationKind.MediaFailed,
                            channel = MeshNotificationChannel.Messages,
                            destination = MeshNotificationDestination.Chat,
                            title = "Media transfer failed",
                            body = "Delivery of ${transfer.fileName} failed."
                        )
                    )
            }
        }
    }

    private fun MutableList<MeshNotificationEvent>.addSystemEvents(
        previous: DirectMeshState,
        current: DirectMeshState
    ) {
        if (previous.transportAvailable && !current.transportAvailable) {
            add(
                MeshNotificationEvent(
                    key = "system:transport",
                    kind = MeshNotificationKind.TransportUnavailable,
                    channel = MeshNotificationChannel.System,
                    destination = MeshNotificationDestination.Dashboard,
                    title = "CryptoMesh unavailable",
                    body = current.scanError
                        ?: "Nearby communication is currently unavailable."
                )
            )
        }
        if (
            current.scanError != null &&
            current.scanError != previous.scanError
        ) {
            add(
                MeshNotificationEvent(
                    key = "system:scan",
                    kind = MeshNotificationKind.ScanFailed,
                    channel = MeshNotificationChannel.System,
                    destination = MeshNotificationDestination.Peers,
                    title = "Peer scan stopped",
                    body = current.scanError
                )
            )
        }
    }
}

private fun peerDisconnectedEvent(peer: DirectPeer): MeshNotificationEvent {
    return MeshNotificationEvent(
        key = "peer:${peer.linkId}",
        kind = MeshNotificationKind.PeerDisconnected,
        channel = MeshNotificationChannel.Connections,
        destination = MeshNotificationDestination.Peers,
        title = "Peer disconnected",
        body = "The secure session with ${peer.notificationName()} ended."
    )
}

private fun DirectMeshState.peerNameFor(deviceId: String): String {
    return peers.firstOrNull { it.deviceId == deviceId }
        ?.notificationName()
        ?: deviceId
}

private fun DirectPeer.notificationName(): String {
    return displayName.trim().takeIf(String::isNotEmpty)
        ?: deviceId
        ?: advertisedDeviceId
        ?: "A nearby peer"
}

private fun String.notificationPreview(): String {
    return lineSequence()
        .firstOrNull()
        .orEmpty()
        .trim()
        .take(MAX_NOTIFICATION_PREVIEW_LENGTH)
        .ifEmpty { "Encrypted message received." }
}

private const val MAX_NOTIFICATION_PREVIEW_LENGTH = 120
