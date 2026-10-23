package com.cryptomesh.frontend.protocol.dop

import com.cryptomesh.frontend.transport.BleFrameCodec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

data class LatencyStats(
    val sampleCount: Int,
    val minMicros: Long,
    val maxMicros: Long,
    val meanMicros: Double,
    val medianMicros: Long,
    val p95Micros: Long
)

data class SizeAndFrameStats(
    val sampleCount: Int,
    val meanBytes: Double,
    val maxBytes: Int,
    val meanBleFrames: Double,
    val maxBleFrames: Int
)

data class DopEvaluationSummary(
    val preKeyGenerationLatency: LatencyStats,
    val preKeyValidationLatency: LatencyStats,
    val dopInitiationCreationLatency: LatencyStats,
    val dopRecipientCompletionLatency: LatencyStats,
    val directHandshakeLatency: LatencyStats,
    val offlineToDeliveryLatencyMillis: LatencyStats,
    val preKeyRecordSize: SizeAndFrameStats,
    val preKeySyncMessageSize: SizeAndFrameStats,
    val dopInitiationPacketSize: SizeAndFrameStats,
    val directHandshakeSize: SizeAndFrameStats,
    val cacheHits: Long,
    val cacheMisses: Long,
    val cacheHitRate: Double,
    val cacheInsertions: Long,
    val cacheEvictions: Long,
    val duplicateRecordsIgnored: Long,
    val staleRecordsIgnored: Long,
    val conflictingRecordsRejected: Long,
    val invalidSignaturesRejected: Long,
    val expiredRecordsRejected: Long,
    val replayInitiationsCoalesced: Long,
    val replayAttacksRejected: Long,
    val concurrentMultiSenderInitiations: Long,
    val opkGraceWindowUses: Long,
    val relayHopDistribution: Map<Int, Int>
)

/**
 * Thread-safe evaluation instrumentation collector for DOP-Handshake and Direct Handshake
 * side-by-side experimental analysis.
 */
class DopMetricsCollector {
    private val preKeyGenMicros = CopyOnWriteArrayList<Long>()
    private val preKeyValMicros = CopyOnWriteArrayList<Long>()
    private val dopInitCreateMicros = CopyOnWriteArrayList<Long>()
    private val dopRecipientCompleteMicros = CopyOnWriteArrayList<Long>()
    private val directHandshakeMicros = CopyOnWriteArrayList<Long>()
    private val offlineDeliveryMillis = CopyOnWriteArrayList<Long>()

    private val preKeyRecordBytes = CopyOnWriteArrayList<Int>()
    private val preKeySyncBytes = CopyOnWriteArrayList<Int>()
    private val dopInitPacketBytes = CopyOnWriteArrayList<Int>()
    private val directHandshakeBytes = CopyOnWriteArrayList<Int>()
    private val relayHops = CopyOnWriteArrayList<Int>()

    private val cacheHitsCounter = AtomicLong(0)
    private val cacheMissesCounter = AtomicLong(0)
    private val cacheInsertionsCounter = AtomicLong(0)
    private val cacheEvictionsCounter = AtomicLong(0)
    private val duplicateRecordsCounter = AtomicLong(0)
    private val staleRecordsCounter = AtomicLong(0)
    private val conflictingRecordsCounter = AtomicLong(0)
    private val invalidSignaturesCounter = AtomicLong(0)
    private val expiredRecordsCounter = AtomicLong(0)
    private val replayCoalescedCounter = AtomicLong(0)
    private val replayRejectedCounter = AtomicLong(0)
    private val concurrentMultiSenderCounter = AtomicLong(0)
    private val opkGraceUsesCounter = AtomicLong(0)

    fun recordPreKeyGeneration(durationNanos: Long, serializedRecordBytes: Int) {
        preKeyGenMicros.add((durationNanos / 1_000L).coerceAtLeast(1L))
        preKeyRecordBytes.add(serializedRecordBytes)
    }

