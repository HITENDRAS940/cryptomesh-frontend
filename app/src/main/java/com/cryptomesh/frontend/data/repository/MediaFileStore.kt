package com.cryptomesh.frontend.data.repository

import java.io.File

interface MediaFileStore {
    fun writeEncryptedChunk(
        transferId: String,
        chunkIndex: Int,
        bytes: ByteArray
    ): String

    fun readEncryptedChunk(path: String): ByteArray

    fun writeCompletedMedia(
        transferId: String,
        fileName: String,
        bytes: ByteArray
    ): String

    /** Delete all files associated with a transfer (encrypted chunks and completed media). */
    fun deleteTransferFiles(transferId: String)
}


class LocalMediaFileStore(
    rootDirectory: File
) : MediaFileStore {
    private val root = rootDirectory.apply { mkdirs() }

    override fun writeEncryptedChunk(
        transferId: String,
        chunkIndex: Int,
        bytes: ByteArray
    ): String {
        val directory = File(root, safeSegment(transferId)).apply { mkdirs() }
        val file = File(directory, "chunk-$chunkIndex.bin")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    override fun readEncryptedChunk(path: String): ByteArray {
        return File(path).readBytes()
    }

    override fun writeCompletedMedia(
        transferId: String,
        fileName: String,
        bytes: ByteArray
    ): String {
        val directory = File(root, safeSegment(transferId)).apply { mkdirs() }
        val file = File(directory, safeSegment(fileName))
        file.writeBytes(bytes)
        return file.absolutePath
    }

    override fun deleteTransferFiles(transferId: String) {
        val directory = File(root, safeSegment(transferId))
        if (directory.exists()) {
            directory.deleteRecursively()
        }
    }
}

private fun safeSegment(value: String): String {
    return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "media" }
}
