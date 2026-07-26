package com.cryptomesh.frontend.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelTest {
    @Test
    fun openingConversationMarksItReadAndClosingReturnsToInbox() {
        val viewModel = ChatViewModel()
        val conversation = viewModel.uiState.value.conversations.first()
        assertTrue(conversation.unreadCount > 0)

        viewModel.openConversation(conversation.id)

        val opened = viewModel.uiState.value
        assertEquals(conversation.id, opened.selectedConversationId)
        assertEquals(0, opened.conversations.first().unreadCount)

        viewModel.closeConversation()
        assertNull(viewModel.uiState.value.selectedConversationId)
    }

    @Test
    fun directMessageProgressesToEndToEndAcknowledgement() {
        val viewModel = ChatViewModel()
        val conversation = viewModel.uiState.value.conversations.first {
            it.route == ConversationRoute.Direct
        }
        viewModel.openConversation(conversation.id)
        viewModel.updateComposer("Meet at the north gate")

        viewModel.sendMessage()

        val queued = selectedConversation(viewModel).messages.last()
        assertEquals(MessageDeliveryStatus.QueuedLocally, queued.deliveryStatus)
        assertTrue(queued.autoAdvance)
        assertTrue(queued.animateOnAppearance)

        viewModel.markMessageAnimationComplete(queued.id)
        assertFalse(
            selectedConversation(viewModel).messages.last().animateOnAppearance
        )

        viewModel.advanceDelivery(queued.id)
        assertEquals(
            MessageDeliveryStatus.DirectlyDelivered,
            selectedConversation(viewModel).messages.last().deliveryStatus
        )

        viewModel.advanceDelivery(queued.id)
        val acknowledged = selectedConversation(viewModel).messages.last()
        assertEquals(MessageDeliveryStatus.Acknowledged, acknowledged.deliveryStatus)
        assertEquals("End-to-end ACK received", acknowledged.delivery?.ackStatus)
        assertFalse(acknowledged.autoAdvance)
    }

    @Test
    fun relayMessageProgressesThroughStoreCarryForward() {
        val viewModel = ChatViewModel()
        val conversation = viewModel.uiState.value.conversations.first {
            it.route == ConversationRoute.Relay
        }
        viewModel.openConversation(conversation.id)
        viewModel.updateComposer("Offline update")
        viewModel.sendMessage()
        val messageId = selectedConversation(viewModel).messages.last().id

        viewModel.advanceDelivery(messageId)
        assertEquals(
            MessageDeliveryStatus.StoredOnRelay,
            selectedConversation(viewModel).messages.last().deliveryStatus
        )

        viewModel.advanceDelivery(messageId)
        assertEquals(
            MessageDeliveryStatus.CarriedByRelay,
            selectedConversation(viewModel).messages.last().deliveryStatus
        )

        viewModel.advanceDelivery(messageId)
        assertEquals(
            MessageDeliveryStatus.Forwarding,
            selectedConversation(viewModel).messages.last().deliveryStatus
        )

        viewModel.advanceDelivery(messageId)
        val acknowledged = selectedConversation(viewModel).messages.last()
        assertEquals(MessageDeliveryStatus.Acknowledged, acknowledged.deliveryStatus)
        assertEquals(1, acknowledged.delivery?.relayCount)
    }

    @Test
    fun failedMessageCanRetryThroughRelay() {
        val viewModel = ChatViewModel()
        val conversation = viewModel.uiState.value.conversations.first {
            it.route == ConversationRoute.Offline
        }
        viewModel.openConversation(conversation.id)
        viewModel.updateComposer("Are you available?")
        viewModel.sendMessage()
        val messageId = selectedConversation(viewModel).messages.last().id

        viewModel.advanceDelivery(messageId)
        assertEquals(
            MessageDeliveryStatus.Failed,
            selectedConversation(viewModel).messages.last().deliveryStatus
        )

        viewModel.retryMessage(messageId)
        viewModel.advanceDelivery(messageId)

        val relayed = selectedConversation(viewModel).messages.last()
        assertEquals(MessageDeliveryStatus.StoredOnRelay, relayed.deliveryStatus)
        assertEquals(2, relayed.deliveryAttempts)
        assertEquals(1, relayed.delivery?.replicaCount)
    }

    @Test
    fun emptyComposerDoesNotCreateMessage() {
        val viewModel = ChatViewModel()
        val conversation = viewModel.uiState.value.conversations.first()
        viewModel.openConversation(conversation.id)
        val originalCount = selectedConversation(viewModel).messages.size

        viewModel.updateComposer("   ")
        viewModel.sendMessage()

        assertEquals(originalCount, selectedConversation(viewModel).messages.size)
    }

    private fun selectedConversation(viewModel: ChatViewModel): ConversationUiModel {
        val state = viewModel.uiState.value
        return state.conversations.first { it.id == state.selectedConversationId }
    }
}
