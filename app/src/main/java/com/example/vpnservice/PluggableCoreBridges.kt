package com.example.vpnservice

import android.content.Context
import android.net.VpnService
import com.example.configmanager.ParsedVpnConfig
import com.example.enginemanager.NativeLibraryLocator
import com.example.security.SafeLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Native bridge for **Mieru** (`enfein/mieru`) bundled as `libmieru.so` in APK `nativeLibraryDir`.
 * When compiled via SHADORA's GitHub Actions NDK workflow (or placed in `app/src/main/jniLibs/`),
 * this bridge compiles the official Mieru v3 JSON config, launches `libmieru.so` on a local
 * SOCKS5 port (`127.0.0.1:10891`), and chains SHADORA's bundled `libbox.so` TUN directly into it.
 *
 * Strictly zero Dynamic Code Loading: only executes binaries packaged inside the signed APK's
 * read-only `nativeLibraryDir`.
 */
object MieruNativeBridge {
    private const val TAG = "MieruNativeBridge"
    const val BINARY_NAME = "libmieru.so"
    const val LOCAL_SOCKS_PORT = 10891

    @Volatile
    private var activeProcess: Process? = null

    fun isAvailable(context: Context): Boolean =
        NativeLibraryLocator.hasBundledLibrary(context, BINARY_NAME)

    fun isRunning(): Boolean = activeProcess?.isAlive == true

    /**
     * Compiles an official Mieru client JSON configuration.
     */
    fun compileClientJson(parsed: ParsedVpnConfig, localSocksPort: Int = LOCAL_SOCKS_PORT): String {
        val transport = parsed.parameters["transport"]?.uppercase()?.ifBlank { "TCP" } ?: "TCP"
        val multiplexing = parsed.parameters["multiplexing"]?.uppercase()?.ifBlank { "MULTIPLEXING_LOW" }
            ?: "MULTIPLEXING_LOW"
        val isIpLiteral = parsed.host.matches(Regex("^[0-9.]+$")) || parsed.host.contains(':')

        val serverObj = JSONObject().apply {
            if (isIpLiteral) put("ipAddress", parsed.host) else put("domainName", parsed.host)
            put(
                "portBindings",
                JSONArray().put(
                    JSONObject().apply {
                        put("port", parsed.port)
                        put("protocol", transport)
                    }
                )
            )
        }

        val profile = JSONObject().apply {
            put("profileName", "shadora")
            put(
                "user",
                JSONObject().apply {
                    put("name", parsed.credentialsMap["username"].orEmpty())
                    put("password", parsed.credentialsMap["password"].orEmpty())
                }
            )
            put("servers", JSONArray().put(serverObj))
            put("mtu", 1400)
            put("multiplexing", JSONObject().put("level", multiplexing))
        }

        return JSONObject().apply {
            put("profiles", JSONArray().put(profile))
            put("activeProfile", "shadora")
            put("rpcPort", 0)
            put("socks5Port", localSocksPort)
            put("loggingLevel", "WARN")
            put("socks5ListenLAN", false)
        }.toString(2)
    }

    @Synchronized
    fun start(context: Context, mieruConfigJson: String, localSocksPort: Int = LOCAL_SOCKS_PORT): Boolean {
        stop()
        val nativeDir = context.applicationInfo?.nativeLibraryDir ?: return false
        val bin = File(nativeDir, BINARY_NAME)
        if (!bin.exists() || !bin.canExecute()) {
            SafeLogger.w(TAG, "Bundled $BINARY_NAME not found in nativeLibraryDir")
            return false
        }

        return try {
            val workDir = File(context.cacheDir, "mieru_run").apply { mkdirs() }
            val cfgFile = File(workDir, "mieru_client.json")
            cfgFile.writeText(mieruConfigJson, Charsets.UTF_8)

            val pb = ProcessBuilder(bin.absolutePath, "run", "-c", cfgFile.absolutePath)
                .directory(workDir)
                .redirectErrorStream(true)
            val proc = pb.start()
            activeProcess = proc

            // Wait up to 3 seconds for local SOCKS5 listener readiness
            val ready = waitForLocalPort("127.0.0.1", localSocksPort, timeoutMs = 3000L)
            if (!ready && !proc.isAlive) {
                SafeLogger.w(TAG, "Mieru process exited prematurely")
                stop()
                return false
            }
            SafeLogger.i(TAG, "Native Mieru core started on 127.0.0.1:$localSocksPort")
            true
        } catch (e: Throwable) {
            SafeLogger.e(TAG, "Failed to start native Mieru process", e)
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        val proc = activeProcess
        activeProcess = null
        if (proc != null) {
            runCatching { proc.destroy() }
            runCatching { proc.destroyForcibly() }
            SafeLogger.i(TAG, "Native Mieru core stopped")
        }
    }
}

/**
 * Native bridge for **Cloak** (`cbeuw/Cloak` `ck-client`) bundled as `libckclient.so` in APK `nativeLibraryDir`.
 * When compiled via SHADORA's GitHub Actions NDK workflow, launches `libckclient.so` locally on
 * `127.0.0.1:10892` and chains SHADORA's bundled `libbox.so` Shadowsocks TUN directly into it.
 */
object CloakNativeBridge {
    private const val TAG = "CloakNativeBridge"
    const val BINARY_NAME = "libckclient.so"
    const val LOCAL_PROXY_PORT = 10892

    @Volatile
    private var activeProcess: Process? = null

    fun isAvailable(context: Context): Boolean =
        NativeLibraryLocator.hasBundledLibrary(context, BINARY_NAME)

    fun isRunning(): Boolean = activeProcess?.isAlive == true

