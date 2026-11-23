package com.example.security

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Zero-dependency security helper for SHADORA App Lock:
 * - Salted SHA-256 PIN hashing & constant-time comparison (PIN is never stored in plaintext)
 * - Framework BiometricPrompt integration on Android 9+ (API 28+) with automatic fallback to 3D PIN pad
 */
object AppLockManager {

    private val secureRandom = SecureRandom()

    fun isValidPin(pin: String): Boolean = pin.length in 4..8 && pin.all { it.isDigit() }

    fun hashPin(pin: String): String {
        require(isValidPin(pin)) { "PIN must be 4 to 8 numeric digits" }
        val saltBytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        val saltHex = saltBytes.toHex()
        val digestHex = sha256Hex("$saltHex:$pin:shadora_lock_v1")
        return "$saltHex:$digestHex"
    }

    fun verifyPin(pin: String, storedHash: String): Boolean {
        if (pin.isEmpty() || storedHash.isBlank()) return false
        val parts = storedHash.split(':')
        if (parts.size != 2) return false
        val saltHex = parts[0]
        val expectedHex = parts[1]
        val actualHex = sha256Hex("$saltHex:$pin:shadora_lock_v1")
        return MessageDigest.isEqual(
            expectedHex.toByteArray(Charsets.UTF_8),
            actualHex.toByteArray(Charsets.UTF_8)
        )
    }

    fun canUseBiometric(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bm = context.getSystemService(BiometricManager::class.java) ?: return false
            val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            } else {
                @Suppress("DEPRECATION")
                bm.canAuthenticate()
            }
            res == BiometricManager.BIOMETRIC_SUCCESS
        } else {
            context.packageManager.hasSystemFeature("android.hardware.fingerprint")
        }
    }

    fun authenticateBiometric(
        context: Context,
        title: String,
        subtitle: String,
        negativeLabel: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onError("Biometric authentication requires Android 9+")
            return
        }
        try {
            val executor = ContextCompat.getMainExecutor(context)
            val cancellationSignal = CancellationSignal()
            val prompt = BiometricPrompt.Builder(context)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButton(negativeLabel, executor) { _, _ ->
                    // User chose PIN fallback
                }
                .build()

            prompt.authenticate(
                cancellationSignal,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        ) {
                            onError(errString?.toString().orEmpty())
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            SafeLogger.w("AppLockManager", "BiometricPrompt failed: ${e.message}")
            onError(e.message.orEmpty())
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
