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
import com.example.vpnservice.OpenVpn3NativeBridge
import com.example.vpnservice.ServerLatencyTester
import com.example.vpnservice.ShadoraQuickSettingsTileService
import com.example.vpnservice.ShadoraVpnService
import com.example.vpnservice.XrayNativeBridge
import com.example.widget.ShadoraVpnWidgetProvider
import org.amnezia.awg.backend.GoBackend as AwgGoBackend
import org.amnezia.awg.backend.NoopTunnelActionHandler
import org.amnezia.awg.backend.Tunnel as AwgTunnel
import org.amnezia.awg.config.Config as AwgConfig
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
 * manages real Android VpnService (sing-box / OpenVPN 3 / optional Xray) and native
 * WireGuard & AmneziaWG GoBackend lifecycles, supports Auto-Failover server switching,
 * updates the Home Screen Widget & Quick Settings Tile live, tracks traffic telemetry,
 * and persists ConnectionHistory into Room.
 */
class EngineManager(
    private val appContext: Context,
    private val repository: ShadoraRepository,
    private val latencyTester: ServerLatencyTester = ServerLatencyTester()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val adapters: List<VpnEngineAdapter> = listOf(
        XrayEngineAdapter(),
        WireGuardEngineAdapter(),
        AmneziaWgEngineAdapter(),
        OpenVpnEngineAdapter(),
        MieruEngineAdapter(),
        CloakEngineAdapter(),
        LightwayEngineAdapter()
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
    private var amneziaWgBackend: AwgGoBackend? = null

    @Volatile
    private var activeAmneziaWgTunnel: ShadoraAwgTunnel? = null

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
                val stageText = when (plan.engineId) {
                    "wireguard", "amneziawg" -> "Initializing libam-go…"
                    "openvpn" -> "Initializing OpenVPN 3…"
                    else -> "Starting sing-box core…"
                }
                updateConnectionState(
                    ConnectionState.Connecting(
                        serverId = plan.serverId,
                        serverName = plan.serverName,
                        protocol = plan.protocol,
                        stage = stageText
                    )
                )

                val isPersian = appSettings.languageCode == "fa"
                when (plan.engineId) {
                    "wireguard", "amneziawg" -> startAmneziaWgNative(plan, isPersian)
                    else -> startShadoraVpnService(plan)
                }
            }
        }
    }

    suspend fun disconnect(reason: String = "USER_DISCONNECT") = mutex.withLock {
        failoverJob?.cancel()
        attemptedFailoverIds.clear()
        stopInternal(reason)
    }

    private suspend fun startAmneziaWgNative(plan: EngineLaunchPlan, isPersian: Boolean) {
        try {
            val awgConfig = AwgConfig.parse(BufferedReader(StringReader(plan.compiledConfig)))
            val backend = amneziaWgBackend
                ?: AwgGoBackend(appContext, NoopTunnelActionHandler()).also { amneziaWgBackend = it }
            val tunnel = ShadoraAwgTunnel("shadora_awg0", preferIpv4 = !plan.ipv6Enabled)
            activeAmneziaWgTunnel = tunnel
            withContext(Dispatchers.IO) {
                backend.setState(tunnel, AwgTunnel.State.UP, awgConfig)
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
                    localAddress = awgConfig.`interface`.addresses.firstOrNull()?.toString() ?: "10.77.0.2/32",
                    dnsServers = listOf(plan.primaryDns, plan.secondaryDns).filter { it.isNotBlank() },
                    underlyingNetwork = networkName
                )
            )
            startTelemetryLoop(TelemetrySource.AMNEZIA_WG)
        } catch (e: Throwable) {
            SafeLogger.e(TAG, "AmneziaWG GoBackend start failed", e)
            val failedId = plan.serverId
            activeAmneziaWgTunnel = null
            activePlan = null
            updateConnectionState(
                ConnectionState.Error(
                    code = "AWG_START_FAILED",
                    messageFa = "راه‌اندازی موتور بومی AmneziaWG با خطا مواجه شد: ${SafeLogger.sanitize(e.message.orEmpty())}",
                    messageEn = "Native AmneziaWG startup failed: ${SafeLogger.sanitize(e.message.orEmpty())}"
                )
            )
            scheduleAutoFailoverIfEligible(failedId, "AWG_START_FAILED")
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
        startTelemetryLoop(TelemetrySource.VPN_SERVICE)
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

        // Stop WireGuard / AmneziaWG native tunnel if running
        val awgTunnel = activeAmneziaWgTunnel
        val awgBackend = amneziaWgBackend
        if (awgTunnel != null && awgBackend != null) {
            try {
                withContext(Dispatchers.IO) {
                    awgBackend.setState(awgTunnel, AwgTunnel.State.DOWN, null)
                }
            } catch (e: Throwable) {
                SafeLogger.w(TAG, "Error stopping AmneziaWG tunnel: ${e.message}")
            }
            activeAmneziaWgTunnel = null
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

    private enum class TelemetrySource {
        AMNEZIA_WG,
        VPN_SERVICE
    }

    private fun startTelemetryLoop(source: TelemetrySource) {
        telemetryJob?.cancel()
        val baseRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        val baseTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        var prevRx = if (source == TelemetrySource.VPN_SERVICE) baseRx else 0L
        var prevTx = if (source == TelemetrySource.VPN_SERVICE) baseTx else 0L
        var prevOvpnRx = 0L
        var prevOvpnTx = 0L
        val rxHist = ArrayDeque<Float>(24)
        val txHist = ArrayDeque<Float>(24)

        var xrayTotalRx = 0L
        var xrayTotalTx = 0L

        telemetryJob = scope.launch {
            while (isActive && _connectionState.value is ConnectionState.Connected) {
                delay(1000L)
                val elapsed = ((System.currentTimeMillis() - sessionStartMillis) / 1000L).coerceAtLeast(0L)
                val (totalRx, totalTx, rxSpeed, txSpeed) = when (source) {
                    TelemetrySource.AMNEZIA_WG -> {
                        val stats = runCatching {
                            activeAmneziaWgTunnel?.let { amneziaWgBackend?.getStatistics(it) }
                        }.getOrNull()
                        val curRx = stats?.totalRx() ?: 0L
                        val curTx = stats?.totalTx() ?: 0L
                        val drx = (curRx - prevRx).coerceAtLeast(0L)
                        val dtx = (curTx - prevTx).coerceAtLeast(0L)
                        prevRx = curRx
                        prevTx = curTx
                        Quad(curRx, curTx, drx, dtx)
                    }

                    TelemetrySource.VPN_SERVICE -> {
                        val ovpnStats = if (OpenVpn3NativeBridge.isRunning()) {
                            OpenVpn3NativeBridge.queryStats()
                        } else {
                            null
                        }
                        val xrayDelta = if (ovpnStats == null && XrayNativeBridge.isRunning()) {
                            XrayNativeBridge.queryAllOutboundTrafficStats()
                        } else {
                            null
                        }

                        when {
                            ovpnStats != null -> {
                                val (curRx, curTx) = ovpnStats
                                val drx = (curRx - prevOvpnRx).coerceAtLeast(0L)
                                val dtx = (curTx - prevOvpnTx).coerceAtLeast(0L)
                                prevOvpnRx = curRx
                                prevOvpnTx = curTx
                                Quad(curRx, curTx, drx, dtx)
                            }
                            xrayDelta != null -> {
                                val (drx, dtx) = xrayDelta
                                xrayTotalRx += drx
                                xrayTotalTx += dtx
                                Quad(xrayTotalRx, xrayTotalTx, drx, dtx)
                            }
                            else -> {
                                val curRx = TrafficStats.getTotalRxBytes().coerceAtLeast(baseRx)
                                val curTx = TrafficStats.getTotalTxBytes().coerceAtLeast(baseTx)
                                val drx = (curRx - prevRx).coerceAtLeast(0L)
                                val dtx = (curTx - prevTx).coerceAtLeast(0L)
                                prevRx = curRx
                                prevTx = curTx
                                Quad(curRx - baseRx, curTx - baseTx, drx, dtx)
                            }
                        }
                    }
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

    private class ShadoraAwgTunnel(
        private val tunnelName: String,
        private val preferIpv4: Boolean
    ) : AwgTunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: AwgTunnel.State) {
            SafeLogger.d(TAG, "AmneziaWG tunnel state -> $newState")
        }
        override fun isIpv4ResolutionPreferred(): Boolean = preferIpv4
        override fun isMetered(): Boolean = false
    }

    companion object {
        private const val TAG = "ShadoraEngineManager"
        private const val MAX_FAILOVER_HOPS = 2
        private val FAILOVER_ELIGIBLE_ERROR_CODES = setOf(
            "SERVER_UNREACHABLE",
            "WG_START_FAILED",
            "AWG_START_FAILED",
            "SINGBOX_CORE_START_FAILED",
            "OVPN_CONNECT_FAILED"
        )
    }
}
