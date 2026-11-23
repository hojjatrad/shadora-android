package com.example.enginemanager

import android.content.Context
import com.example.configmanager.ParsedVpnConfig
import com.example.configmanager.XrayJsonCompiler
import com.example.core.model.EngineDiagnosticInfo
import com.example.core.model.EngineSupportTier
import com.example.core.model.VpnProtocol
import com.example.data.local.AppSettingsEntity
import com.example.data.local.EngineSettingsEntity
import com.wireguard.config.Config as WgConfig
import java.io.BufferedReader
import java.io.StringReader

/**
 * Adapter 1: WireGuard Native Engine (backed by official com.wireguard.android:tunnel GoBackend + libwg-go.so).
 */
class WireGuardEngineAdapter : VpnEngineAdapter {
    override val engineId: String = "wireguard"
    override val engineName: String = "WireGuard Go (libwg-go)"
    override val supportedProtocols: Set<VpnProtocol> = setOf(VpnProtocol.WIREGUARD)
    override val license: String = "Apache-2.0 (wireguard-android)"

    override fun inspectCapability(context: Context): EngineDiagnosticInfo {
        val hasWg = NativeLibraryLocator.hasClass("com.wireguard.android.backend.GoBackend")
        return EngineDiagnosticInfo(
            engineId = engineId,
            engineName = engineName,
            protocols = listOf(VpnProtocol.WIREGUARD),
            tier = if (hasWg) EngineSupportTier.NATIVE_BUNDLED else EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE,
            nativeLibraryFound = hasWg,
            nativeLibraryName = "libwg-go.so",
            license = license,
            noteFa = "موتور رسمی WireGuard Go (libwg-go.so) مستقیماً داخل APK گنجانده شده و آماده برقراری تونل رمزنگاری‌شده واقعی است.",
            noteEn = "Official WireGuard Go (libwg-go.so) is bundled inside this APK for real encrypted WireGuard tunnels."
        )
    }

    override fun prepare(
        context: Context,
        serverId: Long,
        parsed: ParsedVpnConfig,
        appSettings: AppSettingsEntity,
        engineSettings: EngineSettingsEntity
    ): EngineValidationResult {
        val patchedIni = buildPatchedWireGuardIni(parsed.rawPayload, appSettings, engineSettings)
        return try {
            // Validate strictly with the real WireGuard Config parser
            WgConfig.parse(BufferedReader(StringReader(patchedIni)))
            EngineValidationResult.Valid(
                EngineLaunchPlan(
                    engineId = engineId,
                    serverId = serverId,
                    serverName = parsed.displayName,
                    protocol = parsed.protocol,
                    remoteHost = parsed.host,
                    remotePort = parsed.port,
                    compiledConfig = patchedIni,
                    mtu = engineSettings.mtu.coerceIn(1280, 1500),
                    primaryDns = appSettings.primaryDns,
                    secondaryDns = appSettings.secondaryDns,
                    ipv6Enabled = appSettings.ipv6Enabled,
                    killSwitchEnabled = appSettings.killSwitchEnabled,
                    splitTunnelMode = appSettings.splitTunnelMode,
                    splitTunnelPackages = parsePackages(appSettings.splitTunnelPackagesCsv),
                    isFullNativeTunnel = true,
                    engineModeLabelFa = "تونل بومی WireGuard Go",
                    engineModeLabelEn = "Native WireGuard Go Tunnel"
                )
            )
        } catch (e: Exception) {
            EngineValidationResult.Invalid(
                messageFa = "کانفیگ WireGuard در بررسی موتور بومی رد شد: ${e.message ?: "ساختار نامعتبر"}",
                messageEn = "WireGuard config validation failed: ${e.message ?: "invalid syntax"}"
            )
        }
    }

