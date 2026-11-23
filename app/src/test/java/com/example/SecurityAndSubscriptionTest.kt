package com.example

import com.example.security.KeystoreCryptoManager
import com.example.security.NetworkSecurityGuard
import com.example.security.SafeLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SecurityAndSubscriptionTest {

    private val guard = NetworkSecurityGuard(
        dnsResolver = { host ->
            when (host) {
                "public.shadora.test" -> listOf(InetAddress.getByName("93.184.216.34"))
                "private.shadora.test" -> listOf(InetAddress.getByName("192.168.1.10"))
                "metadata.shadora.test" -> listOf(InetAddress.getByName("169.254.169.254"))
                "loopback.shadora.test" -> listOf(InetAddress.getByName("127.0.0.1"))
                "cgnat.shadora.test" -> listOf(InetAddress.getByName("100.64.0.5"))
                else -> emptyList()
            }
        }
    )

    @Test
    fun `SSRF guard rejects non-HTTPS, localhost, embedded creds, and private IPs`() {
        assertTrue(guard.validateSubscriptionUrl("http://public.shadora.test/sub") is NetworkSecurityGuard.ValidationResult.Rejected)
        assertTrue(guard.validateSubscriptionUrl("https://localhost/sub") is NetworkSecurityGuard.ValidationResult.Rejected)
        assertTrue(guard.validateSubscriptionUrl("https://user:pass@public.shadora.test/sub") is NetworkSecurityGuard.ValidationResult.Rejected)
        assertTrue(guard.validateSubscriptionUrl("file:///etc/passwd") is NetworkSecurityGuard.ValidationResult.Rejected)

        val validUrl = guard.validateSubscriptionUrl("https://public.shadora.test/sub")
        assertTrue(validUrl is NetworkSecurityGuard.ValidationResult.Allowed)

        assertTrue(guard.validateHostResolution("public.shadora.test") is NetworkSecurityGuard.ValidationResult.Allowed)
        assertTrue(guard.validateHostResolution("private.shadora.test") is NetworkSecurityGuard.ValidationResult.Rejected)
        assertTrue(guard.validateHostResolution("metadata.shadora.test") is NetworkSecurityGuard.ValidationResult.Rejected)
        assertTrue(guard.validateHostResolution("loopback.shadora.test") is NetworkSecurityGuard.ValidationResult.Rejected)
        assertTrue(guard.validateHostResolution("cgnat.shadora.test") is NetworkSecurityGuard.ValidationResult.Rejected)
    }

    @Test
    fun `SafeLogger strips UUIDs, config links, passwords, and PrivateKeys`() {
        val raw = "Failed connecting vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?pbk=secretkey PrivateKey=ABC123=="
        val sanitized = SafeLogger.sanitize(raw)
        assertFalse(sanitized.contains("11111111-2222-3333-4444-555555555555"))
        assertFalse(sanitized.contains("ABC123=="))
        assertFalse(sanitized.contains("vless://"))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun `KeystoreCryptoManager encrypts with randomized IV and decrypts losslessly`() {
        val crypto = KeystoreCryptoManager("test_alias_v1")
        val secret = "vless://11111111-2222-3333-4444-555555555555@example.com:443#Test"
        val enc1 = crypto.encrypt(secret)
        val enc2 = crypto.encrypt(secret)

        assertTrue(crypto.isEncrypted(enc1))
        assertNotEquals(secret, enc1)
        assertNotEquals(enc1, enc2) // Randomized GCM IV
        assertFalse(enc1.contains("11111111"))
        assertEquals(secret, crypto.decrypt(enc1))
        assertEquals(secret, crypto.decrypt(enc2))
    }
}