    fun recordPreKeyValidation(durationNanos: Long, result: DopValidationResult) {
        preKeyValMicros.add((durationNanos / 1_000L).coerceAtLeast(1L))
        if (result is DopValidationResult.Invalid) {
            when (result.reason) {
                DopValidationFailureReason.INVALID_SIGNATURE,
                DopValidationFailureReason.DEVICE_ID_KEY_MISMATCH,
                DopValidationFailureReason.FINGERPRINT_KEY_MISMATCH -> {
                    invalidSignaturesCounter.incrementAndGet()
                }
                DopValidationFailureReason.EXPIRED_RECORD -> {
                    expiredRecordsCounter.incrementAndGet()
                }
                else -> Unit
            }
        }
    }

    fun recordDopInitiationCreation(durationNanos: Long, wirePacketBytes: Int) {
        dopInitCreateMicros.add((durationNanos / 1_000L).coerceAtLeast(1L))
        dopInitPacketBytes.add(wirePacketBytes)
    }

    fun recordDopRecipientCompletion(
        durationNanos: Long,
        offlineDelayMillis: Long,
        hopCount: Int
    ) {
        dopRecipientCompleteMicros.add((durationNanos / 1_000L).coerceAtLeast(1L))
        offlineDeliveryMillis.add(offlineDelayMillis.coerceAtLeast(0L))
        relayHops.add(hopCount.coerceAtLeast(0))
    }

    fun recordDirectHandshake(durationNanos: Long, wireHelloBytes: Int) {
        directHandshakeMicros.add((durationNanos / 1_000L).coerceAtLeast(1L))
        directHandshakeBytes.add(wireHelloBytes)
    }

    fun recordPreKeySyncMessageSize(serializedSyncBytes: Int) {
        preKeySyncBytes.add(serializedSyncBytes)
    }

    fun recordCacheHit() {
        cacheHitsCounter.incrementAndGet()
    }

    fun recordCacheMiss() {
        cacheMissesCounter.incrementAndGet()
    }

    fun recordCacheInsertion() {
        cacheInsertionsCounter.incrementAndGet()
    }

    fun recordCacheEviction(count: Int = 1) {
        if (count > 0) {
            cacheEvictionsCounter.addAndGet(count.toLong())
        }
    }

    fun recordDuplicateRecordIgnored() {
        duplicateRecordsCounter.incrementAndGet()
    }

    fun recordStaleRecordIgnored() {
        staleRecordsCounter.incrementAndGet()
    }

    fun recordConflictingRecordRejected() {
        conflictingRecordsCounter.incrementAndGet()
    }

    fun recordReplayInitiationCoalesced() {
        replayCoalescedCounter.incrementAndGet()
    }

    fun recordReplayAttackRejected() {
        replayRejectedCounter.incrementAndGet()
    }

    fun recordConcurrentMultiSenderInitiation(usedOpkGrace: Boolean) {
        concurrentMultiSenderCounter.incrementAndGet()
        if (usedOpkGrace) {
            opkGraceUsesCounter.incrementAndGet()
        }
    }

