package com.cryptomesh.frontend.notification

import com.cryptomesh.frontend.data.repository.DirectMeshState
import com.cryptomesh.frontend.data.repository.DirectMediaTransfer
import com.cryptomesh.frontend.data.repository.DirectMessage
import com.cryptomesh.frontend.data.repository.DirectMessageStatus
import com.cryptomesh.frontend.data.repository.DirectPeer
import com.cryptomesh.frontend.data.repository.DirectPeerStatus
import com.cryptomesh.frontend.data.repository.MediaTransferStatus
import com.cryptomesh.frontend.protocol.MediaKind
import com.cryptomesh.frontend.ui.state.FakeDirectMeshRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeshNotificationEventDetectorTest {
    private val detector = MeshNotificationEventDetector()

    @Test
    fun peerDiscoveryAndIncomingAuthenticationCreateNotifications() {
        val discovered = peer(status = DirectPeerStatus.Discovered)
        val discoveryEvents = detector.detect(
            DirectMeshState(),
            DirectMeshState(peers = listOf(discovered))
        )
        val authenticationEvents = detector.detect(
            DirectMeshState(peers = listOf(discovered)),
            DirectMeshState(
                peers = listOf(
                    discovered.copy(
                        status = DirectPeerStatus.Authenticating
                    )
                )
            )
        )

        assertEquals(
            listOf(MeshNotificationKind.PeerFound),
            discoveryEvents.map(MeshNotificationEvent::kind)
        )
        assertEquals(
            listOf(MeshNotificationKind.IncomingConnection),
            authenticationEvents.map(MeshNotificationEvent::kind)
        )
    }

    @Test
    fun locallyStartedAuthenticationIsNotLabeledIncoming() {
        val connecting = peer(
            status = DirectPeerStatus.Connecting,
            connectionInitiatedByLocal = true
        )
        val events = detector.detect(
            DirectMeshState(peers = listOf(connecting)),
            DirectMeshState(
                peers = listOf(
                    connecting.copy(
                        status = DirectPeerStatus.Authenticating
                    )
                )
            )
        )

        assertEquals(
            MeshNotificationKind.ConnectionProgress,
            events.single().kind
        )
    }

    @Test
    fun incomingMessageAndDeliveryChangesCreateChatNotifications() {
        val connected = peer(
            status = DirectPeerStatus.Connected,
            deviceId = DEVICE_ID
        )
        val sending = message(
            id = "outgoing",
            outgoing = true,
            status = DirectMessageStatus.AwaitingAcknowledgement
        )
        val previous = DirectMeshState(
            peers = listOf(connected),
            messages = listOf(sending)
        )
        val current = previous.copy(
            messages = listOf(
                sending.copy(status = DirectMessageStatus.Acknowledged),
                message(
                    id = "incoming",
                    outgoing = false,
                    status = DirectMessageStatus.Acknowledged
                )
            )
        )

        val events = detector.detect(previous, current)

        assertEquals(
            setOf(
                MeshNotificationKind.MessageAcknowledged,
                MeshNotificationKind.IncomingMessage
            ),
            events.map(MeshNotificationEvent::kind).toSet()
        )
        assertTrue(
            events.all {
                it.destination == MeshNotificationDestination.Chat
            }
        )
    }

    @Test
    fun incomingMediaAndCompletionCreateChatNotifications() {
        val connected = peer(
            status = DirectPeerStatus.Connected,
            deviceId = DEVICE_ID
        )
        val incoming = mediaTransfer(
            id = "media-1",
            outgoing = false,
            status = MediaTransferStatus.Receiving
        )
        val previous = DirectMeshState(peers = listOf(connected))
        val current = DirectMeshState(
            peers = listOf(connected),
            mediaTransfers = listOf(incoming)
        )
        val completed = current.copy(
            mediaTransfers = listOf(
                incoming.copy(status = MediaTransferStatus.Completed)
            )
        )

        val incomingEvents = detector.detect(previous, current)
        val completedEvents = detector.detect(current, completed)

        assertEquals(
            MeshNotificationKind.IncomingMedia,
            incomingEvents.single().kind
        )
        assertEquals(
            MeshNotificationKind.MediaCompleted,
            completedEvents.single().kind
        )
    }

    @Test
    fun coordinatorUsesInitialRepositoryStateAsBaseline() = runTest {
        val repository = FakeDirectMeshRepository(
            DirectMeshState(
                peers = listOf(peer(DirectPeerStatus.Discovered))
            )
        )
        val events = mutableListOf<MeshNotificationEvent>()
        val coordinator = MeshNotificationCoordinator(
            repository = repository,
            notifier = events::add,
            scope = this
        )

        coordinator.start()
        runCurrent()

        assertTrue(events.isEmpty())

        repository.emit(
            DirectMeshState(
                peers = listOf(
                    peer(DirectPeerStatus.Authenticating)
                )
            )
        )
        runCurrent()

        assertEquals(
            MeshNotificationKind.IncomingConnection,
            events.single().kind
        )
        coordinator.stop()
    }

    private fun peer(
        status: DirectPeerStatus,
        deviceId: String? = null,
        connectionInitiatedByLocal: Boolean = false
    ): DirectPeer {
        return DirectPeer(
            linkId = "link-1",
            advertisedDeviceId = DEVICE_ID,
            deviceId = deviceId,
            displayName = "Nearby user",
            signalStrength = -45,
            status = status,
            isVerified = status == DirectPeerStatus.Connected,
            connectionInitiatedByLocal = connectionInitiatedByLocal
        )
    }

    private fun message(
        id: String,
        outgoing: Boolean,
        status: DirectMessageStatus
    ): DirectMessage {
        return DirectMessage(
            packetId = id,
            peerDeviceId = DEVICE_ID,
            text = "Encrypted hello",
            sentAtEpochMillis = 1L,
            isOutgoing = outgoing,
            status = status
        )
    }

    private fun mediaTransfer(
        id: String,
        outgoing: Boolean,
        status: MediaTransferStatus
    ): DirectMediaTransfer {
        return DirectMediaTransfer(
            transferId = id,
            peerDeviceId = DEVICE_ID,
            mediaKind = MediaKind.Photo,
            fileName = "offline-photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 4_096,
            completedChunks = if (status == MediaTransferStatus.Completed) {
                1
            } else {
                0
            },
            totalChunks = 1,
            isOutgoing = outgoing,
            status = status,
            outputPath = null
        )
    }

    private companion object {
        const val DEVICE_ID = "CM-NOTIFY01"
    }
}
