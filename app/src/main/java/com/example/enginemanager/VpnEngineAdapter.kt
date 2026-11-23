package com.example.enginemanager

import android.content.Context
import com.example.configmanager.ParsedVpnConfig
import com.example.core.model.EngineDiagnosticInfo
import com.example.core.model.EngineSupportTier
import com.example.core.model.VpnProtocol
import com.example.data.local.AppSettingsEntity
import com.example.data.local.EngineSettingsEntity
import java.io.File

data class EngineLaunchPlan(
    val engineId: String,
    val serverId: Long,
    val serverName: String,
    val protocol: VpnProtocol,
    val remoteHost: String,
    val remotePort: Int,
    val compiledConfig: String,
    val mtu: Int,
    val primaryDns: String,
    val secondaryDns: String,
    val ipv6Enabled: Boolean,
    val killSwitchEnabled: Boolean,
    val splitTunnelMode: String,
    val splitTunnelPackages: List<String>,
    val isFullNativeTunnel: Boolean,
    val engineModeLabelFa: String,
    val engineModeLabelEn: String
)

sealed interface EngineValidationResult {
    data class Valid(val plan: EngineLaunchPlan) : EngineValidationResult
    data class Invalid(val messageFa: String, val messageEn: String) : EngineValidationResult
}

interface VpnEngineAdapter {
    val engineId: String
    val engineName: String
    val supportedProtocols: Set<VpnProtocol>
    val license: String

    fun inspectCapability(context: Context): EngineDiagnosticInfo

    fun prepare(
        context: Context,
        serverId: Long,
        parsed: ParsedVpnConfig,
        appSettings: AppSettingsEntity,
        engineSettings: EngineSettingsEntity
    ): EngineValidationResult
}

/**
 * Inspects strictly bundled native libraries inside the installed APK's nativeLibraryDir.
 * Never downloads or executes external binaries at runtime (Google Play System Integrity compliant).
 */
object NativeLibraryLocator {
    fun hasBundledLibrary(context: Context, soFileName: String): Boolean {
        val nativeDir = context.applicationInfo?.nativeLibraryDir ?: return false
        val file = File(nativeDir, soFileName)
        if (file.exists() && file.canRead()) return true
        // Also check classloader lookup via System.mapLibraryName
        return try {
            val bareName = soFileName.removePrefix("lib").removeSuffix(".so")
            val finder = context.classLoader?.javaClass?.getMethod("findLibrary", String::class.java)
            val resolved: String? = finder?.invoke(context.classLoader, bareName) as? String
            resolved != null && resolved.isNotBlank()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasClass(className: String): Boolean {
        return try {
            Class.forName(className)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
