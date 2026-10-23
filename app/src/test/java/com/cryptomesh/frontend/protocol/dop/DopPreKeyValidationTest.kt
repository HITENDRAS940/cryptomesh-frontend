package com.cryptomesh.frontend.protocol.dop

import com.cryptomesh.frontend.crypto.PacketSignatureService
import com.cryptomesh.frontend.crypto.SoftwareKeyWrappingService
import com.cryptomesh.frontend.data.local.InMemoryDopDao
import com.cryptomesh.frontend.data.repository.RoomDopPreKeyRepository
import com.cryptomesh.frontend.protocol.deviceIdForPublicKey
import com.cryptomesh.frontend.ui.state.LocalIdentity
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DopPreKeyValidationTest {
    private var currentTimeMillis = 1_710_000_000_000L
    private lateinit var bobSigner: TestEcSignatureService
    private lateinit var attackerSigner: TestEcSignatureService
    private lateinit var validator: DopPreKeyValidator
    private lateinit var bobRepository: RoomDopPreKeyRepository
    private lateinit var bobIdentity: LocalIdentity
    private lateinit var attackerIdentity: LocalIdentity

    @Before
    fun setUp() {
        currentTimeMillis = 1_710_000_000_000L
        bobSigner = TestEcSignatureService()
        attackerSigner = TestEcSignatureService()
        validator = DopPreKeyValidator(
            signatureService = bobSigner,
            now = { currentTimeMillis }
        )
        bobRepository = RoomDopPreKeyRepository(
            dao = InMemoryDopDao(),
            signatureService = bobSigner,
            keyWrappingService = SoftwareKeyWrappingService(),
            now = { currentTimeMillis }
        )
        bobIdentity = createIdentity("Bob", bobSigner)
        attackerIdentity = createIdentity("Attacker", attackerSigner)
    }

    @Test
    fun validPreKeyRecordPassesAllChecks() = runTest {
        val record = bobRepository.getOrCreateOwnRecord(bobIdentity)
        val result = validator.validate(
            record = record,
            localDeviceId = attackerIdentity.deviceId,
            evaluationTimeMillis = currentTimeMillis
        )

        assertTrue(result is DopValidationResult.Valid)
        val valid = result as DopValidationResult.Valid
        assertEquals(record, valid.record)
        assertEquals(64, valid.recordDigestHex.length)
        assertEquals(DopProtocolVersion.PROTOCOL_ID, record.protocolId)
        assertEquals(DopProtocolVersion.PROTOCOL_VERSION, record.protocolVersion)
        assertTrue(record.oneTimePreKeys.isNotEmpty())
    }

    @Test
    fun tamperedFieldRejectsRecordSignature() = runTest {
        val record = bobRepository.getOrCreateOwnRecord(bobIdentity)
        val tamperedDisplayName = record.copy(recipientDisplayName = "Mallory")
        val result = validator.validate(
            record = tamperedDisplayName,
            localDeviceId = attackerIdentity.deviceId,
            evaluationTimeMillis = currentTimeMillis
        )

        assertTrue(result is DopValidationResult.Invalid)
        assertEquals(
            DopValidationFailureReason.INVALID_SIGNATURE,
            (result as DopValidationResult.Invalid).reason
        )
    }

    @Test
    fun invalidSignatureBytesRejected() = runTest {
        val record = bobRepository.getOrCreateOwnRecord(bobIdentity)
        val flippedSig = record.signatureBase64.let {
            val bytes = Base64.getDecoder().decode(it)
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
            Base64.getEncoder().encodeToString(bytes)
        }
        val corrupted = record.copy(signatureBase64 = flippedSig)
        val result = validator.validate(
            record = corrupted,
            localDeviceId = attackerIdentity.deviceId,
            evaluationTimeMillis = currentTimeMillis
        )

        assertTrue(result is DopValidationResult.Invalid)
        assertEquals(
            DopValidationFailureReason.INVALID_SIGNATURE,
            (result as DopValidationResult.Invalid).reason
        )
    }

    @Test
    fun wrongSigningKeyOrDeviceIdMismatchRejected() = runTest {
        val record = bobRepository.getOrCreateOwnRecord(bobIdentity)
        // Attacker replaces signing public key with their own while keeping Bob's deviceId
        val forgedWithAttackerKey = record.copy(
            recipientSigningPublicKeyBase64 = Base64.getEncoder().encodeToString(attackerSigner.publicKeyBytes)
        )
        val result = validator.validate(
            record = forgedWithAttackerKey,
            localDeviceId = null,
            evaluationTimeMillis = currentTimeMillis
        )

        assertTrue(result is DopValidationResult.Invalid)
        assertEquals(
            DopValidationFailureReason.DEVICE_ID_KEY_MISMATCH,
            (result as DopValidationResult.Invalid).reason
        )

        // Attacker signs Bob's record with attacker's private key while keeping Bob's public key
        val attackerSignedBytes = attackerSigner.sign(
            DopPreKeyCodec.encodeCanonicalUnsigned(record.toUnsigned())
        )
        val forgedSignature = record.copy(
            signatureBase64 = Base64.getEncoder().encodeToString(attackerSignedBytes)
        )
        val result2 = validator.validate(
            record = forgedSignature,
            localDeviceId = null,
            evaluationTimeMillis = currentTimeMillis
        )
        assertTrue(result2 is DopValidationResult.Invalid)
        assertEquals(
            DopValidationFailureReason.INVALID_SIGNATURE,
            (result2 as DopValidationResult.Invalid).reason
        )
    }

    @Test
    fun expiredRecordRejectedAfterExpiryTime() = runTest {
        val record = bobRepository.getOrCreateOwnRecord(bobIdentity)
        val expiredNow = record.expiresAtEpochMillis + 1_000L
        val result = validator.validate(
            record = record,
            localDeviceId = null,
            evaluationTimeMillis = expiredNow
        )

        assertTrue(result is DopValidationResult.Invalid)
        assertEquals(
            DopValidationFailureReason.EXPIRED_RECORD,
            (result as DopValidationResult.Invalid).reason
        )
    }

    @Test
    fun unsupportedProtocolVersionOrIdRejected() = runTest {
        val record = bobRepository.getOrCreateOwnRecord(bobIdentity)
        val wrongVersion = record.copy(protocolVersion = 99)
        val result = validator.validate(record = wrongVersion, evaluationTimeMillis = currentTimeMillis)

        assertTrue(result is DopValidationResult.Invalid)
        assertEquals(
            DopValidationFailureReason.UNSUPPORTED_PROTOCOL_VERSION,
            (result as DopValidationResult.Invalid).reason
        )

        val wrongProtocolId = record.copy(protocolId = "UNKNOWN-PROTO")
        val result2 = validator.validate(record = wrongProtocolId, evaluationTimeMillis = currentTimeMillis)
        assertTrue(result2 is DopValidationResult.Invalid)
        assertEquals(
            DopValidationFailureReason.UNSUPPORTED_PROTOCOL_ID,
            (result2 as DopValidationResult.Invalid).reason
        )
    }

    @Test
    fun duplicateNewerStaleAndConflictingEpochResolutionBehavesDeterministically() = runTest {
        val epoch1 = bobRepository.getOrCreateOwnRecord(bobIdentity)

        // 1. First time seeing record -> ACCEPT_NEW
        assertEquals(
            DopRecordResolution.ACCEPT_NEW,
            validator.resolveAgainstExisting(incoming = epoch1, existing = null)
        )

        // 2. Duplicate identical record -> IGNORE_DUPLICATE_IDENTICAL
        assertEquals(
            DopRecordResolution.IGNORE_DUPLICATE_IDENTICAL,
            validator.resolveAgainstExisting(incoming = epoch1, existing = epoch1)
        )

        // 3. Newer epoch record replaces older -> ACCEPT_NEWER_EPOCH
        currentTimeMillis += 60_000L
        val epoch2 = bobRepository.rotateOwnEpoch(bobIdentity)
        assertTrue(epoch2.epochId > epoch1.epochId)

        assertEquals(
            DopRecordResolution.ACCEPT_NEWER_EPOCH,
            validator.resolveAgainstExisting(incoming = epoch2, existing = epoch1)
        )

        // 4. Stale epoch ignored when epoch2 is already cached -> IGNORE_STALE_OLDER_EPOCH
        assertEquals(
            DopRecordResolution.IGNORE_STALE_OLDER_EPOCH,
            validator.resolveAgainstExisting(incoming = epoch1, existing = epoch2)
        )

        // 5. Conflicting same-epoch record with different content -> REJECT_CONFLICTING_RECORD
        val conflictingSameEpoch = epoch2.copy(recipientDisplayName = "ConflictingBob")
        assertEquals(
            DopRecordResolution.REJECT_CONFLICTING_RECORD,
            validator.resolveAgainstExisting(incoming = conflictingSameEpoch, existing = epoch2)
        )

        // 6. Identity key conflict with same or older epoch -> REJECT_IDENTITY_KEY_CONFLICT
        val mismatchedIdentityRecord = epoch1.copy(
            recipientIdentityFingerprintHex = DopPreKeyCodec.identityFingerprintHex(attackerSigner.publicKeyBytes),
            recipientSigningPublicKeyBase64 = Base64.getEncoder().encodeToString(attackerSigner.publicKeyBytes)
        )
        assertEquals(
            DopRecordResolution.REJECT_IDENTITY_KEY_CONFLICT,
            validator.resolveAgainstExisting(incoming = mismatchedIdentityRecord, existing = epoch2)
        )
    }

    @Test
    fun clockSkewEdgeCasesAcceptWithinToleranceAndRejectFarFuture() = runTest {
        val record = bobRepository.getOrCreateOwnRecord(bobIdentity)
        // Within 10-minute clock skew window before validFromEpochMillis -> Valid
        val slightlyBehindClock = record.validFromEpochMillis - (DopProtocolVersion.MAX_CLOCK_SKEW_MILLIS - 1_000L)
        val withinTolerance = validator.validate(record = record, evaluationTimeMillis = slightlyBehindClock)
        assertTrue(withinTolerance is DopValidationResult.Valid)

        // Beyond 10-minute clock skew window before validFromEpochMillis -> Invalid (NOT_YET_VALID_CLOCK_SKEW)
        val farBehindClock = record.validFromEpochMillis - (DopProtocolVersion.MAX_CLOCK_SKEW_MILLIS + 5_000L)
        val outsideTolerance = validator.validate(record = record, evaluationTimeMillis = farBehindClock)
        assertTrue(outsideTolerance is DopValidationResult.Invalid)
        assertEquals(
            DopValidationFailureReason.NOT_YET_VALID_CLOCK_SKEW,
            (outsideTolerance as DopValidationResult.Invalid).reason
        )
    }

    @Test
    fun nonCurveOrMalformedPublicKeyRejected() = runTest {
        val record = bobRepository.getOrCreateOwnRecord(bobIdentity)
        val corruptedSpk = record.copy(
            signedPreKeyPublicKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 })
        )
        val result = validator.validate(record = corruptedSpk, evaluationTimeMillis = currentTimeMillis)
        assertTrue(result is DopValidationResult.Invalid)
        assertFalse(result.isValid)
        assertEquals(
            DopValidationFailureReason.MALFORMED_PUBLIC_KEY,
            (result as DopValidationResult.Invalid).reason
        )
    }
}

internal class TestEcSignatureService : PacketSignatureService {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    override val publicKeyBytes: ByteArray
        get() = keyPair.public.encoded.copyOf()

    override fun sign(data: ByteArray): ByteArray {
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(data)
            sign()
        }
    }

    override fun verify(
        data: ByteArray,
        signature: ByteArray,
        publicKeyBytes: ByteArray
    ): Boolean {
        return runCatching {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(publicKeyBytes)
            )
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
    }
}

internal fun createIdentity(
    displayName: String,
    signer: PacketSignatureService
): LocalIdentity {
    return LocalIdentity(
        displayName = displayName,
        deviceId = deviceIdForPublicKey(signer.publicKeyBytes),
        publicKeyPreview = DopPreKeyCodec.identityFingerprintHex(signer.publicKeyBytes).take(16)
    )
}