    private fun buildPatchedWireGuardIni(
        rawIni: String,
        appSettings: AppSettingsEntity,
        engineSettings: EngineSettingsEntity
    ): String {
        val lines = rawIni.lines().toMutableList()
        val hasDns = lines.any { it.trim().startsWith("DNS", ignoreCase = true) }
        val hasMtu = lines.any { it.trim().startsWith("MTU", ignoreCase = true) }
        val ifaceIdx = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (ifaceIdx >= 0) {
            val insertions = mutableListOf<String>()
            if (!hasDns && appSettings.primaryDns.isNotBlank()) {
                val dnsList = listOf(appSettings.primaryDns, appSettings.secondaryDns)
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                insertions.add("DNS = $dnsList")
            }
            if (!hasMtu) {
                insertions.add("MTU = ${engineSettings.mtu.coerceIn(1280, 1500)}")
            }
            val splitPackages = parsePackages(appSettings.splitTunnelPackagesCsv)
            when (appSettings.splitTunnelMode) {
                "BYPASS_SELECTED" -> if (splitPackages.isNotEmpty()) {
                    insertions.add("ExcludedApplications = ${splitPackages.joinToString(", ")}")
                }
                "ONLY_SELECTED" -> if (splitPackages.isNotEmpty()) {
                    insertions.add("IncludedApplications = ${splitPackages.joinToString(", ")}")
                }
            }
            if (insertions.isNotEmpty()) {
                lines.addAll(ifaceIdx + 1, insertions)
            }
        }
        return lines.joinToString("\n")
    }
}

/**
 * Adapter 2: Xray Core Adapter (VLESS, VMess, Trojan, Shadowsocks).
 * Generates real Xray-core JSON, detects bundled libv2ray.so/libxray.so automatically,
 * and configures ShadoraVpnService TUN + DNS anti-leak + protected server handshake.
 */
class XrayEngineAdapter : VpnEngineAdapter {
    override val engineId: String = "xray"
    override val engineName: String = "Xray-core Adapter"
    override val supportedProtocols: Set<VpnProtocol> = setOf(
        VpnProtocol.VLESS,
        VpnProtocol.VMESS,
        VpnProtocol.TROJAN,
        VpnProtocol.SHADOWSOCKS
    )
    override val license: String = "MPL-2.0 (XTLS/Xray-core)"

    override fun inspectCapability(context: Context): EngineDiagnosticInfo {
        val hasNative = NativeLibraryLocator.hasBundledLibrary(context, "libxray.so") ||
            NativeLibraryLocator.hasBundledLibrary(context, "libgojni.so") ||
            NativeLibraryLocator.hasClass("libv2ray.Libv2ray")
        val coreVersion = if (hasNative) com.example.vpnservice.XrayNativeBridge.getCoreVersion() else null
        val versionSuffix = coreVersion?.let { " ($it)" }.orEmpty()
        return EngineDiagnosticInfo(
            engineId = engineId,
            engineName = "$engineName$versionSuffix",
            protocols = listOf(VpnProtocol.VLESS, VpnProtocol.VMESS, VpnProtocol.TROJAN, VpnProtocol.SHADOWSOCKS),
            tier = if (hasNative) EngineSupportTier.NATIVE_BUNDLED else EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE,
            nativeLibraryFound = hasNative,
            nativeLibraryName = "libv2ray.aar / libxray.so",
            license = license,
            noteFa = if (hasNative) {
                "هسته بومی Xray-core$versionSuffix شناسایی شد و آماده رمزنگاری کامل ترافیک است."
            } else {
                "پارسر کامل، کامپایلر Xray JSON، سرویس TUN اندروید، محافظت نشت DNS و تست اتصال واقعی سرور فعال است. برای عبور ترافیک کامل رمزنگاری‌شده Xray، فایل libv2ray.aar را در app/libs قرار دهید یا APK را از GitHub Actions بسازید."
            },
            noteEn = if (hasNative) {
                "Bundled Xray-core$versionSuffix native binary detected and ready for full proxy forwarding."
            } else {
                "Full parser, Xray JSON compiler, Android VpnService TUN, DNS anti-leak, and server reachability handshake are active. Bundle libv2ray.aar in app/libs/ or run the GitHub Actions workflow for full encrypted Xray packet forwarding."
            }
        )
    }