    fun snapshot(): DopEvaluationSummary {
        val hits = cacheHitsCounter.get()
        val misses = cacheMissesCounter.get()
        val totalLookups = hits + misses
        val hitRate = if (totalLookups > 0) hits.toDouble() / totalLookups.toDouble() else 0.0

        return DopEvaluationSummary(
            preKeyGenerationLatency = computeLatencyStats(preKeyGenMicros),
            preKeyValidationLatency = computeLatencyStats(preKeyValMicros),
            dopInitiationCreationLatency = computeLatencyStats(dopInitCreateMicros),
            dopRecipientCompletionLatency = computeLatencyStats(dopRecipientCompleteMicros),
            directHandshakeLatency = computeLatencyStats(directHandshakeMicros),
            offlineToDeliveryLatencyMillis = computeLatencyStats(offlineDeliveryMillis),
            preKeyRecordSize = computeSizeStats(preKeyRecordBytes),
            preKeySyncMessageSize = computeSizeStats(preKeySyncBytes),
            dopInitiationPacketSize = computeSizeStats(dopInitPacketBytes),
            directHandshakeSize = computeSizeStats(directHandshakeBytes),
            cacheHits = hits,
            cacheMisses = misses,
            cacheHitRate = hitRate,
            cacheInsertions = cacheInsertionsCounter.get(),
            cacheEvictions = cacheEvictionsCounter.get(),
            duplicateRecordsIgnored = duplicateRecordsCounter.get(),
            staleRecordsIgnored = staleRecordsCounter.get(),
            conflictingRecordsRejected = conflictingRecordsCounter.get(),
            invalidSignaturesRejected = invalidSignaturesCounter.get(),
            expiredRecordsRejected = expiredRecordsCounter.get(),
            replayInitiationsCoalesced = replayCoalescedCounter.get(),
            replayAttacksRejected = replayRejectedCounter.get(),
            concurrentMultiSenderInitiations = concurrentMultiSenderCounter.get(),
            opkGraceWindowUses = opkGraceUsesCounter.get(),
            relayHopDistribution = relayHops.groupingBy { it }.eachCount()
        )
    }

    fun reset() {
        preKeyGenMicros.clear()
        preKeyValMicros.clear()
        dopInitCreateMicros.clear()
        dopRecipientCompleteMicros.clear()
        directHandshakeMicros.clear()
        offlineDeliveryMillis.clear()
        preKeyRecordBytes.clear()
        preKeySyncBytes.clear()
        dopInitPacketBytes.clear()
        directHandshakeBytes.clear()
        relayHops.clear()
        cacheHitsCounter.set(0)
        cacheMissesCounter.set(0)
        cacheInsertionsCounter.set(0)
        cacheEvictionsCounter.set(0)
        duplicateRecordsCounter.set(0)
        staleRecordsCounter.set(0)
        conflictingRecordsCounter.set(0)
        invalidSignaturesCounter.set(0)
        expiredRecordsCounter.set(0)
        replayCoalescedCounter.set(0)
        replayRejectedCounter.set(0)
        concurrentMultiSenderCounter.set(0)
        opkGraceUsesCounter.set(0)
    }

    companion object {
        private const val BLE_FRAME_PAYLOAD_BYTES = 180 - 9

        fun estimateBleFrameCount(wirePayloadBytes: Int): Int {
            if (wirePayloadBytes <= 0) return 0
            return (wirePayloadBytes + BLE_FRAME_PAYLOAD_BYTES - 1) / BLE_FRAME_PAYLOAD_BYTES
        }

        private fun computeLatencyStats(samples: List<Long>): LatencyStats {
            if (samples.isEmpty()) {
                return LatencyStats(0, 0, 0, 0.0, 0, 0)
            }
            val sorted = samples.sorted()
            val n = sorted.size
            val min = sorted.first()
            val max = sorted.last()
            val mean = sorted.average()
            val median = sorted[n / 2]
            val p95Index = ((n - 1) * 0.95).toInt().coerceIn(0, n - 1)
            val p95 = sorted[p95Index]
            return LatencyStats(
                sampleCount = n,
                minMicros = min,
                maxMicros = max,
                meanMicros = mean,
                medianMicros = median,
                p95Micros = p95
            )
        }

        private fun computeSizeStats(samples: List<Int>): SizeAndFrameStats {
            if (samples.isEmpty()) {
                return SizeAndFrameStats(0, 0.0, 0, 0.0, 0)
            }
            val frames = samples.map { estimateBleFrameCount(it) }
            return SizeAndFrameStats(
                sampleCount = samples.size,
                meanBytes = samples.average(),
                maxBytes = samples.maxOrNull() ?: 0,
                meanBleFrames = frames.average(),
                maxBleFrames = frames.maxOrNull() ?: 0
            )
        }
    }
}