    /**
     * Compiles an official Cloak `ckclient.json` profile.
     */
    fun compileClientJson(parsed: ParsedVpnConfig): String {
        val params = parsed.parameters
        return JSONObject().apply {
            put("Transport", params["Transport"]?.ifBlank { "direct" } ?: "direct")
            put("ProxyMethod", params["ProxyMethod"]?.ifBlank { "shadowsocks" } ?: "shadowsocks")
            put("EncryptionMethod", params["EncryptionMethod"]?.ifBlank { "plain" } ?: "plain")
            put("UID", parsed.credentialsMap["uid"].orEmpty())
            put("PublicKey", parsed.credentialsMap["publicKey"].orEmpty())
            put("ServerName", params["ServerName"]?.ifBlank { parsed.host } ?: parsed.host)
            put("NumConn", params["NumConn"]?.toIntOrNull()?.coerceIn(1, 16) ?: 4)
            put("BrowserSig", params["BrowserSig"]?.ifBlank { "chrome" } ?: "chrome")
            put("StreamTimeout", params["StreamTimeout"]?.toIntOrNull() ?: 300)
        }.toString(2)
    }

    @Synchronized
    fun start(
        context: Context,
        cloakConfigJson: String,
        remoteHost: String,
        remotePort: Int,
        localPort: Int = LOCAL_PROXY_PORT
    ): Boolean {
        stop()
        val nativeDir = context.applicationInfo?.nativeLibraryDir ?: return false
        val bin = File(nativeDir, BINARY_NAME)
        if (!bin.exists() || !bin.canExecute()) {
            SafeLogger.w(TAG, "Bundled $BINARY_NAME not found in nativeLibraryDir")
            return false
        }

        return try {
            val workDir = File(context.cacheDir, "cloak_run").apply { mkdirs() }
            val cfgFile = File(workDir, "ckclient.json")
            cfgFile.writeText(cloakConfigJson, Charsets.UTF_8)

            val pb = ProcessBuilder(
                bin.absolutePath,
                "-c", cfgFile.absolutePath,
                "-s", remoteHost,
                "-p", remotePort.toString(),
                "-i", "127.0.0.1",
                "-l", localPort.toString()
            )
                .directory(workDir)
                .redirectErrorStream(true)
            val proc = pb.start()
            activeProcess = proc

            val ready = waitForLocalPort("127.0.0.1", localPort, timeoutMs = 3000L)
            if (!ready && !proc.isAlive) {
                SafeLogger.w(TAG, "Cloak ck-client exited prematurely")
                stop()
                return false
            }
            SafeLogger.i(TAG, "Native Cloak ck-client started on 127.0.0.1:$localPort -> $remoteHost:$remotePort")
            true
        } catch (e: Throwable) {
            SafeLogger.e(TAG, "Failed to start native Cloak process", e)
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        val proc = activeProcess
        activeProcess = null
        if (proc != null) {
            runCatching { proc.destroy() }
            runCatching { proc.destroyForcibly() }
            SafeLogger.i(TAG, "Native Cloak ck-client stopped")
        }
    }
}

/**
 * Native bridge for **Lightway** (`expressvpn/lightway-core`, Rust/C + wolfSSL) when an Android
 * `liblightway.so` / AAR module is built via Android NDK + Cargo and dropped into `app/libs/`.
 */
object LightwayNativeBridge {
    private const val TAG = "LightwayNativeBridge"
    const val BINARY_NAME = "liblightway.so"
    private const val CLIENT_CLASS = "io.expressvpn.lightway.LightwayClient"

    @Volatile
    private var activeClient: Any? = null

    fun isAvailable(context: Context): Boolean =
        NativeLibraryLocator.hasBundledLibrary(context, BINARY_NAME) ||
            NativeLibraryLocator.hasClass(CLIENT_CLASS)

    fun isRunning(): Boolean = activeClient != null

    fun compileProfileJson(parsed: ParsedVpnConfig): String {
        return JSONObject().apply {
            put("host", parsed.host)
            put("port", parsed.port)
            put("protocol", parsed.parameters["protocol"]?.ifBlank { "udp" } ?: "udp")
            put("cipher", parsed.parameters["cipher"]?.ifBlank { "aes" } ?: "aes")
            put("hasCa", parsed.credentialsMap["ca"].orEmpty().isNotBlank())
            put("hasClientCert", parsed.credentialsMap["cert"].orEmpty().isNotBlank())
        }.toString(2)
    }

    @Synchronized
    fun start(vpnService: VpnService, profileJson: String, tunFd: Int): Boolean {
        stop()
        return try {
            val clazz = Class.forName(CLIENT_CLASS)
            val ctor = clazz.getConstructor()
            val instance = ctor.newInstance()
            val startMethod = clazz.getMethod("start", String::class.java, Int::class.javaPrimitiveType)
            startMethod.invoke(instance, profileJson, tunFd)
            activeClient = instance
            SafeLogger.i(TAG, "Native Lightway client started")
            true
        } catch (e: Throwable) {
            SafeLogger.w(TAG, "Lightway native invocation unavailable: ${e.message}")
            false
        }
    }

    @Synchronized
    fun stop() {
        val client = activeClient ?: return
        activeClient = null
        runCatching {
            client.javaClass.getMethod("stop").invoke(client)
            SafeLogger.i(TAG, "Native Lightway client stopped")
        }
    }
}

private fun waitForLocalPort(host: String, port: Int, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 150)
                return true
            }
        } catch (_: Throwable) {
            runCatching { Thread.sleep(100L) }
        }
    }
    return false
}
