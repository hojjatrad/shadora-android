package com.example

import com.example.configmanager.ConfigParser
import com.example.configmanager.ParseOutcome
import com.example.configmanager.XrayJsonCompiler
import com.example.core.model.VpnProtocol
import com.example.data.local.AppSettingsEntity
import com.example.data.local.EngineSettingsEntity
import com.example.security.NetworkSecurityGuard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ConfigParserAndCompilerTest {

    private val parser = ConfigParser()

    @Test
    fun `import VLESS Reality parses all parameters and compiles valid Xray JSON`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@de1.example.net:443" +
            "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.cloudflare.com" +
            "&fp=chrome&pbk=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdefg&sid=1a2b&type=tcp#%F0%9F%87%A9%F0%9F%87%AA%20Frankfurt%20Vision"

        val results = parser.parseBatch(uri)
        assertEquals(1, results.size)
        val success = results.first() as ParseOutcome.Success
        assertEquals(VpnProtocol.VLESS, success.config.protocol)
        assertEquals("de1.example.net", success.config.host)
        assertEquals(443, success.config.port)
        assertEquals("reality", success.config.security)
        assertEquals("DE", success.config.countryCode)

        val compiled = XrayJsonCompiler.compile(
            parsed = success.config,
            appSettings = AppSettingsEntity(primaryDns = "1.1.1.1", secondaryDns = "8.8.8.8", ipv6Enabled = false),
            engineSettings = EngineSettingsEntity(engineId = "xray", muxEnabled = false)
        )
        val root = JSONObject(compiled)
        assertEquals("UseIPv4", root.getJSONObject("dns").getString("queryStrategy"))
        val proxyOutbound = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", proxyOutbound.getString("protocol"))
        assertEquals(
            "reality",
            proxyOutbound.getJSONObject("streamSettings").getString("security")
        )
    }

    @Test
    fun `import VMess Base64 JSON succeeds and extracts host port uuid`() {
        val vmessJson = """
            {"v":"2","ps":"🇳🇱 Amsterdam WS","add":"nl.example.org","port":"8443","id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.org","path":"/ws","tls":"tls","sni":"cdn.example.org"}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val results = parser.parseBatch("vmess://$encoded")

        assertEquals(1, results.size)
        val success = results.first() as ParseOutcome.Success
        assertEquals(VpnProtocol.VMESS, success.config.protocol)
        assertEquals("nl.example.org", success.config.host)
        assertEquals(8443, success.config.port)
        assertEquals("NL", success.config.countryCode)
        assertEquals("ws", success.config.transport)
    }

    @Test
    fun `import Trojan parses password sni and tls`() {
        val uri = "trojan://SuperSecretPass99@tr.example.com:443?security=tls&sni=tr.example.com&type=grpc&serviceName=shadora#Istanbul%20TR"
        val results = parser.parseBatch(uri)
        val success = results.first() as ParseOutcome.Success
        assertEquals(VpnProtocol.TROJAN, success.config.protocol)
        assertEquals("SuperSecretPass99", success.config.credentialsMap["password"])
        assertEquals("TR", success.config.countryCode)
        assertEquals("grpc", success.config.transport)
    }

    @Test
    fun `import Shadowsocks SIP002 and legacy Base64 formats`() {
        val userInfo = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:MyPass123!".toByteArray())
        val sip002 = "ss://$userInfo@sg.example.com:8388#Singapore%20Edge"
        val legacyPayload = Base64.getEncoder().encodeToString("chacha20-ietf-poly1305:LegacyPass@jp.example.com:9443".toByteArray())
        val legacy = "ss://$legacyPayload#Tokyo%20Node"

        val results = parser.parseBatch("$sip002\n$legacy")
        assertEquals(2, results.size)
        val first = results[0] as ParseOutcome.Success
        val second = results[1] as ParseOutcome.Success
        assertEquals(VpnProtocol.SHADOWSOCKS, first.config.protocol)
        assertEquals("SG", first.config.countryCode)
        assertEquals("aes-256-gcm", first.config.credentialsMap["method"])
        assertEquals(VpnProtocol.SHADOWSOCKS, second.config.protocol)
        assertEquals("JP", second.config.countryCode)
        assertEquals(9443, second.config.port)
    }

    @Test
    fun `import OpenVPN accepts safe ovpn and blocks malicious script directives`() {
        val safeOvpn = """
            client
            dev tun
            proto udp
            remote vpn.example.com 1194
            cipher AES-256-GCM
            <ca>
            -----BEGIN CERTIFICATE-----
            TESTCERT
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()
        val safeResult = parser.parseBatch(safeOvpn).first() as ParseOutcome.Success
        assertEquals(VpnProtocol.OPENVPN, safeResult.config.protocol)
        assertEquals("vpn.example.com", safeResult.config.host)
        assertEquals(1194, safeResult.config.port)

        val maliciousOvpn = """
            client
            dev tun
            remote evil.example.com 1194
            script-security 2
            up /system/bin/sh
        """.trimIndent()
        val blocked = parser.parseBatch(maliciousOvpn).first()
        assertTrue(blocked is ParseOutcome.Failure)
    }

    @Test
    fun `import WireGuard and AmneziaWG correctly distinguishes obfuscation params`() {
        val keyA = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val keyB = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 10).toByte() })

        val wgIni = """
            [Interface]
            PrivateKey = $keyA
            Address = 10.66.66.2/32
            DNS = 1.1.1.1

            [Peer]
            PublicKey = $keyB
            Endpoint = wg.example.com:51820
            AllowedIPs = 0.0.0.0/0, ::/0
        """.trimIndent()
        val wgParsed = parser.parseBatch(wgIni).first() as ParseOutcome.Success
        assertEquals(VpnProtocol.WIREGUARD, wgParsed.config.protocol)

        val awgIni = """
            [Interface]
            PrivateKey = $keyA
            Address = 10.77.0.2/32
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 15
            S2 = 20
            H1 = 12345
            H2 = 23456
            H3 = 34567
            H4 = 45678

            [Peer]
            PublicKey = $keyB
            Endpoint = awg.example.com:51821
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        val awgParsed = parser.parseBatch(awgIni).first() as ParseOutcome.Success
        assertEquals(VpnProtocol.AMNEZIA_WG, awgParsed.config.protocol)
        assertEquals("4", awgParsed.config.parameters["Jc"])
        assertNotEquals(wgParsed.config.fingerprint, awgParsed.config.fingerprint)
    }

    @Test
    fun `subscription Base64 payload decodes multiple protocols cleanly`() {
        val rawBundle = listOf(
            "vless://11111111-2222-3333-4444-555555555555@us1.example.com:443?security=tls&type=tcp#US%20One",
            "trojan://pass12345@uk1.example.com:443?security=tls#London%20UK"
        ).joinToString("\n")
        val b64 = Base64.getEncoder().encodeToString(rawBundle.toByteArray())

        val results = parser.parseBatch(b64)
        assertEquals(2, results.size)
        assertTrue(results.all { it is ParseOutcome.Success })
    }

    @Test
    fun `invalid configs and unsupported protocols fail with clear errors`() {
        val invalidVless = parser.parseBatch("vless://bad@host:99999#BadPort").first()
        assertTrue(invalidVless is ParseOutcome.Failure)

        val missingRealityKey = parser.parseBatch(
            "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=reality#NoPbk"
        ).first()
        assertTrue(missingRealityKey is ParseOutcome.Failure)

        val tuic = parser.parseBatch("tuic://uuid:pass@tuic.example.com:443#TUIC").first() as ParseOutcome.Failure
        assertEquals(VpnProtocol.TUIC, tuic.detectedProtocol)
    }

    @Test
    fun `IPv6 toggle changes compiled Xray DNS queryStrategy`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@de.example.net:443?security=tls&type=tcp#DE"
        val parsed = (parser.parseBatch(uri).first() as ParseOutcome.Success).config

        val ipv6On = JSONObject(
            XrayJsonCompiler.compile(parsed, AppSettingsEntity(ipv6Enabled = true), EngineSettingsEntity("xray"))
        )
        val ipv6Off = JSONObject(
            XrayJsonCompiler.compile(parsed, AppSettingsEntity(ipv6Enabled = false), EngineSettingsEntity("xray"))
        )
        assertEquals("UseIP", ipv6On.getJSONObject("dns").getString("queryStrategy"))
        assertEquals("UseIPv4", ipv6Off.getJSONObject("dns").getString("queryStrategy"))
    }

    @Test
    fun `https subscription URL with custom port is auto-detected and passes security validation`() {
        val subUrl = "https://sub.speedur.org:2096/sub/djMsMTM0NDM5MCwxNzkyNTc0MDAy.FvCwcUODIhLW8R-WUFsd9hbjhXrcsvaMCuu4E64zyDA"
        assertTrue(parser.isSubscriptionUrl(subUrl))
        assertTrue(parser.isSubscriptionUrl("  \uFEFF$subUrl  "))
        assertFalse(parser.isSubscriptionUrl("vless://11111111-2222-3333-4444-555555555555@example.com:443#DE"))

        val guard = NetworkSecurityGuard()
        val validation = guard.validateSubscriptionUrl(subUrl)
        assertTrue(validation is NetworkSecurityGuard.ValidationResult.Allowed)
        assertEquals("sub.speedur.org", (validation as NetworkSecurityGuard.ValidationResult.Allowed).host)
    }

    @Test
    fun `tolerant URI parser handles unencoded spaces emojis brackets pipes and Persian remarks`() {
        val vless = "vless://11111111-2222-3333-4444-555555555555@de.speedur.org:443?security=tls&type=ws&path=/ws#🇩🇪 آلمان | VIP [1]"
        val trojan = "trojan://MyPass_2025!@nl.speedur.org:2096?security=tls&sni=nl.speedur.org#🇳🇱 هلند | پرسرعت [2]"

        val results = parser.parseBatch("$vless\n$trojan")
        assertEquals(2, results.size)

        val vlessOk = results[0] as ParseOutcome.Success
        assertEquals(VpnProtocol.VLESS, vlessOk.config.protocol)
        assertEquals("de.speedur.org", vlessOk.config.host)
        assertEquals(443, vlessOk.config.port)
        assertEquals("DE", vlessOk.config.countryCode)
        assertTrue(vlessOk.config.displayName.contains("آلمان"))

        val trojanOk = results[1] as ParseOutcome.Success
        assertEquals(VpnProtocol.TROJAN, trojanOk.config.protocol)
        assertEquals("nl.speedur.org", trojanOk.config.host)
        assertEquals(2096, trojanOk.config.port)
        assertEquals("NL", trojanOk.config.countryCode)
        assertTrue(trojanOk.config.displayName.contains("هلند"))
    }
}
