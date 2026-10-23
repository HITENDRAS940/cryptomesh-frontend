package com.cryptomesh.frontend.protocol.dop

import com.cryptomesh.frontend.crypto.SoftwareKeyWrappingService
import com.cryptomesh.frontend.data.local.InMemoryDopDao
import com.cryptomesh.frontend.data.repository.RoomDopPreKeyRepository
import com.cryptomesh.frontend.relay.DopCacheIngestOutcome
import com.cryptomesh.frontend.relay.DopRelayCacheManager
import com.cryptomesh.frontend.ui.state.LocalIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DopRelayCacheManagerTest {
    private var currentTimeMillis = 1_710_000_000_000L
    private lateinit var relaySigner: TestEcSignatureService
    private lateinit var validator: DopPreKeyValidator
    private lateinit var metrics: DopMetricsCollector
    private lateinit var relayRepo: RoomDopPreKeyRepository
    private lateinit var relayCache: DopRelayCacheManager
    private lateinit var relayIdentity: LocalIdentity

    @Before
    fun setUp() {
        currentTimeMillis = 1_710_000_000_000L
        relaySigner = TestEcSignatureService()
        validator = DopPreKeyValidator(relaySigner, now = { currentTimeMillis })
        metrics = DopMetricsCollector()
        relayRepo = RoomDopPreKeyRepository(
            dao = InMemoryDopDao(),
            signatureService = relaySigner,
            keyWrappingService = SoftwareKeyWrappingService(),
            metrics = metrics,
            now = { currentTimeMillis }
        )
        relayCache = DopRelayCacheManager(
            repository = relayRepo,
            validator = validator,
            metrics = metrics,
            maxCachedPeerRecords = 3,
            maxRecordsPerBleSync = 4,
            maxIngestPerSyncMessage = 8,
            now = { currentTimeMillis }
        )
        relayIdentity = createIdentity("RelayNode", relaySigner)
    }

    @Test
    fun cacheInsertionVerifiesSignatureAndRejectsTamperedRecordBeforeCaching() = runTest {
        val peerSigner = TestEcSignatureService()
        val peerIdentity = createIdentity("Bob", peerSigner)
        val peerRepo = createPeerRepo(peerSigner)
        val validRecord = peerRepo.getOrCreateOwnRecord(peerIdentity)

        val tamperedRecord = validRecord.copy(recipientDisplayName = "ForgedBob")
        val rejectedOutcome = relayCache.ingestRecord(
            record = tamperedRecord,
            localDeviceId = relayIdentity.deviceId,
            sourcePeerDeviceId = peerIdentity.deviceId
        )
        assertTrue(rejectedOutcome is DopCacheIngestOutcome.RejectedInvalid)
        assertNull(relayCache.getVerifiedRecordForRecipient(peerIdentity.deviceId, relayIdentity.deviceId))
        assertEquals(1L, metrics.snapshot().invalidSignaturesRejected)

        val acceptedOutcome = relayCache.ingestRecord(
            record = validRecord,
            localDeviceId = relayIdentity.deviceId,
            sourcePeerDeviceId = peerIdentity.deviceId
        )
        assertTrue(acceptedOutcome is DopCacheIngestOutcome.AcceptedNew)
        assertNotNull(relayCache.getVerifiedRecordForRecipient(peerIdentity.deviceId, relayIdentity.deviceId))
        assertEquals(1L, metrics.snapshot().cacheInsertions)
    }

    @Test
    fun duplicateSuppressionAndEpochUpgradeWorkCorrectly() = runTest {
        val peerSigner = TestEcSignatureService()
        val peerIdentity = createIdentity("Charlie", peerSigner)
        val peerRepo = createPeerRepo(peerSigner)
        val epoch1 = peerRepo.getOrCreateOwnRecord(peerIdentity)

        val first = relayCache.ingestRecord(
            record = epoch1,
            localDeviceId = relayIdentity.deviceId,
            sourcePeerDeviceId = peerIdentity.deviceId
        )
        assertTrue(first is DopCacheIngestOutcome.AcceptedNew)

        val duplicate = relayCache.ingestRecord(
            record = epoch1,
            localDeviceId = relayIdentity.deviceId,
            sourcePeerDeviceId = peerIdentity.deviceId
        )
        assertTrue(duplicate is DopCacheIngestOutcome.IgnoredDuplicate)
        assertEquals(1L, metrics.snapshot().duplicateRecordsIgnored)

        currentTimeMillis += 10_000L
        val epoch2 = peerRepo.rotateOwnEpoch(peerIdentity)
        val upgrade = relayCache.ingestRecord(
            record = epoch2,
            localDeviceId = relayIdentity.deviceId,
            sourcePeerDeviceId = peerIdentity.deviceId
        )
        assertTrue(upgrade is DopCacheIngestOutcome.ReplacedNewerEpoch)
        assertEquals(
            epoch2.epochId,
            relayCache.getVerifiedRecordForRecipient(peerIdentity.deviceId, relayIdentity.deviceId)?.record?.epochId
        )

        val stale = relayCache.ingestRecord(
            record = epoch1,
            localDeviceId = relayIdentity.deviceId,
            sourcePeerDeviceId = "malicious-relay"
        )
        assertTrue(stale is DopCacheIngestOutcome.IgnoredStaleEpoch)
        assertEquals(1L, metrics.snapshot().staleRecordsIgnored)
    }

    @Test
    fun capacityEvictionEvictsLeastRecentlyAccessedNonOwnRecordAndProtectsOwnRecord() = runTest {
        // Relay creates its own pre-key record (isOwnRecord = true)
        val ownRecord = relayRepo.getOrCreateOwnRecord(relayIdentity)
        assertNotNull(ownRecord)

        // maxCachedPeerRecords is 3 (for non-own records)
        val peers = (1..4).map { idx ->
            val signer = TestEcSignatureService()
            val id = createIdentity("Peer$idx", signer)
            val repo = createPeerRepo(signer)
            id to repo.getOrCreateOwnRecord(id)
        }

        // Insert Peer1, Peer2, Peer3 at increasing timestamps
        for (i in 0..2) {
            currentTimeMillis += 1_000L
            val outcome = relayCache.ingestRecord(
                record = peers[i].second,
                localDeviceId = relayIdentity.deviceId,
                sourcePeerDeviceId = peers[i].first.deviceId
            )
            assertTrue(outcome is DopCacheIngestOutcome.AcceptedNew)
        }

        // Touch Peer1 so Peer2 becomes the oldest accessed non-own record
        currentTimeMillis += 1_000L
        assertNotNull(relayCache.getVerifiedRecordForRecipient(peers[0].first.deviceId, relayIdentity.deviceId))

        // Insert Peer4 -> capacity is 3 non-own records, so Peer2 (LRU non-own) must be evicted, while OwnRecord and Peer1 remain
        currentTimeMillis += 1_000L
        val fourthOutcome = relayCache.ingestRecord(
            record = peers[3].second,
            localDeviceId = relayIdentity.deviceId,
            sourcePeerDeviceId = peers[3].first.deviceId
        )
        assertTrue(fourthOutcome is DopCacheIngestOutcome.AcceptedNew)

        assertNotNull(relayCache.getVerifiedRecordForRecipient(relayIdentity.deviceId, null))
        assertNotNull(relayCache.getVerifiedRecordForRecipient(peers[0].first.deviceId, relayIdentity.deviceId))
        assertNull(relayCache.getVerifiedRecordForRecipient(peers[1].first.deviceId, relayIdentity.deviceId))
        assertNotNull(relayCache.getVerifiedRecordForRecipient(peers[2].first.deviceId, relayIdentity.deviceId))
        assertNotNull(relayCache.getVerifiedRecordForRecipient(peers[3].first.deviceId, relayIdentity.deviceId))
        assertTrue(metrics.snapshot().cacheEvictions >= 1L)
    }

    @Test
    fun syncExchangePropagatesOwnAndRelayedRecordsAndRespectsFloodLimits() = runTest {
        val signerA = TestEcSignatureService()
        val signerB = TestEcSignatureService()
        val signerC = TestEcSignatureService()
        val nodeA = createIdentity("NodeA", signerA)
        val nodeB = createIdentity("NodeB", signerB)
        val nodeC = createIdentity("NodeC", signerC)

        val repoA = createPeerRepo(signerA)
        val repoB = createPeerRepo(signerB)
        val repoC = createPeerRepo(signerC)
        val cacheA = DopRelayCacheManager(repoA, validator, metrics, now = { currentTimeMillis })
        val cacheB = DopRelayCacheManager(repoB, validator, metrics, now = { currentTimeMillis })

        repoA.getOrCreateOwnRecord(nodeA)
        repoB.getOrCreateOwnRecord(nodeB)
        val recordC = repoC.getOrCreateOwnRecord(nodeC)

        // Node A has cached offline Node C's record
        cacheA.ingestRecord(recordC, localDeviceId = nodeA.deviceId, sourcePeerDeviceId = nodeC.deviceId)

        // Node A builds a sync message for Node B
        val syncFromA = cacheA.buildSyncMessageForPeer(
            localIdentity = nodeA,
            targetPeerDeviceId = nodeB.deviceId,
            linkId = "link-ab"
        )
        assertNotNull(syncFromA.ownRecord)
        assertTrue(syncFromA.relayedRecords.any { it.recipientDeviceId == nodeC.deviceId })

        // Node B ingests Node A's sync message, caching both Node A and offline Node C
        val resultAtB = cacheB.processIncomingSyncMessage(
            localIdentity = nodeB,
            sourcePeerDeviceId = nodeA.deviceId,
            linkId = "link-ba",
            syncMessage = syncFromA
        )
        assertEquals(2, resultAtB.newlyAcceptedRecords.size)
        assertNotNull(cacheB.getVerifiedRecordForRecipient(nodeA.deviceId, nodeB.deviceId))
        assertNotNull(cacheB.getVerifiedRecordForRecipient(nodeC.deviceId, nodeB.deviceId))
    }

    private fun createPeerRepo(signer: TestEcSignatureService): RoomDopPreKeyRepository {
        return RoomDopPreKeyRepository(
            dao = InMemoryDopDao(),
            signatureService = signer,
            keyWrappingService = SoftwareKeyWrappingService(),
            metrics = metrics,
            now = { currentTimeMillis }
        )
    }
}
