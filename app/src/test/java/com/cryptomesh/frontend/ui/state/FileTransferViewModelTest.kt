package com.cryptomesh.frontend.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferViewModelTest {
    @Test
    fun directFileTransferCompletesWithIntegrityVerification() {
        val viewModel = ChatViewModel()
        openConversationWithRoute(viewModel, ConversationRoute.Direct)
        viewModel.selectAttachment(
            uri = "content://documents/brief.pdf",
            name = "brief.pdf",
            mimeType = "application/pdf",
            sizeBytes = 2L * 1_024L * 1_024L
        )

        viewModel.sendMessage()

        val initial = latestTransfer(viewModel)
        assertEquals(FileTransferStatus.Preparing, initial.status)
        assertFalse(initial.policy.isLargeFile)
        assertTrue(initial.policy.replicationEnabled)
        assertNull(viewModel.uiState.value.selectedAttachment)

        repeat(8) {
            viewModel.advanceFileTransfer(initial.id)
        }

        val completed = latestTransfer(viewModel)
        assertEquals(FileTransferStatus.Completed, completed.status)
        assertEquals(FileVerificationStatus.Passed, completed.verificationStatus)
        assertEquals(completed.totalChunks, completed.transferredChunks)
        assertFalse(completed.autoAdvance)
    }

    @Test
    fun relayTransferStoresReplicaBeforeForwardingChunks() {
        val viewModel = ChatViewModel()
        openConversationWithRoute(viewModel, ConversationRoute.Relay)
        viewModel.selectAttachment(
            uri = "content://documents/notes.txt",
            name = "notes.txt",
            mimeType = "text/plain",
            sizeBytes = 640_000
        )
        viewModel.sendMessage()
        val transferId = latestTransfer(viewModel).id

        viewModel.advanceFileTransfer(transferId)

        val stored = latestTransfer(viewModel)
        assertEquals(FileTransferStatus.StoredForForwarding, stored.status)
        assertEquals(1, stored.policy.replicaCount)

        viewModel.advanceFileTransfer(transferId)
        assertEquals(FileTransferStatus.Transferring, latestTransfer(viewModel).status)
    }

    @Test
    fun largeFileUsesDirectPreferredPolicyAndRejectsUnsuitableRelay() {
        val viewModel = ChatViewModel()
        openConversationWithRoute(viewModel, ConversationRoute.Relay)
        viewModel.selectAttachment(
            uri = "content://documents/video.mp4",
            name = "video.mp4",
            mimeType = "video/mp4",
            sizeBytes = 24L * 1_024L * 1_024L
        )
        viewModel.sendMessage()
        val transferId = latestTransfer(viewModel).id

        val initial = latestTransfer(viewModel)
        assertTrue(initial.policy.isLargeFile)
        assertFalse(initial.policy.replicationEnabled)
        assertEquals(0, initial.policy.maximumReplicas)

        viewModel.advanceFileTransfer(transferId)
        assertEquals(
            FileTransferStatus.WaitingForSuitableConnection,
            latestTransfer(viewModel).status
        )

        viewModel.advanceFileTransfer(transferId)
        val rejected = latestTransfer(viewModel)
        assertEquals(FileTransferStatus.InsufficientRelayStorage, rejected.status)
        assertTrue(rejected.status.canRetry)
        assertFalse(rejected.autoAdvance)
    }

    @Test
    fun offlineTransferFailsAndCanBeQueuedAgain() {
        val viewModel = ChatViewModel()
        openConversationWithRoute(viewModel, ConversationRoute.Offline)
        viewModel.selectAttachment(
            uri = "content://documents/map.png",
            name = "map.png",
            mimeType = "image/png",
            sizeBytes = 512_000
        )
        viewModel.sendMessage()
        val transferId = latestTransfer(viewModel).id

        viewModel.advanceFileTransfer(transferId)
        viewModel.advanceFileTransfer(transferId)
        assertEquals(FileTransferStatus.Failed, latestTransfer(viewModel).status)

        viewModel.retryFileTransfer(transferId)
        val retried = latestTransfer(viewModel)
        assertEquals(FileTransferStatus.WaitingForSuitableConnection, retried.status)
        assertTrue(retried.autoAdvance)
    }

    @Test
    fun incomingRequestCreatesIncomingTransferWhenAccepted() {
        val viewModel = ChatViewModel()
        viewModel.openConversation("conversation-meera")

        assertNotNull(viewModel.uiState.value.incomingFileRequest)
        val originalCount = selectedConversation(viewModel).messages.size

        viewModel.acceptIncomingFile()

        assertNull(viewModel.uiState.value.incomingFileRequest)
        assertEquals(originalCount + 1, selectedConversation(viewModel).messages.size)
        val transfer = latestTransfer(viewModel)
        assertTrue(transfer.isIncoming)
        assertEquals(FileTransferStatus.Transferring, transfer.status)
    }

    private fun openConversationWithRoute(
        viewModel: ChatViewModel,
        route: ConversationRoute
    ) {
        val conversation = viewModel.uiState.value.conversations.first {
            it.route == route
        }
        viewModel.openConversation(conversation.id)
    }

    private fun latestTransfer(viewModel: ChatViewModel): FileTransferUiModel {
        return selectedConversation(viewModel).messages.last().fileTransfer
            ?: error("Latest message does not contain a file transfer")
    }

    private fun selectedConversation(viewModel: ChatViewModel): ConversationUiModel {
        val state = viewModel.uiState.value
        return state.conversations.first { it.id == state.selectedConversationId }
    }
}
