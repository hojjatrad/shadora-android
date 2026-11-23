package com.example.enginemanager

import android.content.Context
import com.example.configmanager.ParsedVpnConfig
import com.example.configmanager.SingBoxJsonCompiler
import com.example.configmanager.XrayJsonCompiler
import com.example.core.model.EngineDiagnosticInfo
import com.example.core.model.EngineSupportTier
import com.example.core.model.VpnProtocol
import com.example.data.local.AppSettingsEntity
import com.example.data.local.EngineSettingsEntity
import com.example.vpnservice.CloakNativeBridge
import com.example.vpnservice.LightwayNativeBridge
import com.example.vpnservice.MieruNativeBridge
import com.example.vpnservice.OpenVpn3NativeBridge
import com.example.vpnservice.SingBoxNativeBridge
import com.example.vpnservice.XrayNativeBridge
import com.wireguard.config.Config as WgConfig
import org.amnezia.awg.config.Config as AwgConfig
import org.json.JSONObject
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
        val patchedIni = buildPatchedWgIni(parsed.rawPayload, appSettings, engineSettings, maxMtu = 1500)
        return try {
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
}

/**
 * Adapter 2: Universal Proxy Core Adapter (VLESS, VMess, Trojan, Shadowsocks, Hysteria2, AnyTLS, ShadowTLS v3, TUIC v5).
 * Backed out-of-the-box by the bundled native `libbox.so` (sing-box 1.12+) core.
 */
class XrayEngineAdapter : VpnEngineAdapter {
    override val engineId: String = "xray"
    override val engineName: String = "sing-box / Xray Native Core"
    override val supportedProtocols: Set<VpnProtocol> = setOf(
        VpnProtocol.VLESS,
        VpnProtocol.VMESS,
        VpnProtocol.TROJAN,
        VpnProtocol.SHADOWSOCKS,
        VpnProtocol.HYSTERIA2,
        VpnProtocol.ANYTLS,
        VpnProtocol.SHADOWTLS,
        VpnProtocol.TUIC
    )
    override val license: String = "GPL-3.0 (sing-box / libbox) • MPL-2.0 (Xray-core)"

    override fun inspectCapability(context: Context): EngineDiagnosticInfo {
        val hasSingBox = SingBoxNativeBridge.isAvailable()
        val hasLibv2ray = XrayNativeBridge.isAvailable()
        val hasNative = hasSingBox || hasLibv2ray
        val coreVersion = when {
            hasSingBox -> SingBoxNativeBridge.getCoreVersion()
            hasLibv2ray -> XrayNativeBridge.getCoreVersion()
            else -> null
        }
        val versionSuffix = coreVersion?.let { " (v$it)" }.orEmpty()
        val activeName = when {
            hasSingBox && hasLibv2ray -> "sing-box + Xray-core$versionSuffix"
            hasSingBox -> "sing-box Core (libbox)$versionSuffix"
            hasLibv2ray -> "Xray-core (libv2ray)$versionSuffix"
            else -> engineName
        }

        return EngineDiagnosticInfo(
            engineId = engineId,
            engineName = activeName,
            protocols = listOf(
                VpnProtocol.VLESS,
                VpnProtocol.VMESS,
                VpnProtocol.TROJAN,
                VpnProtocol.SHADOWSOCKS,
                VpnProtocol.HYSTERIA2,
                VpnProtocol.ANYTLS,
                VpnProtocol.SHADOWTLS,
                VpnProtocol.TUIC
            ),
            tier = if (hasNative) EngineSupportTier.NATIVE_BUNDLED else EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE,
            nativeLibraryFound = hasNative,
            nativeLibraryName = if (hasSingBox) "libbox.so" else "libv2ray.aar",
            license = license,
            noteFa = if (hasSingBox) {
                "هسته بومی sing-box (libbox.so)$versionSuffix مستقیماً داخل APK گنجانده شده و آماده برقراری تونل واقعی رمزنگاری‌شده برای VLESS (Reality/Vision)، VMess، Trojan، Shadowsocks، Hysteria2، AnyTLS، ShadowTLS و TUIC v5 است."
            } else if (hasLibv2ray) {
                "هسته بومی Xray-core$versionSuffix شناسایی شد و آماده رمزنگاری کامل ترافیک است."
            } else {
                "پارسر کامل، کامپایلر JSON و سرویس TUN آماده است، اما فایل هسته بومی در APK یافت نشد."
            },
            noteEn = if (hasSingBox) {
                "Native sing-box core (libbox.so)$versionSuffix is bundled inside this APK for real encrypted VLESS (Reality/Vision), VMess, Trojan, Shadowsocks, Hysteria2, AnyTLS, ShadowTLS v3, and TUIC v5 tunnels."
            } else if (hasLibv2ray) {
                "Bundled Xray-core$versionSuffix native binary detected and ready for full proxy forwarding."
            } else {
                "Full parser, JSON compiler, and Android TUN are ready, but no native proxy binary was found."
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
                "پروتکل ${parsed.protocol.displayName} توسط موتور sing-box/Xray پشتیبانی نمی‌شود.",
                "Protocol ${parsed.protocol.displayName} is not handled by sing-box/Xray adapter."
            )
        }

        val hasSingBox = SingBoxNativeBridge.isAvailable()
        val singBoxOnly = parsed.protocol in setOf(
            VpnProtocol.TUIC,
            VpnProtocol.HYSTERIA2,
            VpnProtocol.ANYTLS,
            VpnProtocol.SHADOWTLS
        )
        val useLibv2ray = XrayNativeBridge.isAvailable() && !singBoxOnly && !hasSingBox

        val compiledJson = try {
            if (useLibv2ray || !hasSingBox) {
                XrayJsonCompiler.compile(parsed, appSettings, engineSettings)
            } else {
                SingBoxJsonCompiler.compile(parsed, appSettings, engineSettings)
            }
        } catch (e: Exception) {
            return EngineValidationResult.Invalid(
                "خطا در تولید پیکربندی هسته: ${e.message}",
                "Failed compiling core JSON: ${e.message}"
            )
        }

        val hasNative = hasSingBox || useLibv2ray
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
                engineModeLabelFa = when {
                    hasSingBox -> "تونل بومی sing-box (libbox)"
                    useLibv2ray -> "تونل بومی Xray-core"
                    else -> "TUN آماده هسته بومی"
                },
                engineModeLabelEn = when {
                    hasSingBox -> "Native sing-box Tunnel (libbox)"
                    useLibv2ray -> "Native Xray-core Tunnel"
                    else -> "TUN Bridge Ready"
                }
            )
        )
    }
}

