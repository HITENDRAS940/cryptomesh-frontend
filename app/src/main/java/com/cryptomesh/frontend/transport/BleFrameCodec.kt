package com.cryptomesh.frontend.transport

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BleFrameCodec(
    private val maximumFrameBytes: Int = DEFAULT_FRAME_BYTES
) {
    private val nextMessageId = AtomicInteger(1)

    init {
        require(maximumFrameBytes > HEADER_BYTES) {
            "A BLE frame must leave room for payload data."
        }
    }

    fun chunk(message: ByteArray): List<ByteArray> {
        require(message.isNotEmpty()) { "Cannot frame an empty message." }
        val payloadSize = maximumFrameBytes - HEADER_BYTES
        val totalChunks = (message.size + payloadSize - 1) / payloadSize
        require(totalChunks <= MAX_CHUNKS) {
            "Wire message exceeds the BLE frame limit."
        }
        val messageId = nextMessageId.getAndIncrement()
        return (0 until totalChunks).map { index ->
            val start = index * payloadSize
            val end = minOf(start + payloadSize, message.size)
            ByteBuffer.allocate(HEADER_BYTES + end - start)
                .put(MAGIC_FIRST)
                .put(MAGIC_SECOND)
                .put(FRAME_VERSION)
                .putInt(messageId)
                .put(index.toByte())
                .put(totalChunks.toByte())
                .put(message, start, end - start)
                .array()
        }
    }

    fun decode(frame: ByteArray): BleFrame {
        require(frame.size > HEADER_BYTES) { "BLE frame is incomplete." }
        val buffer = ByteBuffer.wrap(frame)
        require(
            buffer.get() == MAGIC_FIRST &&
                buffer.get() == MAGIC_SECOND
        ) {
            "BLE frame magic is invalid."
        }
        require(buffer.get() == FRAME_VERSION) {
            "Unsupported BLE frame version."
        }
        val messageId = buffer.int
        val chunkIndex = buffer.get().toInt() and 0xFF
        val totalChunks = buffer.get().toInt() and 0xFF
        require(totalChunks in 1..MAX_CHUNKS) {
            "BLE frame chunk count is invalid."
        }
        require(chunkIndex < totalChunks) {
            "BLE frame chunk index is invalid."
        }
        return BleFrame(
            messageId = messageId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            payload = ByteArray(buffer.remaining()).also(buffer::get)
        )
    }

    companion object {
        const val DEFAULT_FRAME_BYTES = 180
        private const val HEADER_BYTES = 9
        private const val MAX_CHUNKS = 128
        private const val FRAME_VERSION: Byte = 1
        private const val MAGIC_FIRST: Byte = 0x43
        private const val MAGIC_SECOND: Byte = 0x4D
    }
}

data class BleFrame(
    val messageId: Int,
    val chunkIndex: Int,
    val totalChunks: Int,
    val payload: ByteArray
)

class BleFrameReassembler(
    private val frameCodec: BleFrameCodec = BleFrameCodec()
) {
    private val partialMessages =
        ConcurrentHashMap<String, PartialBleMessage>()

    fun accept(sourceId: String, encodedFrame: ByteArray): ByteArray? {
        val frame = frameCodec.decode(encodedFrame)
        val key = "$sourceId:${frame.messageId}"
        require(
            partialMessages.containsKey(key) ||
                partialMessages.size < MAX_PENDING_MESSAGES
        ) {
            "Too many incomplete BLE messages."
        }
        val partial = partialMessages.compute(key) { _, current ->
            val message = current ?: PartialBleMessage(frame.totalChunks)
            require(message.totalChunks == frame.totalChunks) {
                "BLE frame count changed within a message."
            }
            message.chunks.putIfAbsent(frame.chunkIndex, frame.payload)
            message
        } ?: return null

        if (partial.chunks.size != partial.totalChunks) return null
        partialMessages.remove(key)
        return (0 until partial.totalChunks)
            .map { index ->
                requireNotNull(partial.chunks[index]) {
                    "BLE message is missing a frame."
                }
            }
            .fold(byteArrayOf()) { result, chunk -> result + chunk }
    }

    fun clear(sourceId: String) {
        partialMessages.keys.removeAll { it.startsWith("$sourceId:") }
    }

    private companion object {
        const val MAX_PENDING_MESSAGES = 32
    }
}

private data class PartialBleMessage(
    val totalChunks: Int,
    val chunks: MutableMap<Int, ByteArray> = ConcurrentHashMap()
)
