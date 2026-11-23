package com.example.vpnservice

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.example.enginemanager.EngineLaunchPlan
import com.example.security.SafeLogger
import net.openvpn.ovpn3.ClientAPI_Config
import net.openvpn.ovpn3.ClientAPI_Event
import net.openvpn.ovpn3.ClientAPI_LogInfo
import net.openvpn.ovpn3.ClientAPI_OpenVPNClient
import net.openvpn.ovpn3.DnsOptions
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct native bridge to the bundled `libovpn3.so` (OpenVPN 3 C++ Core).
 * Runs inside SHADORA's Play-compliant `specialUse` VpnService without needing
 * `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` or any separate service process.
 */
object OpenVpn3NativeBridge {
    private const val TAG = "OpenVpn3NativeBridge"

    @Volatile
    private var nativeLoaded: Boolean = false

    @Volatile
    private var activeClient: ShadoraOpenVpn3Client? = null

    @Volatile
    private var workerThread: Thread? = null

    fun isClassPresent(): Boolean = try {
        Class.forName("net.openvpn.ovpn3.ClientAPI_OpenVPNClient", false, OpenVpn3NativeBridge::class.java.classLoader)
        true
    } catch (_: Throwable) {
        false
    }

    fun isAvailable(): Boolean {
        if (!isClassPresent()) return false
        return ensureNativeLoaded()
    }

    fun isRunning(): Boolean = activeClient != null

