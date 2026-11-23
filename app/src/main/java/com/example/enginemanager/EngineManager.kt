package com.example.enginemanager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.service.quicksettings.TileService
import com.example.core.model.ConnectionState
import com.example.core.model.EngineDiagnosticInfo
import com.example.core.model.TrafficSnapshot
import com.example.core.model.VpnProtocol
import com.example.data.local.ConnectionHistoryEntity
import com.example.data.repository.ShadoraRepository
import com.example.security.SafeLogger
import com.example.vpnservice.ServerLatencyTester
import com.example.vpnservice.ShadoraQuickSettingsTileService
import com.example.vpnservice.ShadoraVpnService
import com.example.vpnservice.XrayNativeBridge
import com.example.widget.ShadoraVpnWidgetProvider
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config as WgConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader

/**
 * Coordinates all VPN engine adapters, prevents concurrent incompatible engines,
 * manages real Android VpnService and native WireGuard GoBackend lifecycles,
 * supports Auto-Failover server switching, updates the Home Screen Widget live,
 * tracks traffic telemetry, and persists ConnectionHistory into Room.
 */
class EngineManager(
    private val appContext: Context,
    private val repository: ShadoraRepository,
    private val latencyTester: ServerLatencyTester = ServerLatencyTester()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val adapters: List<VpnEngineAdapter> = listOf(
        WireGuardEngineAdapter(),
        XrayEngineAdapter(),
        AmneziaWgEngineAdapter(),
        OpenVpnEngineAdapter(),
        UnsupportedProtocolAdapter(
            protocol = VpnProtocol.TUIC,
            license = "GPL-3.0 (EAimTY/tuic)",
            reasonFa = "موتور بومی TUIC (Rust/QUIC) در این نسخه گنجانده نشده است.",
            reasonEn = "Native TUIC (Rust/QUIC) engine binary is not bundled in this build."
        ),
        UnsupportedProtocolAdapter(
            protocol = VpnProtocol.MIERU,
            license = "GPL-3.0 (enfein/mieru)",
            reasonFa = "موتور بومی Mieru در این نسخه گنجانده نشده است.",
            reasonEn = "Native Mieru engine binary is not bundled in this build."
        ),
        UnsupportedProtocolAdapter(
            protocol = VpnProtocol.LIGHTWAY,
            license = "GPL-2.0 (ExpressVPN Lightway Core)",
            reasonFa = "موتور بومی Lightway (wolfSSL/C) در این نسخه گنجانده نشده است.",
            reasonEn = "Native Lightway C core is not bundled in this build."
        ),
        UnsupportedProtocolAdapter(
            protocol = VpnProtocol.CLOAK,
            license = "GPL-3.0 (cbeuw/Cloak)",
            reasonFa = "پلاگین بومی Cloak در این نسخه گنجانده نشده است.",
            reasonEn = "Native Cloak pluggable transport binary is not bundled in this build."
        )
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _trafficSnapshot = MutableStateFlow(TrafficSnapshot())
    val trafficSnapshot: StateFlow<TrafficSnapshot> = _trafficSnapshot.asStateFlow()

    private val _failoverMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val failoverMessages: SharedFlow<String> = _failoverMessages.asSharedFlow()

    @Volatile
    private var activePlan: EngineLaunchPlan? = null

    @Volatile
    private var wireGuardBackend: GoBackend? = null

    @Volatile
    private var activeWireGuardTunnel: ShadoraWgTunnel? = null

    private val attemptedFailoverIds = mutableSetOf<Long>()
    private var failoverJob: Job? = null
    private var telemetryJob: Job? = null
    private var sessionStartMillis: Long = 0L

    private fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
        runCatching { ShadoraVpnWidgetProvider.requestRefresh(appContext) }
        runCatching {
            TileService.requestListeningState(
                appContext,
                ComponentName(appContext, ShadoraQuickSettingsTileService::class.java)
            )
        }
    }

    fun getEngineDiagnostics(): List<EngineDiagnosticInfo> =
        adapters.map { it.inspectCapability(appContext) }

    fun getAdapterForProtocol(protocol: VpnProtocol): VpnEngineAdapter? =
        adapters.firstOrNull { protocol in it.supportedProtocols }

    fun getCurrentLaunchPlan(): EngineLaunchPlan? = activePlan

    suspend fun connect(serverId: Long, isFailoverAttempt: Boolean = false) = mutex.withLock {
        if (!isFailoverAttempt) {
            failoverJob?.cancel()
            attemptedFailoverIds.clear()
        }
        attemptedFailoverIds.add(serverId)

        val currentState = _connectionState.value
        if (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting) {
            stopInternal("SWITCH_SERVER")
        }

        updateConnectionState(ConnectionState.Preparing)

        val parsed = repository.getParsedConfigForServer(serverId)
        if (parsed == null) {
            updateConnectionState(
                ConnectionState.Error(
                    code = "CONFIG_NOT_FOUND",
                    messageFa = "کانفیگ سرور انتخاب‌شده یافت نشد یا رمزگشایی نشد.",
                    messageEn = "Selected server configuration could not be loaded."
                )
            )
            return@withLock
        }

        val adapter = getAdapterForProtocol(parsed.protocol)
        if (adapter == null) {
            updateConnectionState(
                ConnectionState.Error(
                    code = "NO_ADAPTER",
                    messageFa = "موتوری برای پروتکل ${parsed.protocol.displayName} یافت نشد.",
                    messageEn = "No engine adapter registered for ${parsed.protocol.displayName}."
                )
            )
            return@withLock
        }

        val appSettings = repository.getAppSettings()
        val engineSettings = repository.getEngineSettings(adapter.engineId)

        when (val validation = adapter.prepare(appContext, serverId, parsed, appSettings, engineSettings)) {
            is EngineValidationResult.Invalid -> {
                updateConnectionState(
                    ConnectionState.Error(
                        code = "ENGINE_VALIDATION_FAILED",
                        messageFa = validation.messageFa,
                        messageEn = validation.messageEn
                    )
                )
                return@withLock
            }

            is EngineValidationResult.Valid -> {
                val plan = validation.plan
                activePlan = plan
                updateConnectionState(
                    ConnectionState.Connecting(
                        serverId = plan.serverId,
                        serverName = plan.serverName,
                        protocol = plan.protocol,
                        stage = if (plan.engineId == "wireguard") "Initializing libwg-go…" else "Checking server…"
                    )
                )

                if (plan.engineId == "wireguard") {
                    startWireGuardNative(plan, appSettings.languageCode == "fa")
                } else {
                    startShadoraVpnService(plan)
                }
            }
        }
    }

    suspend fun disconnect(reason: String = "USER_DISCONNECT") = mutex.withLock {
        failoverJob?.cancel()
        attemptedFailoverIds.clear()
        stopInternal(reason)
    }

    private suspend fun startWireGuardNative(plan: EngineLaunchPlan, isPersian: Boolean) {
        try {
            val wgConfig = WgConfig.parse(BufferedReader(StringReader(plan.compiledConfig)))
            val backend = wireGuardBackend ?: GoBackend(appContext).also { wireGuardBackend = it }
            val tunnel = ShadoraWgTunnel("shadora_wg0")
            activeWireGuardTunnel = tunnel
            withContext(Dispatchers.IO) {
                backend.setState(tunnel, Tunnel.State.UP, wgConfig)
            }
            attemptedFailoverIds.clear()
            sessionStartMillis = System.currentTimeMillis()
            val networkName = detectUnderlyingNetworkName()
            updateConnectionState(
                ConnectionState.Connected(
                    serverId = plan.serverId,
                    serverName = plan.serverName,
                    protocol = plan.protocol,
                    engineId = plan.engineId,
                    engineModeLabel = if (isPersian) plan.engineModeLabelFa else plan.engineModeLabelEn,
                    isFullNativeTunnel = true,
                    startedAtMillis = sessionStartMillis,
                    localAddress = wgConfig.`interface`.addresses.firstOrNull()?.toString() ?: "10.0.0.2/32",
                    dnsServers = listOf(plan.primaryDns, plan.secondaryDns).filter { it.isNotBlank() },
                    underlyingNetwork = networkName
                )
            )
            startTelemetryLoop(useWireGuardStats = true)
        } catch (e: Throwable) {
            SafeLogger.e(TAG, "WireGuard GoBackend start failed", e)
            val failedId = plan.serverId
            activeWireGuardTunnel = null
            activePlan = null
            updateConnectionState(
                ConnectionState.Error(
                    code = "WG_START_FAILED",
                    messageFa = "راه‌اندازی موتور بومی WireGuard با خطا مواجه شد: ${SafeLogger.sanitize(e.message.orEmpty())}",
                    messageEn = "Native WireGuard startup failed: ${SafeLogger.sanitize(e.message.orEmpty())}"
                )
            )
            scheduleAutoFailoverIfEligible(failedId, "WG_START_FAILED")
        }
    }

    private fun startShadoraVpnService(plan: EngineLaunchPlan) {
        val intent = Intent(appContext, ShadoraVpnService::class.java).apply {
            action = ShadoraVpnService.ACTION_START
            putExtra(ShadoraVpnService.EXTRA_SERVER_ID, plan.serverId)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        } catch (e: Throwable) {
            SafeLogger.e(TAG, "Failed to start ShadoraVpnService", e)
            activePlan = null
            updateConnectionState(
                ConnectionState.Error(
                    code = "VPN_SERVICE_START_FAILED",
                    messageFa = "اجرای سرویس VPN اندروید ناموفق بود.",
                    messageEn = "Failed to start Android VpnService."
                )
            )
        }
    }

    internal fun onVpnServiceConnected(
        plan: EngineLaunchPlan,
        localAddress: String,
        dnsServers: List<String>,
        isPersian: Boolean
    ) {
        attemptedFailoverIds.clear()
        sessionStartMillis = System.currentTimeMillis()
        updateConnectionState(
            ConnectionState.Connected(
                serverId = plan.serverId,
                serverName = plan.serverName,
                protocol = plan.protocol,
                engineId = plan.engineId,
                engineModeLabel = if (isPersian) plan.engineModeLabelFa else plan.engineModeLabelEn,
                isFullNativeTunnel = plan.isFullNativeTunnel,
                startedAtMillis = sessionStartMillis,
                localAddress = localAddress,
                dnsServers = dnsServers,
                underlyingNetwork = detectUnderlyingNetworkName()
            )
        )
        startTelemetryLoop(useWireGuardStats = false)
    }

    internal fun onVpnServiceNetworkChanged(networkLabel: String) {
        val current = _connectionState.value
        if (current is ConnectionState.Connected) {
            updateConnectionState(current.copy(underlyingNetwork = networkLabel))
        }
    }

    internal fun onVpnServiceReconnecting(attempt: Int, reason: String) {
        val plan = activePlan ?: return
        updateConnectionState(
            ConnectionState.Reconnecting(
                serverId = plan.serverId,
                serverName = plan.serverName,
                protocol = plan.protocol,
                attempt = attempt,
                reason = reason
            )
        )
    }

    internal fun onVpnServiceError(code: String, messageFa: String, messageEn: String) {
        val failedServerId = activePlan?.serverId ?: -1L
        stopTelemetryAndRecord("ERROR_$code")
        activePlan = null
        updateConnectionState(ConnectionState.Error(code, messageFa, messageEn))
        if (failedServerId > 0L && code in FAILOVER_ELIGIBLE_ERROR_CODES) {
            scheduleAutoFailoverIfEligible(failedServerId, code)
        }
    }

    internal fun onVpnServiceStopped(reason: String) {
        stopTelemetryAndRecord(reason)
        activePlan = null
        if (_connectionState.value !is ConnectionState.Error) {
            updateConnectionState(ConnectionState.Disconnected)
        }
    }

    /**
     * Auto-Failover Server Switcher:
     * When enabled and a server fails due to unreachability, automatically probes peer servers
     * in the same subscription (or all saved servers), picks the fastest reachable backup,
     * updates the selected server, and connects (bounded to MAX_FAILOVER_HOPS).
     */
    private fun scheduleAutoFailoverIfEligible(failedServerId: Long, errorCode: String) {
        if (attemptedFailoverIds.size > MAX_FAILOVER_HOPS) return
        failoverJob?.cancel()
        failoverJob = scope.launch {
            val settings = runCatching { repository.getAppSettings() }.getOrNull() ?: return@launch
            if (!settings.autoFailoverEnabled) return@launch
            val isFa = settings.languageCode == "fa"

            val failedServer = repository.getServerById(failedServerId)
            val allServers = repository.getAllServers()
            val sameSubCandidates = if (failedServer?.subscriptionId != null) {
                allServers.filter {
                    it.subscriptionId == failedServer.subscriptionId && it.id !in attemptedFailoverIds
                }
            } else {
                emptyList()
            }
            val candidates = sameSubCandidates.ifEmpty {
                allServers.filter { it.id !in attemptedFailoverIds }
            }.take(12)

            if (candidates.isEmpty()) return@launch

            SafeLogger.i(TAG, "Auto-Failover triggered after $errorCode; testing ${candidates.size} backup server(s)")
            val measured = mutableMapOf<Long, Int>()
            latencyTester.measureBatch(candidates.map { Triple(it.id, it.host, it.port) }) { id, rtt ->
                measured[id] = rtt
                repository.updateServerLatency(id, rtt)
            }

            val bestBackup = candidates
                .filter { (measured[it.id] ?: -1) > 0 }
                .minByOrNull { measured[it.id] ?: Int.MAX_VALUE }
                ?: return@launch

            val bestRtt = measured[bestBackup.id] ?: 0
            repository.updateAppSettings { it.copy(selectedServerId = bestBackup.id) }
            val notice = if (isFa) {
                "تعویض خودکار سرور به ${bestBackup.name} (${bestRtt}ms)"
            } else {
                "Auto-Failover switching to ${bestBackup.name} (${bestRtt}ms)"
            }
            SafeLogger.i(TAG, "Auto-Failover selected server #${bestBackup.id} (${bestRtt}ms)")
            _failoverMessages.tryEmit(notice)
            connect(bestBackup.id, isFailoverAttempt = true)
        }
    }

    private suspend fun stopInternal(reason: String) {
        updateConnectionState(ConnectionState.Disconnecting)
        stopTelemetryAndRecord(reason)

        // Stop WireGuard native tunnel if running
        val wgTunnel = activeWireGuardTunnel
        val wgBackend = wireGuardBackend
        if (wgTunnel != null && wgBackend != null) {
            try {
                withContext(Dispatchers.IO) {
                    wgBackend.setState(wgTunnel, Tunnel.State.DOWN, null)
                }
            } catch (e: Throwable) {
                SafeLogger.w(TAG, "Error stopping WireGuard tunnel: ${e.message}")
            }
            activeWireGuardTunnel = null
        }

        // Stop ShadoraVpnService if running
        try {
            val stopIntent = Intent(appContext, ShadoraVpnService::class.java).apply {
                action = ShadoraVpnService.ACTION_STOP
            }
            appContext.startService(stopIntent)
        } catch (_: Throwable) {
            // Service may not be running
        }

        activePlan = null
        _trafficSnapshot.value = TrafficSnapshot()
        updateConnectionState(ConnectionState.Disconnected)
    }

    private fun startTelemetryLoop(useWireGuardStats: Boolean) {
        telemetryJob?.cancel()
        val baseRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        val baseTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        var prevRx = baseRx
        var prevTx = baseTx
        val rxHist = ArrayDeque<Float>(24)
        val txHist = ArrayDeque<Float>(24)

        var xrayTotalRx = 0L
        var xrayTotalTx = 0L

        telemetryJob = scope.launch {
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                delay(1000L)
                val elapsed = ((System.currentTimeMillis() - sessionStartMillis) / 1000L).coerceAtLeast(0L)
                val (totalRx, totalTx, rxSpeed, txSpeed) = if (useWireGuardStats) {
                    val stats = try {
                        activeWireGuardTunnel?.let { wireGuardBackend?.getStatistics(it) }
                    } catch (_: Throwable) {
                        null
                    }
                    val curRx = stats?.totalRx() ?: 0L
                    val curTx = stats?.totalTx() ?: 0L
                    val drx = (curRx - prevRx).coerceAtLeast(0L)
                    val dtx = (curTx - prevTx).coerceAtLeast(0L)
                    prevRx = curRx
                    prevTx = curTx
                    Quad(curRx, curTx, drx, dtx)
                } else if (XrayNativeBridge.isRunning()) {
                    val drx = XrayNativeBridge.queryStats("proxy", "downlink")
                    val dtx = XrayNativeBridge.queryStats("proxy", "uplink")
                    if (drx >= 0L && dtx >= 0L) {
                        xrayTotalRx += drx
                        xrayTotalTx += dtx
                        Quad(xrayTotalRx, xrayTotalTx, drx, dtx)
                    } else {
                        val curRx = TrafficStats.getTotalRxBytes().coerceAtLeast(baseRx)
                        val curTx = TrafficStats.getTotalTxBytes().coerceAtLeast(baseTx)
                        val fallbackRx = (curRx - prevRx).coerceAtLeast(0L)
                        val fallbackTx = (curTx - prevTx).coerceAtLeast(0L)
                        prevRx = curRx
                        prevTx = curTx
                        Quad(curRx - baseRx, curTx - baseTx, fallbackRx, fallbackTx)
                    }
                } else {
                    val curRx = TrafficStats.getTotalRxBytes().coerceAtLeast(baseRx)
                    val curTx = TrafficStats.getTotalTxBytes().coerceAtLeast(baseTx)
                    val drx = (curRx - prevRx).coerceAtLeast(0L)
                    val dtx = (curTx - prevTx).coerceAtLeast(0L)
                    prevRx = curRx
                    prevTx = curTx
                    Quad(curRx - baseRx, curTx - baseTx, drx, dtx)
                }

                if (rxHist.size >= 24) rxHist.removeFirst()
                if (txHist.size >= 24) txHist.removeFirst()
                rxHist.addLast(rxSpeed.toFloat())
                txHist.addLast(txSpeed.toFloat())

                _trafficSnapshot.value = TrafficSnapshot(
                    rxBytesPerSec = rxSpeed,
                    txBytesPerSec = txSpeed,
                    totalRxBytes = totalRx,
                    totalTxBytes = totalTx,
                    elapsedSeconds = elapsed,
                    rxHistory = rxHist.toList(),
                    txHistory = txHist.toList()
                )
            }
        }
    }

    private fun stopTelemetryAndRecord(reason: String) {
        telemetryJob?.cancel()
        telemetryJob = null
        val plan = activePlan
        val snap = _trafficSnapshot.value
        val started = sessionStartMillis
        if (plan != null && started > 0L) {
            sessionStartMillis = 0L
            scope.launch {
                try {
                    repository.recordConnectionSession(
                        ConnectionHistoryEntity(
                            serverId = plan.serverId,
                            serverName = plan.serverName,
                            protocol = plan.protocol.name,
                            engineId = plan.engineId,
                            startedAt = started,
                            endedAt = System.currentTimeMillis(),
                            bytesDown = snap.totalRxBytes,
                            bytesUp = snap.totalTxBytes,
                            disconnectReason = reason
                        )
                    )
                } catch (_: Throwable) {
                    // Ignore DB error during teardown
                }
            }
        }
    }

    fun detectUnderlyingNetworkName(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "Unknown"
        val active = cm.activeNetwork ?: return "Offline"
        val caps = cm.getNetworkCapabilities(active) ?: return "Offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Network"
        }
    }

    private data class Quad(val a: Long, val b: Long, val c: Long, val d: Long)

    private class ShadoraWgTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) {
            SafeLogger.d(TAG, "WireGuard tunnel state -> $newState")
        }
    }

    companion object {
        private const val TAG = "ShadoraEngineManager"
        private const val MAX_FAILOVER_HOPS = 2
        private val FAILOVER_ELIGIBLE_ERROR_CODES = setOf(
            "SERVER_UNREACHABLE",
            "WG_START_FAILED"
        )
    }
}
