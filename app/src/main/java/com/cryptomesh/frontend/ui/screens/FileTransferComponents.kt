package com.cryptomesh.frontend.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptomesh.frontend.ui.components.InfoRow
import com.cryptomesh.frontend.ui.state.FileTransferStatus
import com.cryptomesh.frontend.ui.state.FileTransferUiModel
import com.cryptomesh.frontend.ui.state.IncomingFileRequestUiModel
import java.util.Locale

@Composable
internal fun FileTransferContent(
    transfer: FileTransferUiModel
) {
    Column(
        modifier = Modifier.widthIn(min = 220.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = fileStatusContainerColor(transfer.status),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = fileStatusIcon(transfer.status),
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                    tint = fileStatusColor(transfer.status)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transfer.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatFileSize(transfer.sizeBytes)} | " +
                        fileTypeLabel(transfer.mimeType),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (transfer.status == FileTransferStatus.Transferring ||
            transfer.status == FileTransferStatus.Verifying
        ) {
            LinearProgressIndicator(
                progress = { transfer.progress },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = if (transfer.status == FileTransferStatus.Verifying) {
                    "Checking file integrity"
                } else {
                    "${(transfer.progress * 100).toInt()}% | " +
                        "${transfer.transferredChunks} of ${transfer.totalChunks} chunks"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = transfer.status.userLabel,
                style = MaterialTheme.typography.labelMedium,
                color = fileStatusColor(transfer.status)
            )
            if (transfer.policy.isLargeFile) {
                Text(
                    text = "Direct preferred",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
internal fun IncomingFileRequestDialog(
    request: IncomingFileRequestUiModel,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = null
            )
        },
        title = { Text("Incoming file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = request.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${formatFileSize(request.sizeBytes)} | " +
                        fileTypeLabel(request.mimeType)
                )
                Text(
                    text = "From ${request.peerName}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text("Receive")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text("Decline")
            }
        }
    )
}

@Composable
internal fun FileTransferDetails(
    transfer: FileTransferUiModel,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit
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
                        fileStatusContainerColor(transfer.status),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = fileStatusIcon(transfer.status),
                    contentDescription = null,
                    tint = fileStatusColor(transfer.status)
                )
            }
            Column {
                Text(
                    text = "File transfer",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = transfer.status.technicalLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = fileStatusColor(transfer.status)
                )
            }
        }

        Text(
            text = transfer.name,
            style = MaterialTheme.typography.titleMedium
        )
        InfoRow(label = "Type", value = fileTypeLabel(transfer.mimeType))
        InfoRow(label = "Size", value = formatFileSize(transfer.sizeBytes))

        if (transfer.status == FileTransferStatus.Transferring ||
            transfer.status == FileTransferStatus.Verifying
        ) {
            LinearProgressIndicator(
                progress = { transfer.progress },
                modifier = Modifier.fillMaxWidth()
            )
        }
        InfoRow(
            label = "Chunks",
            value = "${transfer.transferredChunks} / ${transfer.totalChunks}"
        )
        InfoRow(
            label = "Verification",
            value = transfer.verificationStatus.label
        )

        HorizontalDivider()
        Text(
            text = "Delivery policy",
            style = MaterialTheme.typography.titleMedium
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = transfer.policy.notice,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        InfoRow(
            label = "Replication",
            value = if (transfer.policy.replicationEnabled) {
                "Enabled"
            } else {
                "Disabled for this file"
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow(
                label = "Replicas",
                value = "${transfer.policy.replicaCount} / " +
                    transfer.policy.maximumReplicas,
                modifier = Modifier.weight(1f)
            )
            InfoRow(
                label = "File TTL",
                value = transfer.policy.fileTtl,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow(
                label = "Required link",
                value = transfer.policy.requiredConnectionQuality,
                modifier = Modifier.weight(1f)
            )
            InfoRow(
                label = "Relay candidates",
                value = transfer.policy.availableRelayCandidates.toString(),
                modifier = Modifier.weight(1f)
            )
        }

        if (transfer.status.canRetry) {
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null
                )
                Text(
                    text = "Retry transfer",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        if (transfer.status == FileTransferStatus.Completed) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onOpen,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileOpen,
                        contentDescription = null
                    )
                    Text(
                        text = "Open",
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                if (transfer.isIncoming) {
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SaveAlt,
                            contentDescription = null
                        )
                        Text(
                            text = "Save",
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

internal fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0) return "Unknown size"
    val kilobytes = sizeBytes / 1_024.0
    if (kilobytes < 1_024) {
        return String.format(Locale.getDefault(), "%.1f KB", kilobytes)
    }
    val megabytes = kilobytes / 1_024.0
    if (megabytes < 1_024) {
        return String.format(Locale.getDefault(), "%.1f MB", megabytes)
    }
    return String.format(
        Locale.getDefault(),
        "%.1f GB",
        megabytes / 1_024.0
    )
}

private fun fileTypeLabel(mimeType: String): String {
    return when {
        mimeType == "application/pdf" -> "PDF document"
        mimeType.startsWith("image/") -> "Image"
        mimeType.startsWith("video/") -> "Video"
        mimeType.startsWith("audio/") -> "Audio"
        mimeType.startsWith("text/") -> "Text document"
        else -> "File"
    }
}

@Composable
private fun fileStatusColor(status: FileTransferStatus): Color {
    return when (status) {
        FileTransferStatus.Completed -> MaterialTheme.colorScheme.primary
        FileTransferStatus.Failed,
        FileTransferStatus.Expired -> MaterialTheme.colorScheme.error

        FileTransferStatus.InsufficientRelayStorage,
        FileTransferStatus.RelayRejectedLowResources ->
            MaterialTheme.colorScheme.onTertiaryContainer

        FileTransferStatus.StoredForForwarding ->
            MaterialTheme.colorScheme.onSecondaryContainer

        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun fileStatusContainerColor(status: FileTransferStatus): Color {
    return when (status) {
        FileTransferStatus.Completed -> MaterialTheme.colorScheme.primaryContainer
        FileTransferStatus.Failed,
        FileTransferStatus.Expired -> MaterialTheme.colorScheme.errorContainer

        FileTransferStatus.InsufficientRelayStorage,
        FileTransferStatus.RelayRejectedLowResources ->
            MaterialTheme.colorScheme.tertiaryContainer

        FileTransferStatus.StoredForForwarding ->
            MaterialTheme.colorScheme.secondaryContainer

        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
}

private fun fileStatusIcon(status: FileTransferStatus): ImageVector {
    return when (status) {
        FileTransferStatus.Completed -> Icons.Default.CheckCircle
        FileTransferStatus.Failed,
        FileTransferStatus.Expired -> Icons.Default.ErrorOutline

        FileTransferStatus.StoredForForwarding -> Icons.Default.Inventory2
        FileTransferStatus.Transferring,
        FileTransferStatus.Verifying -> Icons.Default.Sync

        FileTransferStatus.Preparing,
        FileTransferStatus.WaitingForSuitableConnection,
        FileTransferStatus.InsufficientRelayStorage,
        FileTransferStatus.RelayRejectedLowResources -> Icons.Default.HourglassTop
    }
}
