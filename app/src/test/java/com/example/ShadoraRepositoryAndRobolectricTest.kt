package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.configmanager.QrCodeHelper
import com.example.core.model.EngineSupportTier
import com.example.core.model.VpnProtocol
import com.example.data.local.ShadoraDatabase
import com.example.data.repository.ShadoraRepository
import com.example.enginemanager.EngineManager
import com.example.enginemanager.EngineValidationResult
import com.example.enginemanager.WireGuardEngineAdapter
import com.example.security.KeystoreCryptoManager
import com.example.subscriptionmanager.SubscriptionManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShadoraRepositoryAndRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: ShadoraDatabase
    private lateinit var crypto: KeystoreCryptoManager
    private lateinit var repository: ShadoraRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShadoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        crypto = KeystoreCryptoManager("robolectric_test_key")
        repository = ShadoraRepository(db, crypto)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `duplicate detection skips identical configs and encrypts payloads in Room`() = runTest {
        val link = "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?security=tls&type=tcp#Germany%201"
        val first = repository.importRawConfigs(link, isManual = true)
        assertEquals(1, first.importedCount)
        assertEquals(0, first.duplicateCount)

        val second = repository.importRawConfigs(link, isManual = true)
        assertEquals(0, second.importedCount)
        assertEquals(1, second.duplicateCount)

        // Verify stored Room ConfigurationEntity is encrypted (no plaintext UUID in DB)
        val server = repository.getAllServers().first()
        val storedConfig = db.configurationDao().getById(server.configurationId)!!
        assertTrue(crypto.isEncrypted(storedConfig.encryptedRawPayload))
        assertFalse(storedConfig.encryptedRawPayload.contains("11111111-2222-3333-4444-555555555555"))

        val decrypted = repository.getDecryptedRawConfig(server.id)!!.second
        assertEquals(link, decrypted)
    }

    @Test
    fun `subscription refresh replaces subscription servers while preserving manual configs`() = runTest {
        val manualLink = "trojan://ManualSecret99@manual.example.com:443?security=tls#Manual%20Server"
        repository.importRawConfigs(manualLink, subscriptionId = null, isManual = true)

        val subId = repository.createSubscription("Main Sub", "https://sub.example.com/feed", 24)
        val initialSubPayload = "vless://22222222-2222-2222-2222-222222222222@old.example.com:443?security=tls#Old%20Sub"
        repository.replaceSubscriptionConfigs(subId, initialSubPayload, isPersian = false)
        assertEquals(2, repository.getAllServers().size)

        val refreshedSubPayload = "vless://33333333-3333-3333-3333-333333333333@new.example.com:443?security=tls#New%20Sub"
        repository.replaceSubscriptionConfigs(subId, refreshedSubPayload, isPersian = false)

        val allServers = repository.getAllServers()
        assertEquals(2, allServers.size)
        assertTrue(allServers.any { it.host == "manual.example.com" && it.isManual })
        assertTrue(allServers.any { it.host == "new.example.com" && !it.isManual })
        assertFalse(allServers.any { it.host == "old.example.com" })
    }

    @Test
    fun `QR code round trip encodes and decodes VLESS config accurately`() {
        val original = "vless://11111111-2222-3333-4444-555555555555@nl.example.com:443?security=tls#QRNode"
        val bitmap = QrCodeHelper.generateQrBitmap(original, 400)
        assertNotNull(bitmap)
        val decoded = QrCodeHelper.decodeFromBitmap(bitmap!!)
        assertEquals(original, decoded)
    }

    @Test
    fun `WireGuard adapter validates real WireGuard config and EngineManager reports honest protocol tiers`() = runTest {
        val keyA = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        val keyB = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 40).toByte() })
        val wgIni = """
            [Interface]
            PrivateKey = $keyA
            Address = 10.66.66.2/32

            [Peer]
            PublicKey = $keyB
            Endpoint = 198.51.100.10:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val report = repository.importRawConfigs(wgIni)
        assertEquals(1, report.importedCount)
        val parsed = repository.getParsedConfigForServer(report.importedServerIds.first())!!

        val wgAdapter = WireGuardEngineAdapter()
        val result = wgAdapter.prepare(
            context = context,
            serverId = report.importedServerIds.first(),
            parsed = parsed,
            appSettings = repository.getAppSettings(),
            engineSettings = repository.getEngineSettings("wireguard")
        )
        assertTrue(result is EngineValidationResult.Valid)
        assertTrue((result as EngineValidationResult.Valid).plan.isFullNativeTunnel)

        val engineManager = EngineManager(context, repository)
        val diagnostics = engineManager.getEngineDiagnostics()
        val wgDiag = diagnostics.first { it.engineId == "wireguard" }
        val tuicDiag = diagnostics.first { VpnProtocol.TUIC in it.protocols }
        assertEquals(EngineSupportTier.NATIVE_BUNDLED, wgDiag.tier)
        assertEquals(EngineSupportTier.UNSUPPORTED_NO_ENGINE, tuicDiag.tier)
    }

    @Test
    fun `SubscriptionManager parses subscription-userinfo quota header`() {
        val subMgr = SubscriptionManager(repository)
        val quota = subMgr.parseUserInfoHeader("upload=1048576; download=2097152; total=10737418240; expire=1893456000")
        assertEquals(1048576L, quota.uploadBytes)
        assertEquals(2097152L, quota.downloadBytes)
        assertEquals(10737418240L, quota.totalBytes)
        assertEquals(1893456000L, quota.expireEpochSeconds)
    }
}