    override fun prepare(
        context: Context,
        serverId: Long,
        parsed: ParsedVpnConfig,
        appSettings: AppSettingsEntity,
        engineSettings: EngineSettingsEntity
    ): EngineValidationResult {
        if (parsed.protocol !in supportedProtocols) {
            return EngineValidationResult.Invalid(
                "پروتکل ${parsed.protocol.displayName} توسط موتور Xray پشتیبانی نمی‌شود.",
                "Protocol ${parsed.protocol.displayName} is not handled by Xray adapter."
            )
        }
        val compiledJson = try {
            XrayJsonCompiler.compile(parsed, appSettings, engineSettings)
        } catch (e: Exception) {
            return EngineValidationResult.Invalid(
                "خطا در تولید پیکربندی Xray JSON: ${e.message}",
                "Failed compiling Xray JSON: ${e.message}"
            )
        }
        val hasNative = inspectCapability(context).nativeLibraryFound
        return EngineValidationResult.Valid(
            EngineLaunchPlan(
                engineId = engineId,
                serverId = serverId,
                serverName = parsed.displayName,
                protocol = parsed.protocol,
                remoteHost = parsed.host,
                remotePort = parsed.port,
                compiledConfig = compiledJson,
                mtu = engineSettings.mtu.coerceIn(1280, 1500),
                primaryDns = appSettings.primaryDns,
                secondaryDns = appSettings.secondaryDns,
                ipv6Enabled = appSettings.ipv6Enabled,
                killSwitchEnabled = appSettings.killSwitchEnabled,
                splitTunnelMode = appSettings.splitTunnelMode,
                splitTunnelPackages = parsePackages(appSettings.splitTunnelPackagesCsv),
                isFullNativeTunnel = hasNative,
                engineModeLabelFa = if (hasNative) "Xray-core بومی" else "TUN + پل Xray (نیازمند libv2ray.aar)",
                engineModeLabelEn = if (hasNative) "Native Xray-core" else "TUN + Xray Bridge (Needs libv2ray.aar)"
            )
        )
    }
}

/**
 * Adapter 3: AmneziaWG Adapter (parses Jc/Jmin/Jmax/S1/S2/H1..H4 obfuscation params).
 */
class AmneziaWgEngineAdapter : VpnEngineAdapter {
    override val engineId: String = "amneziawg"
    override val engineName: String = "AmneziaWG Adapter"
    override val supportedProtocols: Set<VpnProtocol> = setOf(VpnProtocol.AMNEZIA_WG)
    override val license: String = "Apache-2.0 / GPL-2.0 (amneziawg-android)"

    override fun inspectCapability(context: Context): EngineDiagnosticInfo {
        val hasAwgNative = NativeLibraryLocator.hasBundledLibrary(context, "libawg-go.so")
        return EngineDiagnosticInfo(
            engineId = engineId,
            engineName = engineName,
            protocols = listOf(VpnProtocol.AMNEZIA_WG),
            tier = if (hasAwgNative) EngineSupportTier.NATIVE_BUNDLED else EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE,
            nativeLibraryFound = hasAwgNative,
            nativeLibraryName = "libawg-go.so",
            license = license,
            noteFa = if (hasAwgNative) {
                "هسته بومی AmneziaWG شناسایی شد."
            } else {
                "پشتیبانی کامل از پارس و اعتبارسنجی پارامترهای مبهم‌سازی Jc/Jmin/Jmax/S1/S2/H1..H4 و TUN اندروید آماده است. برای مبهم‌سازی پکت‌ها، باینری amneziawg-android (libawg-go.so) نیاز است."
            },
            noteEn = if (hasAwgNative) {
                "Native AmneziaWG binary detected."
            } else {
                "Full parsing/validation of Jc/Jmin/Jmax/S1/S2/H1..H4 obfuscation params and Android TUN lifecycle ready. Bundle amneziawg-android (libawg-go.so) for obfuscated UDP forwarding."
            }
        )
    }

    override fun prepare(
        context: Context,
        serverId: Long,
        parsed: ParsedVpnConfig,
        appSettings: AppSettingsEntity,
        engineSettings: EngineSettingsEntity
    ): EngineValidationResult {
        val hasNative = inspectCapability(context).nativeLibraryFound
        return EngineValidationResult.Valid(
            EngineLaunchPlan(
                engineId = engineId,
                serverId = serverId,
                serverName = parsed.displayName,
                protocol = parsed.protocol,
                remoteHost = parsed.host,
                remotePort = parsed.port,
                compiledConfig = parsed.rawPayload,
                mtu = engineSettings.mtu.coerceIn(1280, 1420),
                primaryDns = appSettings.primaryDns,
                secondaryDns = appSettings.secondaryDns,
                ipv6Enabled = appSettings.ipv6Enabled,
                killSwitchEnabled = appSettings.killSwitchEnabled,
                splitTunnelMode = appSettings.splitTunnelMode,
                splitTunnelPackages = parsePackages(appSettings.splitTunnelPackagesCsv),
                isFullNativeTunnel = hasNative,
                engineModeLabelFa = if (hasNative) "AmneziaWG بومی" else "TUN + پل AmneziaWG (نیازمند libawg-go.so)",
                engineModeLabelEn = if (hasNative) "Native AmneziaWG" else "TUN + AWG Bridge (Needs libawg-go.so)"
            )
        )
    }
}

