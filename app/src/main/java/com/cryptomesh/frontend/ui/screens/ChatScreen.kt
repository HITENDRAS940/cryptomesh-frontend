package com.cryptomesh.frontend.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptomesh.frontend.data.repository.MediaTransferStatus
import com.cryptomesh.frontend.protocol.MediaKind
import com.cryptomesh.frontend.ui.components.EmptyState
import com.cryptomesh.frontend.ui.components.MainTabHeader
import com.cryptomesh.frontend.ui.components.ScreenHeader
import com.cryptomesh.frontend.ui.components.StatusPill
import com.cryptomesh.frontend.ui.state.ChatMessageUiModel
import com.cryptomesh.frontend.ui.state.ChatViewModel
import com.cryptomesh.frontend.ui.state.ConversationUiModel
import com.cryptomesh.frontend.ui.state.MediaAttachmentUiModel
import com.cryptomesh.frontend.ui.state.MediaTransferUiModel
import com.cryptomesh.frontend.ui.state.MessageDeliveryStatus
import java.io.File
import java.util.Locale

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activeConversation = uiState.conversations.firstOrNull {
        it.id == uiState.selectedConversationId
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val attachment = uri?.let { readMediaAttachment(context, it) }
        if (attachment != null) {
            viewModel.selectAttachment(
                uri = attachment.uri,
                mediaKind = attachment.mediaKind,
                fileName = attachment.fileName,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes,
                bytes = attachment.bytes
            )
        }
    }

    BackHandler(enabled = activeConversation != null) {
        viewModel.closeConversation()
    }
    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissError()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (activeConversation == null) {
            ConversationInbox(
                conversations = uiState.conversations,
                onOpenConversation = viewModel::openConversation,
                modifier = Modifier.padding(padding)
            )
        } else {
            ConversationThread(
                conversation = activeConversation,
                composerText = uiState.composerText,
                selectedAttachment = uiState.selectedAttachment,
                onBack = viewModel::closeConversation,
                onComposerChange = viewModel::updateComposer,
                onAttach = {
                    mediaPicker.launch(
                        arrayOf(
                            "image/*",
                            "video/*",
                            "audio/*",
                            "application/pdf"
                        )
                    )
                },
                onClearAttachment = viewModel::clearAttachment,
                onSend = viewModel::sendMessage,
                onRetry = viewModel::retryMessage,
                onMediaRetry = viewModel::retryMedia,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun ConversationInbox(
    conversations: List<ConversationUiModel>,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            MainTabHeader(
                title = "Secure Chat",
                supportingText = "${conversations.size} authenticated conversations",
                trailingContent = {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )
            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        title = "No conversations",
                        description =
                            "Authenticated peer sessions appear here."
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        conversations,
                        key = ConversationUiModel::id
                    ) { conversation ->
                        ConversationCard(
                            conversation = conversation,
                            onClick = {
                                onOpenConversation(conversation.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationCard(
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
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        conversation.peerName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        conversation.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    conversation.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusPill(
                text = if (conversation.isConnected) "Live" else "Offline"
            )
        }
    }
}

private sealed interface ConversationTimelineItem {
    val id: String
    val createdAtEpochMillis: Long
}

private data class MessageTimelineItem(
    val message: ChatMessageUiModel
) : ConversationTimelineItem {
    override val id: String = message.id
    override val createdAtEpochMillis: Long = message.createdAtEpochMillis
}

private data class TransferTimelineItem(
    val transfer: MediaTransferUiModel
) : ConversationTimelineItem {
    override val id: String = transfer.id
    override val createdAtEpochMillis: Long = transfer.createdAtEpochMillis
}

@Composable
private fun ConversationThread(
    conversation: ConversationUiModel,
    composerText: String,
    selectedAttachment: MediaAttachmentUiModel?,
    onBack: () -> Unit,
    onComposerChange: (String) -> Unit,
    onAttach: () -> Unit,
    onClearAttachment: () -> Unit,
    onSend: () -> Unit,
    onRetry: (String) -> Unit,
    onMediaRetry: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val context = LocalContext.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val timeline = remember(
        conversation.messages,
        conversation.mediaTransfers
    ) {
        (conversation.messages.map(::MessageTimelineItem) +
            conversation.mediaTransfers.map(::TransferTimelineItem))
            .sortedBy(ConversationTimelineItem::createdAtEpochMillis)
    }

    LaunchedEffect(timeline.size, isKeyboardVisible) {
        if (timeline.isNotEmpty()) {
            listState.animateScrollToItem(timeline.lastIndex)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            ScreenHeader(
                title = conversation.peerName,
                supportingText = if (conversation.isConnected) {
                    "Verified encrypted session"
                } else {
                    "Verified offline queue"
                },
                onBack = onBack,
                trailingContent = {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Encrypted session",
                        tint = if (conversation.isConnected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            )
            if (conversation.messages.isEmpty() && conversation.mediaTransfers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(
                        icon = Icons.Default.Lock,
                        title = "Encrypted session ready",
                        description = conversation.deviceId
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        vertical = 14.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = timeline,
                        key = { item -> item.id }
                    ) { item ->
                        when (item) {
                            is MessageTimelineItem -> AnimatedMessageBubble(
                                message = item.message,
                                onRetry = { onRetry(item.message.id) }
                            )
                            is TransferTimelineItem -> MediaTransferCard(
                                transfer = item.transfer,
                                onRetry = { transferId ->
                                    onMediaRetry(transferId)
                                },
                                onOpen = {
                                    openTransferFile(context, item.transfer)
                                }
                            )
                        }
                    }
                }
            }
            MessageComposer(
                text = composerText,
                selectedAttachment = selectedAttachment,
                enabled = conversation.isVerifiedSession,
                onTextChange = onComposerChange,
                onAttach = onAttach,
                onClearAttachment = onClearAttachment,
                onSend = onSend
            )
        }
    }
}

@Composable
private fun AnimatedMessageBubble(
    message: ChatMessageUiModel,
    onRetry: () -> Unit
) {
    var visible by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 3 }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (message.isOutgoing) {
                Alignment.End
            } else {
                Alignment.Start
            }
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 300.dp),
                color = if (message.isOutgoing) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    message.text,
                    modifier = Modifier.padding(
                        horizontal = 14.dp,
                        vertical = 10.dp
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    message.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (message.isOutgoing) {
                    DeliveryIcon(message.deliveryStatus)
                }
            }
            if (
                message.deliveryStatus == MessageDeliveryStatus.Failed
            ) {
                OutlinedButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun DeliveryIcon(status: MessageDeliveryStatus?) {
    val icon = when (status) {
        MessageDeliveryStatus.Queued -> Icons.Default.Schedule
        MessageDeliveryStatus.Sending -> Icons.Default.Schedule
        MessageDeliveryStatus.AwaitingAcknowledgement -> Icons.Default.Check
        MessageDeliveryStatus.Acknowledged -> Icons.Default.DoneAll
        MessageDeliveryStatus.Failed -> Icons.Default.ErrorOutline
        null -> return
    }
    Icon(
        imageVector = icon,
        contentDescription = when (status) {
            MessageDeliveryStatus.Queued -> "Queued for mesh delivery"
            MessageDeliveryStatus.Sending -> "Sending"
            MessageDeliveryStatus.AwaitingAcknowledgement ->
                "Awaiting acknowledgement"
            MessageDeliveryStatus.Acknowledged -> "Acknowledged"
            MessageDeliveryStatus.Failed -> "Failed"
        },
        modifier = Modifier.size(16.dp),
        tint = if (status == MessageDeliveryStatus.Failed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }
    )
}

@Composable
private fun MessageComposer(
    text: String,
    selectedAttachment: MediaAttachmentUiModel?,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onAttach: () -> Unit,
    onClearAttachment: () -> Unit,
    onSend: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = 10.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectedAttachment != null) {
                SelectedAttachmentRow(
                    attachment = selectedAttachment,
                    onClear = onClearAttachment
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onAttach,
                    enabled = enabled
                ) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "Attach media"
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    placeholder = {
                        Text(
                            if (enabled) "Message" else "Authentication required"
                        )
                    },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (text.isNotBlank() ||
                                selectedAttachment != null
                            ) {
                                onSend()
                            }
                        }
                    )
                )
                IconButton(
                    onClick = onSend,
                    enabled = enabled &&
                        (text.isNotBlank() || selectedAttachment != null),
                    colors = IconButtonDefaults.filledIconButtonColors()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send"
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedAttachmentRow(
    attachment: MediaAttachmentUiModel,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            mediaIcon(attachment.mediaKind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                attachment.fileName,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                formatFileSize(attachment.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onClear) {
            Text("Remove")
        }
    }
}

@Composable
private fun MediaTransferCard(
    transfer: MediaTransferUiModel,
    onRetry: (String) -> Unit,
    onOpen: () -> Unit
) {
    val openEnabled = transfer.status == MediaTransferStatus.Completed &&
        !transfer.outputPath.isNullOrBlank()
    val showPreview = !transfer.outputPath.isNullOrBlank() &&
        (transfer.mediaKind == MediaKind.Photo ||
            transfer.mediaKind == MediaKind.Video ||
            transfer.mediaKind == MediaKind.Document)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (openEnabled) Modifier.clickable(onClick = onOpen) else Modifier
                ),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        mediaIcon(transfer.mediaKind),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            transfer.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${formatFileSize(transfer.sizeBytes)} | " +
                                transfer.status.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (showPreview) {
                    MediaTransferPreview(transfer = transfer, onOpen = onOpen)
                }
                LinearProgressIndicator(
                    progress = { transfer.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    transfer.progressText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (transfer.status == MediaTransferStatus.Failed) {
                    OutlinedButton(onClick = { onRetry(transfer.id) }) {
                        Text("Retry")
                    }
                }
            }
        }

        if (transfer.status == MediaTransferStatus.Offered ||
            transfer.status == MediaTransferStatus.Transferring ||
            transfer.status == MediaTransferStatus.Receiving
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0x88000000))
                )
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun MediaTransferPreview(
    transfer: MediaTransferUiModel,
    onOpen: () -> Unit
) {
    val thumbnail = remember(transfer.outputPath) {
        transfer.outputPath?.let { path ->
            buildMediaPreviewBitmap(path, transfer.mediaKind)
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 220.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 1.dp
    ) {
        when (transfer.mediaKind) {
            MediaKind.Photo -> {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Preview of ${transfer.fileName}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    FilePlaceholder(
                        icon = Icons.Default.Image,
                        title = "Open photo",
                        subtitle = transfer.fileName
                    )
                }
            }
            MediaKind.Video -> {
                if (thumbnail != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = "Preview of ${transfer.fileName}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Surface(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(48.dp),
                            shape = CircleShape,
                            color = Color(0x88000000)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                        }
                    }
                } else {
                    FilePlaceholder(
                        icon = Icons.Default.VideoFile,
                        title = "Open video",
                        subtitle = transfer.fileName
                    )
                }
            }
            MediaKind.Audio -> {
                FilePlaceholder(
                    icon = Icons.Default.AudioFile,
                    title = "Open audio",
                    subtitle = transfer.fileName
                )
            }
            MediaKind.Document -> {
                FilePlaceholder(
                    icon = Icons.Default.PictureAsPdf,
                    title = "Open PDF",
                    subtitle = transfer.fileName
                )
            }
        }
    }
}

@Composable
private fun FilePlaceholder(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun openTransferFile(context: Context, transfer: MediaTransferUiModel) {
    val outputPath = transfer.outputPath ?: return
    val file = File(outputPath)
    if (!file.exists()) return

    val mimeType = transfer.mimeType.ifBlank {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension)
            ?: "application/octet-stream"
    }

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addCategory(Intent.CATEGORY_BROWSABLE)
    }

    val chooser = Intent.createChooser(
        intent,
        "Open ${transfer.fileName}"
    )
    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(chooser)
    }
}

