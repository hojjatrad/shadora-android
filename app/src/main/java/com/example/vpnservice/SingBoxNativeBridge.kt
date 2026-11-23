package com.example.vpnservice

import android.content.Context
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.example.enginemanager.EngineLaunchPlan
import com.example.security.SafeLogger
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.InetAddress

/**
 * Direct native bridge to the bundled `libbox.so` (sing-box 1.12+) Go/C core.
 * Handles VLESS (XTLS-Reality/Vision/TLS/WS/gRPC/HTTPUpgrade), VMess, Trojan,
 * Shadowsocks, and TUIC v5 tunnels with full Android VpnService TUN integration,
 * socket protection, default interface tracking, DNS hijack, and Split Tunneling.
 */
object SingBoxNativeBridge {
    private const val TAG = "SingBoxNativeBridge"

    @Volatile
    private var isInitialized: Boolean = false

    @Volatile
    private var commandServer: CommandServer? = null

    @Volatile
    private var activeTunPfd: ParcelFileDescriptor? = null

    @Volatile
    private var interfaceMonitorCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var lastLocalAddress: String = "172.19.0.1"

    fun isAvailable(): Boolean = try {
        Class.forName("io.nekohasekai.libbox.Libbox", false, SingBoxNativeBridge::class.java.classLoader)
        true
    } catch (_: Throwable) {
        false
    }

    fun getCoreVersion(): String? = try {
        Libbox.version().trim().takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    fun isRunning(): Boolean = commandServer != null && activeTunPfd != null

    fun getActiveLocalAddress(): String = lastLocalAddress

    fun resetNetwork() {
        runCatching { commandServer?.resetNetwork() }
    }

    @Synchronized
    fun start(
        vpnService: VpnService,
        plan: EngineLaunchPlan,
        bypassLanCidrs: Boolean
    ): Result<String> {
        return try {
            stop()
            ensureSetup(vpnService)

            val platformInterface = ShadoraPlatformInterface(
                vpnService = vpnService,
                plan = plan,
                bypassLanCidrs = bypassLanCidrs
            )
            val serverHandler = object : CommandServerHandler {
                override fun serviceStop() {
                    SafeLogger.i(TAG, "libbox requested serviceStop")
                }

                override fun serviceReload() {
                    SafeLogger.i(TAG, "libbox requested serviceReload")
                }

                override fun getSystemProxyStatus(): SystemProxyStatus {
                    return SystemProxyStatus().apply {
                        available = false
                        enabled = false
                    }
                }

                override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

                override fun writeDebugMessage(message: String?) {
                    val text = message?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        SafeLogger.d(TAG, text)
                    }
                }
            }

            val server = Libbox.newCommandServer(serverHandler, platformInterface)
            server.startWithTemporaryPort()
            commandServer = server

            val overrides = OverrideOptions()
            server.startOrReloadService(plan.compiledConfig, overrides)

            SafeLogger.i(TAG, "Native sing-box/libbox tunnel started (${plan.protocol.displayName}, v${getCoreVersion() ?: "?"})")
            Result.success(lastLocalAddress)
        } catch (e: Throwable) {
            SafeLogger.e(TAG, "Failed to start native sing-box/libbox core", e)
            stop()
            Result.failure(e)
        }
    }

    @Synchronized
    fun stop() {
        val server = commandServer
        commandServer = null
        if (server != null) {
            runCatching { server.closeService() }
            runCatching { server.close() }
        }
        val pfd = activeTunPfd
        activeTunPfd = null
        if (pfd != null) {
            runCatching { pfd.close() }
        }
    }

    private fun ensureSetup(context: Context) {
        if (isInitialized) return
        val baseDir = File(context.filesDir, "singbox").apply { mkdirs() }
        val workDir = File(baseDir, "work").apply { mkdirs() }
        val tempDir = File(context.cacheDir, "singbox_tmp").apply { mkdirs() }

        val options = SetupOptions().apply {
            basePath = baseDir.absolutePath
            workingPath = workDir.absolutePath
            tempPath = tempDir.absolutePath
            fixAndroidStack = true
            debug = false
        }
        Libbox.setup(options)
        runCatching { Libbox.setMemoryLimit(true) }
        isInitialized = true
    }

