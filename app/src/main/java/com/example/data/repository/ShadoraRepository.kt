package com.example.data.repository

import com.example.configmanager.BatchImportReport
import com.example.configmanager.ConfigParser
import com.example.configmanager.ParseOutcome
import com.example.configmanager.ParsedVpnConfig
import com.example.data.backup.BackupRestoreSummary
import com.example.data.backup.ShadoraBackupCodec
import com.example.data.local.AppSettingsEntity
import com.example.data.local.ConfigurationEntity
import com.example.data.local.ConnectionHistoryEntity
import com.example.data.local.EngineSettingsEntity
import com.example.data.local.ServerEntity
import com.example.data.local.ShadoraDatabase
import com.example.data.local.SubscriptionEntity
import com.example.security.KeystoreCryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

class ShadoraRepository(
    private val database: ShadoraDatabase,
    private val crypto: KeystoreCryptoManager,
    val configParser: ConfigParser = ConfigParser()
) {
    private val configDao = database.configurationDao()
    private val serverDao = database.serverDao()
    private val subDao = database.subscriptionDao()
    private val historyDao = database.connectionHistoryDao()
    private val appSettingsDao = database.appSettingsDao()
    private val engineSettingsDao = database.engineSettingsDao()

    val serversFlow: Flow<List<ServerEntity>> = serverDao.observeAll()
    val subscriptionsFlow: Flow<List<SubscriptionEntity>> = subDao.observeAll()
    val historyFlow: Flow<List<ConnectionHistoryEntity>> = historyDao.observeRecent()
    val appSettingsFlow: Flow<AppSettingsEntity> = appSettingsDao.observeSettings().map {
        it ?: AppSettingsEntity()
    }
    val engineSettingsFlow: Flow<List<EngineSettingsEntity>> = engineSettingsDao.observeAll()

    suspend fun getAppSettings(): AppSettingsEntity {
        return appSettingsDao.getSettings() ?: AppSettingsEntity().also {
            appSettingsDao.upsert(it)
        }
    }

    suspend fun updateAppSettings(transform: (AppSettingsEntity) -> AppSettingsEntity) {
        val current = getAppSettings()
        appSettingsDao.upsert(transform(current))
    }

    suspend fun getEngineSettings(engineId: String): EngineSettingsEntity {
        return engineSettingsDao.getByEngine(engineId) ?: EngineSettingsEntity(engineId = engineId).also {
            engineSettingsDao.upsert(it)
        }
    }

    suspend fun updateEngineSettings(settings: EngineSettingsEntity) {
        engineSettingsDao.upsert(settings)
    }

    suspend fun importRawConfigs(
        rawText: String,
        subscriptionId: Long? = null,
        isManual: Boolean = true,
        isPersian: Boolean = true
    ): BatchImportReport {
        val outcomes = configParser.parseBatch(rawText)
        if (outcomes.isEmpty()) {
            return BatchImportReport(
                failedCount = 1,
                errors = listOf(if (isPersian) "ورودی خالی یا فاقد کانفیگ معتبر است." else "Input contains no valid configs.")
            )
        }

        val existingFingerprints = configDao.getAllFingerprints().toMutableSet()
        var imported = 0
        var duplicates = 0
        var failed = 0
        val errors = mutableListOf<String>()
        val insertedServerIds = mutableListOf<Long>()

        for (outcome in outcomes) {
            when (outcome) {
                is ParseOutcome.Failure -> {
                    failed++
                    errors.add(if (isPersian) outcome.reasonFa else outcome.reasonEn)
                }
                is ParseOutcome.Success -> {
                    val parsed = outcome.config
                    if (existingFingerprints.contains(parsed.fingerprint)) {
                        duplicates++
                        continue
                    }
                    val serverId = persistParsedConfig(parsed, subscriptionId, isManual)
                    if (serverId > 0L) {
                        existingFingerprints.add(parsed.fingerprint)
                        imported++
                        insertedServerIds.add(serverId)
                    } else {
                        duplicates++
                    }
                }
            }
        }

        // Auto-select first imported server if none is selected yet
        if (insertedServerIds.isNotEmpty()) {
            val settings = getAppSettings()
            if (settings.selectedServerId <= 0L) {
                updateAppSettings { it.copy(selectedServerId = insertedServerIds.first()) }
            }
        }

        return BatchImportReport(
            importedCount = imported,
            duplicateCount = duplicates,
            failedCount = failed,
            errors = errors.distinct().take(5),
            importedServerIds = insertedServerIds
        )
    }

    private suspend fun persistParsedConfig(
        parsed: ParsedVpnConfig,
        subscriptionId: Long?,
        isManual: Boolean
    ): Long {
        val credsJson = JSONObject().apply {
            parsed.credentialsMap.forEach { (k, v) -> put(k, v) }
        }.toString()

        val encryptedPayload = crypto.encrypt(parsed.rawPayload)
        val encryptedCreds = crypto.encrypt(credsJson)

        val configEntity = ConfigurationEntity(
            protocol = parsed.protocol.name,
            format = parsed.format.name,
            encryptedRawPayload = encryptedPayload,
            encryptedCredentialsJson = encryptedCreds,
            fingerprint = parsed.fingerprint,
            subscriptionId = subscriptionId,
            isManual = isManual
        )

        return serverDao.insertServerWithConfig(configDao, configEntity) { configId ->
            ServerEntity(
                configurationId = configId,
                subscriptionId = subscriptionId,
                isManual = isManual,
                name = parsed.displayName,
                protocol = parsed.protocol.name,
                host = parsed.host,
                port = parsed.port,
                countryCode = parsed.countryCode,
                countryName = parsed.countryName,
                transport = parsed.transport,
                security = parsed.security
            )
        }
    }

    suspend fun getDecryptedRawConfig(serverId: Long): Pair<ServerEntity, String>? {
        val server = serverDao.getById(serverId) ?: return null
        val config = configDao.getById(server.configurationId) ?: return null
        return server to crypto.decrypt(config.encryptedRawPayload)
    }

    suspend fun getParsedConfigForServer(serverId: Long): ParsedVpnConfig? {
        val (_, raw) = getDecryptedRawConfig(serverId) ?: return null
        val parsed = configParser.parseBatch(raw).firstOrNull() as? ParseOutcome.Success
        return parsed?.config
    }

    suspend fun getServerById(serverId: Long): ServerEntity? = serverDao.getById(serverId)

    suspend fun getAllServers(): List<ServerEntity> = serverDao.getAll()

    suspend fun updateServerLatency(serverId: Long, latencyMs: Int) {
        serverDao.updateLatency(serverId, latencyMs, System.currentTimeMillis())
    }

    suspend fun toggleFavorite(serverId: Long, isFavorite: Boolean) {
        serverDao.updateFavorite(serverId, isFavorite)
    }

    suspend fun renameServer(serverId: Long, newName: String) {
        val clean = newName.trim()
        if (clean.isNotEmpty()) {
            serverDao.updateName(serverId, clean)
        }
    }

    /**
     * Deletes servers marked unreachable (`latencyMs == -2`) after explicit user confirmation.
     */
    suspend fun deleteUnreachableServers(): Int {
        val configIds = serverDao.getUnreachableConfigurationIds()
        configIds.forEach { cfgId ->
            configDao.deleteById(cfgId)
        }
        val settings = getAppSettings()
        if (settings.selectedServerId > 0L && serverDao.getById(settings.selectedServerId) == null) {
            val next = serverDao.getAll().firstOrNull()?.id ?: -1L
            updateAppSettings { it.copy(selectedServerId = next) }
        }
        return configIds.size
    }

    /**
     * Deletes a server and its underlying encrypted configuration (only upon explicit user action).
     */
    suspend fun deleteServer(serverId: Long) {
        val server = serverDao.getById(serverId) ?: return
        configDao.deleteById(server.configurationId)
        val settings = getAppSettings()
        if (settings.selectedServerId == serverId) {
            val next = serverDao.getAll().firstOrNull()?.id ?: -1L
            updateAppSettings { it.copy(selectedServerId = next) }
        }
    }

    // Subscriptions
    suspend fun createSubscription(name: String, url: String, intervalHours: Int): Long {
        return subDao.insert(
            SubscriptionEntity(
                name = name.trim(),
                url = url.trim(),
                refreshIntervalHours = intervalHours
            )
        )
    }

    suspend fun getSubscription(id: Long): SubscriptionEntity? = subDao.getById(id)

    suspend fun getSubscriptionByUrl(url: String): SubscriptionEntity? = subDao.getByUrl(url)

    suspend fun getAllSubscriptions(): List<SubscriptionEntity> = subDao.getAll()

    suspend fun updateSubscription(entity: SubscriptionEntity) = subDao.update(entity)

    suspend fun updateSubscriptionDetails(
        id: Long,
        name: String,
        url: String,
        refreshIntervalHours: Int
    ): Boolean {
        val existing = subDao.getById(id) ?: return false
        val cleanUrl = url.trim()
        val conflict = subDao.getByUrl(cleanUrl)
        if (conflict != null && conflict.id != id) return false
        subDao.update(
            existing.copy(
                name = name.trim().ifBlank { existing.name },
                url = cleanUrl,
                refreshIntervalHours = refreshIntervalHours.coerceAtLeast(0)
            )
        )
        return true
    }

    /**
     * Replaces only subscription-owned servers during a refresh while strictly preserving
     * all manual configurations (`isManual = true`).
     */
    suspend fun replaceSubscriptionConfigs(
        subscriptionId: Long,
        rawPayload: String,
        isPersian: Boolean
    ): BatchImportReport {
        // Delete only non-manual configs belonging to this subscription
        configDao.deleteNonManualBySubscription(subscriptionId)
        return importRawConfigs(
            rawText = rawPayload,
            subscriptionId = subscriptionId,
            isManual = false,
            isPersian = isPersian
        )
    }

    suspend fun deleteSubscriptionAndServers(subscriptionId: Long) {
        configDao.deleteNonManualBySubscription(subscriptionId)
        subDao.deleteById(subscriptionId)
    }

    suspend fun countServersForSubscription(subscriptionId: Long): Int =
        serverDao.countBySubscription(subscriptionId)

    // Backup & Restore (zero-permission SAF JSON bundle)
    suspend fun exportBackupJson(): String {
        val settings = getAppSettings()
        val subs = subDao.getAll()
        val manualConfigs = configDao.getManualConfigs().mapNotNull { cfg ->
            runCatching { crypto.decrypt(cfg.encryptedRawPayload) }.getOrNull()
        }
        return ShadoraBackupCodec.encode(settings, subs, manualConfigs)
    }

    suspend fun importBackupJson(rawJson: String, isPersian: Boolean): Result<BackupRestoreSummary> {
        val decoded = ShadoraBackupCodec.decode(rawJson).getOrElse { return Result.failure(it) }
        var restoredSettings = false
        decoded.settings?.let { s ->
            updateAppSettings { current ->
                current.copy(
                    languageCode = s.languageCode,
                    themeMode = s.themeMode,
                    primaryDns = s.primaryDns,
                    secondaryDns = s.secondaryDns,
                    ipv6Enabled = s.ipv6Enabled,
                    splitTunnelMode = s.splitTunnelMode,
                    splitTunnelPackagesCsv = s.splitTunnelPackagesCsv,
                    autoConnect = s.autoConnect,
                    killSwitchEnabled = s.killSwitchEnabled,
                    notificationsEnabled = s.notificationsEnabled,
                    bypassIranDomains = s.bypassIranDomains,
                    bypassLanCidrs = s.bypassLanCidrs,
                    customDirectDomainsCsv = s.customDirectDomainsCsv,
                    customBlockDomainsCsv = s.customBlockDomainsCsv,
                    autoFailoverEnabled = s.autoFailoverEnabled
                )
            }
            restoredSettings = true
        }

        var restoredSubs = 0
        decoded.subscriptions.forEach { item ->
            if (subDao.getByUrl(item.url) == null) {
                runCatching {
                    createSubscription(
                        name = item.name.ifBlank { item.url },
                        url = item.url,
                        intervalHours = item.refreshIntervalHours
                    )
                    restoredSubs++
                }
            }
        }

        var restoredManual = 0
        var skippedDupes = 0
        decoded.manualConfigs.forEach { raw ->
            val report = importRawConfigs(
                rawText = raw,
                subscriptionId = null,
                isManual = true,
                isPersian = isPersian
            )
            restoredManual += report.importedCount
            skippedDupes += report.duplicateCount
        }

        return Result.success(
            BackupRestoreSummary(
                restoredSettings = restoredSettings,
                restoredSubscriptions = restoredSubs,
                restoredManualConfigs = restoredManual,
                skippedDuplicates = skippedDupes
            )
        )
    }

    // Connection history
    suspend fun recordConnectionSession(entry: ConnectionHistoryEntity) {
        historyDao.insert(entry)
    }

    suspend fun clearConnectionHistory() {
        historyDao.clearAll()
    }
}
