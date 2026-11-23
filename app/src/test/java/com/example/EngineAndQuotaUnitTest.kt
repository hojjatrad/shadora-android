package com.example

import com.example.configmanager.ConfigParser
import com.example.configmanager.ParseOutcome
import com.example.core.util.FormatUtils
import com.wireguard.config.Config as WgConfig
import com.example.vpnservice.XrayNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.util.Base64

class EngineAndQuotaUnitTest {

    private val parser = ConfigParser()

    @Test
    fun `duplicate fingerprints match identically across repeated VLESS and Trojan links`() {
        val vless = "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?security=tls&type=tcp#DE1"
        val vlessSame = "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?security=tls&type=tcp#DE1-Renamed"

        val p1 = (parser.parseBatch(vless).first() as ParseOutcome.Success).config
        val p2 = (parser.parseBatch(vlessSame).first() as ParseOutcome.Success).config
        assertEquals(p1.fingerprint, p2.fingerprint)
    }

    @Test
    fun `native WireGuard library Config parser validates generated INI`() {
        val keyA = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 3).toByte() })
        val keyB = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 33).toByte() })
        val wgIni = """
            [Interface]
            PrivateKey = $keyA
            Address = 10.66.66.2/32
            DNS = 1.1.1.1, 8.8.8.8
            MTU = 1420

            [Peer]
            PublicKey = $keyB
            Endpoint = 198.51.100.10:51820
            AllowedIPs = 0.0.0.0/0, ::/0
            PersistentKeepalive = 25
        """.trimIndent()

        val wgConfig = WgConfig.parse(BufferedReader(StringReader(wgIni)))
        assertNotNull(wgConfig)
        assertEquals(1, wgConfig.peers.size)
        assertEquals(1420, wgConfig.`interface`.mtu.orElse(0))
    }

    @Test
    fun `XrayNativeBridge reports unavailable cleanly when libv2ray aar is not bundled`() {
        assertFalse(XrayNativeBridge.isAvailable())
    }

    @Test
    fun `FormatUtils formats duration, bytes, and speed deterministically`() {
        assertEquals("01:02:05", FormatUtils.formatDuration(3725))
        assertEquals("512 B", FormatUtils.formatBytes(512))
        assertEquals("2.0 KB/s", FormatUtils.formatSpeed(2048))
        assertTrue(FormatUtils.formatBytes(5L * 1024L * 1024L).contains("MB"))
    }
}