private fun buildMediaPreviewBitmap(
    path: String,
    mediaKind: MediaKind
): Bitmap? {
    val file = File(path)
    if (!file.exists()) return null
    return when (mediaKind) {
        MediaKind.Photo -> {
            BitmapFactory.decodeFile(file.absolutePath)
        }
        MediaKind.Video -> {
            runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val bitmap = retriever.frameAtTime
                retriever.release()
                bitmap
            }.getOrNull()
        }
        MediaKind.Audio -> null
        MediaKind.Document -> null
    }
}

private data class PickedMediaAttachment(
    val uri: String,
    val mediaKind: MediaKind,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bytes: ByteArray
)

private fun readMediaAttachment(
    context: Context,
    uri: Uri
): PickedMediaAttachment? {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri) ?: return null
    val metadata = resolver.query(
        uri,
        arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
        ),
        null,
        null,
        null
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameIndex >= 0) {
            cursor.getString(nameIndex)
        } else {
            "media"
        }
        val size = if (sizeIndex >= 0) {
            cursor.getLong(sizeIndex)
        } else {
            0L
        }
        name to size
    }
    val fileName = metadata?.first ?: "media"
    val mediaKind = when {
        mimeType.startsWith("image/") -> MediaKind.Photo
        mimeType.startsWith("video/") -> MediaKind.Video
        mimeType.startsWith("audio/") -> MediaKind.Audio
        mimeType == "application/pdf" || fileName.endsWith(".pdf", ignoreCase = true) -> MediaKind.Document
        else -> return null
    }
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: return null
    return PickedMediaAttachment(
        uri = uri.toString(),
        mediaKind = mediaKind,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = metadata?.second?.takeIf { it > 0 } ?: bytes.size.toLong(),
        bytes = bytes
    )
}

private fun mediaIcon(kind: MediaKind): ImageVector {
    return when (kind) {
        MediaKind.Photo -> Icons.Default.Image
        MediaKind.Video -> Icons.Default.VideoFile
        MediaKind.Audio -> Icons.Default.AudioFile
        MediaKind.Document -> Icons.Default.PictureAsPdf
    }
}

private fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes < 1_024L) return "$sizeBytes B"
    val units = listOf("KB", "MB", "GB")
    var value = sizeBytes / 1_024.0
    var unitIndex = 0
    while (value >= 1_024.0 && unitIndex < units.lastIndex) {
        value /= 1_024.0
        unitIndex += 1
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}
