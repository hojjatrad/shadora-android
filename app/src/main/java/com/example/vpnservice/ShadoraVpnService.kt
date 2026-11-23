package com.example.vpnservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.ShadoraApplication
import com.example.core.model.VpnProtocol
import com.example.core.util.FormatUtils
import com.example.enginemanager.EngineLaunchPlan
import com.example.security.SafeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real Android VpnService for SHADORA:
 * - Hosts bundled native `libbox.so` (sing-box 1.12+) for VLESS (XTLS-Reality/Vision/TLS/WS/gRPC/HTTPUpgrade),
 *   VMess, Trojan, Shadowsocks, and TUIC v5
 * - Hosts bundled native `libovpn3.so` (OpenVPN 3 C++ Core) for `.ovpn` tunnels
 * - Also supports optional `libv2ray.aar` (Xray-core) drop-in builds
 * - Supports Split Tunneling, Kill Switch, IPv6 leak guard, Always-On VPN, Wi-Fi/Cellular handoff,
 *   and live ↓/↑ speed notifications
 */
class ShadoraVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var notificationUpdateJob: Job? = null
    private var reconnectJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentPlan: EngineLaunchPlan? = null

    @Volatile
    private var hadNetworkLoss: Boolean = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> startTunnelSession()
            ACTION_STOP -> {
                stopTunnelSession("USER_DISCONNECT")
                stopSelf()
            }
            null, SERVICE_INTERFACE -> {
                // Triggered by Android System Always-On VPN (or process restart)
                startAlwaysOnSession()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAlwaysOnSession() {
        val app = application as? ShadoraApplication ?: run {
            stopSelf()
            return
        }
        if (app.engineManager.getCurrentLaunchPlan() != null) {
            startTunnelSession()
            return
        }
        ensureNotificationChannel()
        startForegroundCompat(
            buildNotification(
                title = getString(R.string.app_name),
                body = getString(R.string.vpn_status_connecting)
            )
        )
        serviceScope.launch {
            val settings = app.repository.getAppSettings()
            val targetId = if (settings.selectedServerId > 0L) {
                settings.selectedServerId
            } else {
                app.repository.getAllServers().firstOrNull()?.id ?: -1L
            }
            if (targetId <= 0L) {
                SafeLogger.w(TAG, "Always-On VPN triggered, but no server is configured yet")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            SafeLogger.i(TAG, "Always-On VPN auto-starting server #$targetId")
            app.engineManager.connect(targetId)
        }
    }

    private fun startTunnelSession() {
        val app = application as? ShadoraApplication ?: run {
            stopSelf()
            return
        }
        val engineManager = app.engineManager
        val plan = engineManager.getCurrentLaunchPlan()
        if (plan == null) {
            engineManager.onVpnServiceError(
                code = "MISSING_PLAN",
                messageFa = "اطلاعات پیکربندی برای راه‌اندازی سرویس VPN موجود نیست.",
                messageEn = "Missing launch plan for VPN service."
            )
            stopSelf()
            return
        }
        currentPlan = plan
        hadNetworkLoss = false

        ensureNotificationChannel()
        val connectingNotification = buildNotification(
            title = getString(R.string.app_name),
            body = "${plan.serverName} • ${getString(R.string.vpn_status_connecting)}"
        )
        startForegroundCompat(connectingNotification)

        serviceScope.launch {
            val settings = app.repository.getAppSettings()
            val isPersian = settings.languageCode == "fa"
            val dnsList = listOf(plan.primaryDns, plan.secondaryDns).filter { it.isNotBlank() }
                .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }

            // Step 1: Protected TCP reachability handshake for TCP-based proxy protocols
            // (Skipped for UDP/QUIC protocols so UDP servers are not falsely rejected)
            val isUdpProtocol = plan.protocol in setOf(
                VpnProtocol.TUIC,
                VpnProtocol.HYSTERIA2,
                VpnProtocol.OPENVPN,
                VpnProtocol.LIGHTWAY
            )
            val reachableMs = if (isUdpProtocol) {
                0
            } else {
                probeRemoteServerProtected(plan.remoteHost, plan.remotePort)
            }

            if (!isUdpProtocol && reachableMs < 0) {
                SafeLogger.w(TAG, "Remote server unreachable on ${plan.remoteHost}:${plan.remotePort}")
                engineManager.onVpnServiceError(
                    code = "SERVER_UNREACHABLE",
                    messageFa = "سرور ${plan.remoteHost}:${plan.remotePort} پاسخ نداد. اتصال اینترنت یا آدرس سرور را بررسی کنید.",
                    messageEn = "Remote server ${plan.remoteHost}:${plan.remotePort} did not respond."
                )
                stopTunnelSession("UNREACHABLE")
                stopSelf()
                return@launch
            }

            // Step 2: Ensure a real native forwarding engine is available (never black-hole device traffic)
            if (!plan.isFullNativeTunnel) {
                val missingBin = when (plan.engineId) {
                    "mieru" -> MieruNativeBridge.BINARY_NAME
                    "cloak" -> CloakNativeBridge.BINARY_NAME
                    "lightway" -> LightwayNativeBridge.BINARY_NAME
                    else -> "${plan.engineId}-native"
                }
                SafeLogger.w(TAG, "Native engine '$missingBin' missing for ${plan.engineId}; aborting TUN capture.")
                engineManager.onVpnServiceError(
                    code = "NATIVE_CORE_MISSING",
                    messageFa = "کانفیگ ${plan.protocol.displayName} معتبر است، اما باینری بومی ($missingBin) در این APK وجود ندارد. برای ساخت خودکار APK دارای $missingBin، ورک‌فلو GitHub Actions پروژه را اجرا کنید.",
                    messageEn = "${plan.protocol.displayName} config is valid, but native binary ($missingBin) is not bundled in this build. Run the project's GitHub Actions workflow to auto-compile and bundle $missingBin."
                )
                stopTunnelSession("NATIVE_CORE_MISSING")
                stopSelf()
                return@launch
            }

            // Step 3A: Bundled sing-box / libbox.so native engine (VLESS, VMess, Trojan, Shadowsocks, TUIC v5)
            if (plan.engineId == "xray" && SingBoxNativeBridge.isAvailable()) {
                val result = SingBoxNativeBridge.start(
                    vpnService = this@ShadoraVpnService,
                    plan = plan,
                    bypassLanCidrs = settings.bypassLanCidrs
                )
                result.onSuccess { localAddr ->
                    registerNetworkCallback()
                    val latencyLabel = if (reachableMs > 0) " (${reachableMs}ms)" else ""
                    SafeLogger.i(TAG, "Native sing-box tunnel active for ${plan.serverName} (${plan.protocol.displayName})$latencyLabel")
                    updateForegroundNotification(
                        title = "${getString(R.string.app_name)} • ${plan.protocol.displayName}",
                        body = "${plan.serverName}$latencyLabel"
                    )
                    engineManager.onVpnServiceConnected(
                        plan = plan,
                        localAddress = localAddr,
                        dnsServers = dnsList,
                        isPersian = isPersian
                    )
                    startLiveNotificationUpdates(app, plan, settings.notificationsEnabled)
                }.onFailure { err ->
                    val detail = SafeLogger.sanitize(err.message.orEmpty())
                    engineManager.onVpnServiceError(
                        code = "SINGBOX_CORE_START_FAILED",
                        messageFa = "راه‌اندازی هسته بومی sing-box با خطا مواجه شد: $detail",
                        messageEn = "Failed to start native sing-box core: $detail"
                    )
                    stopTunnelSession("SINGBOX_CORE_FAILED")
                    stopSelf()
                }
                return@launch
            }

            // Step 3B: Bundled OpenVPN 3 C++ native engine (`libovpn3.so`)
            if (plan.engineId == "openvpn" && OpenVpn3NativeBridge.isAvailable()) {
                val started = OpenVpn3NativeBridge.start(
                    vpnService = this@ShadoraVpnService,
                    plan = plan,
                    bypassLanCidrs = settings.bypassLanCidrs,
                    onConnected = { localIp ->
                        registerNetworkCallback()
                        SafeLogger.i(TAG, "Native OpenVPN 3 tunnel connected (${plan.serverName}, $localIp)")
                        updateForegroundNotification(
                            title = "${getString(R.string.app_name)} • OpenVPN",
                            body = plan.serverName
                        )
                        engineManager.onVpnServiceConnected(
                            plan = plan,
                            localAddress = localIp,
                            dnsServers = dnsList,
                            isPersian = isPersian
                        )
                        startLiveNotificationUpdates(app, plan, settings.notificationsEnabled)
                    },
                    onError = { code, detail ->
                        val safe = SafeLogger.sanitize(detail)
                        engineManager.onVpnServiceError(
                            code = code,
                            messageFa = "خطا در اتصال OpenVPN 3: $safe",
                            messageEn = "OpenVPN 3 connection failed: $safe"
                        )
                        stopTunnelSession(code)
                        stopSelf()
                    }
                )
                if (!started) {
                    stopTunnelSession("OVPN_INIT_FAILED")
                    stopSelf()
                }
                return@launch
            }

            // Step 3B-2: Bundled Mieru (`libmieru.so`) chained into sing-box TUN
            if (plan.engineId == "mieru" && MieruNativeBridge.isAvailable(this@ShadoraVpnService)) {
                val env = runCatching { org.json.JSONObject(plan.compiledConfig) }.getOrNull()
                val mieruCfg = env?.optString("engineConfig").orEmpty()
                val tunChainCfg = env?.optString("tunChainConfig").orEmpty()
                if (mieruCfg.isBlank() || tunChainCfg.isBlank() ||
                    !MieruNativeBridge.start(this@ShadoraVpnService, mieruCfg)
                ) {
                    engineManager.onVpnServiceError(
                        code = "MIERU_START_FAILED",
                        messageFa = "راه‌اندازی هسته بومی Mieru ناموفق بود.",
                        messageEn = "Failed to start native Mieru core."
                    )
                    stopTunnelSession("MIERU_FAILED")
                    stopSelf()
                    return@launch
                }
                val chainedPlan = plan.copy(compiledConfig = tunChainCfg)
                SingBoxNativeBridge.start(this@ShadoraVpnService, chainedPlan, settings.bypassLanCidrs)
                    .onSuccess { localAddr ->
                        registerNetworkCallback()
                        updateForegroundNotification(
                            title = "${getString(R.string.app_name)} • Mieru",
                            body = plan.serverName
                        )
                        engineManager.onVpnServiceConnected(plan, localAddr, dnsList, isPersian)
                        startLiveNotificationUpdates(app, plan, settings.notificationsEnabled)
                    }
                    .onFailure { err ->
                        engineManager.onVpnServiceError(
                            code = "MIERU_TUN_CHAIN_FAILED",
                            messageFa = "خطا در اتصال تونل Mieru: ${SafeLogger.sanitize(err.message.orEmpty())}",
                            messageEn = "Failed chaining Mieru into TUN: ${SafeLogger.sanitize(err.message.orEmpty())}"
                        )
                        stopTunnelSession("MIERU_TUN_FAILED")
                        stopSelf()
                    }
                return@launch
            }

            // Step 3B-3: Bundled Cloak (`libckclient.so`) chained into sing-box TUN
            if (plan.engineId == "cloak" && CloakNativeBridge.isAvailable(this@ShadoraVpnService)) {
                val env = runCatching { org.json.JSONObject(plan.compiledConfig) }.getOrNull()
                val cloakCfg = env?.optString("engineConfig").orEmpty()
                val tunChainCfg = env?.optString("tunChainConfig").orEmpty()
                if (cloakCfg.isBlank() || tunChainCfg.isBlank() ||
                    !CloakNativeBridge.start(this@ShadoraVpnService, cloakCfg, plan.remoteHost, plan.remotePort)
                ) {
                    engineManager.onVpnServiceError(
                        code = "CLOAK_START_FAILED",
                        messageFa = "راه‌اندازی پلاگین بومی Cloak ناموفق بود.",
                        messageEn = "Failed to start native Cloak ck-client."
                    )
                    stopTunnelSession("CLOAK_FAILED")
                    stopSelf()
                    return@launch
                }
                val chainedPlan = plan.copy(compiledConfig = tunChainCfg)
                SingBoxNativeBridge.start(this@ShadoraVpnService, chainedPlan, settings.bypassLanCidrs)
                    .onSuccess { localAddr ->
                        registerNetworkCallback()
                        updateForegroundNotification(
                            title = "${getString(R.string.app_name)} • Cloak",
                            body = plan.serverName
                        )
                        engineManager.onVpnServiceConnected(plan, localAddr, dnsList, isPersian)
                        startLiveNotificationUpdates(app, plan, settings.notificationsEnabled)
                    }
                    .onFailure { err ->
                        engineManager.onVpnServiceError(
                            code = "CLOAK_TUN_CHAIN_FAILED",
                            messageFa = "خطا در اتصال تونل Cloak: ${SafeLogger.sanitize(err.message.orEmpty())}",
                            messageEn = "Failed chaining Cloak into TUN: ${SafeLogger.sanitize(err.message.orEmpty())}"
                        )
                        stopTunnelSession("CLOAK_TUN_FAILED")
                        stopSelf()
                    }
                return@launch
            }

            // Step 3B-4: Bundled Lightway AAR (`liblightway.so`)
            if (plan.engineId == "lightway" && LightwayNativeBridge.isAvailable(this@ShadoraVpnService)) {
                val established = establishTunInterface(plan, settings.bypassLanCidrs)
                if (established == null || !LightwayNativeBridge.start(this@ShadoraVpnService, plan.compiledConfig, established.fd)) {
                    runCatching { established?.close() }
                    engineManager.onVpnServiceError(
                        code = "LIGHTWAY_START_FAILED",
                        messageFa = "راه‌اندازی هسته بومی Lightway ناموفق بود.",
                        messageEn = "Failed to start native Lightway core."
                    )
                    stopTunnelSession("LIGHTWAY_FAILED")
                    stopSelf()
                    return@launch
                }
                tunInterface = established
                registerNetworkCallback()
                updateForegroundNotification(
                    title = "${getString(R.string.app_name)} • Lightway",
                    body = plan.serverName
                )
                engineManager.onVpnServiceConnected(plan, TUN_IPV4_ADDRESS, dnsList, isPersian)
                startLiveNotificationUpdates(app, plan, settings.notificationsEnabled)
                return@launch
            }

            // Step 3C: Optional drop-in `libv2ray.aar` Xray-core fallback
            if (plan.engineId == "xray" && XrayNativeBridge.isAvailable()) {
                val established = establishTunInterface(plan, settings.bypassLanCidrs)
                if (established == null) {
                    engineManager.onVpnServiceError(
                        code = "TUN_ESTABLISH_FAILED",
                        messageFa = "ایجاد رابط TUN اندروید ناموفق بود. مجوز VPN را دوباره بررسی کنید.",
                        messageEn = "Failed to establish Android TUN interface."
                    )
                    stopTunnelSession("TUN_FAILED")
                    stopSelf()
                    return@launch
                }
                tunInterface = established

                val coreStarted = XrayNativeBridge.start(this@ShadoraVpnService, plan.compiledConfig, established.fd)
                if (!coreStarted) {
                    engineManager.onVpnServiceError(
                        code = "XRAY_CORE_START_FAILED",
                        messageFa = "راه‌اندازی هسته بومی Xray با خطا مواجه شد. تنظیمات کانفیگ را بررسی کنید.",
                        messageEn = "Failed to start native Xray core loop."
                    )
                    stopTunnelSession("XRAY_CORE_FAILED")
                    stopSelf()
                    return@launch
                }

                registerNetworkCallback()
                SafeLogger.i(TAG, "Native libv2ray TUN active for ${plan.serverName} (${plan.protocol.displayName}, ${reachableMs}ms)")
                updateForegroundNotification(
                    title = "${getString(R.string.app_name)} • ${plan.protocol.displayName}",
                    body = "${plan.serverName} (${reachableMs}ms)"
                )
                engineManager.onVpnServiceConnected(
                    plan = plan,
                    localAddress = TUN_IPV4_ADDRESS,
                    dnsServers = dnsList,
                    isPersian = isPersian
                )
                startLiveNotificationUpdates(app, plan, settings.notificationsEnabled)
                return@launch
            }

            // Fallback safety net: never leave an unbacked TUN active
            engineManager.onVpnServiceError(
                code = "NATIVE_CORE_UNAVAILABLE",
                messageFa = "هسته بومی مناسب برای ${plan.protocol.displayName} در دسترس نیست.",
                messageEn = "No native forwarding engine available for ${plan.protocol.displayName}."
            )
            stopTunnelSession("NO_ENGINE")
            stopSelf()
        }
    }

    private fun isAnyNativeTunnelActive(): Boolean =
        SingBoxNativeBridge.isRunning() || OpenVpn3NativeBridge.isRunning() || tunInterface != null

    private fun startLiveNotificationUpdates(
        app: ShadoraApplication,
        plan: EngineLaunchPlan,
        showLiveSpeed: Boolean
    ) {
        notificationUpdateJob?.cancel()
        if (!showLiveSpeed) return
        notificationUpdateJob = serviceScope.launch {
            while (isActive && isAnyNativeTunnelActive()) {
                delay(2000L)
                val snap = app.engineManager.trafficSnapshot.value
                val down = FormatUtils.formatSpeed(snap.rxBytesPerSec)
                val up = FormatUtils.formatSpeed(snap.txBytesPerSec)
                updateForegroundNotification(
                    title = "${getString(R.string.app_name)} • ${plan.serverName}",
                    body = "↓ $down  •  ↑ $up"
                )
            }
        }
    }

    private fun triggerAutoReconnect(networkLabel: String) {
        val app = application as? ShadoraApplication ?: return
        val plan = currentPlan ?: return
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            val settings = app.repository.getAppSettings()
            if (!settings.autoConnect) return@launch
            val isPersian = settings.languageCode == "fa"

            for (attempt in 1..3) {
                if (!isAnyNativeTunnelActive() && currentPlan == null) return@launch
                app.engineManager.onVpnServiceReconnecting(attempt, networkLabel)
                SafeLogger.i(TAG, "Auto-reconnect attempt #$attempt on $networkLabel")
                delay(attempt * 1200L)

                if (SingBoxNativeBridge.isRunning()) {
                    SingBoxNativeBridge.resetNetwork()
                }
                val isUdpProtocol = plan.protocol == VpnProtocol.TUIC || plan.protocol == VpnProtocol.OPENVPN
                val ms = if (isUdpProtocol) 1 else probeRemoteServerProtected(plan.remoteHost, plan.remotePort)
                if (ms > 0) {
                    val dnsList = listOf(plan.primaryDns, plan.secondaryDns).filter { it.isNotBlank() }
                        .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
                    app.engineManager.onVpnServiceConnected(
                        plan = plan,
                        localAddress = if (SingBoxNativeBridge.isRunning()) SingBoxNativeBridge.getActiveLocalAddress() else TUN_IPV4_ADDRESS,
                        dnsServers = dnsList,
                        isPersian = isPersian
                    )
                    app.engineManager.onVpnServiceNetworkChanged(networkLabel)
                    SafeLogger.i(TAG, "Auto-reconnect succeeded on $networkLabel (${ms}ms)")
                    return@launch
                }
            }
            SafeLogger.w(TAG, "Auto-reconnect failed after 3 attempts on $networkLabel")
        }
    }

    private fun establishTunInterface(plan: EngineLaunchPlan, bypassLanCidrs: Boolean): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("Shadora - ${plan.serverName}")
                .setMtu(plan.mtu.coerceIn(1280, 1500))
                .addAddress(TUN_IPV4_ADDRESS, 30)
                .addRoute("0.0.0.0", 0)
                .setBlocking(false)

            if (bypassLanCidrs && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(
                    "10.0.0.0" to 8,
                    "172.16.0.0" to 12,
                    "192.168.0.0" to 16
                ).forEach { (addr, prefix) ->
                    runCatching {
                        val ipPrefix = android.net.IpPrefix(java.net.InetAddress.getByName(addr), prefix)
                        builder.excludeRoute(ipPrefix)
                    }
                }
            }

            if (plan.ipv6Enabled) {
                builder.addAddress(TUN_IPV6_ADDRESS, 126)
                builder.addRoute("::", 0)
            } else {
                try {
                    builder.addAddress(TUN_IPV6_ADDRESS, 126)
                    builder.addRoute("::", 0)
                } catch (_: Throwable) {
                    // Device kernel may have IPv6 disabled
                }
            }

            val primaryDns = plan.primaryDns.ifBlank { "1.1.1.1" }
            runCatching { builder.addDnsServer(primaryDns) }
            if (plan.secondaryDns.isNotBlank()) {
                runCatching { builder.addDnsServer(plan.secondaryDns) }
            }

            if (!plan.killSwitchEnabled) {
                builder.allowBypass()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            when (plan.splitTunnelMode) {
                "BYPASS_SELECTED" -> {
                    runCatching { builder.addDisallowedApplication(packageName) }
                    plan.splitTunnelPackages.forEach { pkg ->
                        runCatching { builder.addDisallowedApplication(pkg) }
                    }
                }
                "ONLY_SELECTED" -> {
                    val filtered = plan.splitTunnelPackages.filter { it != packageName }
                    if (filtered.isNotEmpty()) {
                        filtered.forEach { pkg ->
                            runCatching { builder.addAllowedApplication(pkg) }
                        }
                    } else {
                        runCatching { builder.addDisallowedApplication(packageName) }
                    }
                }
                else -> {
                    runCatching { builder.addDisallowedApplication(packageName) }
                }
            }

            builder.establish()
        } catch (e: Throwable) {
            SafeLogger.e(TAG, "TUN Builder establish failed", e)
            null
        }
    }

    private fun probeRemoteServerProtected(host: String, port: Int): Int {
        val start = System.currentTimeMillis()
        val socket = Socket()
        return try {
            protect(socket)
            socket.connect(InetSocketAddress(host, port), 5000)
            (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
        } catch (_: Throwable) {
            -1
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    setUnderlyingNetworks(arrayOf(network))
                } catch (_: Throwable) {
                    // Ignored on older API
                }
                if (SingBoxNativeBridge.isRunning()) {
                    SingBoxNativeBridge.resetNetwork()
                }
                val caps = cm.getNetworkCapabilities(network)
                val label = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile Data"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                    else -> "Network"
                }
                (application as? ShadoraApplication)?.engineManager?.onVpnServiceNetworkChanged(label)
                if (hadNetworkLoss) {
                    hadNetworkLoss = false
                    triggerAutoReconnect(label)
                }
            }

            override fun onLost(network: Network) {
                hadNetworkLoss = true
                val app = application as? ShadoraApplication ?: return
                app.engineManager.onVpnServiceNetworkChanged("Offline")
                SafeLogger.w(TAG, "Underlying network lost; monitoring for recovery")
            }
        }
        networkCallback = callback
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun stopTunnelSession(reason: String) {
        unregisterNetworkCallback()
        reconnectJob?.cancel()
        reconnectJob = null
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null

        SingBoxNativeBridge.stop()
        OpenVpn3NativeBridge.stop()
        MieruNativeBridge.stop()
        CloakNativeBridge.stop()
        LightwayNativeBridge.stop()
        XrayNativeBridge.stop()

        runCatching { tunInterface?.close() }
        tunInterface = null
        currentPlan = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        (application as? ShadoraApplication)?.engineManager?.onVpnServiceStopped(reason)
        SafeLogger.i(TAG, "VPN session stopped ($reason)")
    }

    override fun onRevoke() {
        super.onRevoke()
        stopTunnelSession("SYSTEM_REVOKED")
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnelSession("SERVICE_DESTROYED")
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateForegroundNotification(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(title, body)) }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_notification_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, body: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, ShadoraVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val disconnectPending = PendingIntent.getService(
            this,
            101,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shadora_tile)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_disconnect),
                disconnectPending
            )
            .build()
    }

    companion object {
        private const val TAG = "ShadoraVpnService"
        private const val CHANNEL_ID = "shadora_vpn_tunnel_channel"
        private const val NOTIFICATION_ID = 7071
        private const val TUN_IPV4_ADDRESS = "10.88.0.2"
        private const val TUN_IPV6_ADDRESS = "fd88:5a::2"

        const val ACTION_START = "com.example.shadora.ACTION_START_VPN"
        const val ACTION_STOP = "com.example.shadora.ACTION_STOP_VPN"
        const val EXTRA_SERVER_ID = "extra_server_id"
    }
}
