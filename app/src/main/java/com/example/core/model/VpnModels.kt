package com.example.core.model

enum class EngineSupportTier {
    /** Native compiled binary (e.g. libwg-go.so) is bundled directly inside this APK. */
    NATIVE_BUNDLED,

    /**
     * Full config parsing, validation, engine JSON/OVPN compilation, Android VpnService TUN,
     * DNS/IPv6/Split-Tunneling, and TCP/TLS server probe are implemented.
     * Drops seamlessly into native packet forwarding when libv2ray.aar / libovpnexec.so is bundled.
     */
    TUN_BRIDGE_READY_FOR_CORE,

    /** Protocol is recognized so configs are never misclassified, but no native engine is bundled. */
    UNSUPPORTED_NO_ENGINE
}

enum class VpnProtocol(
    val displayName: String,
    val engineId: String,
    val defaultTier: EngineSupportTier,
    val uriSchemes: List<String>
) {
    VLESS("VLESS", "xray", EngineSupportTier.NATIVE_BUNDLED, listOf("vless")),
    VMESS("VMess", "xray", EngineSupportTier.NATIVE_BUNDLED, listOf("vmess")),
    TROJAN("Trojan", "xray", EngineSupportTier.NATIVE_BUNDLED, listOf("trojan")),
    SHADOWSOCKS("Shadowsocks", "xray", EngineSupportTier.NATIVE_BUNDLED, listOf("ss")),
    HYSTERIA2("Hysteria2", "xray", EngineSupportTier.NATIVE_BUNDLED, listOf("hy2", "hysteria2")),
    ANYTLS("AnyTLS", "xray", EngineSupportTier.NATIVE_BUNDLED, listOf("anytls")),
    SHADOWTLS("ShadowTLS", "xray", EngineSupportTier.NATIVE_BUNDLED, listOf("shadowtls")),
    TUIC("TUIC", "xray", EngineSupportTier.NATIVE_BUNDLED, listOf("tuic")),
    WIREGUARD("WireGuard", "wireguard", EngineSupportTier.NATIVE_BUNDLED, listOf("wg", "wireguard")),
    AMNEZIA_WG("AmneziaWG", "amneziawg", EngineSupportTier.NATIVE_BUNDLED, listOf("awg", "amneziawg")),
    OPENVPN("OpenVPN", "openvpn", EngineSupportTier.NATIVE_BUNDLED, listOf("ovpn", "openvpn")),
    MIERU("Mieru", "mieru", EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE, listOf("mieru")),
    CLOAK("Cloak", "cloak", EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE, listOf("cloak")),
    LIGHTWAY("Lightway", "lightway", EngineSupportTier.TUN_BRIDGE_READY_FOR_CORE, listOf("lightway"));

    companion object {
        fun fromName(name: String): VpnProtocol =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: VLESS
    }
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Preparing : ConnectionState

    data class Connecting(
        val serverId: Long,
        val serverName: String,
        val protocol: VpnProtocol,
        val stage: String
    ) : ConnectionState

    data class Connected(
        val serverId: Long,
        val serverName: String,
        val protocol: VpnProtocol,
        val engineId: String,
        val engineModeLabel: String,
        val isFullNativeTunnel: Boolean,
        val startedAtMillis: Long,
        val localAddress: String,
        val dnsServers: List<String>,
        val underlyingNetwork: String
    ) : ConnectionState

    data class Reconnecting(
        val serverId: Long,
        val serverName: String,
        val protocol: VpnProtocol,
        val attempt: Int,
        val reason: String
    ) : ConnectionState

    data object Disconnecting : ConnectionState

    data class Error(
        val code: String,
        val messageFa: String,
        val messageEn: String
    ) : ConnectionState
}

data class TrafficSnapshot(
    val rxBytesPerSec: Long = 0L,
    val txBytesPerSec: Long = 0L,
    val totalRxBytes: Long = 0L,
    val totalTxBytes: Long = 0L,
    val elapsedSeconds: Long = 0L,
    val rxHistory: List<Float> = emptyList(),
    val txHistory: List<Float> = emptyList()
)

enum class SplitTunnelMode {
    ALL_APPS,
    BYPASS_SELECTED,
    ONLY_SELECTED
}

enum class AppThemeMode {
    OBSIDIAN_DARK,
    SILVER_LIGHT,
    SYSTEM
}

enum class AppLanguage(val code: String, val isRtl: Boolean) {
    FA("fa", true),
    EN("en", false);

    companion object {
        fun fromCode(code: String): AppLanguage =
            if (code.equals("en", ignoreCase = true)) EN else FA
    }
}

enum class SortOrder {
    PING_ASC,
    NAME_ASC,
    PROTOCOL,
    RECENT
}

enum class RefreshIntervalHours(val hours: Int) {
    MANUAL(0),
    EVERY_6H(6),
    EVERY_12H(12),
    EVERY_24H(24),
    EVERY_72H(72);

    companion object {
        fun fromHours(hours: Int): RefreshIntervalHours =
            entries.firstOrNull { it.hours == hours } ?: MANUAL
    }
}

data class EngineDiagnosticInfo(
    val engineId: String,
    val engineName: String,
    val protocols: List<VpnProtocol>,
    val tier: EngineSupportTier,
    val nativeLibraryFound: Boolean,
    val nativeLibraryName: String?,
    val license: String,
    val noteFa: String,
    val noteEn: String
)