    /**
     * Returns cumulative (bytesIn, bytesOut) from the native OpenVPN 3 transport layer,
     * or null if no session is active.
     */
    fun queryStats(): Pair<Long, Long>? {
        val client = activeClient ?: return null
        return try {
            val stats = client.transport_stats()
            stats.bytesIn.coerceAtLeast(0L) to stats.bytesOut.coerceAtLeast(0L)
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun start(
        vpnService: VpnService,
        plan: EngineLaunchPlan,
        bypassLanCidrs: Boolean,
        onConnected: (localAddress: String) -> Unit,
        onError: (code: String, detail: String) -> Unit
    ): Boolean {
        stop()
        if (!isAvailable()) {
            onError("OVPN3_NATIVE_MISSING", "libovpn3.so could not be loaded")
            return false
        }

        return try {
            val client = ShadoraOpenVpn3Client(
                vpnService = vpnService,
                plan = plan,
                bypassLanCidrs = bypassLanCidrs,
                onConnected = onConnected,
                onError = onError
            )
            val cfg = ClientAPI_Config().apply {
                content = plan.compiledConfig
                compressionMode = "no"
            }
            val eval = client.eval_config(cfg)
            if (eval.error) {
                val msg = eval.message.orEmpty().ifBlank { "Invalid OpenVPN profile" }
                SafeLogger.w(TAG, "OpenVPN 3 eval_config error: $msg")
                onError("OVPN_CONFIG_INVALID", msg)
                return false
            }

            activeClient = client
            val thread = Thread(
                {
                    try {
                        val status = client.connect()
                        if (status.error && activeClient === client) {
                            val detail = status.message.orEmpty().ifBlank { status.status.orEmpty() }
                            SafeLogger.w(TAG, "OpenVPN 3 connect exited with error: $detail")
                            onError("OVPN_CONNECT_FAILED", detail)
                        }
                    } catch (e: Throwable) {
                        if (activeClient === client) {
                            SafeLogger.e(TAG, "OpenVPN 3 worker crashed", e)
                            onError("OVPN_RUNTIME_ERROR", e.message.orEmpty())
                        }
                    }
                },
                "shadora-ovpn3-worker"
            ).apply {
                isDaemon = true
            }
            workerThread = thread
            thread.start()
            SafeLogger.i(TAG, "OpenVPN 3 native core started for ${plan.serverName}")
            true
        } catch (e: Throwable) {
            SafeLogger.e(TAG, "Failed to initialize OpenVPN 3 client", e)
            stop()
            onError("OVPN_INIT_FAILED", e.message.orEmpty())
            false
        }
    }

    @Synchronized
    fun stop() {
        val client = activeClient
        activeClient = null
        workerThread = null
        if (client != null) {
            runCatching { client.stop() }
            runCatching { client.closeTunDescriptor() }
            SafeLogger.i(TAG, "OpenVPN 3 native core stopped")
        }
    }

    private fun ensureNativeLoaded(): Boolean {
        if (nativeLoaded) return true
        return try {
            System.loadLibrary("ovpn3")
            nativeLoaded = true
            true
        } catch (e: Throwable) {
            SafeLogger.w(TAG, "libovpn3.so load failed: ${e.message}")
            false
        }
    }

    private data class TunCidr(val address: String, val prefixLength: Int, val ipv6: Boolean)

    private class ShadoraOpenVpn3Client(
        private val vpnService: VpnService,
        private val plan: EngineLaunchPlan,
        private val bypassLanCidrs: Boolean,
        private val onConnected: (localAddress: String) -> Unit,
        private val onError: (code: String, detail: String) -> Unit
    ) : ClientAPI_OpenVPNClient() {

        private val connectedNotified = AtomicBoolean(false)
        private val errorNotified = AtomicBoolean(false)

        private var mtu: Int = plan.mtu.coerceIn(1280, 1500)
        private var sessionName: String = "Shadora - ${plan.serverName}"
        private val addresses = mutableListOf<TunCidr>()
        private val routes = mutableListOf<TunCidr>()
        private val excludedRoutes = mutableListOf<TunCidr>()
        private var rerouteGwV4: Boolean = false
        private var rerouteGwV6: Boolean = false

        @Volatile
        private var tunPfd: ParcelFileDescriptor? = null

        @Volatile
        private var assignedLocalIp: String = "10.8.0.2"

        override fun socket_protect(socket: Int, remote: String?, ipv6: Boolean): Boolean {
            return vpnService.protect(socket)
        }

        override fun tun_builder_new(): Boolean {
            mtu = plan.mtu.coerceIn(1280, 1500)
            sessionName = "Shadora - ${plan.serverName}"
            addresses.clear()
            routes.clear()
            excludedRoutes.clear()
            rerouteGwV4 = false
            rerouteGwV6 = false
            return true
        }

        override fun tun_builder_set_mtu(mtu: Int): Boolean {
            if (mtu in 1280..1500) {
                this.mtu = mtu
            }
            return true
        }

        override fun tun_builder_set_session_name(name: String?): Boolean {
            if (!name.isNullOrBlank()) {
                sessionName = "Shadora - $name"
            }
            return true
        }

        override fun tun_builder_add_address(
            address: String?,
            prefixLength: Int,
            gateway: String?,
            ipv6: Boolean,
            net30: Boolean
        ): Boolean {
            if (!address.isNullOrBlank()) {
                addresses.add(TunCidr(address, prefixLength, ipv6))
                if (!ipv6) {
                    assignedLocalIp = address
                }
            }
            return true
        }

        override fun tun_builder_add_route(
            address: String?,
            prefixLength: Int,
            metric: Int,
            ipv6: Boolean
        ): Boolean {
            if (!address.isNullOrBlank()) {
                routes.add(TunCidr(address, prefixLength, ipv6))
            }
            return true
        }

        override fun tun_builder_exclude_route(
            address: String?,
            prefixLength: Int,
            metric: Int,
            ipv6: Boolean
        ): Boolean {
            if (!address.isNullOrBlank()) {
                excludedRoutes.add(TunCidr(address, prefixLength, ipv6))
            }
            return true
        }

        override fun tun_builder_reroute_gw(ipv4: Boolean, ipv6: Boolean, flags: Long): Boolean {
            rerouteGwV4 = ipv4
            rerouteGwV6 = ipv6
            return true
        }

        override fun tun_builder_set_dns_options(dns: DnsOptions?): Boolean = true

        override fun tun_builder_establish(): Int {
            return try {
                closeTunDescriptor()
                val builder = vpnService.Builder()
                    .setSession(sessionName)
                    .setMtu(mtu)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                if (addresses.isEmpty()) {
                    builder.addAddress("10.8.0.2", 24)
                } else {
                    addresses.forEach { cidr ->
                        if (!cidr.ipv6 || plan.ipv6Enabled) {
                            runCatching { builder.addAddress(cidr.address, cidr.prefixLength) }
                        }
                    }
                }

                var addedV4Route = false
                if (rerouteGwV4) {
                    builder.addRoute("0.0.0.0", 0)
                    addedV4Route = true
                }
                routes.forEach { cidr ->
                    if (!cidr.ipv6) {
                        runCatching {
                            builder.addRoute(cidr.address, cidr.prefixLength)
                            addedV4Route = true
                        }
                    } else if (plan.ipv6Enabled) {
                        runCatching { builder.addRoute(cidr.address, cidr.prefixLength) }
                    }
                }
                if (!addedV4Route) {
                    builder.addRoute("0.0.0.0", 0)
                }

                if (rerouteGwV6 && plan.ipv6Enabled) {
                    runCatching { builder.addRoute("::", 0) }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    excludedRoutes.forEach { cidr ->
                        runCatching {
                            builder.excludeRoute(IpPrefix(InetAddress.getByName(cidr.address), cidr.prefixLength))
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

                val primaryDns = plan.primaryDns.ifBlank { "1.1.1.1" }
                runCatching { builder.addDnsServer(primaryDns) }
                if (plan.secondaryDns.isNotBlank()) {
                    runCatching { builder.addDnsServer(plan.secondaryDns) }
                }

                if (!plan.killSwitchEnabled) {
                    builder.allowBypass()
                }

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
                            filtered.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
                        } else {
                            runCatching { builder.addDisallowedApplication(selfPkg) }
                        }
                    }
                    else -> {
                        runCatching { builder.addDisallowedApplication(selfPkg) }
                    }
                }

                val pfd = builder.establish() ?: return -1
                tunPfd = pfd
                pfd.fd
            } catch (e: Throwable) {
                SafeLogger.e(TAG, "OpenVPN 3 tun_builder_establish failed", e)
                -1
            }
        }

        override fun tun_builder_teardown(disconnect: Boolean) {
            closeTunDescriptor()
        }

        override fun event(ev: ClientAPI_Event?) {
            val name = ev?.name.orEmpty()
            val info = ev?.info.orEmpty()
            if (name.isNotBlank()) {
                SafeLogger.d(TAG, "OpenVPN3 event: $name ${if (info.isNotBlank()) "($info)" else ""}")
            }
            when {
                name.equals("CONNECTED", ignoreCase = true) -> {
                    if (connectedNotified.compareAndSet(false, true)) {
                        onConnected(assignedLocalIp)
                    }
                }
                ev?.fatal == true || (ev?.error == true && name in FATAL_EVENT_NAMES) -> {
                    if (errorNotified.compareAndSet(false, true)) {
                        onError("OVPN_$name", info.ifBlank { name })
                    }
                }
            }
        }

        override fun log(info: ClientAPI_LogInfo?) {
            val line = info?.text?.trim().orEmpty()
            if (line.isNotEmpty()) {
                SafeLogger.d(TAG, line)
            }
        }

        fun closeTunDescriptor() {
            val pfd = tunPfd
            tunPfd = null
            if (pfd != null) {
                runCatching { pfd.close() }
            }
        }
    }

    private val FATAL_EVENT_NAMES = setOf(
        "AUTH_FAILED",
        "CERT_VERIFY_FAIL",
        "TLS_VERSION_MIN",
        "CLIENT_HALT",
        "CLIENT_RESTART",
        "CONNECTION_TIMEOUT",
        "INACTIVE_TIMEOUT",
        "PROXY_ERROR"
    )
}
