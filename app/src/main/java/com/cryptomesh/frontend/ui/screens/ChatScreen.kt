package com.cryptomesh.frontend.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.cryptomesh.frontend.ui.components.InfoRow
import com.cryptomesh.frontend.ui.components.MainTabHeader
import com.cryptomesh.frontend.ui.components.ScreenHeader
import com.cryptomesh.frontend.ui.components.StatusPill
import com.cryptomesh.frontend.ui.state.ChatMessageUiModel
import com.cryptomesh.frontend.ui.state.ChatUiState
import com.cryptomesh.frontend.ui.state.ChatViewModel
import com.cryptomesh.frontend.ui.state.ConversationRoute
import com.cryptomesh.frontend.ui.state.ConversationUiModel
import com.cryptomesh.frontend.ui.state.FileTransferStatus
import com.cryptomesh.frontend.ui.state.MessageDeliveryStatus
import com.cryptomesh.frontend.ui.state.PacketDeliveryUiModel
import com.cryptomesh.frontend.ui.state.PacketPriorityUi
import com.cryptomesh.frontend.ui.state.SelectedAttachmentUiModel
import com.cryptomesh.frontend.ui.theme.CryptoMeshTheme
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activeConversation = uiState.conversations.firstOrNull {
        it.id == uiState.selectedConversationId
    }
    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val attachment = readSelectedAttachment(context, uri)
            viewModel.selectAttachment(
                uri = attachment.uri,
                name = attachment.name,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes
            )
        }
    }

    BackHandler(enabled = activeConversation != null) {
        viewModel.closeConversation()
    }

    uiState.conversations
        .flatMap { it.messages }
        .filter { it.autoAdvance }
        .forEach { message ->
            LaunchedEffect(message.id, message.deliveryStatus) {
                delay(1_000)
                viewModel.advanceDelivery(message.id)
            }
        }

    uiState.conversations
        .flatMap { it.messages }
        .filter { it.animateOnAppearance }
        .forEach { message ->
            LaunchedEffect(message.id, message.animateOnAppearance) {
                delay(320)
                viewModel.markMessageAnimationComplete(message.id)
            }
        }

    uiState.conversations
        .flatMap { it.messages }
        .mapNotNull { it.fileTransfer }
        .filter { it.autoAdvance }
        .forEach { transfer ->
            LaunchedEffect(transfer.id, transfer.status, transfer.progress) {
                delay(700)
                viewModel.advanceFileTransfer(transfer.id)
            }
        }

    ChatContent(
        uiState = uiState,
        onOpenConversation = viewModel::openConversation,
        onCloseConversation = viewModel::closeConversation,
        onComposerChange = viewModel::updateComposer,
        onSendMessage = viewModel::sendMessage,
        onPickAttachment = { attachmentPicker.launch(arrayOf("*/*")) },
        onClearAttachment = viewModel::clearAttachment,
        onRetryMessage = viewModel::retryMessage,
        onRetryFileTransfer = viewModel::retryFileTransfer,
        onShowMessageDetails = viewModel::showMessageDetails,
        onDismissMessageDetails = viewModel::dismissMessageDetails,
        onShowFileTransferDetails = viewModel::showFileTransferDetails,
        onDismissFileTransferDetails = viewModel::dismissFileTransferDetails,
        onAcceptIncomingFile = viewModel::acceptIncomingFile,
        onDeclineIncomingFile = viewModel::declineIncomingFile,
        onOpenFile = {
            Toast.makeText(
                context,
                "Open action will use local encrypted storage.",
                Toast.LENGTH_SHORT
            ).show()
        },
        onSaveFile = {
            Toast.makeText(
                context,
                "Save action will be connected to local file storage.",
                Toast.LENGTH_SHORT
            ).show()
        },
        onShowContactPicker = viewModel::showContactPicker,
        onDismissContactPicker = viewModel::dismissContactPicker
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    uiState: ChatUiState,
    onOpenConversation: (String) -> Unit,
    onCloseConversation: () -> Unit,
    onComposerChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onPickAttachment: () -> Unit,
    onClearAttachment: () -> Unit,
    onRetryMessage: (String) -> Unit,
    onRetryFileTransfer: (String) -> Unit,
    onShowMessageDetails: (String) -> Unit,
    onDismissMessageDetails: () -> Unit,
    onShowFileTransferDetails: (String) -> Unit,
    onDismissFileTransferDetails: () -> Unit,
    onAcceptIncomingFile: () -> Unit,
    onDeclineIncomingFile: () -> Unit,
    onOpenFile: () -> Unit,
    onSaveFile: () -> Unit,
    onShowContactPicker: () -> Unit,
    onDismissContactPicker: () -> Unit
) {
    val activeConversation = uiState.conversations.firstOrNull {
        it.id == uiState.selectedConversationId
    }
    val selectedMessage = uiState.conversations
        .flatMap { it.messages }
        .firstOrNull { it.id == uiState.selectedMessageId }
    val selectedFileTransfer = uiState.conversations
        .flatMap { it.messages }
        .mapNotNull { it.fileTransfer }
        .firstOrNull { it.id == uiState.selectedFileTransferId }

    if (activeConversation == null) {
        ConversationInbox(
            conversations = uiState.conversations,
            onOpenConversation = onOpenConversation,
            onNewMessage = onShowContactPicker
        )
    } else {
        ConversationThread(
            conversation = activeConversation,
            composerText = uiState.composerText,
            selectedAttachment = uiState.selectedAttachment,
            onBack = onCloseConversation,
            onComposerChange = onComposerChange,
            onSend = onSendMessage,
            onPickAttachment = onPickAttachment,
            onClearAttachment = onClearAttachment,
            onRetry = onRetryMessage,
            onRetryFileTransfer = onRetryFileTransfer,
            onShowDetails = onShowMessageDetails,
            onShowFileTransferDetails = onShowFileTransferDetails
        )
    }

    if (uiState.showContactPicker) {
        ModalBottomSheet(
            onDismissRequest = onDismissContactPicker,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            UseLightDialogSystemBars()
            ContactPicker(
                conversations = uiState.conversations,
                onSelect = onOpenConversation
            )
        }
    }

    if (selectedMessage?.delivery != null) {
        ModalBottomSheet(
            onDismissRequest = onDismissMessageDetails,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            UseLightDialogSystemBars()
            MessageDeliveryDetails(
                status = selectedMessage.deliveryStatus,
                delivery = selectedMessage.delivery
            )
        }
    }

    if (selectedFileTransfer != null) {
        ModalBottomSheet(
            onDismissRequest = onDismissFileTransferDetails,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            UseLightDialogSystemBars()
            FileTransferDetails(
                transfer = selectedFileTransfer,
                onRetry = {
                    onRetryFileTransfer(selectedFileTransfer.id)
                    onDismissFileTransferDetails()
                },
                onOpen = onOpenFile,
                onSave = onSaveFile
            )
        }
    }

    uiState.incomingFileRequest?.let { request ->
        IncomingFileRequestDialog(
            request = request,
            onAccept = onAcceptIncomingFile,
            onDecline = onDeclineIncomingFile
        )
    }
}