    private class ShadoraPlatformInterface(
        private val vpnService: VpnService,
        private val plan: EngineLaunchPlan,
        private val bypassLanCidrs: Boolean
    ) : PlatformInterface {

        override fun localDNSTransport(): LocalDNSTransport? = null

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

        override fun autoDetectInterfaceControl(fd: Int) {
            if (!vpnService.protect(fd)) {
                SafeLogger.w(TAG, "VpnService.protect($fd) returned false")
            }
        }

        override fun openTun(options: TunOptions): Int {
            val builder = vpnService.Builder()
                .setSession("Shadora - ${plan.serverName}")
                .setMtu(options.mtu.takeIf { it in 1280..1500 } ?: plan.mtu.coerceIn(1280, 1500))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            // 1. TUN interface addresses from sing-box options
            var hasIpv4Addr = false
            val v4Iter = options.inet4Address
            while (v4Iter.hasNext()) {
                val prefix = v4Iter.next()
                builder.addAddress(prefix.address(), prefix.prefix())
                if (!hasIpv4Addr) {
                    lastLocalAddress = prefix.address()
                    hasIpv4Addr = true
                }
            }
            if (!hasIpv4Addr) {
                builder.addAddress("172.19.0.1", 30)
                lastLocalAddress = "172.19.0.1"
            }

            var hasIpv6Addr = false
            val v6Iter = options.inet6Address
            while (v6Iter.hasNext()) {
                val prefix = v6Iter.next()
                runCatching {
                    builder.addAddress(prefix.address(), prefix.prefix())
                    hasIpv6Addr = true
                }
            }
            // Capture IPv6 black-hole address when IPv6 is OFF to prevent ISP IPv6 leak
            if (!plan.ipv6Enabled && !hasIpv6Addr) {
                runCatching { builder.addAddress("fdfe:dcba:9876::1", 126) }
            }

            // 2. Routes & DNS
            if (options.autoRoute) {
                val dnsAddr = runCatching { options.dnsServerAddress.value }.getOrNull().orEmpty()
                if (dnsAddr.isNotBlank()) {
                    runCatching { builder.addDnsServer(dnsAddr) }
                } else {
                    val primary = plan.primaryDns.ifBlank { "1.1.1.1" }
                    runCatching { builder.addDnsServer(primary) }
                }

                var hasV4Route = false
                val v4RouteIter = options.inet4RouteAddress
                while (v4RouteIter.hasNext()) {
                    val r = v4RouteIter.next()
                    builder.addRoute(r.address(), r.prefix())
                    hasV4Route = true
                }
                if (!hasV4Route) {
                    builder.addRoute("0.0.0.0", 0)
                }

                var hasV6Route = false
                val v6RouteIter = options.inet6RouteAddress
                while (v6RouteIter.hasNext()) {
                    val r = v6RouteIter.next()
                    runCatching {
                        builder.addRoute(r.address(), r.prefix())
                        hasV6Route = true
                    }
                }
                if (!hasV6Route) {
                    runCatching { builder.addRoute("::", 0) }
                }

                // Apply excluded routes (LAN bypass etc.) on Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val v4Excl = options.inet4RouteExcludeAddress
                    while (v4Excl.hasNext()) {
                        val ex = v4Excl.next()
                        runCatching {
                            builder.excludeRoute(IpPrefix(InetAddress.getByName(ex.address()), ex.prefix()))
                        }
                    }
                    val v6Excl = options.inet6RouteExcludeAddress
                    while (v6Excl.hasNext()) {
                        val ex = v6Excl.next()
                        runCatching {
                            builder.excludeRoute(IpPrefix(InetAddress.getByName(ex.address()), ex.prefix()))
                        }
                    }
                    if (bypassLanCidrs) {
                        listOf("10.0.0.0" to 8, "172.16.0.0" to 12, "192.168.0.0" to 16).forEach { (addr, pfx) ->
                            runCatching {
                                builder.excludeRoute(IpPrefix(InetAddress.getByName(addr), pfx))
                            }
                        }
                    }
                }
            }

            // 3. Kill Switch policy
            if (!plan.killSwitchEnabled) {
                builder.allowBypass()
            }

            // 4. Split Tunneling (always exclude our own package to prevent routing loop)
            val selfPkg = vpnService.packageName
            when (plan.splitTunnelMode) {
                "BYPASS_SELECTED" -> {
                    runCatching { builder.addDisallowedApplication(selfPkg) }
                    plan.splitTunnelPackages.forEach { pkg ->
                        runCatching { builder.addDisallowedApplication(pkg) }
                    }
                }
                "ONLY_SELECTED" -> {
                    val filtered = plan.splitTunnelPackages.filter { it != selfPkg }
                    if (filtered.isNotEmpty()) {
                        filtered.forEach { pkg ->
                            runCatching { builder.addAllowedApplication(pkg) }
                        }
                    } else {
                        runCatching { builder.addDisallowedApplication(selfPkg) }
                    }
                }
                else -> {
                    runCatching { builder.addDisallowedApplication(selfPkg) }
                }
            }

            val pfd = builder.establish()
                ?: throw IllegalStateException("Android VpnService.Builder.establish() returned null")
            activeTunPfd = pfd
            return pfd.fd
        }