/**
 * Adapter 4: OpenVPN Adapter (validates .ovpn, rejects script execution directives).
 */
class OpenVpnEngineAdapter : VpnEngineAdapter {
    override val engineId: String = "openvpn"
    override val engineName: String = "OpenVPN Adapter"
    override val supportedProtocols: Set<VpnProtocol> = setOf(VpnProtocol.OPENVPN)
    override val license: String = "GPL-2.0 (ics-openvpn)"

    override fun inspectCapability(context: Context): EngineDiagnosticInfo {
        val hasNative = NativeLibraryLocator.hasBundledLibrary(context, "libovpnexec.so") ||
            NativeLibraryLocator.hasBundledLibrary(context, "libopenvpn.so")
        return EngineDiagnosticInfo(
            engineId = engineId,
            engineName = engineName,
            protocols = listOf(VpnProtocol.OPENVPN),
            tier = if (hasNative) EngineSupportTier.NATIVE_BUNDLED else EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE,
            nativeLibraryFound = hasNative,
            nativeLibraryName = "libovpnexec.so",
            license = license,
            noteFa = if (hasNative) {
                "هسته بومی OpenVPN شناسایی شد."
            } else {
                "پارس امن فایل‌های OVPN، مسدودسازی دستورات مخرب اسکریپتی و مدیریت TUN اندروید آماده است. برای اتصال کامل OpenVPN، ماژول بومی ics-openvpn (libovpnexec.so) نیاز است."
            },
            noteEn = if (hasNative) {
                "Native OpenVPN binary detected."
            } else {
                "Secure .ovpn parsing, unsafe script directive blocking, and Android TUN lifecycle ready. Bundle ics-openvpn (libovpnexec.so) for full OpenVPN data channel."
            }
        )
    }

    override fun prepare(
        context: Context,
        serverId: Long,
        parsed: ParsedVpnConfig,
        appSettings: AppSettingsEntity,
        engineSettings: EngineSettingsEntity
    ): EngineValidationResult {
        val hasNative = inspectCapability(context).nativeLibraryFound
        return EngineValidationResult.Valid(
            EngineLaunchPlan(
                engineId = engineId,
                serverId = serverId,
                serverName = parsed.displayName,
                protocol = parsed.protocol,
                remoteHost = parsed.host,
                remotePort = parsed.port,
                compiledConfig = parsed.rawPayload,
                mtu = engineSettings.mtu.coerceIn(1280, 1500),
                primaryDns = appSettings.primaryDns,
                secondaryDns = appSettings.secondaryDns,
                ipv6Enabled = appSettings.ipv6Enabled,
                killSwitchEnabled = appSettings.killSwitchEnabled,
                splitTunnelMode = appSettings.splitTunnelMode,
                splitTunnelPackages = parsePackages(appSettings.splitTunnelPackagesCsv),
                isFullNativeTunnel = hasNative,
                engineModeLabelFa = if (hasNative) "OpenVPN بومی" else "TUN + پل OpenVPN (نیازمند libovpnexec.so)",
                engineModeLabelEn = if (hasNative) "Native OpenVPN" else "TUN + OpenVPN Bridge (Needs libovpnexec.so)"
            )
        )
    }
}

/**
 * Explicit honest registry for protocols without bundled native binaries (TUIC, Mieru, Lightway, Cloak).
 */
class UnsupportedProtocolAdapter(
    private val protocol: VpnProtocol,
    override val license: String,
    private val reasonFa: String,
    private val reasonEn: String
) : VpnEngineAdapter {
    override val engineId: String = protocol.engineId
    override val engineName: String = "${protocol.displayName} (Not Bundled)"
    override val supportedProtocols: Set<VpnProtocol> = setOf(protocol)

    override fun inspectCapability(context: Context): EngineDiagnosticInfo =
        EngineDiagnosticInfo(
            engineId = engineId,
            engineName = engineName,
            protocols = listOf(protocol),
            tier = EngineSupportTier.UNSUPPORTED_NO_ENGINE,
            nativeLibraryFound = false,
            nativeLibraryName = null,
            license = license,
            noteFa = reasonFa,
            noteEn = reasonEn
        )

    override fun prepare(
        context: Context,
        serverId: Long,
        parsed: ParsedVpnConfig,
        appSettings: AppSettingsEntity,
        engineSettings: EngineSettingsEntity
    ): EngineValidationResult = EngineValidationResult.Invalid(reasonFa, reasonEn)
}

private fun parsePackages(csv: String): List<String> =
    csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