@Composable
private fun ConversationInbox(
    conversations: List<ConversationUiModel>,
    onOpenConversation: (String) -> Unit,
    onNewMessage: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            MainTabHeader(
                title = "Secure Chat",
                supportingText = "${conversations.size} local conversations",
                trailingContent = {
                    IconButton(onClick = onNewMessage) {
                        Icon(
                            imageVector = Icons.Default.AddComment,
                            contentDescription = "Start conversation"
                        )
                    }
                }
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp,
                    vertical = 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    text = "Session security",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Verified sessions are marked in the conversation list.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                items(conversations, key = { it.id }) { conversation ->
                    ConversationListItem(
                        conversation = conversation,
                        onClick = { onOpenConversation(conversation.id) }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun ConversationListItem(
    conversation: ConversationUiModel,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ChatAvatar(name = conversation.peerName)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.peerName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = conversation.timestamp,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (conversation.isVerifiedSession) {
                            Icons.Default.Lock
                        } else {
                            Icons.Default.LockOpen
                        },
                        contentDescription = if (conversation.isVerifiedSession) {
                            "Verified secure session"
                        } else {
                            "Session not verified"
                        },
                        modifier = Modifier.size(16.dp),
                        tint = if (conversation.isVerifiedSession) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Text(
                        text = conversation.preview,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (conversation.unreadCount > 0) {
                        UnreadBadge(count = conversation.unreadCount)
                    }
                }
                Text(
                    text = conversation.route.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = routeColor(conversation.route)
                )
            }
        }
    }
}

@Composable
private fun ConversationThread(
    conversation: ConversationUiModel,
    composerText: String,
    selectedAttachment: SelectedAttachmentUiModel?,
    onBack: () -> Unit,
    onComposerChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickAttachment: () -> Unit,
    onClearAttachment: () -> Unit,
    onRetry: (String) -> Unit,
    onRetryFileTransfer: (String) -> Unit,
    onShowDetails: (String) -> Unit,
    onShowFileTransferDetails: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val latestMessage = conversation.messages.lastOrNull()

    LaunchedEffect(latestMessage?.id) {
        if (conversation.messages.isNotEmpty()) {
            if (latestMessage?.animateOnAppearance == true) {
                delay(220)
            }
            listState.animateScrollToItem(conversation.messages.size)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ConversationHeader(
                conversation = conversation,
                onBack = onBack
            )
            AnimatedVisibility(
                visible = !isKeyboardVisible,
                enter = expandVertically(
                    animationSpec = tween(220),
                    expandFrom = Alignment.Top
                ) + fadeIn(animationSpec = tween(180)),
                exit = shrinkVertically(
                    animationSpec = tween(180),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(animationSpec = tween(120))
            ) {
                RouteStatus(route = conversation.route)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = "Messages are stored locally",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(conversation.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        onRetry = { onRetry(message.id) },
                        onRetryFileTransfer = {
                            message.fileTransfer?.let {
                                onRetryFileTransfer(it.id)
                            }
                        },
                        onShowDetails = { onShowDetails(message.id) },
                        onShowFileTransferDetails = {
                            message.fileTransfer?.let {
                                onShowFileTransferDetails(it.id)
                            }
                        }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            MessageComposer(
                value = composerText,
                selectedAttachment = selectedAttachment,
                onValueChange = onComposerChange,
                onSend = onSend,
                onPickAttachment = onPickAttachment,
                onClearAttachment = onClearAttachment
            )
        }
    }
}

@Composable
private fun ConversationHeader(
    conversation: ConversationUiModel,
    onBack: () -> Unit
) {
    ScreenHeader(
        title = conversation.peerName,
        supportingText = conversation.deviceId,
        onBack = onBack,
        leadingContent = {
            ChatAvatar(name = conversation.peerName, size = 40)
        },
        trailingContent = {
            Icon(
                imageVector = if (conversation.isVerifiedSession) {
                    Icons.Default.Lock
                } else {
                    Icons.Default.LockOpen
                },
                contentDescription = if (conversation.isVerifiedSession) {
                    "Verified secure session"
                } else {
                    "Session not verified"
                },
                tint = if (conversation.isVerifiedSession) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    )
}

@Composable
private fun RouteStatus(route: ConversationRoute) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = when (route) {
            ConversationRoute.Direct -> MaterialTheme.colorScheme.primaryContainer
            ConversationRoute.Relay -> MaterialTheme.colorScheme.secondaryContainer
            ConversationRoute.Offline -> MaterialTheme.colorScheme.errorContainer
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = routeIcon(route),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = routeColor(route)
            )
            Column {
                Text(
                    text = route.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = routeColor(route)
                )
                Text(
                    text = route.supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = routeColor(route)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessageUiModel,
    onRetry: () -> Unit,
    onRetryFileTransfer: () -> Unit,
    onShowDetails: () -> Unit,
    onShowFileTransferDetails: () -> Unit
) {
    val visibilityState = remember(message.id) {
        MutableTransitionState(!message.animateOnAppearance).apply {
            targetState = true
        }
    }

    AnimatedVisibility(
        visibleState = visibilityState,
        modifier = Modifier.fillMaxWidth(),
        enter = fadeIn(animationSpec = tween(220)) +
            slideInVertically(
                animationSpec = tween(260),
                initialOffsetY = { it / 3 }
            ) +
            expandVertically(
                animationSpec = tween(240),
                expandFrom = Alignment.Bottom
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(180)),
            horizontalAlignment = if (message.isOutgoing) {
                Alignment.End
            } else {
                Alignment.Start
            }
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clickable(
                        enabled = message.delivery != null || message.fileTransfer != null,
                        onClick = if (message.fileTransfer != null) {
                            onShowFileTransferDetails
                        } else {
                            onShowDetails
                        }
                    ),
                shape = MaterialTheme.shapes.medium,
                color = if (message.isOutgoing) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    message.fileTransfer?.let { transfer ->
                        FileTransferContent(transfer = transfer)
                    }
                    if (message.text.isNotEmpty()) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = message.timestamp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (message.isOutgoing && message.fileTransfer == null) {
                            AnimatedContent(
                                targetState = message.deliveryStatus,
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(160)) +
                                        slideInVertically(
                                            animationSpec = tween(180),
                                            initialOffsetY = { it / 2 }
                                        )).togetherWith(
                                        fadeOut(animationSpec = tween(100)) +
                                            slideOutVertically(
                                                animationSpec = tween(120),
                                                targetOffsetY = { -it / 2 }
                                            )
                                    )
                                },
                                label = "messageDeliveryStatus"
                            ) { status ->
                                if (status != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        DeliveryStatusIcon(status = status)
                                        Text(
                                            text = status.userLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = deliveryColor(status)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (message.deliveryStatus == MessageDeliveryStatus.Failed ||
                message.deliveryStatus == MessageDeliveryStatus.Expired ||
                message.fileTransfer?.status?.canRetry == true
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            message.fileTransfer?.status?.canRetry == true ->
                                message.fileTransfer.status.userLabel
                            message.deliveryStatus == MessageDeliveryStatus.Expired ->
                                "Packet expired"
                            else -> "Could not deliver"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    IconButton(
                        onClick = if (message.fileTransfer != null) {
                            onRetryFileTransfer
                        } else {
                            onRetry
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = if (message.fileTransfer != null) {
                                "Retry file transfer"
                            } else {
                                "Retry message"
                            },
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryStatusIcon(status: MessageDeliveryStatus) {
    when (status) {
        MessageDeliveryStatus.QueuedLocally,
        MessageDeliveryStatus.WaitingForDestination -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = deliveryColor(status)
            )
        }

        MessageDeliveryStatus.DirectlyDelivered -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = deliveryColor(status)
            )
        }

        MessageDeliveryStatus.StoredOnRelay,
        MessageDeliveryStatus.CarriedByRelay -> {
            Icon(
                imageVector = Icons.Default.Inventory2,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = deliveryColor(status)
            )
        }

        MessageDeliveryStatus.Forwarding,
        MessageDeliveryStatus.ReplicaLimitReached -> {
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = deliveryColor(status)
            )
        }

        MessageDeliveryStatus.Acknowledged -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = deliveryColor(status)
            )
        }

        MessageDeliveryStatus.Expired,
        MessageDeliveryStatus.Failed -> {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = deliveryColor(status)
            )
        }
    }
}

@Composable
private fun MessageComposer(
    value: String,
    selectedAttachment: SelectedAttachmentUiModel?,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickAttachment: () -> Unit,
    onClearAttachment: () -> Unit
) {
    val composerModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Modifier.imePadding()
    } else {
        Modifier
    }

    Surface(
        modifier = composerModifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            selectedAttachment?.let { attachment ->
                PendingAttachmentPreview(
                    attachment = attachment,
                    onClear = onClearAttachment
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = onPickAttachment,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach file"
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (selectedAttachment == null) {
                                "Message"
                            } else {
                                "Add a caption"
                            }
                        )
                    },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (value.isNotBlank() || selectedAttachment != null) {
                                onSend()
                            }
                        }
                    )
                )
                IconButton(
                    onClick = onSend,
                    enabled = value.isNotBlank() || selectedAttachment != null,
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (selectedAttachment == null) {
                            "Send message"
                        } else {
                            "Send file"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingAttachmentPreview(
    attachment: SelectedAttachmentUiModel,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatFileSize(attachment.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove attachment"
                )
            }
        }
    }
}

@Composable
private fun ContactPicker(
    conversations: List<ConversationUiModel>,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "New message",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Nearby and previously encountered peers",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        conversations.forEach { conversation ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(conversation.id) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ChatAvatar(name = conversation.peerName, size = 40)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = conversation.peerName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = conversation.route.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = routeColor(conversation.route)
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageDeliveryDetails(
    status: MessageDeliveryStatus?,
    delivery: PacketDeliveryUiModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = status?.let { deliveryContainerColor(it) }
                            ?: MaterialTheme.colorScheme.surfaceContainer,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (status != null) {
                    DeliveryStatusIcon(status = status)
                } else {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null
                    )
                }
            }
            Column {
                Text(
                    text = "Delivery details",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = status?.technicalLabel ?: "Incoming packet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = status?.let { deliveryColor(it) }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            text = status?.userLabel ?: "Received",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = deliveryExplanation(status),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
        Text(
            text = "Packet",
            style = MaterialTheme.typography.titleMedium
        )
        InfoRow(label = "Packet ID", value = delivery.packetId)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow(
                label = "Priority",
                value = delivery.priority.label,
                modifier = Modifier.weight(1f)
            )
            InfoRow(
                label = "Expires",
                value = delivery.expiresAt,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow(
                label = "Replicas",
                value = "${delivery.replicaCount} / ${delivery.maximumReplicas}",
                modifier = Modifier.weight(1f)
            )
            InfoRow(
                label = "Hops",
                value = "${delivery.hopCount} / ${delivery.maximumHops}",
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow(
                label = "Relay count",
                value = delivery.relayCount.toString(),
                modifier = Modifier.weight(1f)
            )
            InfoRow(
                label = "ACK",
                value = delivery.ackStatus,
                modifier = Modifier.weight(1f)
            )
        }
        InfoRow(
            label = "Last forwarding attempt",
            value = delivery.lastForwardingAttempt
        )
        InfoRow(
            label = "Delivery path",
            value = delivery.deliveryPath.joinToString(" -> ")
        )

        Text(
            text = "Packet details do not include message contents or cryptographic keys.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChatAvatar(
    name: String,
    size: Int = 46
) {
    val initials = name
        .split(" ")
        .mapNotNull { it.firstOrNull()?.uppercase() }
        .take(2)
        .joinToString("")

    Box(
        modifier = Modifier
            .size(size.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials.ifEmpty { "CM" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.coerceAtMost(9).toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun deliveryColor(status: MessageDeliveryStatus): Color {
    return when (status) {
        MessageDeliveryStatus.Acknowledged,
        MessageDeliveryStatus.DirectlyDelivered -> MaterialTheme.colorScheme.primary

        MessageDeliveryStatus.StoredOnRelay,
        MessageDeliveryStatus.CarriedByRelay,
        MessageDeliveryStatus.Forwarding -> MaterialTheme.colorScheme.onSecondaryContainer

        MessageDeliveryStatus.Failed,
        MessageDeliveryStatus.Expired -> MaterialTheme.colorScheme.error

        MessageDeliveryStatus.QueuedLocally,
        MessageDeliveryStatus.WaitingForDestination,
        MessageDeliveryStatus.ReplicaLimitReached -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun deliveryContainerColor(status: MessageDeliveryStatus): Color {
    return when (status) {
        MessageDeliveryStatus.Acknowledged,
        MessageDeliveryStatus.DirectlyDelivered -> MaterialTheme.colorScheme.primaryContainer

        MessageDeliveryStatus.StoredOnRelay,
        MessageDeliveryStatus.CarriedByRelay,
        MessageDeliveryStatus.Forwarding -> MaterialTheme.colorScheme.secondaryContainer

        MessageDeliveryStatus.Failed,
        MessageDeliveryStatus.Expired -> MaterialTheme.colorScheme.errorContainer

        MessageDeliveryStatus.QueuedLocally,
        MessageDeliveryStatus.WaitingForDestination,
        MessageDeliveryStatus.ReplicaLimitReached -> MaterialTheme.colorScheme.surfaceContainer
    }
}

@Composable
private fun routeColor(route: ConversationRoute): Color {
    return when (route) {
        ConversationRoute.Direct -> MaterialTheme.colorScheme.onPrimaryContainer
        ConversationRoute.Relay -> MaterialTheme.colorScheme.onSecondaryContainer
        ConversationRoute.Offline -> MaterialTheme.colorScheme.onErrorContainer
    }
}

private fun routeIcon(route: ConversationRoute): ImageVector {
    return when (route) {
        ConversationRoute.Direct -> Icons.Default.Person
        ConversationRoute.Relay -> Icons.Default.Route
        ConversationRoute.Offline -> Icons.Default.CloudOff
    }
}

private fun deliveryExplanation(status: MessageDeliveryStatus?): String {
    return when (status) {
        MessageDeliveryStatus.QueuedLocally ->
            "The message is saved locally and will be attempted when a connection is available."
        MessageDeliveryStatus.WaitingForDestination ->
            "The destination is not reachable yet. The packet remains protected on this device."
        MessageDeliveryStatus.DirectlyDelivered ->
            "The destination received the packet directly. An acknowledgement is still pending."
        MessageDeliveryStatus.StoredOnRelay ->
            "A trusted relay is storing the packet until it can reach the destination."
        MessageDeliveryStatus.CarriedByRelay ->
            "The relay is carrying the packet while waiting to encounter the destination."
        MessageDeliveryStatus.Forwarding ->
            "A relay has found the destination and is forwarding the packet."
        MessageDeliveryStatus.Acknowledged ->
            "The destination received the packet and returned an end-to-end acknowledgement."
        MessageDeliveryStatus.Expired ->
            "The packet reached its expiry time and was removed before delivery."
        MessageDeliveryStatus.ReplicaLimitReached ->
            "The allowed replica limit has been reached. Existing copies continue delivery attempts."
        MessageDeliveryStatus.Failed ->
            "The latest delivery attempt failed. The message can be queued again."
        null -> "This packet was received from the peer."
    }
}

private fun readSelectedAttachment(
    context: Context,
    uri: Uri
): SelectedAttachmentUiModel {
    var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    var sizeBytes = 0L

    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                displayName = cursor.getString(nameColumn)
            }
            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                sizeBytes = cursor.getLong(sizeColumn)
            }
        }
    }

    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    return SelectedAttachmentUiModel(
        uri = uri.toString(),
        name = displayName,
        mimeType = context.contentResolver.getType(uri)
            ?: "application/octet-stream",
        sizeBytes = sizeBytes
    )
}

@Composable
private fun UseLightDialogSystemBars() {
    val view = LocalView.current

    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let {
            WindowCompat.getInsetsController(it, it.decorView)
        }
        controller?.isAppearanceLightStatusBars = true
        controller?.isAppearanceLightNavigationBars = true

        onDispose { }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun ChatInboxPreview() {
    CryptoMeshTheme {
        ConversationInbox(
            conversations = listOf(
                ConversationUiModel(
                    id = "preview",
                    peerName = "Aarav's Pixel",
                    deviceId = "CM-7A21F4C8",
                    preview = "I am near the north gate.",
                    timestamp = "10:42 AM",
                    unreadCount = 1,
                    isVerifiedSession = true,
                    route = ConversationRoute.Direct,
                    messages = emptyList()
                )
            ),
            onOpenConversation = {},
            onNewMessage = {}
        )
    }
}
