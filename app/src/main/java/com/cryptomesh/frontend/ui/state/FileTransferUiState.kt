package com.cryptomesh.frontend.ui.state

enum class FileTransferStatus(
    val userLabel: String,
    val technicalLabel: String
) {
    Preparing("Preparing file", "Preparing encrypted chunks"),
    WaitingForSuitableConnection(
        "Waiting for suitable connection",
        "Waiting for required link quality"
    ),
    Transferring("Sending file", "Forwarding encrypted chunks"),
    StoredForForwarding("Stored securely", "Stored for later forwarding"),
    Verifying("Verifying file", "Verifying reconstructed file"),
    Completed("Transfer complete", "Verified transfer complete"),
    Failed("Transfer failed", "Chunk transfer failed"),
    Expired("Transfer expired", "File TTL expired"),
    InsufficientRelayStorage(
        "Waiting for another relay",
        "Insufficient relay storage"
    ),
    RelayRejectedLowResources(
        "Waiting for another relay",
        "Relay rejected due to low resources"
    );

    val canRetry: Boolean
        get() = this == Failed ||
            this == Expired ||
            this == InsufficientRelayStorage ||
            this == RelayRejectedLowResources
}

enum class FileVerificationStatus(val label: String) {
    NotStarted("Not started"),
    Pending("Pending"),
    Passed("Integrity verified"),
    Failed("Verification failed")
}

data class FileReplicationPolicyUiModel(
    val isLargeFile: Boolean,
    val replicationEnabled: Boolean,
    val replicaCount: Int,
    val maximumReplicas: Int,
    val fileTtl: String,
    val requiredConnectionQuality: String,
    val availableRelayCandidates: Int,
    val notice: String
)

data class FileTransferUiModel(
    val id: String,
    val uri: String?,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: FileTransferStatus,
    val progress: Float,
    val transferredChunks: Int,
    val totalChunks: Int,
    val verificationStatus: FileVerificationStatus,
    val policy: FileReplicationPolicyUiModel,
    val isIncoming: Boolean,
    val autoAdvance: Boolean
)

data class SelectedAttachmentUiModel(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long
)

data class IncomingFileRequestUiModel(
    val id: String,
    val conversationId: String,
    val peerName: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long
)
