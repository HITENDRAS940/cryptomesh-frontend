package com.cryptomesh.frontend.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

class GroupCryptoTest {

    @Test
    fun groupKeyGenerationProduces32BytesAndDecodesCorrectly() {
        val key = GroupCrypto.generateGroupKey()
        assertEquals(32, key.size)

        val encoded = GroupCrypto.encodeKey(key)
        val decoded = GroupCrypto.decodeKey(encoded)
        assertTrue(Arrays.equals(key, decoded))
    }

    @Test
    fun deriveBroadcastKeyIsDeterministicForSameChannel() {
        val key1 = GroupCrypto.deriveBroadcastKey("CHANNEL-GENERAL")
        val key2 = GroupCrypto.deriveBroadcastKey("channel-general")
        assertEquals(32, key1.size)
        assertTrue(Arrays.equals(key1, key2))
    }

    @Test
    fun deriveBroadcastKeyDiffersAcrossChannels() {
        val generalKey = GroupCrypto.deriveBroadcastKey("CHANNEL-GENERAL")
        val emergencyKey = GroupCrypto.deriveBroadcastKey("CHANNEL-EMERGENCY")
        assertFalse(Arrays.equals(generalKey, emergencyKey))
    }

    @Test
    fun groupCipherEncryptsAndDecryptsSuccessfully() {
        val key = GroupCrypto.generateGroupKey()
        val cipher = GroupCrypto.createGroupCipher(key)
        val aad = "group-aad-test".toByteArray(Charsets.UTF_8)

        val plaintext = "Hello CryptoMesh Group!".toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.encrypt(plaintext, aad)
        assertFalse(Arrays.equals(plaintext, ciphertext))

        val decrypted = cipher.decrypt(ciphertext, aad)
        assertEquals("Hello CryptoMesh Group!", String(decrypted, Charsets.UTF_8))
    }

    @Test(expected = Exception::class)
    fun groupCipherFailsOnCorruptedCiphertext() {
        val key = GroupCrypto.generateGroupKey()
        val cipher = GroupCrypto.createGroupCipher(key)
        val aad = "group-aad-test".toByteArray(Charsets.UTF_8)

        val plaintext = "Secret Group Mission".toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.encrypt(plaintext, aad)
        ciphertext[ciphertext.lastIndex] = ((ciphertext[ciphertext.lastIndex].toInt() xor 1).toByte())

        cipher.decrypt(ciphertext, aad)
    }

    @Test(expected = Exception::class)
    fun groupCipherFailsWithWrongKey() {
        val key1 = GroupCrypto.generateGroupKey()
        val key2 = GroupCrypto.generateGroupKey()
        val aad = "group-aad-test".toByteArray(Charsets.UTF_8)

        val cipher1 = GroupCrypto.createGroupCipher(key1)
        val cipher2 = GroupCrypto.createGroupCipher(key2)

        val plaintext = "Top secret instructions".toByteArray(Charsets.UTF_8)
        val ciphertext = cipher1.encrypt(plaintext, aad)

        cipher2.decrypt(ciphertext, aad)
    }
}
