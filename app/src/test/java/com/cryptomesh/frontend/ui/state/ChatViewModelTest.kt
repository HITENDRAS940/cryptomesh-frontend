package com.cryptomesh.frontend.ui.state

import com.cryptomesh.frontend.data.repository.DirectMeshState
import com.cryptomesh.frontend.data.repository.DirectMessage
import com.cryptomesh.frontend.data.repository.DirectMessageStatus
import com.cryptomesh.frontend.data.repository.DirectPeer
import com.cryptomesh.frontend.data.repository.DirectPeerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun betaStartsWithoutSeededConversations() {
        val viewModel = ChatViewModel(FakeDirectMeshRepository())

        assertTrue(viewModel.uiState.value.conversations.isEmpty())
        assertNull(viewModel.uiState.value.selectedConversationId)
    }

    @Test
    fun authenticatedPeerCreatesLiveConversationAndSendsText() = runTest {
        val repository = FakeDirectMeshRepository(
            DirectMeshState(peers = listOf(connectedPeer()))
        )
        val viewModel = ChatViewModel(repository)
        advanceUntilIdle()
        val conversation = viewModel.uiState.value.conversations.single()

        viewModel.openConversation(conversation.id)
        viewModel.updateComposer("  real BLE message  ")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(
            listOf("CM-12345678" to "real BLE message"),
            repository.sentMessages
        )
        assertEquals("", viewModel.uiState.value.composerText)
    }

    @Test
    fun duplicateLinksForAuthenticatedDeviceCreateOneConversation() = runTest {
        val repository = FakeDirectMeshRepository(
            DirectMeshState(
                peers = listOf(
                    connectedPeer().copy(
                        linkId = "stale-link",
                        displayName = "Stale peer",
                        status = DirectPeerStatus.Discovered,
                        isVerified = false
                    ),
                    connectedPeer()
                )
            )
        )
        val viewModel = ChatViewModel(repository)
        advanceUntilIdle()

        val conversation = viewModel.uiState.value.conversations.single()

        assertEquals("live-CM-12345678", conversation.id)
        assertEquals("Beta Peer", conversation.peerName)
        assertTrue(conversation.isConnected)
    }

    @Test
    fun repositoryMessagesMapToAcknowledgementAndFailureStates() = runTest {
        val peer = connectedPeer()
        val repository = FakeDirectMeshRepository(
            DirectMeshState(
                peers = listOf(peer),
                messages = listOf(
                    directMessage(
                        "packet-ack",
                        DirectMessageStatus.Acknowledged
                    ),
                    directMessage(
                        "packet-failed",
                        DirectMessageStatus.Failed
                    )
                )
            )
        )
        val viewModel = ChatViewModel(repository)
        advanceUntilIdle()

        val messages = viewModel.uiState.value
            .conversations.single().messages

        assertEquals(
            MessageDeliveryStatus.Acknowledged,
            messages.first().deliveryStatus
        )
        assertEquals(
            MessageDeliveryStatus.Failed,
            messages.last().deliveryStatus
        )
    }

    @Test
    fun verifiedOfflineConversationQueuesText() = runTest {
        val repository = FakeDirectMeshRepository(
            DirectMeshState(
                peers = listOf(
                    connectedPeer().copy(
                        status = DirectPeerStatus.Discovered,
                        isVerified = true
                    )
                )
            )
        )
        val viewModel = ChatViewModel(repository)
        advanceUntilIdle()
        val conversation = viewModel.uiState.value.conversations.single()

        viewModel.openConversation(conversation.id)
        viewModel.updateComposer("send later")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(
            listOf("CM-12345678" to "send later"),
            repository.sentMessages
        )
        assertEquals("", viewModel.uiState.value.composerText)
    }

    @Test
    fun unauthenticatedConversationDoesNotSend() = runTest {
        val repository = FakeDirectMeshRepository(
            DirectMeshState(
                peers = listOf(
                    connectedPeer().copy(
                        status = DirectPeerStatus.Discovered,
                        isVerified = false
                    )
                )
            )
        )
        val viewModel = ChatViewModel(repository)
        advanceUntilIdle()
        val conversation = viewModel.uiState.value.conversations.single()

        viewModel.openConversation(conversation.id)
        viewModel.updateComposer("cannot send")
        viewModel.sendMessage()

        assertTrue(repository.sentMessages.isEmpty())
        assertFalse(viewModel.uiState.value.errorMessage.isNullOrBlank())
    }

    @Test
    fun groupConversationRendersAndSendsGroupText() = runTest {
        val group = com.cryptomesh.frontend.data.repository.DirectGroup(
            groupId = "GRP-TEST1",
            groupName = "Alpha Squad",
            isBroadcastChannel = false,
            creatorDeviceId = "CM-ME",
            members = listOf("CM-ME", "CM-PEER1"),
            createdAtEpochMillis = 1000L,
            lastActivityEpochMillis = 1000L
        )
        val repository = FakeDirectMeshRepository(
            DirectMeshState(groups = listOf(group))
        )
        val viewModel = ChatViewModel(repository)
        advanceUntilIdle()

        val conv = viewModel.uiState.value.conversations.single()
        assertEquals("group-GRP-TEST1", conv.id)
        assertEquals("Alpha Squad", conv.peerName)
        assertTrue(conv.isGroup)
        assertFalse(conv.isBroadcastChannel)
        assertEquals(2, conv.memberCount)

        viewModel.openConversation(conv.id)
        viewModel.updateComposer("Hold position")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(listOf("GRP-TEST1" to "Hold position"), repository.sentGroupMessages)
        assertEquals("", viewModel.uiState.value.composerText)
    }

    @Test
    fun broadcastConversationRendersAndSendsBroadcastText() = runTest {
        val channel = com.cryptomesh.frontend.data.repository.DirectGroup(
            groupId = "CHANNEL-GENERAL",
            groupName = "#general",
            isBroadcastChannel = true,
            creatorDeviceId = "SYSTEM",
            members = emptyList(),
            createdAtEpochMillis = 1000L,
            lastActivityEpochMillis = 1000L
        )
        val repository = FakeDirectMeshRepository(
            DirectMeshState(groups = listOf(channel))
        )
        val viewModel = ChatViewModel(repository)
        advanceUntilIdle()

        val conv = viewModel.uiState.value.conversations.single()
        assertEquals("group-CHANNEL-GENERAL", conv.id)
        assertTrue(conv.isBroadcastChannel)

        viewModel.openConversation(conv.id)
        viewModel.updateComposer("Mesh broadcast beacon active")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(
            listOf("CHANNEL-GENERAL" to "Mesh broadcast beacon active"),
            repository.sentBroadcastMessages
        )
    }

    @Test
    fun createGroupDialogOpensAndCreatesGroup() = runTest {
        val peer = connectedPeer()
        val repository = FakeDirectMeshRepository(
            DirectMeshState(peers = listOf(peer))
        )
        val viewModel = ChatViewModel(repository)
        advanceUntilIdle()

        viewModel.openCreateGroupDialog()
        assertTrue(viewModel.uiState.value.isCreateGroupDialogOpen)
        assertEquals(1, viewModel.uiState.value.availablePeersForGroup.size)
        assertEquals("CM-12345678", viewModel.uiState.value.availablePeersForGroup.first().deviceId)

        viewModel.createGroup("Delta Force", listOf("CM-12345678"))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isCreateGroupDialogOpen)
        assertEquals(
            listOf("Delta Force" to listOf("CM-12345678")),
            repository.createdGroups
        )
        assertNotNull(viewModel.uiState.value.groupCreationSuccessMessage)
    }

    private fun connectedPeer(): DirectPeer {
        return DirectPeer(
            linkId = "link-1",
            advertisedDeviceId = "CM-12345678",
            deviceId = "CM-12345678",
            displayName = "Beta Peer",
            signalStrength = -50,
            status = DirectPeerStatus.Connected,
            isVerified = true
        )
    }

    private fun directMessage(
        id: String,
        status: DirectMessageStatus
    ): DirectMessage {
        return DirectMessage(
            packetId = id,
            peerDeviceId = "CM-12345678",
            text = id,
            sentAtEpochMillis = if (id == "packet-ack") 1L else 2L,
            isOutgoing = true,
            status = status
        )
    }
}
