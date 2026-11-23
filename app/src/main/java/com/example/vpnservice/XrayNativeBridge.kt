package com.example.vpnservice

import android.net.VpnService
import com.example.security.SafeLogger
import java.lang.reflect.Proxy

/**
 * Reflection bridge for the standard AndroidLibXrayLite (libv2ray.aar) native core.
 * When libv2ray.aar is bundled in app/libs/, this bridge automatically initializes
 * CoreController and starts real Xray-core tunneling bound to Android VpnService's TUN fd.
 * No dynamic code loading is performed: only classes already packaged inside the signed APK are resolved.
 */
object XrayNativeBridge {
    private const val TAG = "XrayNativeBridge"
    private const val LIBV2RAY_CLASS = "libv2ray.Libv2ray"
    private const val CALLBACK_HANDLER_CLASS = "libv2ray.CoreCallbackHandler"

    @Volatile
    private var activeController: Any? = null

    fun isAvailable(): Boolean = try {
        Class.forName(LIBV2RAY_CLASS)
        true
    } catch (_: Throwable) {
        false
    }

    fun start(vpnService: VpnService, configJson: String, tunFd: Int): Boolean {
        return try {
            val libClass = Class.forName(LIBV2RAY_CLASS)
            val callbackInterface = Class.forName(CALLBACK_HANDLER_CLASS)

            // Initialize Xray environment directory using app's internal files dir
            runCatching {
                val initMethod = libClass.getMethod("initCoreEnv", String::class.java, String::class.java)
                initMethod.invoke(null, vpnService.filesDir.absolutePath, "")
            }

            // Create CoreCallbackHandler proxy that delegates protect(fd) directly to VpnService
            val callbackProxy = Proxy.newProxyInstance(
                callbackInterface.classLoader,
                arrayOf(callbackInterface)
            ) { _, method, args ->
                when (method.name) {
                    "protect" -> {
                        val fd = (args?.firstOrNull() as? Number)?.toInt() ?: -1
                        if (fd >= 0) vpnService.protect(fd) else false
                    }
                    "onEmitStatus" -> {
                        val msg = args?.getOrNull(1)?.toString().orEmpty()
                        if (msg.isNotBlank()) SafeLogger.d(TAG, "Core status: $msg")
                        0L
                    }
                    "startup" -> 0L
                    "shutdown" -> 0L
                    else -> 0L
                }
            }

            val newControllerMethod = libClass.getMethod("newCoreController", callbackInterface)
            val controller = newControllerMethod.invoke(null, callbackProxy) ?: return false

            // Invoke startLoop(configContent: String, tunFd: Int) or startLoop(configContent: String)
            val started = runCatching {
                val m = controller.javaClass.getMethod("startLoop", String::class.java, Int::class.javaPrimitiveType)
                m.invoke(controller, configJson, tunFd)
                true
            }.getOrElse {
                runCatching {
                    val m = controller.javaClass.getMethod("startLoop", String::class.java)
                    m.invoke(controller, configJson)
                    true
                }.getOrDefault(false)
            }

            if (started) {
                activeController = controller
                SafeLogger.i(TAG, "Native libv2ray CoreController started successfully")
            }
            started
        } catch (e: Throwable) {
            SafeLogger.e(TAG, "Failed to start native libv2ray bridge", e)
            false
        }
    }

    fun getCoreVersion(): String? {
        return try {
            val libClass = Class.forName(LIBV2RAY_CLASS)
            val m = libClass.getMethod("checkVersionX")
            (m.invoke(null) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Queries incremental byte counters directly from Xray's built-in stats policy
     * (tag = "proxy", direction = "downlink" | "uplink"). Returns -1L if unavailable.
     */
    fun queryStats(tag: String, direction: String): Long {
        val controller = activeController ?: return -1L
        return try {
            val m = controller.javaClass.getMethod("queryStats", String::class.java, String::class.java)
            (m.invoke(controller, tag, direction) as? Number)?.toLong() ?: -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    fun isRunning(): Boolean = activeController != null

    fun stop() {
        val controller = activeController ?: return
        activeController = null
        runCatching {
            val stopMethod = controller.javaClass.getMethod("stopLoop")
            stopMethod.invoke(controller)
            SafeLogger.i(TAG, "Native libv2ray CoreController stopped")
        }.onFailure {
            SafeLogger.w(TAG, "Error stopping libv2ray controller: ${it.message}")
        }
    }
}
