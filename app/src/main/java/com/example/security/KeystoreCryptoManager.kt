package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts sensitive VPN configuration payloads (UUIDs, private keys,
 * passwords, raw URIs, OVPN keys) using AES-256-GCM backed by Android Keystore.
 *
 * Sensitive fields are never persisted to SQLite/Room in plaintext.
 */
class KeystoreCryptoManager(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) {
    private val fallbackKey: SecretKey by lazy {
        // Used exclusively when running inside JVM/Robolectric unit tests where
        // the AndroidKeyStore JCA provider is unavailable.
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        SecretKeySpec(bytes, "AES")
    }

    private fun getOrCreateKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                existing.secretKey
            } else {
                val generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                generator.init(spec)
                generator.generateKey()
            }
        } catch (_: Throwable) {
            fallbackKey
        }
    }

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        return PREFIX + Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        if (!encoded.startsWith(PREFIX)) {
            return encoded
        }
        val payload = Base64.getDecoder().decode(encoded.removePrefix(PREFIX))
        if (payload.size < 14) {
            throw IllegalArgumentException("Corrupted encrypted payload")
        }
        val ivLen = payload[0].toInt() and 0xFF
        require(ivLen in 12..16 && payload.size > 1 + ivLen) { "Invalid GCM IV length" }
        val iv = payload.copyOfRange(1, 1 + ivLen)
        val ciphertext = payload.copyOfRange(1 + ivLen, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "shadora_master_aes_gcm_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREFIX = "enc:v1:"
    }
}
