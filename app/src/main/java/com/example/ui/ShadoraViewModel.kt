package com.example.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ShadoraApplication
import com.example.configmanager.BatchImportReport
import com.example.configmanager.QrCodeHelper
import com.example.configmanager.SingBoxJsonCompiler
import com.example.configmanager.XrayJsonCompiler
import com.example.vpnservice.CloakNativeBridge
import com.example.vpnservice.LightwayNativeBridge
import com.example.vpnservice.MieruNativeBridge
import com.example.vpnservice.SingBoxNativeBridge
import com.example.core.model.AppLanguage
import com.example.core.model.AppThemeMode
import com.example.core.model.ConnectionState
import com.example.core.model.EngineDiagnosticInfo
import com.example.core.model.SortOrder
import com.example.core.model.SplitTunnelMode
import com.example.core.model.TrafficSnapshot
import com.example.core.model.VpnProtocol
import com.example.data.local.AppSettingsEntity
import com.example.data.local.ConnectionHistoryEntity
import com.example.data.local.EngineSettingsEntity
import com.example.data.local.ServerEntity
import com.example.data.local.SubscriptionEntity
import com.example.security.AppLockManager
import com.example.security.SafeLogEntry
import com.example.security.SafeLogger
import com.example.subscriptionmanager.SubscriptionAutoRefreshScheduler
import com.example.subscriptionmanager.SubscriptionFetchResult
import com.example.vpnservice.PublicIpCheckResult
import com.example.widget.ShadoraVpnWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ShadoraTab {
    HOME,
    SERVERS,
    SUBSCRIPTIONS,
    SETTINGS,
    ABOUT
}

data class InstalledAppItem(
    val packageName: String,
    val label: String
)

data class ServerInspectionData(
    val server: ServerEntity,
    val rawPayload: String,
    val compiledEngineOutput: String,
    val qrBitmap: Bitmap?
)

class ShadoraViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ShadoraApplication
    private val repository = app.repository
    private val subscriptionManager = app.subscriptionManager
    private val engineManager = app.engineManager
    private val latencyTester = app.latencyTester
    private val publicIpChecker = app.publicIpChecker

    val servers: StateFlow<List<ServerEntity>> = repository.serversFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val subscriptions: StateFlow<List<SubscriptionEntity>> = repository.subscriptionsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val connectionHistory: StateFlow<List<ConnectionHistoryEntity>> = repository.historyFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val appSettings: StateFlow<AppSettingsEntity> = repository.appSettingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppSettingsEntity()
    )

    val engineSettingsList: StateFlow<List<EngineSettingsEntity>> = repository.engineSettingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val connectionState: StateFlow<ConnectionState> = engineManager.connectionState
    val trafficSnapshot: StateFlow<TrafficSnapshot> = engineManager.trafficSnapshot

    private val _currentTab = MutableStateFlow(ShadoraTab.HOME)
    val currentTab: StateFlow<ShadoraTab> = _currentTab.asStateFlow()

    private val _showAddConfigScreen = MutableStateFlow(false)
    val showAddConfigScreen: StateFlow<Boolean> = _showAddConfigScreen.asStateFlow()

    private val _pendingImportDraft = MutableStateFlow("")
    val pendingImportDraft: StateFlow<String> = _pendingImportDraft.asStateFlow()

    private val _isPingingAll = MutableStateFlow(false)
    val isPingingAll: StateFlow<Boolean> = _isPingingAll.asStateFlow()

    private val _refreshingSubIds = MutableStateFlow<Set<Long>>(emptySet())
    val refreshingSubIds: StateFlow<Set<Long>> = _refreshingSubIds.asStateFlow()

    private val _serverSearchQuery = MutableStateFlow("")
    val serverSearchQuery: StateFlow<String> = _serverSearchQuery.asStateFlow()

    private val _serverSortOrder = MutableStateFlow(SortOrder.PING_ASC)
    val serverSortOrder: StateFlow<SortOrder> = _serverSortOrder.asStateFlow()

    private val _selectedCountryFilter = MutableStateFlow<String?>(null)
    val selectedCountryFilter: StateFlow<String?> = _selectedCountryFilter.asStateFlow()

    private val _selectedProtocolFilter = MutableStateFlow<VpnProtocol?>(null)
    val selectedProtocolFilter: StateFlow<VpnProtocol?> = _selectedProtocolFilter.asStateFlow()

    /** null = All sources, -1L = Manual configs only, >0L = specific Subscription ID */
    private val _selectedSubscriptionFilter = MutableStateFlow<Long?>(null)
    val selectedSubscriptionFilter: StateFlow<Long?> = _selectedSubscriptionFilter.asStateFlow()

    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    private val _inspectedServer = MutableStateFlow<ServerInspectionData?>(null)
    val inspectedServer: StateFlow<ServerInspectionData?> = _inspectedServer.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppItem>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppItem>> = _installedApps.asStateFlow()

    private val _isSmartConnecting = MutableStateFlow(false)
    val isSmartConnecting: StateFlow<Boolean> = _isSmartConnecting.asStateFlow()

    private val _isCheckingIp = MutableStateFlow(false)
    val isCheckingIp: StateFlow<Boolean> = _isCheckingIp.asStateFlow()

    private val _publicIpResult = MutableStateFlow<PublicIpCheckResult?>(null)
    val publicIpResult: StateFlow<PublicIpCheckResult?> = _publicIpResult.asStateFlow()

    private val _diagnosticLogs = MutableStateFlow<List<SafeLogEntry>>(emptyList())
    val diagnosticLogs: StateFlow<List<SafeLogEntry>> = _diagnosticLogs.asStateFlow()

    private val _isAppUnlocked = MutableStateFlow(false)
    val isAppUnlocked: StateFlow<Boolean> = _isAppUnlocked.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastEvents = _toastEvents.asSharedFlow()

    val engineDiagnostics: List<EngineDiagnosticInfo>
        get() = engineManager.getEngineDiagnostics()

    init {
        refreshDueSubscriptionsIfNeeded()
        viewModelScope.launch {
            engineManager.failoverMessages.collect { msg ->
                _toastEvents.tryEmit(msg)
            }
        }
    }

    fun unlockApp() {
        _isAppUnlocked.value = true
    }

    fun lockAppIfEnabled() {
        val cfg = appSettings.value
        if (cfg.appLockEnabled && cfg.appLockPinHash.isNotBlank()) {
            _isAppUnlocked.value = false
        }
    }

    fun selectTab(tab: ShadoraTab) {
        _showAddConfigScreen.value = false
        _currentTab.value = tab
    }

    fun openAddConfig(prefill: String = "") {
        if (prefill.isNotBlank()) {
            _pendingImportDraft.value = prefill
        }
        _showAddConfigScreen.value = true
    }

    fun closeAddConfig() {
        _showAddConfigScreen.value = false
        _pendingImportDraft.value = ""
    }

    fun updateImportDraft(text: String) {
        _pendingImportDraft.value = text
    }

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    fun detectProtocolPreview(text: String): VpnProtocol? =
        repository.configParser.detectProtocolPreview(text)

    fun isSubscriptionUrlDraft(text: String): Boolean =
        repository.configParser.isSubscriptionUrl(text)

    // Connection Controls
    fun selectServer(serverId: Long) {
        viewModelScope.launch {
            repository.updateAppSettings { it.copy(selectedServerId = serverId) }
            ShadoraVpnWidgetProvider.requestRefresh(app)
        }
    }

    fun connectSelectedServer() {
        viewModelScope.launch {
            val settings = repository.getAppSettings()
            val targetId = if (settings.selectedServerId > 0L) {
                settings.selectedServerId
            } else {
                repository.getAllServers().firstOrNull()?.id ?: -1L
            }
            if (targetId <= 0L) {
                val isFa = settings.languageCode == "fa"
                _toastEvents.tryEmit(
                    if (isFa) "ابتدا یک کانفیگ سرور وارد کنید." else "Please import a server configuration first."
                )
                return@launch
            }
            repository.updateAppSettings { it.copy(selectedServerId = targetId) }
            engineManager.connect(targetId)
        }
    }

    fun connectServer(serverId: Long) {
        viewModelScope.launch {
            repository.updateAppSettings { it.copy(selectedServerId = serverId) }
            engineManager.connect(serverId)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            engineManager.disconnect("USER_DISCONNECT")
        }
    }

    fun notifyVpnPermissionDenied() {
        val isFa = appSettings.value.languageCode == "fa"
        _toastEvents.tryEmit(
            if (isFa) "مجوز VPN سیستم اندروید رد شد." else "Android VPN permission was denied."
        )
    }

    // Config Import (automatically handles BOTH direct configs AND HTTPS Subscription links!)
    fun importConfigsFromText(rawText: String, onDone: (BatchImportReport) -> Unit = {}) {
        if (_isImporting.value) return
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val isFa = appSettings.value.languageCode == "fa"
                val trimmed = rawText.trim()

                if (repository.configParser.isSubscriptionUrl(trimmed)) {
                    val result = subscriptionManager.addAndRefreshSubscription(
                        name = "",
                        rawUrl = trimmed,
                        refreshIntervalHours = 24,
                        isPersian = isFa,
                        refreshIfExists = true
                    )
                    emitSubscriptionFeedback(result, isFa)
                    if (result is SubscriptionFetchResult.Success) {
                        onDone(result.report)
                        closeAddConfig()
                        _currentTab.value = ShadoraTab.SERVERS
                    } else {
                        onDone(BatchImportReport(failedCount = 1))
                    }
                    return@launch
                }

                val report = repository.importRawConfigs(
                    rawText = trimmed,
                    subscriptionId = null,
                    isManual = true,
                    isPersian = isFa
                )
                val summary = buildString {
                    if (report.importedCount > 0) {
                        append(if (isFa) "${report.importedCount} سرور ذخیره شد" else "${report.importedCount} server(s) imported")
                    }
                    if (report.duplicateCount > 0) {
                        if (isNotEmpty()) append(" • ")
                        append(if (isFa) "${report.duplicateCount} تکراری" else "${report.duplicateCount} duplicate(s)")
                    }
                    if (report.failedCount > 0) {
                        if (isNotEmpty()) append(" • ")
                        append(report.errors.firstOrNull() ?: if (isFa) "${report.failedCount} نامعتبر" else "${report.failedCount} invalid")
                    }
                }
                if (summary.isNotBlank()) _toastEvents.tryEmit(summary)
                onDone(report)
                if (report.importedCount > 0) {
                    closeAddConfig()
                    _currentTab.value = ShadoraTab.SERVERS
                }
            } finally {
                _isImporting.value = false
            }
        }
    }

    // Server Operations
    fun updateSearchQuery(q: String) {
        _serverSearchQuery.value = q
    }

    fun updateSortOrder(order: SortOrder) {
        _serverSortOrder.value = order
    }

    fun updateCountryFilter(code: String?) {
        _selectedCountryFilter.value = code
    }

    fun updateProtocolFilter(protocol: VpnProtocol?) {
        _selectedProtocolFilter.value = protocol
    }

    fun updateSubscriptionFilter(subscriptionId: Long?) {
        _selectedSubscriptionFilter.value = subscriptionId
    }

    fun toggleFavoritesOnly() {
        _favoritesOnly.value = !_favoritesOnly.value
    }

    fun toggleServerFavorite(server: ServerEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(server.id, !server.isFavorite)
        }
    }

    fun renameServer(serverId: Long, newName: String) {
        viewModelScope.launch {
            val clean = newName.trim()
            if (clean.isEmpty()) return@launch
            repository.renameServer(serverId, clean)
            val currentInspected = _inspectedServer.value
            if (currentInspected != null && currentInspected.server.id == serverId) {
                _inspectedServer.value = currentInspected.copy(
                    server = currentInspected.server.copy(name = clean)
                )
            }
            val isFa = appSettings.value.languageCode == "fa"
            _toastEvents.tryEmit(if (isFa) "نام سرور ذخیره شد" else "Server renamed")
        }
    }

    fun deleteUnreachableServers() {
        viewModelScope.launch {
            val removed = repository.deleteUnreachableServers()
            val isFa = appSettings.value.languageCode == "fa"
            _toastEvents.tryEmit(
                if (removed > 0) {
                    if (isFa) "$removed سرور بدون پاسخ حذف شد" else "Removed $removed unreachable server(s)"
                } else {
                    if (isFa) "سرور بدون پاسخی برای حذف وجود ندارد" else "No unreachable servers found"
                }
            )
        }
    }

    /**
     * Smart Connect: pings unpinged servers (or all if none reachable), picks the lowest-latency
     * reachable server, selects it, and invokes [onServerChosen] so the Activity can ensure VPN permission.
     */
    fun findFastestServerForSmartConnect(onServerChosen: (Long) -> Unit) {
        if (_isSmartConnecting.value) return
        viewModelScope.launch {
            _isSmartConnecting.value = true
            try {
                val isFa = appSettings.value.languageCode == "fa"
                val all = repository.getAllServers()
                if (all.isEmpty()) {
                    _toastEvents.tryEmit(
                        if (isFa) "ابتدا یک کانفیگ یا لینک اشتراک اضافه کنید." else "Add a config or subscription first."
                    )
                    return@launch
                }

                // Ping any unpinged servers, or re-ping all if none currently have a valid positive RTT
                val alreadyReachable = all.filter { it.latencyMs > 0 }
                val targets = if (alreadyReachable.isEmpty()) all else all.filter { it.latencyMs < 0 }
                if (targets.isNotEmpty()) {
                    latencyTester.measureBatch(targets.map { Triple(it.id, it.host, it.port) }) { id, rtt ->
                        repository.updateServerLatency(id, rtt)
                    }
                }

                val refreshed = repository.getAllServers()
                val fastest = refreshed
                    .filter { it.latencyMs > 0 }
                    .minByOrNull { it.latencyMs }
                    ?: refreshed.firstOrNull()

                if (fastest == null) {
                    _toastEvents.tryEmit(
                        if (isFa) "سرور قابل دسترسی یافت نشد." else "No reachable server found."
                    )
                    return@launch
                }

                repository.updateAppSettings { it.copy(selectedServerId = fastest.id) }
                val rttLabel = if (fastest.latencyMs > 0) " (${fastest.latencyMs}ms)" else ""
                _toastEvents.tryEmit(
                    if (isFa) "سریع‌ترین سرور انتخاب شد: ${fastest.name}$rttLabel"
                    else "Fastest server selected: ${fastest.name}$rttLabel"
                )
                onServerChosen(fastest.id)
            } finally {
                _isSmartConnecting.value = false
            }
        }
    }

    fun checkPublicIp() {
        if (_isCheckingIp.value) return
        viewModelScope.launch {
            _isCheckingIp.value = true
            try {
                val isFa = appSettings.value.languageCode == "fa"
                publicIpChecker.checkPublicIp()
                    .onSuccess { result ->
                        _publicIpResult.value = result
                        SafeLogger.i("ShadoraIpCheck", "Public IP check succeeded (${result.countryCode}, ${result.latencyMs}ms)")
                    }
                    .onFailure {
                        _toastEvents.tryEmit(
                            if (isFa) "بررسی آی‌پی عمومی ناموفق بود. اتصال اینترنت را بررسی کنید."
                            else "Public IP check failed. Verify your internet connection."
                        )
                    }
            } finally {
                _isCheckingIp.value = false
            }
        }
    }

    fun deleteServer(serverId: Long) {
        viewModelScope.launch {
            val conn = connectionState.value
            if (conn is ConnectionState.Connected && conn.serverId == serverId) {
                engineManager.disconnect("SERVER_DELETED")
            }
            repository.deleteServer(serverId)
        }
    }

    fun pingSingleServer(server: ServerEntity) {
        viewModelScope.launch {
            val rtt = latencyTester.measureTcpLatencyMs(server.host, server.port)
            repository.updateServerLatency(server.id, rtt)
        }
    }

    fun pingAllServers() {
        if (_isPingingAll.value) return
        viewModelScope.launch {
            _isPingingAll.value = true
            try {
                val list = repository.getAllServers()
                latencyTester.measureBatch(list.map { Triple(it.id, it.host, it.port) }) { id, rtt ->
                    repository.updateServerLatency(id, rtt)
                }
            } finally {
                _isPingingAll.value = false
            }
        }
    }

    fun inspectServer(serverId: Long) {
        viewModelScope.launch {
            val pair = repository.getDecryptedRawConfig(serverId) ?: return@launch
            val (server, raw) = pair
            val parsed = repository.getParsedConfigForServer(serverId)
            val appCfg = repository.getAppSettings()
            val proto = VpnProtocol.fromName(server.protocol)
            val engineCfg = repository.getEngineSettings(proto.engineId)

            val compiled = withContext(Dispatchers.Default) {
                when {
                    parsed != null && proto.engineId == "xray" -> runCatching {
                        if (SingBoxNativeBridge.isAvailable() || proto in setOf(
                                VpnProtocol.TUIC, VpnProtocol.HYSTERIA2, VpnProtocol.ANYTLS, VpnProtocol.SHADOWTLS
                            )
                        ) {
                            SingBoxJsonCompiler.compile(parsed, appCfg, engineCfg)
                        } else {
                            XrayJsonCompiler.compile(parsed, appCfg, engineCfg)
                        }
                    }.getOrDefault(raw)
                    parsed != null && proto == VpnProtocol.MIERU ->
                        runCatching { MieruNativeBridge.compileClientJson(parsed) }.getOrDefault(raw)
                    parsed != null && proto == VpnProtocol.CLOAK ->
                        runCatching { CloakNativeBridge.compileClientJson(parsed) }.getOrDefault(raw)
                    parsed != null && proto == VpnProtocol.LIGHTWAY ->
                        runCatching { LightwayNativeBridge.compileProfileJson(parsed) }.getOrDefault(raw)
                    else -> raw
                }
            }
            val qr = withContext(Dispatchers.Default) {
                if (raw.length <= 2200) QrCodeHelper.generateQrBitmap(raw, 480) else null
            }
            _inspectedServer.value = ServerInspectionData(
                server = server,
                rawPayload = raw,
                compiledEngineOutput = compiled,
                qrBitmap = qr
            )
        }
    }

    fun closeServerInspector() {
        _inspectedServer.value = null
    }

    // Subscriptions
    fun addSubscription(name: String, url: String, intervalHours: Int) {
        viewModelScope.launch {
            val isFa = appSettings.value.languageCode == "fa"
            val result = subscriptionManager.addAndRefreshSubscription(name, url, intervalHours, isFa)
            emitSubscriptionFeedback(result, isFa)
        }
    }

    fun updateSubscriptionDetails(id: Long, name: String, url: String, intervalHours: Int) {
        viewModelScope.launch {
            val isFa = appSettings.value.languageCode == "fa"
            val ok = repository.updateSubscriptionDetails(id, name, url, intervalHours)
            if (ok) {
                _toastEvents.tryEmit(if (isFa) "اشتراک به‌روزرسانی شد" else "Subscription updated")
            } else {
                _toastEvents.tryEmit(
                    if (isFa) "ویرایش اشتراک ناموفق بود (آدرس تکراری یا نامعتبر)."
                    else "Could not update subscription (duplicate or invalid URL)."
                )
            }
        }
    }

    fun refreshDueSubscriptionsIfNeeded() {
        viewModelScope.launch {
            val all = repository.getAllSubscriptions()
            val due = SubscriptionAutoRefreshScheduler.findDueSubscriptions(all)
            if (due.isEmpty()) return@launch
            SafeLogger.i("ShadoraSubAuto", "Auto-refreshing ${due.size} due subscription(s)")
            val isFa = repository.getAppSettings().languageCode == "fa"
            due.forEach { sub ->
                _refreshingSubIds.value = _refreshingSubIds.value + sub.id
                try {
                    subscriptionManager.refreshSubscription(sub.id, isFa)
                } finally {
                    _refreshingSubIds.value = _refreshingSubIds.value - sub.id
                }
            }
        }
    }

    fun refreshSubscription(subId: Long) {
        viewModelScope.launch {
            _refreshingSubIds.value = _refreshingSubIds.value + subId
            try {
                val isFa = appSettings.value.languageCode == "fa"
                val result = subscriptionManager.refreshSubscription(subId, isFa)
                emitSubscriptionFeedback(result, isFa)
            } finally {
                _refreshingSubIds.value = _refreshingSubIds.value - subId
            }
        }
    }

    fun refreshAllSubscriptions() {
        viewModelScope.launch {
            val all = repository.getAllSubscriptions()
            if (all.isEmpty()) return@launch
            _refreshingSubIds.value = all.map { it.id }.toSet()
            try {
                val isFa = appSettings.value.languageCode == "fa"
                val results = subscriptionManager.refreshAllSubscriptions(isFa)
                val successCount = results.count { it is SubscriptionFetchResult.Success }
                _toastEvents.tryEmit(
                    if (isFa) "$successCount از ${results.size} اشتراک به‌روزرسانی شد"
                    else "Updated $successCount of ${results.size} subscription(s)"
                )
            } finally {
                _refreshingSubIds.value = emptySet()
            }
        }
    }

    fun deleteSubscription(subId: Long) {
        viewModelScope.launch {
            if (_selectedSubscriptionFilter.value == subId) {
                _selectedSubscriptionFilter.value = null
            }
            subscriptionManager.deleteSubscription(subId)
        }
    }

    // Backup & Restore
    suspend fun generateBackupJson(): String = repository.exportBackupJson()

    fun restoreBackupFromJson(rawJson: String) {
        viewModelScope.launch {
            val isFa = appSettings.value.languageCode == "fa"
            repository.importBackupJson(rawJson, isFa)
                .onSuccess { summary ->
                    val msg = if (isFa) {
                        "بازیابی انجام شد: ${summary.restoredSubscriptions} اشتراک، ${summary.restoredManualConfigs} کانفیگ دستی"
                    } else {
                        "Restored ${summary.restoredSubscriptions} subscription(s) and ${summary.restoredManualConfigs} manual config(s)"
                    }
                    _toastEvents.tryEmit(msg)
                    SafeLogger.i("ShadoraBackup", "Backup restored successfully")
                }
                .onFailure {
                    _toastEvents.tryEmit(
                        if (isFa) "فایل پشتیبان نامعتبر است." else "Invalid SHADORA backup file."
                    )
                }
        }
    }

    // Safe Diagnostic Logs
    fun refreshDiagnosticLogs() {
        _diagnosticLogs.value = SafeLogger.getRecentLogs()
    }

    fun clearDiagnosticLogs() {
        SafeLogger.clearLogs()
        _diagnosticLogs.value = emptyList()
    }

    fun exportDiagnosticLogsText(): String = SafeLogger.exportSanitizedText()

    private fun emitSubscriptionFeedback(result: SubscriptionFetchResult, isFa: Boolean) {
        when (result) {
            is SubscriptionFetchResult.Success -> {
                _toastEvents.tryEmit(
                    if (isFa) "اشتراک همگام شد: ${result.report.importedCount} سرور"
                    else "Subscription synced: ${result.report.importedCount} server(s)"
                )
            }
            is SubscriptionFetchResult.Error -> {
                _toastEvents.tryEmit(if (isFa) result.messageFa else result.messageEn)
            }
        }
    }

    // Settings & Preferences
    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            repository.updateAppSettings { it.copy(languageCode = language.code) }
            ShadoraVpnWidgetProvider.requestRefresh(app)
        }
    }

    fun toggleLanguage() {
        val current = AppLanguage.fromCode(appSettings.value.languageCode)
        setLanguage(if (current == AppLanguage.FA) AppLanguage.EN else AppLanguage.FA)
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            repository.updateAppSettings { it.copy(themeMode = mode.name) }
        }
    }

    fun updateDns(primary: String, secondary: String) {
        viewModelScope.launch {
            repository.updateAppSettings {
                it.copy(primaryDns = primary.trim(), secondaryDns = secondary.trim())
            }
        }
    }

    fun setIpv6Enabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAppSettings { it.copy(ipv6Enabled = enabled) }
        }
    }

    fun setBypassIranDomains(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAppSettings { it.copy(bypassIranDomains = enabled) }
        }
    }

    fun setBypassLanCidrs(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAppSettings { it.copy(bypassLanCidrs = enabled) }
        }
    }

    fun saveCustomRoutingDomains(directCsv: String, blockCsv: String) {
        viewModelScope.launch {
            repository.updateAppSettings {
                it.copy(
                    customDirectDomainsCsv = directCsv.trim(),
                    customBlockDomainsCsv = blockCsv.trim()
                )
            }
            val isFa = appSettings.value.languageCode == "fa"
            _toastEvents.tryEmit(if (isFa) "قوانین مسیریابی هوشمند ذخیره شد" else "Smart routing rules saved")
        }
    }

    fun setSplitTunnelMode(mode: SplitTunnelMode) {
        viewModelScope.launch {
            repository.updateAppSettings { it.copy(splitTunnelMode = mode.name) }
        }
    }

    fun toggleSplitTunnelPackage(packageName: String) {
        viewModelScope.launch {
            repository.updateAppSettings { current ->
                val set = current.splitTunnelPackagesCsv
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toMutableSet()
                if (!set.add(packageName)) set.remove(packageName)
                current.copy(splitTunnelPackagesCsv = set.sorted().joinToString(","))
            }
        }
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAppSettings { it.copy(autoConnect = enabled) }
        }
    }

    fun setAutoFailover(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAppSettings { it.copy(autoFailoverEnabled = enabled) }
        }
    }

    fun enableAppLockWithPin(pin: String) {
        if (!AppLockManager.isValidPin(pin)) return
        viewModelScope.launch {
            val hashed = AppLockManager.hashPin(pin)
            repository.updateAppSettings {
                it.copy(appLockEnabled = true, appLockPinHash = hashed)
            }
            _isAppUnlocked.value = true
            val isFa = appSettings.value.languageCode == "fa"
            _toastEvents.tryEmit(if (isFa) "قفل امنیتی SHADORA فعال شد" else "SHADORA App Lock enabled")
        }
    }

    fun disableAppLock() {
        viewModelScope.launch {
            repository.updateAppSettings {
                it.copy(appLockEnabled = false, appLockPinHash = "")
            }
            _isAppUnlocked.value = true
            val isFa = appSettings.value.languageCode == "fa"
            _toastEvents.tryEmit(if (isFa) "قفل امنیتی غیرفعال شد" else "App Lock disabled")
        }
    }

    fun setAppLockBiometric(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAppSettings { it.copy(appLockBiometricEnabled = enabled) }
        }
    }

    fun setKillSwitch(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAppSettings { it.copy(killSwitchEnabled = enabled) }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAppSettings { it.copy(notificationsEnabled = enabled) }
        }
    }

    fun updateXrayEngineSettings(mtu: Int, mux: Boolean, allowInsecure: Boolean) {
        viewModelScope.launch {
            val current = repository.getEngineSettings("xray")
            repository.updateEngineSettings(
                current.copy(
                    mtu = mtu.coerceIn(1280, 1500),
                    muxEnabled = mux,
                    allowInsecureTls = allowInsecure
                )
            )
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearConnectionHistory()
        }
    }

    /**
     * Queries only launcher apps using our Manifest <queries> declaration (Play-compliant,
     * avoids QUERY_ALL_PACKAGES sensitive permission).
     */
    fun loadLauncherAppsForSplitTunnel() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = app.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolved = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            val items = resolved
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == app.packageName) return@mapNotNull null
                    val label = info.loadLabel(pm).toString().ifBlank { pkg }
                    InstalledAppItem(pkg, label)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
            _installedApps.value = items
        }
    }

    fun emitToast(message: String) {
        _toastEvents.tryEmit(message)
    }
}
