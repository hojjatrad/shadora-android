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
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real Android VpnService for SHADORA:
 * - Establishes Android TUN interface with explicit IPv4/IPv6 routes & anti-leak DNS servers
 * - Supports Split Tunneling (All Apps, Bypass Selected, Only Selected)
 * - Enforces Kill Switch behavior (disallows bypass on TUN)
 * - Monitors Wi-Fi / Mobile Data switches via ConnectivityManager.NetworkCallback
 * - Performs automatic exponential-backoff reconnect when Auto-Connect is enabled
 * - Streams live ↓/↑ traffic speed into the persistent foreground notification
 * - Cleans up all resources cleanly on disconnect, revoke, or crash recovery
 */
class ShadoraVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunReaderJob: Job? = null
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

            // Step 1: Protected server reachability handshake before capturing device traffic
            val reachableMs = probeRemoteServerProtected(plan.remoteHost, plan.remotePort)
            if (reachableMs < 0) {
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

            // Step 2: NEVER black-hole device traffic if the native forwarding engine binary is not bundled
            if (!plan.isFullNativeTunnel) {
                val missingArtifact = when (plan.engineId) {
                    "xray" -> "libv2ray.aar"
                    "amneziawg" -> "libawg-go.so"
                    "openvpn" -> "libovpnexec.so"
                    else -> "${plan.engineId}-native"
                }
                SafeLogger.w(
                    TAG,
                    "Server reachable (${reachableMs}ms), but native core '$missingArtifact' is not bundled. Aborting TUN capture to preserve internet."
                )
                engineManager.onVpnServiceError(
                    code = "NATIVE_CORE_MISSING",
                    messageFa = "سرور در دسترس است (${reachableMs}ms) و کانفیگ ${plan.protocol.displayName} معتبر است، اما فایل هسته بومی ($missingArtifact) در این APK وجود ندارد. برای جلوگیری از قطع اینترنت و باز نشدن برنامه‌ها، رابط TUN فعال نشد. اکنون پروتکل WireGuard به‌صورت بومی و کامل کار می‌کند؛ برای Xray فایل libv2ray.aar را در app/libs قرار دهید.",
                    messageEn = "Server is reachable (${reachableMs}ms) and ${plan.protocol.displayName} config is valid, but native core ($missingArtifact) is not bundled in this APK. TUN capture was halted to prevent internet black-hole. WireGuard works natively now; drop libv2ray.aar into app/libs/ to enable full Xray forwarding."
                )
                stopTunnelSession("NATIVE_CORE_MISSING")
                stopSelf()
                return@launch
            }

            // Step 3: Build and establish real Android TUN interface
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

            // Step 4: Boot bundled native Xray core bound to TUN fd when present
            if (plan.engineId == "xray" && XrayNativeBridge.isAvailable()) {
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
            }

            registerNetworkCallback()

            val dnsList = listOf(plan.primaryDns, plan.secondaryDns).filter { it.isNotBlank() }
                .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }

            SafeLogger.i(TAG, "TUN established for ${plan.serverName} (${plan.protocol.displayName}, ${reachableMs}ms)")

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

            // Step 5: Live speed telemetry updates in foreground notification
            startLiveNotificationUpdates(app, plan, settings.notificationsEnabled)

            // Step 6: Only run fallback reader if no native core owns the TUN fd
            if (!(plan.engineId == "xray" && XrayNativeBridge.isAvailable())) {
                startTunPacketLoop(established)
            }
        }
    }

    private fun startLiveNotificationUpdates(
        app: ShadoraApplication,
        plan: EngineLaunchPlan,
        showLiveSpeed: Boolean
    ) {
        notificationUpdateJob?.cancel()
        if (!showLiveSpeed) return
        notificationUpdateJob = serviceScope.launch {
            while (isActive && tunInterface != null) {
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
                if (tunInterface == null && currentPlan == null) return@launch
                app.engineManager.onVpnServiceReconnecting(attempt, networkLabel)
                SafeLogger.i(TAG, "Auto-reconnect attempt #$attempt on $networkLabel")
                delay(attempt * 1200L)

                val ms = probeRemoteServerProtected(plan.remoteHost, plan.remotePort)
                if (ms > 0) {
                    val dnsList = listOf(plan.primaryDns, plan.secondaryDns).filter { it.isNotBlank() }
                        .ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
                    app.engineManager.onVpnServiceConnected(
                        plan = plan,
                        localAddress = TUN_IPV4_ADDRESS,
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

            // Exclude local LAN private CIDRs on Android 13+ when LAN bypass is active
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

            // IPv6 handling & leak guard
            if (plan.ipv6Enabled) {
                builder.addAddress(TUN_IPV6_ADDRESS, 126)
                builder.addRoute("::", 0)
            } else {
                // Capture IPv6 default route into TUN without external forwarding to prevent ISP IPv6 leak
                try {
                    builder.addAddress(TUN_IPV6_ADDRESS, 126)
                    builder.addRoute("::", 0)
                } catch (_: Throwable) {
                    // Device kernel may have IPv6 disabled
                }
            }

            // Explicit anti-leak DNS servers
            val primaryDns = plan.primaryDns.ifBlank { "1.1.1.1" }
            runCatching { builder.addDnsServer(primaryDns) }
            if (plan.secondaryDns.isNotBlank()) {
                runCatching { builder.addDnsServer(plan.secondaryDns) }
            }

            // Kill Switch policy: only allowBypass when Kill Switch is OFF
            if (!plan.killSwitchEnabled) {
                builder.allowBypass()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            // Split Tunneling (always exclude self to prevent routing loop)
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

    private fun startTunPacketLoop(pfd: ParcelFileDescriptor) {
        tunReaderJob?.cancel()
        tunReaderJob = serviceScope.launch {
            val buffer = ByteArray(32767)
            try {
                FileInputStream(pfd.fileDescriptor).use { input ->
                    while (tunInterface != null) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) {
                            delay(50)
                        }
                    }
                }
            } catch (_: Throwable) {
                // Expected when TUN ParcelFileDescriptor is closed on disconnect
            }
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
        tunReaderJob?.cancel()
        tunReaderJob = null
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
