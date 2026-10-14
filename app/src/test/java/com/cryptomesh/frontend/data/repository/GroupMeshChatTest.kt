package com.cryptomesh.frontend.data.repository

import com.cryptomesh.frontend.crypto.PacketSignatureService
import com.cryptomesh.frontend.protocol.deviceIdForPublicKey
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GroupMeshChatTest {

    @Test
    fun groupCreationDispatchesInviteAndAllowsEncryptedGroupChat() = runBlocking {
        val aliceSigner = TestSignatureService()
        val bobSigner = TestSignatureService()
        val aliceIdentity = identity("Alice", aliceSigner)
        val bobIdentity = identity("Bob", bobSigner)

        val aliceTransport = PairedNearbyTransport("alice")
        val bobTransport = PairedNearbyTransport("bob")
        aliceTransport.partner = bobTransport
        bobTransport.partner = aliceTransport

        val aliceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = IncrementingClock()

        val aliceGroupRepo = MemoryGroupRepository(clock::now)
        val bobGroupRepo = MemoryGroupRepository(clock::now)

        val aliceRepository = BleDirectMeshRepository(
            identityRepository = FixedIdentityRepository(aliceIdentity),
            signatureService = aliceSigner,
            packetRepository = MemoryPacketRepository(),
            mediaTransferRepository = MemoryMediaTransferRepository(),
            mediaFileStore = testMediaFileStore(),
            transport = aliceTransport,
            applicationScope = aliceScope,
            groupRepository = aliceGroupRepo,
            now = clock::now
        )
        val bobRepository = BleDirectMeshRepository(
            identityRepository = FixedIdentityRepository(bobIdentity),
            signatureService = bobSigner,
            packetRepository = MemoryPacketRepository(),
            mediaTransferRepository = MemoryMediaTransferRepository(),
            mediaFileStore = testMediaFileStore(),
            transport = bobTransport,
            applicationScope = bobScope,
            groupRepository = bobGroupRepo,
            now = clock::now
        )

        try {
            aliceRepository.start()
            bobRepository.start()
            aliceRepository.startScan()

            val discovered = awaitState(aliceRepository) {
                it.peers.any { peer -> peer.advertisedDeviceId == bobIdentity.deviceId }
            }
            aliceRepository.connect(discovered.peers.single().linkId)

            awaitState(aliceRepository) {
                it.peers.any { peer ->
                    peer.deviceId == bobIdentity.deviceId &&
                        peer.status == DirectPeerStatus.Connected &&
                        peer.isVerified
                }
            }
            awaitState(bobRepository) {
                it.peers.any { peer ->
                    peer.deviceId == aliceIdentity.deviceId &&
                        peer.status == DirectPeerStatus.Connected &&
                        peer.isVerified
                }
            }

            // 1. Alice creates a group including Bob
            val group = aliceRepository.createGroup(
                name = "Squad Alpha",
                memberDeviceIds = listOf(bobIdentity.deviceId)
            ).getOrThrow()

            // 2. Bob receives the invite via authenticated 1-to-1 ECDH session and stores the group
            awaitState(bobRepository) { state ->
                state.groups.any { it.groupId == group.groupId }
            }

            val bobStoredGroup = bobGroupRepo.getGroup(group.groupId)
            assertNotNull(bobStoredGroup)
            assertEquals("Squad Alpha", bobStoredGroup?.groupName)

            // 3. Alice sends a group message
            val packetId = aliceRepository.sendGroupText(
                groupId = group.groupId,
                text = "Welcome to Squad Alpha!"
            ).getOrThrow()

            // 4. Bob receives the message, decrypts with the shared group key, and verifies Alice's signature
            val bobState = awaitState(bobRepository) { state ->
                state.messages.any { msg ->
                    msg.groupId == group.groupId && !msg.isOutgoing
                }
            }

            val groupMsg = bobState.messages.first { it.groupId == group.groupId && !it.isOutgoing }
            assertEquals("Welcome to Squad Alpha!", groupMsg.text)
            assertEquals("Alice", groupMsg.senderDisplayName)
            assertEquals(group.groupId, groupMsg.groupId)
        } finally {
            aliceScope.cancel()
            bobScope.cancel()
        }
    }

    @Test
    fun broadcastChannelAllowsCommunicationWithoutInvite() = runBlocking {
        val aliceSigner = TestSignatureService()
        val bobSigner = TestSignatureService()
        val aliceIdentity = identity("Alice", aliceSigner)
        val bobIdentity = identity("Bob", bobSigner)

        val aliceTransport = PairedNearbyTransport("alice")
        val bobTransport = PairedNearbyTransport("bob")
        aliceTransport.partner = bobTransport
        bobTransport.partner = aliceTransport

        val aliceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = IncrementingClock()

        val aliceGroupRepo = MemoryGroupRepository(clock::now)
        val bobGroupRepo = MemoryGroupRepository(clock::now)

        aliceGroupRepo.initDefaultChannels(aliceIdentity.deviceId)
        bobGroupRepo.initDefaultChannels(bobIdentity.deviceId)

        val aliceRepository = BleDirectMeshRepository(
            identityRepository = FixedIdentityRepository(aliceIdentity),
            signatureService = aliceSigner,
            packetRepository = MemoryPacketRepository(),
            mediaTransferRepository = MemoryMediaTransferRepository(),
            mediaFileStore = testMediaFileStore(),
            transport = aliceTransport,
            applicationScope = aliceScope,
            groupRepository = aliceGroupRepo,
            now = clock::now
        )
        val bobRepository = BleDirectMeshRepository(
            identityRepository = FixedIdentityRepository(bobIdentity),
            signatureService = bobSigner,
            packetRepository = MemoryPacketRepository(),
            mediaTransferRepository = MemoryMediaTransferRepository(),
            mediaFileStore = testMediaFileStore(),
            transport = bobTransport,
            applicationScope = bobScope,
            groupRepository = bobGroupRepo,
            now = clock::now
        )

        try {
            aliceRepository.start()
            bobRepository.start()
            aliceRepository.startScan()

            val discovered = awaitState(aliceRepository) {
                it.peers.any { peer -> peer.advertisedDeviceId == bobIdentity.deviceId }
            }
            aliceRepository.connect(discovered.peers.single().linkId)

            awaitState(aliceRepository) {
                it.peers.any { peer ->
                    peer.deviceId == bobIdentity.deviceId &&
                        peer.status == DirectPeerStatus.Connected &&
                        peer.isVerified
                }
            }
            awaitState(bobRepository) {
                it.peers.any { peer ->
                    peer.deviceId == aliceIdentity.deviceId &&
                        peer.status == DirectPeerStatus.Connected &&
                        peer.isVerified
                }
            }

            // Alice sends a broadcast message to #general
            val packetId = aliceRepository.sendBroadcastText(
                channelId = RoomGroupRepository.CHANNEL_GENERAL_ID,
                text = "Mesh broadcast alert: all nodes operational"
            ).getOrThrow()

            // Bob receives the broadcast message, decrypts with derived channel key, and verifies signature
            val bobState = awaitState(bobRepository) { state ->
                state.messages.any { msg ->
                    msg.groupId == RoomGroupRepository.CHANNEL_GENERAL_ID && !msg.isOutgoing
                }
            }

            val broadcastMsg = bobState.messages.first {
                it.groupId == RoomGroupRepository.CHANNEL_GENERAL_ID && !it.isOutgoing
            }
            assertEquals("Mesh broadcast alert: all nodes operational", broadcastMsg.text)
            assertEquals("Alice", broadcastMsg.senderDisplayName)
            assertEquals(RoomGroupRepository.CHANNEL_GENERAL_ID, broadcastMsg.groupId)
        } finally {
            aliceScope.cancel()
            bobScope.cancel()
        }
    }

    private suspend fun awaitState(
        repository: DirectMeshRepository,
        predicate: (DirectMeshState) -> Boolean
    ): DirectMeshState {
        return withTimeout(5_000) {
            repository.state.first(predicate)
        }
    }

    private fun identity(
        name: String,
        signer: PacketSignatureService
    ): LocalIdentity {
        return LocalIdentity(
            displayName = name,
            deviceId = deviceIdForPublicKey(signer.publicKeyBytes),
            publicKeyPreview = "test"
        )
    }

    private fun testMediaFileStore(): MediaFileStore {
        return LocalMediaFileStore(
            Files.createTempDirectory("cryptomesh-group-media-test").toFile()
        )
    }
}
