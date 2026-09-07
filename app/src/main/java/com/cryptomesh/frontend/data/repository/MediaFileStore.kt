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

    /** Write a received fragment for a chunk and assemble when complete.
     * Returns the assembled chunk path when assembly completes, otherwise null. */
    fun writeEncryptedFragment(
        transferId: String,
        chunkIndex: Int,
        fragmentIndex: Int,
        totalFragments: Int,
        bytes: ByteArray
    ): String?

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

    override fun writeEncryptedFragment(
        transferId: String,
        chunkIndex: Int,
        fragmentIndex: Int,
        totalFragments: Int,
        bytes: ByteArray
    ): String? {
        val transferDir = File(root, safeSegment(transferId)).apply { mkdirs() }
        val fragDir = File(transferDir, "fragments/chunk-$chunkIndex").apply { mkdirs() }
        val fragFile = File(fragDir, "fragment-$fragmentIndex.bin")
        fragFile.writeBytes(bytes)

        // Check if all fragments present
        val files = fragDir.listFiles()?.filter { it.name.startsWith("fragment-") } ?: emptyList()
        if (files.size < totalFragments) return null

        // Assemble fragments in order
        val assembled = File(transferDir, "chunk-$chunkIndex.bin")
        assembled.outputStream().use { out ->
            (0 until totalFragments).forEach { idx ->
                val part = File(fragDir, "fragment-$idx.bin")
                if (!part.exists()) throw IllegalStateException("Missing fragment $idx")
                out.write(part.readBytes())
            }
        }
        // Cleanup fragment files
        try {
            files.forEach { it.delete() }
            fragDir.delete()
        } catch (_: Exception) {
            // best-effort
        }
        return assembled.absolutePath
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