/**
 * Adapter 3: AmneziaWG Native Engine (backed by com.zaneschepke:amneziawg-android GoBackend + libam-go.so).
 */
class AmneziaWgEngineAdapter : VpnEngineAdapter {
    override val engineId: String = "amneziawg"
    override val engineName: String = "AmneziaWG Go (libam-go)"
    override val supportedProtocols: Set<VpnProtocol> = setOf(VpnProtocol.AMNEZIA_WG)
    override val license: String = "Apache-2.0 / GPL-2.0 (amneziawg-android)"

    override fun inspectCapability(context: Context): EngineDiagnosticInfo {
        val hasAwgNative = NativeLibraryLocator.hasClass("org.amnezia.awg.backend.GoBackend")
        return EngineDiagnosticInfo(
            engineId = engineId,
            engineName = engineName,
            protocols = listOf(VpnProtocol.AMNEZIA_WG),
            tier = if (hasAwgNative) EngineSupportTier.NATIVE_BUNDLED else EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE,
            nativeLibraryFound = hasAwgNative,
            nativeLibraryName = "libam-go.so",
            license = license,
            noteFa = if (hasAwgNative) {
                "هسته بومی AmneziaWG (libam-go.so) با پشتیبانی کامل از پارامترهای ضد‌فیلتر Jc/Jmin/Jmax/S1/S2/H1..H4 مستقیماً داخل APK گنجانده شده است."
            } else {
                "پارس پارامترهای مبهم‌سازی AmneziaWG آماده است، اما کتابخانه بومی libam-go.so یافت نشد."
            },
            noteEn = if (hasAwgNative) {
                "Native AmneziaWG Go core (libam-go.so) with full Jc/Jmin/Jmax/S1/S2/H1..H4 obfuscation is bundled directly inside this APK."
            } else {
                "AmneziaWG obfuscation parser is ready, but libam-go.so was not found."
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
        val patchedIni = buildPatchedWgIni(parsed.rawPayload, appSettings, engineSettings, maxMtu = 1420)
        return try {
            AwgConfig.parse(BufferedReader(StringReader(patchedIni)))
            EngineValidationResult.Valid(
                EngineLaunchPlan(
                    engineId = engineId,
                    serverId = serverId,
                    serverName = parsed.displayName,
                    protocol = parsed.protocol,
                    remoteHost = parsed.host,
                    remotePort = parsed.port,
                    compiledConfig = patchedIni,
                    mtu = engineSettings.mtu.coerceIn(1280, 1420),
                    primaryDns = appSettings.primaryDns,
                    secondaryDns = appSettings.secondaryDns,
                    ipv6Enabled = appSettings.ipv6Enabled,
                    killSwitchEnabled = appSettings.killSwitchEnabled,
                    splitTunnelMode = appSettings.splitTunnelMode,
                    splitTunnelPackages = parsePackages(appSettings.splitTunnelPackagesCsv),
                    isFullNativeTunnel = hasNative,
                    engineModeLabelFa = "تونل بومی AmneziaWG Go",
                    engineModeLabelEn = "Native AmneziaWG Go Tunnel"
                )
            )
        } catch (e: Exception) {
            EngineValidationResult.Invalid(
                messageFa = "کانفیگ AmneziaWG در بررسی موتور بومی رد شد: ${e.message ?: "ساختار نامعتبر"}",
                messageEn = "AmneziaWG config validation failed: ${e.message ?: "invalid syntax"}"
            )
        }
    }
}

/**
 * Adapter 4: OpenVPN 3 Native Engine (backed by bundled `libovpn3.so` C++ Core).
 */
class OpenVpnEngineAdapter : VpnEngineAdapter {
    override val engineId: String = "openvpn"
    override val engineName: String = "OpenVPN 3 Core (libovpn3)"
    override val supportedProtocols: Set<VpnProtocol> = setOf(VpnProtocol.OPENVPN)
    override val license: String = "AGPL-3.0 / Apache-2.0 (OpenVPN 3 Core)"

    override fun inspectCapability(context: Context): EngineDiagnosticInfo {
        val hasNative = OpenVpn3NativeBridge.isClassPresent() ||
            NativeLibraryLocator.hasBundledLibrary(context, "libovpn3.so")
        return EngineDiagnosticInfo(
            engineId = engineId,
            engineName = engineName,
            protocols = listOf(VpnProtocol.OPENVPN),
            tier = if (hasNative) EngineSupportTier.NATIVE_BUNDLED else EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE,
            nativeLibraryFound = hasNative,
            nativeLibraryName = "libovpn3.so",
            license = license,
            noteFa = if (hasNative) {
                "هسته بومی OpenVPN 3 C++ (libovpn3.so) با مسدودسازی دستورات اسکریپتی ناامن مستقیماً داخل APK گنجانده شده و آماده اتصال واقعی OVPN است."
            } else {
                "پارس امن فایل‌های OVPN آماده است، اما باینری بومی libovpn3.so یافت نشد."
            },
            noteEn = if (hasNative) {
                "Native OpenVPN 3 C++ core (libovpn3.so) with unsafe script blocking is bundled inside this APK for real .ovpn tunnels."
            } else {
                "Secure .ovpn parsing is ready, but native libovpn3.so was not found."
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
                engineModeLabelFa = "تونل بومی OpenVPN 3",
                engineModeLabelEn = "Native OpenVPN 3 Tunnel"
            )
        )
    }
}

/**
 * Adapter 5: Mieru Adapter (`libmieru.so` chained into bundled `libbox.so` TUN).
 * Parser, JSON compiler, TUN chain, and GitHub Actions Go cross-compile CI are built-in.
 */
class MieruEngineAdapter : VpnEngineAdapter {
    override val engineId: String = "mieru"
    override val engineName: String = "Mieru Core (libmieru + TUN)"
    override val supportedProtocols: Set<VpnProtocol> = setOf(VpnProtocol.MIERU)
    override val license: String = "GPL-3.0 (enfein/mieru)"

    override fun inspectCapability(context: Context): EngineDiagnosticInfo {
        val hasNative = MieruNativeBridge.isAvailable(context)
        return EngineDiagnosticInfo(
            engineId = engineId,
            engineName = engineName,
            protocols = listOf(VpnProtocol.MIERU),
            tier = if (hasNative) EngineSupportTier.NATIVE_BUNDLED else EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE,
            nativeLibraryFound = hasNative,
            nativeLibraryName = MieruNativeBridge.BINARY_NAME,
            license = license,
            noteFa = if (hasNative) {
                "باینری بومی Mieru (libmieru.so) شناسایی شد و با اتصال به تونل sing-box آماده اجرا است."
            } else {
                "پارسر کامل mieru://، کامپایلر JSON و پل اتصال به TUN آماده است. با اجرای GitHub Actions CI پروژه، باینری libmieru.so به‌صورت خودکار کامپایل و به APK اضافه می‌شود."
            },
            noteEn = if (hasNative) {
                "Native Mieru binary (libmieru.so) detected and ready to chain through sing-box TUN."
            } else {
                "Full mieru:// parser, JSON compiler, and TUN bridge are ready. Run the project's GitHub Actions workflow to auto-compile and bundle libmieru.so into the APK."
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
        val hasNative = MieruNativeBridge.isAvailable(context) && SingBoxNativeBridge.isAvailable()
        val mieruJson = MieruNativeBridge.compileClientJson(parsed, MieruNativeBridge.LOCAL_SOCKS_PORT)
        val tunChainJson = SingBoxJsonCompiler.compileLocalSocksChain(
            localHost = "127.0.0.1",
            localPort = MieruNativeBridge.LOCAL_SOCKS_PORT,
            appSettings = appSettings,
            engineSettings = engineSettings
        )
        val envelope = JSONObject().apply {
            put("engineConfig", mieruJson)
            put("tunChainConfig", tunChainJson)
        }.toString()

        return EngineValidationResult.Valid(
            EngineLaunchPlan(
                engineId = engineId,
                serverId = serverId,
                serverName = parsed.displayName,
                protocol = parsed.protocol,
                remoteHost = parsed.host,
                remotePort = parsed.port,
                compiledConfig = envelope,
                mtu = engineSettings.mtu.coerceIn(1280, 1500),
                primaryDns = appSettings.primaryDns,
                secondaryDns = appSettings.secondaryDns,
                ipv6Enabled = appSettings.ipv6Enabled,
                killSwitchEnabled = appSettings.killSwitchEnabled,
                splitTunnelMode = appSettings.splitTunnelMode,
                splitTunnelPackages = parsePackages(appSettings.splitTunnelPackagesCsv),
                isFullNativeTunnel = hasNative,
                engineModeLabelFa = if (hasNative) "تونل بومی Mieru" else "پل Mieru آماده بیلد GitHub Actions",
                engineModeLabelEn = if (hasNative) "Native Mieru Tunnel" else "Mieru Bridge (Run GitHub CI)"
            )
        )
    }
}

/**
 * Adapter 6: Cloak Pluggable Transport Adapter (`libckclient.so` chained into bundled `libbox.so` Shadowsocks TUN).
 */
class CloakEngineAdapter : VpnEngineAdapter {
    override val engineId: String = "cloak"
    override val engineName: String = "Cloak Transport (libckclient + TUN)"
    override val supportedProtocols: Set<VpnProtocol> = setOf(VpnProtocol.CLOAK)
    override val license: String = "GPL-3.0 (cbeuw/Cloak)"

    override fun inspectCapability(context: Context): EngineDiagnosticInfo {
        val hasNative = CloakNativeBridge.isAvailable(context)
        return EngineDiagnosticInfo(
            engineId = engineId,
            engineName = engineName,
            protocols = listOf(VpnProtocol.CLOAK),
            tier = if (hasNative) EngineSupportTier.NATIVE_BUNDLED else EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE,
            nativeLibraryFound = hasNative,
            nativeLibraryName = CloakNativeBridge.BINARY_NAME,
            license = license,
            noteFa = if (hasNative) {
                "باینری بومی Cloak (libckclient.so) شناسایی شد و آماده مخفی‌سازی ترافیک روی TUN است."
            } else {
                "پارسر cloak:// و پلاگین ck-client، کامپایلر ckclient.json و پل TUN آماده است. با اجرای GitHub Actions CI پروژه، باینری libckclient.so به‌صورت خودکار کامپایل و داخل APK قرار می‌گیرد."
            },
            noteEn = if (hasNative) {
                "Native Cloak binary (libckclient.so) detected and ready for obfuscated tunneling."
            } else {
                "Full cloak:// & ck-client parser, ckclient.json compiler, and TUN bridge are ready. Run the project's GitHub Actions workflow to auto-compile libckclient.so."
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
        val hasNative = CloakNativeBridge.isAvailable(context) && SingBoxNativeBridge.isAvailable()
        val cloakJson = CloakNativeBridge.compileClientJson(parsed)
        val ssMethod = parsed.credentialsMap["ss_method"].orEmpty().ifBlank { "aes-256-gcm" }
        val ssPass = parsed.credentialsMap["ss_password"].orEmpty()
        val tunChainJson = if (ssPass.isNotBlank()) {
            SingBoxJsonCompiler.compileLocalShadowsocksChain(
                localHost = "127.0.0.1",
                localPort = CloakNativeBridge.LOCAL_PROXY_PORT,
                method = ssMethod,
                password = ssPass,
                appSettings = appSettings,
                engineSettings = engineSettings
            )
        } else {
            SingBoxJsonCompiler.compileLocalSocksChain(
                localHost = "127.0.0.1",
                localPort = CloakNativeBridge.LOCAL_PROXY_PORT,
                appSettings = appSettings,
                engineSettings = engineSettings
            )
        }
        val envelope = JSONObject().apply {
            put("engineConfig", cloakJson)
            put("tunChainConfig", tunChainJson)
        }.toString()

        return EngineValidationResult.Valid(
            EngineLaunchPlan(
                engineId = engineId,
                serverId = serverId,
                serverName = parsed.displayName,
                protocol = parsed.protocol,
                remoteHost = parsed.host,
                remotePort = parsed.port,
                compiledConfig = envelope,
                mtu = engineSettings.mtu.coerceIn(1280, 1500),
                primaryDns = appSettings.primaryDns,
                secondaryDns = appSettings.secondaryDns,
                ipv6Enabled = appSettings.ipv6Enabled,
                killSwitchEnabled = appSettings.killSwitchEnabled,
                splitTunnelMode = appSettings.splitTunnelMode,
                splitTunnelPackages = parsePackages(appSettings.splitTunnelPackagesCsv),
                isFullNativeTunnel = hasNative,
                engineModeLabelFa = if (hasNative) "تونل بومی Cloak" else "پل Cloak آماده بیلد GitHub Actions",
                engineModeLabelEn = if (hasNative) "Native Cloak Tunnel" else "Cloak Bridge (Run GitHub CI)"
            )
        )
    }
}

/**
 * Adapter 7: Lightway Adapter (`liblightway.so` / AAR bridge).
 */
class LightwayEngineAdapter : VpnEngineAdapter {
    override val engineId: String = "lightway"
    override val engineName: String = "Lightway Core (wolfSSL / NDK Bridge)"
    override val supportedProtocols: Set<VpnProtocol> = setOf(VpnProtocol.LIGHTWAY)
    override val license: String = "GPL-2.0 (ExpressVPN Lightway Core)"

    override fun inspectCapability(context: Context): EngineDiagnosticInfo {
        val hasNative = LightwayNativeBridge.isAvailable(context)
        return EngineDiagnosticInfo(
            engineId = engineId,
            engineName = engineName,
            protocols = listOf(VpnProtocol.LIGHTWAY),
            tier = if (hasNative) EngineSupportTier.NATIVE_BUNDLED else EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE,
            nativeLibraryFound = hasNative,
            nativeLibraryName = LightwayNativeBridge.BINARY_NAME,
            license = license,
            noteFa = if (hasNative) {
                "هسته بومی Lightway شناسایی شد و آماده اتصال است."
            } else {
                "پارسر lightway://، ساختار پروفایل و پل JNI آماده است. برای فعال‌سازی تونل کامل، ماژول کامپایل‌شده wolfSSL/Lightway AAR را در app/libs قرار دهید."
            },
            noteEn = if (hasNative) {
                "Native Lightway module detected and ready."
            } else {
                "Lightway URI parser, profile compiler, and JNI bridge are ready. Place a compiled Lightway wolfSSL AAR in app/libs/ to enable native Lightway tunneling."
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
        val hasNative = LightwayNativeBridge.isAvailable(context)
        val profileJson = LightwayNativeBridge.compileProfileJson(parsed)
        return EngineValidationResult.Valid(
            EngineLaunchPlan(
                engineId = engineId,
                serverId = serverId,
                serverName = parsed.displayName,
                protocol = parsed.protocol,
                remoteHost = parsed.host,
                remotePort = parsed.port,
                compiledConfig = profileJson,
                mtu = engineSettings.mtu.coerceIn(1280, 1500),
                primaryDns = appSettings.primaryDns,
                secondaryDns = appSettings.secondaryDns,
                ipv6Enabled = appSettings.ipv6Enabled,
                killSwitchEnabled = appSettings.killSwitchEnabled,
                splitTunnelMode = appSettings.splitTunnelMode,
                splitTunnelPackages = parsePackages(appSettings.splitTunnelPackagesCsv),
                isFullNativeTunnel = hasNative,
                engineModeLabelFa = if (hasNative) "تونل بومی Lightway" else "پل Lightway (نیازمند AAR کامپایل‌شده)",
                engineModeLabelEn = if (hasNative) "Native Lightway Tunnel" else "Lightway Bridge (Needs AAR)"
            )
        )
    }
}

private fun buildPatchedWgIni(
    rawIni: String,
    appSettings: AppSettingsEntity,
    engineSettings: EngineSettingsEntity,
    maxMtu: Int
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
            insertions.add("MTU = ${engineSettings.mtu.coerceIn(1280, maxMtu)}")
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

private fun parsePackages(csv: String): List<String> =
    csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
