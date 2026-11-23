package com.example

import com.example.configmanager.ConfigParser
import com.example.configmanager.ParseOutcome
import com.example.configmanager.SingBoxJsonCompiler
import com.example.configmanager.XrayJsonCompiler
import com.example.data.backup.ShadoraBackupCodec
import com.example.data.local.AppSettingsEntity
import com.example.data.local.EngineSettingsEntity
import com.example.data.local.SubscriptionEntity
import com.example.panelapi.PanelSyncResult
import com.example.panelapi.StandaloneOfflinePanelClient
import com.example.security.AppLockManager
import com.example.security.SafeLogger
import com.example.subscriptionmanager.SubscriptionAutoRefreshScheduler
import com.example.vpnservice.CloakNativeBridge
import com.example.vpnservice.LightwayNativeBridge
import com.example.vpnservice.MieruNativeBridge
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadoraProFeaturesUnitTest {

    @Test
    fun `SafeLogger ring buffer records events and strictly redacts sensitive secrets`() {
        SafeLogger.clearLogs()
        SafeLogger.i(
            "TestTag",
            "Connecting vless://11111111-2222-3333-4444-555555555555@example.com:443?pbk=SECRETKEY"
        )
        SafeLogger.w("TestTag", "PrivateKey=SuperSecretWgKey1234567890=")

        val logs = SafeLogger.getRecentLogs()
        assertEquals(2, logs.size)
        val exported = SafeLogger.exportSanitizedText()
        assertFalse(exported.contains("11111111-2222-3333-4444-555555555555"))
        assertFalse(exported.contains("SuperSecretWgKey1234567890="))
        assertTrue(exported.contains("[REDACTED]"))
    }

    @Test
    fun `ShadoraBackupCodec round-trips settings subscriptions and manual configs cleanly without exporting PIN`() {
        val secretPinHash = AppLockManager.hashPin("246810")
        val settings = AppSettingsEntity(
            languageCode = "fa",
            themeMode = "OBSIDIAN_DARK",
            primaryDns = "1.1.1.1",
            secondaryDns = "1.0.0.1",
            ipv6Enabled = true,
            splitTunnelMode = "BYPASS_SELECTED",
            splitTunnelPackagesCsv = "com.whatsapp,org.telegram.messenger",
            autoConnect = true,
            killSwitchEnabled = true,
            notificationsEnabled = true,
            bypassIranDomains = true,
            bypassLanCidrs = true,
            customDirectDomainsCsv = "mybank.ir, intranet.local",
            customBlockDomainsCsv = "ads.example.com",
            autoFailoverEnabled = true,
            appLockEnabled = true,
            appLockPinHash = secretPinHash
        )
        val subs = listOf(
            SubscriptionEntity(
                id = 1L,
                name = "Speedur VIP",
                url = "https://sub.speedur.org:2096/sub/sample-token",
                refreshIntervalHours = 12
            )
        )
        val manual = listOf(
            "vless://11111111-2222-3333-4444-555555555555@de.example.net:443?security=tls#DE"
        )

        val json = ShadoraBackupCodec.encode(settings, subs, manual)
        assertFalse("Backup JSON must never contain PIN hash", json.contains(secretPinHash))
        assertFalse("Backup JSON must never contain appLockPinHash key", json.contains("appLockPinHash"))

        val decoded = ShadoraBackupCodec.decode(json).getOrThrow()

        assertEquals(1, decoded.version)
        assertEquals("1.1.1.1", decoded.settings?.primaryDns)
        assertEquals(true, decoded.settings?.autoConnect)
        assertEquals(true, decoded.settings?.bypassIranDomains)
        assertEquals(true, decoded.settings?.bypassLanCidrs)
        assertEquals("mybank.ir, intranet.local", decoded.settings?.customDirectDomainsCsv)
        assertEquals("ads.example.com", decoded.settings?.customBlockDomainsCsv)
        assertEquals(true, decoded.settings?.autoFailoverEnabled)
        assertEquals(1, decoded.subscriptions.size)
        assertEquals("Speedur VIP", decoded.subscriptions.first().name)
        assertEquals(12, decoded.subscriptions.first().refreshIntervalHours)
        assertEquals(1, decoded.manualConfigs.size)
        assertTrue(decoded.manualConfigs.first().startsWith("vless://"))

        assertTrue(ShadoraBackupCodec.decode("""{"format":"wrong"}""").isFailure)
    }

    @Test
    fun `SubscriptionAutoRefreshScheduler accurately detects due and non-due subscriptions`() {
        val now = 1_700_000_000_000L
        val hour = 3_600_000L

        val manualSub = SubscriptionEntity(
            id = 1, name = "Manual", url = "https://a.example/sub",
            refreshIntervalHours = 0, lastUpdatedAt = now - 100 * hour
        )
        val freshSub = SubscriptionEntity(
            id = 2, name = "Fresh", url = "https://b.example/sub",
            refreshIntervalHours = 24, lastUpdatedAt = now - 2 * hour
        )
        val dueSub = SubscriptionEntity(
            id = 3, name = "Due", url = "https://c.example/sub",
            refreshIntervalHours = 6, lastUpdatedAt = now - 7 * hour
        )
        val neverSyncedSub = SubscriptionEntity(
            id = 4, name = "Never", url = "https://d.example/sub",
            refreshIntervalHours = 12, lastUpdatedAt = 0L
        )

        assertFalse(SubscriptionAutoRefreshScheduler.isDueForRefresh(manualSub, now))
        assertNull(SubscriptionAutoRefreshScheduler.nextRefreshMillis(manualSub))
        assertFalse(SubscriptionAutoRefreshScheduler.isDueForRefresh(freshSub, now))
        assertNotNull(SubscriptionAutoRefreshScheduler.nextRefreshMillis(freshSub))
        assertTrue(SubscriptionAutoRefreshScheduler.isDueForRefresh(dueSub, now))
        assertTrue(SubscriptionAutoRefreshScheduler.isDueForRefresh(neverSyncedSub, now))

        val dueList = SubscriptionAutoRefreshScheduler.findDueSubscriptions(
            listOf(manualSub, freshSub, dueSub, neverSyncedSub),
            now
        )
        assertEquals(listOf(3L, 4L), dueList.map { it.id })
    }

    @Test
    fun `StandaloneOfflinePanelClient reports standalone v1 mode without backend dependency`() = runTest {
        val client = StandaloneOfflinePanelClient()
        assertFalse(client.isConfigured)
        assertNull(client.endpoint)
        assertEquals(PanelSyncResult.StandaloneMode, client.syncAccountSubscriptions())
    }

    @Test
    fun `XrayJsonCompiler compiles Smart Routing rules for Iran bypass LAN bypass custom direct and custom block`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443" +
            "?security=reality&sni=www.cloudflare.com&fp=chrome&pbk=PublicKeySample123456789012345678901234567890&sid=abcd1234&type=tcp#DE-Reality"
        val parsed = (ConfigParser().parseBatch(uri).first() as ParseOutcome.Success).config
        val settings = AppSettingsEntity(
            bypassIranDomains = true,
            bypassLanCidrs = true,
            customDirectDomainsCsv = "mybank.ir, full:intranet.local",
            customBlockDomainsCsv = "ads.example.com, regexp:.*tracker.*"
        )

        val compiledJson = XrayJsonCompiler.compile(parsed, settings, EngineSettingsEntity(engineId = "xray"))
        val root = JSONObject(compiledJson)
        val rules = root.getJSONObject("routing").getJSONArray("rules")

        // Block rule should be evaluated first
        val firstRule = rules.getJSONObject(0)
        assertEquals("block", firstRule.getString("outboundTag"))
        assertEquals("domain:ads.example.com", firstRule.getJSONArray("domain").getString(0))
        assertEquals("regexp:.*tracker.*", firstRule.getJSONArray("domain").getString(1))

        // Verify LAN CIDR direct rule, Iran direct rule, and Custom Direct rule are all present
        val ruleStrings = (0 until rules.length()).map { rules.getJSONObject(it).toString() }
        assertTrue(ruleStrings.any { it.contains("192.168.0.0/16") && it.contains("\"outboundTag\":\"direct\"") })
        assertTrue(ruleStrings.any { it.contains("shaparak.ir") && it.contains("\"outboundTag\":\"direct\"") })
        assertTrue(ruleStrings.any { it.contains("domain:mybank.ir") && it.contains("full:intranet.local") && it.contains("\"outboundTag\":\"direct\"") })

        // Verify native Xray traffic stats & outbound counters policy
        assertTrue(root.has("stats"))
        val systemPolicy = root.getJSONObject("policy").getJSONObject("system")
        assertTrue(systemPolicy.getBoolean("statsOutboundUplink"))
        assertTrue(systemPolicy.getBoolean("statsOutboundDownlink"))
    }

    @Test
    fun `XrayJsonCompiler parseDomainRules normalizes bare tokens and preserves explicit prefixes`() {
        val parsed = XrayJsonCompiler.parseDomainRules(
            "  Example.COM, domain:digikala.com ; full:api.local\nregexp:.*\\.ir$ bad..host  "
        )
        assertEquals(
            listOf("domain:example.com", "domain:digikala.com", "full:api.local", "regexp:.*\\.ir$"),
            parsed
        )
    }

    @Test
    fun `AppLockManager hashes PINs with unique random salts and verifies accurately`() {
        assertTrue(AppLockManager.isValidPin("1234"))
        assertTrue(AppLockManager.isValidPin("123456"))
        assertFalse(AppLockManager.isValidPin("123"))
        assertFalse(AppLockManager.isValidPin("12ab"))
        assertFalse(AppLockManager.isValidPin("123456789"))

        val hash1 = AppLockManager.hashPin("7391")
        val hash2 = AppLockManager.hashPin("7391")

        assertNotEquals("Salted hashes of the same PIN must be unique", hash1, hash2)
        assertFalse("Hash must never contain plaintext PIN", hash1.contains("7391"))
        assertTrue(AppLockManager.verifyPin("7391", hash1))
        assertTrue(AppLockManager.verifyPin("7391", hash2))
        assertFalse(AppLockManager.verifyPin("0000", hash1))
        assertFalse(AppLockManager.verifyPin("", hash1))
        assertFalse(AppLockManager.verifyPin("7391", "malformed_hash_without_colon"))
    }

    @Test
    fun `SingBoxJsonCompiler compiles VLESS Reality Vision with Smart Routing and anti-loop DNS`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443" +
            "?security=reality&flow=xtls-rprx-vision&sni=www.cloudflare.com&fp=chrome" +
            "&pbk=PublicKeySample123456789012345678901234567890&sid=abcd1234&type=tcp#DE-Vision"
        val parsed = (ConfigParser().parseBatch(uri).first() as ParseOutcome.Success).config
        val settings = AppSettingsEntity(
            primaryDns = "1.1.1.1",
            secondaryDns = "8.8.8.8",
            ipv6Enabled = false,
            bypassIranDomains = true,
            bypassLanCidrs = true,
            customDirectDomainsCsv = "mybank.ir, full:intranet.local",
            customBlockDomainsCsv = "ads.example.com"
        )

        val compiled = SingBoxJsonCompiler.compile(parsed, settings, EngineSettingsEntity(engineId = "xray", mtu = 1420))
        val root = JSONObject(compiled)

        // Verify TUN inbound
        val tun = root.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun", tun.getString("type"))
        assertEquals(1420, tun.getInt("mtu"))
        assertEquals("gvisor", tun.getString("stack"))
        assertTrue(tun.has("route_exclude_address"))

        // Verify proxy outbound (VLESS + Reality + Vision + uTLS)
        val proxy = root.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", proxy.getString("type"))
        assertEquals("xtls-rprx-vision", proxy.getString("flow"))
        val tls = proxy.getJSONObject("tls")
        assertTrue(tls.getBoolean("enabled"))
        assertEquals("www.cloudflare.com", tls.getString("server_name"))
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
        assertEquals("PublicKeySample123456789012345678901234567890", tls.getJSONObject("reality").getString("public_key"))

        // Verify anti-loop DNS + Smart Routing rules
        val route = root.getJSONObject("route")
        assertEquals("local-dns", route.getString("default_domain_resolver"))
        assertEquals("proxy", route.getString("final"))
        val rulesJson = route.getJSONArray("rules").toString()
        assertTrue(rulesJson.contains("\"action\":\"hijack-dns\""))
        assertTrue(rulesJson.contains("\"ip_is_private\":true"))
        assertTrue(rulesJson.contains("shaparak.ir"))
        assertTrue(rulesJson.contains("mybank.ir"))
        assertTrue(rulesJson.contains("ads.example.com"))
    }

    @Test
    fun `SingBoxJsonCompiler compiles TUIC v5, VMess WS, Trojan gRPC, and Shadowsocks cleanly`() {
        val parser = ConfigParser()
        val settings = AppSettingsEntity()
        val engine = EngineSettingsEntity(engineId = "xray")

        val tuicUri = "tuic://11111111-2222-3333-4444-555555555555:MyTuicPass@tuic.example.com:8443" +
            "?congestion_control=bbr&udp_relay_mode=native&alpn=h3&sni=tuic.example.com#TUIC"
        val tuicParsed = (parser.parseBatch(tuicUri).first() as ParseOutcome.Success).config
        val tuicOut = JSONObject(SingBoxJsonCompiler.compile(tuicParsed, settings, engine))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("tuic", tuicOut.getString("type"))
        assertEquals("bbr", tuicOut.getString("congestion_control"))
        assertEquals("h3", tuicOut.getJSONObject("tls").getJSONArray("alpn").getString(0))

        val trojanUri = "trojan://SuperPass@tr.example.com:443?security=tls&sni=tr.example.com&type=grpc&serviceName=shadora#TR"
        val trojanParsed = (parser.parseBatch(trojanUri).first() as ParseOutcome.Success).config
        val trojanOut = JSONObject(SingBoxJsonCompiler.compile(trojanParsed, settings, engine))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("trojan", trojanOut.getString("type"))
        assertEquals("grpc", trojanOut.getJSONObject("transport").getString("type"))
        assertEquals("shadora", trojanOut.getJSONObject("transport").getString("service_name"))

        val ssUser = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:Pass123".toByteArray())
        val ssParsed = (parser.parseBatch("ss://$ssUser@sg.example.com:8388#SG").first() as ParseOutcome.Success).config
        val ssOut = JSONObject(SingBoxJsonCompiler.compile(ssParsed, settings, engine))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("shadowsocks", ssOut.getString("type"))
        assertEquals("aes-256-gcm", ssOut.getString("method"))

        // Hysteria2 + Salamander obfs
        val hy2Parsed = (parser.parseBatch(
            "hy2://Hy2Pass@hy2.example.com:8443?sni=hy2.example.com&obfs=salamander&obfs-password=SalPass&upmbps=40&downmbps=150#Hy2"
        ).first() as ParseOutcome.Success).config
        val hy2Out = JSONObject(SingBoxJsonCompiler.compile(hy2Parsed, settings, engine))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria2", hy2Out.getString("type"))
        assertEquals("salamander", hy2Out.getJSONObject("obfs").getString("type"))
        assertEquals(40, hy2Out.getInt("up_mbps"))

        // AnyTLS
        val anyParsed = (parser.parseBatch("anytls://AnyPass@any.example.com:443?sni=any.example.com&fp=chrome#Any").first() as ParseOutcome.Success).config
        val anyOut = JSONObject(SingBoxJsonCompiler.compile(anyParsed, settings, engine))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("anytls", anyOut.getString("type"))
        assertEquals("AnyPass", anyOut.getString("password"))

        // ShadowTLS v3 (2-outbound detour chain)
        val stParsed = (parser.parseBatch(
            "shadowtls://StSecret@st.example.com:443?version=3&sni=www.cloudflare.com&ss_method=aes-256-gcm&ss_password=SsSecret#ST"
        ).first() as ParseOutcome.Success).config
        val stOutbounds = JSONObject(SingBoxJsonCompiler.compile(stParsed, settings, engine)).getJSONArray("outbounds")
        assertEquals("shadowsocks", stOutbounds.getJSONObject(0).getString("type"))
        assertEquals("shadowtls-out", stOutbounds.getJSONObject(0).getString("detour"))
        assertEquals("shadowtls", stOutbounds.getJSONObject(1).getString("type"))
        assertEquals(3, stOutbounds.getJSONObject(1).getInt("version"))

        // Mieru, Cloak, Lightway compilers + TUN chain
        val mieruParsed = (parser.parseBatch("mieru://bob:Pass123@mieru.example.com:8443?transport=UDP#M").first() as ParseOutcome.Success).config
        val mieruJson = JSONObject(MieruNativeBridge.compileClientJson(mieruParsed, 10891))
        assertEquals(10891, mieruJson.getInt("socks5Port"))
        val socksChain = JSONObject(SingBoxJsonCompiler.compileLocalSocksChain("127.0.0.1", 10891, settings, engine))
        assertEquals("socks", socksChain.getJSONArray("outbounds").getJSONObject(0).getString("type"))

        val cloakParsed = (parser.parseBatch("cloak://UidVal@cloak.example.com:443?PublicKey=PkVal&ServerName=www.bing.com#C").first() as ParseOutcome.Success).config
        val cloakJson = JSONObject(CloakNativeBridge.compileClientJson(cloakParsed))
        assertEquals("UidVal", cloakJson.getString("UID"))
        assertEquals("PkVal", cloakJson.getString("PublicKey"))

        val lwParsed = (parser.parseBatch("lightway://lw.example.com:443?protocol=udp&cipher=aes#L").first() as ParseOutcome.Success).config
        val lwJson = JSONObject(LightwayNativeBridge.compileProfileJson(lwParsed))
        assertEquals("lw.example.com", lwJson.getString("host"))
    }
}

