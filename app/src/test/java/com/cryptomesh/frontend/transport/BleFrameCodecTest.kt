package com.cryptomesh.frontend.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFrameCodecTest {
    @Test
    fun largeWireMessageReassemblesWhenFramesArriveOutOfOrder() {
        val codec = BleFrameCodec(maximumFrameBytes = 32)
        val reassembler = BleFrameReassembler(codec)
        val message = ByteArray(400) { (it % 251).toByte() }
        val frames = codec.chunk(message)

        var result: ByteArray? = null
        frames.reversed().forEach { frame ->
            result = reassembler.accept("peer-a", frame) ?: result
        }

        assertTrue(frames.size > 1)
        assertArrayEquals(message, result)
    }

    @Test
    fun framesFromDifferentPeersCannotBeCombined() {
        val codec = BleFrameCodec(maximumFrameBytes = 24)
        val reassembler = BleFrameReassembler(codec)
        val frames = codec.chunk(ByteArray(80) { 7 })

        assertNull(reassembler.accept("peer-a", frames.first()))
        frames.drop(1).forEach {
            assertNull(reassembler.accept("peer-b", it))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidFrameMagicIsRejected() {
        val codec = BleFrameCodec()
        val frame = codec.chunk("hello".encodeToByteArray()).single()
        frame[0] = 0

        codec.decode(frame)
    }
}
