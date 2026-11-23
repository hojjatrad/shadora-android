package com.example

import com.example.configmanager.ConfigParser
import com.example.configmanager.ParseOutcome
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
}