        override fun useProcFS(): Boolean = false

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String?,
            sourcePort: Int,
            destinationAddress: String?,
            destinationPort: Int
        ): ConnectionOwner = ConnectionOwner()

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            val cm = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            // Immediately push currently active default interface
            notifyCurrentActiveInterface(cm, listener)

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    notifyNetworkInterface(cm, network, listener)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    notifyLinkProperties(cm, network, linkProperties, listener)
                }

                override fun onLost(network: Network) {
                    runCatching { listener.updateDefaultInterface("", -1, false, false) }
                }
            }
            interfaceMonitorCallback = cb
            runCatching { cm.registerNetworkCallback(request, cb) }
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
            val cb = interfaceMonitorCallback ?: return
            interfaceMonitorCallback = null
            val cm = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            runCatching { cm.unregisterNetworkCallback(cb) }
        }

        override fun getInterfaces(): NetworkInterfaceIterator = EmptyNetworkInterfaceIterator

        override fun underNetworkExtension(): Boolean = false

        override fun includeAllNetworks(): Boolean = false

        override fun clearDNSCache() = Unit

        override fun readWIFIState(): WIFIState? = null

        override fun systemCertificates(): StringIterator? = null

        override fun sendNotification(notification: Notification?) = Unit

        private fun notifyCurrentActiveInterface(
            cm: ConnectivityManager,
            listener: InterfaceUpdateListener
        ) {
            val active = cm.activeNetwork ?: return
            notifyNetworkInterface(cm, active, listener)
        }

        private fun notifyNetworkInterface(
            cm: ConnectivityManager,
            network: Network,
            listener: InterfaceUpdateListener
        ) {
            val lp = cm.getLinkProperties(network) ?: return
            notifyLinkProperties(cm, network, lp, listener)
        }

        private fun notifyLinkProperties(
            cm: ConnectivityManager,
            network: Network,
            lp: LinkProperties,
            listener: InterfaceUpdateListener
        ) {
            val ifaceName = lp.interfaceName ?: return
            val ifaceIndex = runCatching {
                java.net.NetworkInterface.getByName(ifaceName)?.index ?: -1
            }.getOrDefault(-1)
            val caps = cm.getNetworkCapabilities(network)
            val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            runCatching {
                listener.updateDefaultInterface(ifaceName, ifaceIndex, isExpensive, false)
            }
        }
    }

    private object EmptyNetworkInterfaceIterator : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = false
        override fun next(): NetworkInterface {
            throw NoSuchElementException("No interfaces")
        }
    }
}
