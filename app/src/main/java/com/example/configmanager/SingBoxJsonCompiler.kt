package com.example.configmanager

import com.example.core.model.VpnProtocol
import com.example.data.local.AppSettingsEntity
import com.example.data.local.EngineSettingsEntity
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Compiles parsed VLESS (XTLS-Reality/Vision/TLS/WS/gRPC/HTTPUpgrade), VMess,
 * Trojan, Shadowsocks, Hysteria2, AnyTLS, ShadowTLS v3, and TUIC v5 configurations
 * into spec-compliant sing-box 1.12+ JSON for the bundled native `libbox.so` core.
 *
 * Also builds local SOCKS5 / Shadowsocks TUN-chain profiles used by pluggable cores
 * (Mieru `libmieru.so` and Cloak `libckclient.so`) so every protocol inherits
 * SHADORA's Android TUN, DNS hijack, Smart Routing, and Split Tunneling seamlessly.
 */
object SingBoxJsonCompiler {

    fun compile(
        parsed: ParsedVpnConfig,
        appSettings: AppSettingsEntity,
        engineSettings: EngineSettingsEntity
    ): String {
        val outbounds = buildProxyOutbounds(parsed, engineSettings)
        return buildBaseEnvelope(outbounds, appSettings, engineSettings)
    }

    /**
     * Compiles a sing-box TUN profile that forwards device traffic into a local SOCKS5 listener
     * (used to bridge native `libmieru.so` into Android VpnService TUN + Smart Routing).
     */
    fun compileLocalSocksChain(
        localHost: String,
        localPort: Int,
        appSettings: AppSettingsEntity,
        engineSettings: EngineSettingsEntity
    ): String {
        val socksOutbound = JSONObject().apply {
            put("type", "socks")
            put("tag", "proxy")
            put("server", localHost)
            put("server_port", localPort)
            put("version", "5")
        }
        return buildBaseEnvelope(listOf(socksOutbound), appSettings, engineSettings)
    }

    /**
     * Compiles a sing-box TUN profile that forwards device traffic via Shadowsocks into a local
     * Cloak `libckclient.so` listener (127.0.0.1:<localPort>).
     */
    fun compileLocalShadowsocksChain(
        localHost: String,
        localPort: Int,
        method: String,
        password: String,
        appSettings: AppSettingsEntity,
        engineSettings: EngineSettingsEntity
    ): String {
        val ssOutbound = JSONObject().apply {
            put("type", "shadowsocks")
            put("tag", "proxy")
            put("server", localHost)
            put("server_port", localPort)
            put("method", method.ifBlank { "aes-256-gcm" })
            put("password", password)
        }
        return buildBaseEnvelope(listOf(ssOutbound), appSettings, engineSettings)
    }

    private fun buildBaseEnvelope(
        proxyOutbounds: List<JSONObject>,
        appSettings: AppSettingsEntity,
        engineSettings: EngineSettingsEntity
    ): String {
        val root = JSONObject()

        // 1. Log configuration
        val level = when (engineSettings.logLevel.lowercase()) {
            "debug" -> "debug"
            "info" -> "info"
            "error" -> "error"
            "none" -> "fatal"
            else -> "warn"
        }
        root.put(
            "log",
            JSONObject().apply {
                put("level", level)
                put("timestamp", false)
            }
        )

        // 2. DNS with anti-loop resolver separation
        val primaryDns = appSettings.primaryDns.trim().ifBlank { "1.1.1.1" }
        val secondaryDns = appSettings.secondaryDns.trim().ifBlank { "8.8.8.8" }
        val dnsServers = JSONArray().apply {
            put(buildDnsServer(tag = "remote-dns", rawAddress = primaryDns, detour = "proxy"))
            put(buildDnsServer(tag = "local-dns", rawAddress = secondaryDns, detour = null))
        }
        val dnsRules = JSONArray()
        if (appSettings.bypassIranDomains) {
            dnsRules.put(
                JSONObject().apply {
                    put("domain_suffix", JSONArray(IRAN_DOMAIN_SUFFIXES))
                    put("server", "local-dns")
                }
            )
        }
        val customDirectBuckets = parseSingBoxDomainBuckets(appSettings.customDirectDomainsCsv)
        if (!customDirectBuckets.isEmpty()) {
            dnsRules.put(
                customDirectBuckets.toRuleJson().apply {
                    put("server", "local-dns")
                }
            )
        }

        root.put(
            "dns",
            JSONObject().apply {
                put("servers", dnsServers)
                if (dnsRules.length() > 0) {
                    put("rules", dnsRules)
                }
                put("final", "remote-dns")
                put("strategy", if (appSettings.ipv6Enabled) "prefer_ipv4" else "ipv4_only")
                put("independent_cache", true)
            }
        )

        // 3. TUN Inbound (Android VpnService fd supplied via PlatformInterface.openTun)
        val tunAddresses = JSONArray().apply {
            put("172.19.0.1/30")
            if (appSettings.ipv6Enabled) {
                put("fdfe:dcba:9876::1/126")
            }
        }
        val tunInbound = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "shadora0")
            put("address", tunAddresses)
            put("mtu", engineSettings.mtu.coerceIn(1280, 1500))
            put("auto_route", true)
            put("strict_route", true)
            put("stack", "gvisor")
            if (appSettings.bypassLanCidrs) {
                put("route_exclude_address", JSONArray(LAN_ROUTE_EXCLUDE_CIDRS))
            }
        }
        root.put("inbounds", JSONArray().put(tunInbound))

        // 4. Outbounds: primary proxy (+ optional detour chain) + direct
        val outbounds = JSONArray().apply {
            proxyOutbounds.forEach { put(it) }
            put(
                JSONObject().apply {
                    put("type", "direct")
                    put("tag", "direct")
                }
            )
        }
        root.put("outbounds", outbounds)

        // 5. Smart Routing
        val routeRules = JSONArray().apply {
            put(JSONObject().put("action", "sniff"))
            put(
                JSONObject().apply {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                }
            )
        }

        val customBlockBuckets = parseSingBoxDomainBuckets(appSettings.customBlockDomainsCsv)
        if (!customBlockBuckets.isEmpty()) {
            routeRules.put(
                customBlockBuckets.toRuleJson().apply {
                    put("action", "reject")
                }
            )
        }

        if (appSettings.bypassLanCidrs) {
            routeRules.put(
                JSONObject().apply {
                    put("ip_is_private", true)
                    put("outbound", "direct")
                }
            )
            routeRules.put(
                JSONObject().apply {
                    put("domain_suffix", JSONArray().put("local").put("localhost"))
                    put("outbound", "direct")
                }
            )
        }

        if (appSettings.bypassIranDomains) {
            routeRules.put(
                JSONObject().apply {
                    put("domain_suffix", JSONArray(IRAN_DOMAIN_SUFFIXES))
                    put("outbound", "direct")
                }
            )
        }

        if (!customDirectBuckets.isEmpty()) {
            routeRules.put(
                customDirectBuckets.toRuleJson().apply {
                    put("outbound", "direct")
                }
            )
        }

        root.put(
            "route",
            JSONObject().apply {
                put("auto_detect_interface", true)
                put("default_domain_resolver", "local-dns")
                put("rules", routeRules)
                put("final", "proxy")
            }
        )

        return root.toString(2)
    }

    private fun buildDnsServer(tag: String, rawAddress: String, detour: String?): JSONObject {
        val trimmed = rawAddress.trim()
        return JSONObject().apply {
            put("tag", tag)
            when {
                trimmed.startsWith("https://", ignoreCase = true) -> {
                    val uri = runCatching { URI(trimmed) }.getOrNull()
                    put("type", "https")
                    put("server", uri?.host ?: "1.1.1.1")
                    put("server_port", if ((uri?.port ?: -1) > 0) uri!!.port else 443)
                    put("path", uri?.rawPath?.ifBlank { "/dns-query" } ?: "/dns-query")
                }
                trimmed.startsWith("tls://", ignoreCase = true) -> {
                    val uri = runCatching { URI(trimmed) }.getOrNull()
                    put("type", "tls")
                    put("server", uri?.host ?: "1.1.1.1")
                    put("server_port", if ((uri?.port ?: -1) > 0) uri!!.port else 853)
                }
                else -> {
                    put("type", "udp")
                    put("server", trimmed.substringBefore(':').ifBlank { "1.1.1.1" })
                    put("server_port", 53)
                }
            }
            if (!detour.isNullOrBlank()) {
                put("detour", detour)
            }
        }
    }

    private fun buildProxyOutbounds(
        parsed: ParsedVpnConfig,
        engineSettings: EngineSettingsEntity
    ): List<JSONObject> {
        val params = parsed.parameters

        // ShadowTLS requires a 2-outbound chain: Shadowsocks ("proxy") -> detour "shadowtls-out"
        if (parsed.protocol == VpnProtocol.SHADOWTLS) {
            val stVersion = params["version"]?.toIntOrNull()?.coerceIn(1, 3) ?: 3
            val stPassword = parsed.credentialsMap["password"].orEmpty()
            val ssMethod = parsed.credentialsMap["ss_method"].orEmpty().ifBlank { "2022-blake3-aes-128-gcm" }
            val ssPassword = parsed.credentialsMap["ss_password"].orEmpty().ifBlank { stPassword }

            val ssOutbound = JSONObject().apply {
                put("type", "shadowsocks")
                put("tag", "proxy")
                put("method", ssMethod)
                put("password", ssPassword)
                put("detour", "shadowtls-out")
            }
            val shadowTlsOutbound = JSONObject().apply {
                put("type", "shadowtls")
                put("tag", "shadowtls-out")
                put("server", parsed.host)
                put("server_port", parsed.port)
                put("version", stVersion)
                if (stVersion >= 2 && stPassword.isNotBlank()) {
                    put("password", stPassword)
                }
                put("tls", buildTlsObject(parsed, engineSettings, forceTls = true) ?: JSONObject().put("enabled", true))
            }
            return listOf(ssOutbound, shadowTlsOutbound)
        }

        val outbound = JSONObject().apply {
            put("tag", "proxy")
            put("server", parsed.host)
            put("server_port", parsed.port)
        }

        when (parsed.protocol) {
            VpnProtocol.VLESS -> {
                outbound.put("type", "vless")
                outbound.put("uuid", parsed.credentialsMap["uuid"].orEmpty().ifBlank { parsed.credentialsMap["credential"].orEmpty() })
                val flow = parsed.credentialsMap["flow"].orEmpty()
                if (flow.isNotBlank()) {
                    outbound.put("flow", flow)
                }
                outbound.put("packet_encoding", "xudp")
                buildTlsObject(parsed, engineSettings, forceTls = false)?.let { outbound.put("tls", it) }
                buildTransportObject(parsed)?.let { outbound.put("transport", it) }
            }

            VpnProtocol.VMESS -> {
                outbound.put("type", "vmess")
                outbound.put("uuid", parsed.credentialsMap["uuid"].orEmpty().ifBlank { parsed.credentialsMap["credential"].orEmpty() })
                outbound.put("security", parsed.credentialsMap["security"]?.ifBlank { "auto" } ?: "auto")
                outbound.put("alter_id", parsed.credentialsMap["alterId"]?.toIntOrNull() ?: 0)
                outbound.put("packet_encoding", "xudp")
                buildTlsObject(parsed, engineSettings, forceTls = false)?.let { outbound.put("tls", it) }
                buildTransportObject(parsed)?.let { outbound.put("transport", it) }
            }

            VpnProtocol.TROJAN -> {
                outbound.put("type", "trojan")
                outbound.put("password", parsed.credentialsMap["password"].orEmpty().ifBlank { parsed.credentialsMap["credential"].orEmpty() })
                val useTls = parsed.security.lowercase() != "none"
                buildTlsObject(parsed, engineSettings, forceTls = useTls)?.let { outbound.put("tls", it) }
                buildTransportObject(parsed)?.let { outbound.put("transport", it) }
            }

            VpnProtocol.SHADOWSOCKS -> {
                outbound.put("type", "shadowsocks")
                outbound.put("method", parsed.credentialsMap["method"].orEmpty().ifBlank { "aes-256-gcm" })
                outbound.put("password", parsed.credentialsMap["password"].orEmpty().ifBlank { parsed.credentialsMap["credential"].orEmpty() })
                val pluginRaw = params["plugin"].orEmpty()
                if (pluginRaw.isNotBlank()) {
                    val pluginName = pluginRaw.substringBefore(';')
                    val pluginOpts = pluginRaw.substringAfter(';', "")
                    outbound.put("plugin", pluginName)
                    if (pluginOpts.isNotBlank()) {
                        outbound.put("plugin_opts", pluginOpts)
                    }
                }
            }

            VpnProtocol.HYSTERIA2 -> {
                outbound.put("type", "hysteria2")
                outbound.put("password", parsed.credentialsMap["password"].orEmpty())
                val obfsType = params["obfs"].orEmpty().lowercase()
                val obfsPass = parsed.credentialsMap["obfsPassword"].orEmpty()
                if (obfsType == "salamander" && obfsPass.isNotBlank()) {
                    outbound.put(
                        "obfs",
                        JSONObject().apply {
                            put("type", "salamander")
                            put("password", obfsPass)
                        }
                    )
                }
                params["upmbps"]?.toIntOrNull()?.takeIf { it > 0 }?.let { outbound.put("up_mbps", it) }
                params["downmbps"]?.toIntOrNull()?.takeIf { it > 0 }?.let { outbound.put("down_mbps", it) }
                val hy2Tls = buildTlsObject(parsed, engineSettings, forceTls = true) ?: JSONObject().put("enabled", true)
                if (!hy2Tls.has("alpn")) {
                    hy2Tls.put("alpn", JSONArray().put("h3"))
                }
                outbound.put("tls", hy2Tls)
            }

            VpnProtocol.ANYTLS -> {
                outbound.put("type", "anytls")
                outbound.put("password", parsed.credentialsMap["password"].orEmpty())
                val anyTlsObj = buildTlsObject(parsed, engineSettings, forceTls = true) ?: JSONObject().put("enabled", true)
                outbound.put("tls", anyTlsObj)
            }

            VpnProtocol.TUIC -> {
                outbound.put("type", "tuic")
                outbound.put("uuid", parsed.credentialsMap["uuid"].orEmpty())
                val password = parsed.credentialsMap["password"].orEmpty()
                if (password.isNotBlank()) {
                    outbound.put("password", password)
                }
                val cc = params["congestion_control"]?.lowercase()?.ifBlank { "bbr" } ?: "bbr"
                outbound.put("congestion_control", cc)
                val udpMode = params["udp_relay_mode"]?.lowercase()?.ifBlank { "native" } ?: "native"
                if (udpMode == "native" || udpMode == "quic") {
                    outbound.put("udp_relay_mode", udpMode)
                }
                if (params["reduce_rtt"] == "1" || params["zero_rtt_handshake"] == "true") {
                    outbound.put("zero_rtt_handshake", true)
                }
                val tuicTls = buildTlsObject(parsed, engineSettings, forceTls = true) ?: JSONObject().put("enabled", true)
                if (!tuicTls.has("alpn")) {
                    tuicTls.put("alpn", JSONArray().put("h3"))
                }
                if (params["allow_insecure"] == "1" || params["allowInsecure"] == "1") {
                    tuicTls.put("insecure", true)
                }
                if (params["disable_sni"] == "1") {
                    tuicTls.put("disable_sni", true)
                }
                outbound.put("tls", tuicTls)
            }

            else -> {
                throw IllegalArgumentException("SingBoxJsonCompiler does not support ${parsed.protocol}")
            }
        }

        val flow = parsed.credentialsMap["flow"].orEmpty()
        val supportsMux = parsed.protocol in setOf(
            VpnProtocol.VLESS,
            VpnProtocol.VMESS,
            VpnProtocol.TROJAN,
            VpnProtocol.SHADOWSOCKS
        )
        if (engineSettings.muxEnabled && flow.isBlank() && supportsMux) {
            outbound.put(
                "multiplex",
                JSONObject().apply {
                    put("enabled", true)
                    put("protocol", "smux")
                    put("max_connections", 4)
                    put("min_streams", 4)
                }
            )
        }

        return listOf(outbound)
    }

    private fun buildTlsObject(
        parsed: ParsedVpnConfig,
        engineSettings: EngineSettingsEntity,
        forceTls: Boolean
    ): JSONObject? {
        val security = parsed.security.lowercase()
        val params = parsed.parameters
        if (!forceTls && security != "tls" && security != "reality") {
            return null
        }

        return JSONObject().apply {
            put("enabled", true)
            val sni = params["sni"]?.ifBlank { params["host"] }?.ifBlank { parsed.host } ?: parsed.host
            put("server_name", sni)

            val allowInsecure = engineSettings.allowInsecureTls ||
                params["allowInsecure"] == "1" ||
                params["insecure"] == "1" ||
                params["allow_insecure"] == "1"
            if (allowInsecure && security != "reality") {
                put("insecure", true)
            }

            val alpnRaw = params["alpn"].orEmpty()
            if (alpnRaw.isNotBlank()) {
                val alpnList = alpnRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (alpnList.isNotEmpty()) {
                    put("alpn", JSONArray(alpnList))
                }
            }

            val fp = params["fp"].orEmpty().ifBlank { if (security == "reality") "chrome" else "" }
            val supportsUtls = parsed.protocol !in setOf(VpnProtocol.TUIC, VpnProtocol.HYSTERIA2)
            if (fp.isNotBlank() && supportsUtls) {
                put(
                    "utls",
                    JSONObject().apply {
                        put("enabled", true)
                        put("fingerprint", fp)
                    }
                )
            }

            if (security == "reality") {
                put(
                    "reality",
                    JSONObject().apply {
                        put("enabled", true)
                        put("public_key", parsed.credentialsMap["pbk"].orEmpty())
                        val sid = parsed.credentialsMap["sid"].orEmpty()
                        if (sid.isNotBlank()) {
                            put("short_id", sid)
                        }
                    }
                )
            }
        }
    }

    private fun buildTransportObject(parsed: ParsedVpnConfig): JSONObject? {
        val network = parsed.transport.lowercase()
        val params = parsed.parameters
        return when (network) {
            "ws" -> JSONObject().apply {
                put("type", "ws")
                val rawPath = params["path"].orEmpty().ifBlank { "/" }
                val edMatch = Regex("[?&]ed=(\\d+)").find(rawPath)
                if (edMatch != null) {
                    val cleanPath = rawPath.replace(Regex("[?&]ed=\\d+"), "").ifBlank { "/" }
                    put("path", cleanPath)
                    put("max_early_data", edMatch.groupValues[1].toIntOrNull() ?: 2048)
                    put("early_data_header_name", "Sec-WebSocket-Protocol")
                } else {
                    put("path", rawPath)
                }
                val hostHeader = params["host"].orEmpty()
                if (hostHeader.isNotBlank()) {
                    put("headers", JSONObject().put("Host", hostHeader))
                }
            }

            "grpc" -> JSONObject().apply {
                put("type", "grpc")
                put("service_name", params["serviceName"].orEmpty().ifBlank { params["path"].orEmpty() })
            }

            "httpupgrade" -> JSONObject().apply {
                put("type", "httpupgrade")
                put("path", params["path"].orEmpty().ifBlank { "/" })
                val hostHeader = params["host"].orEmpty()
                if (hostHeader.isNotBlank()) {
                    put("host", hostHeader)
                }
            }

            "h2", "http" -> JSONObject().apply {
                put("type", "http")
                put("path", params["path"].orEmpty().ifBlank { "/" })
                val hostHeader = params["host"].orEmpty()
                if (hostHeader.isNotBlank()) {
                    put("host", JSONArray(hostHeader.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                }
            }

            else -> null
        }
    }

    private data class DomainRuleBuckets(
        val fullDomains: List<String>,
        val suffixes: List<String>,
        val keywords: List<String>,
        val regexes: List<String>
    ) {
        fun isEmpty(): Boolean =
            fullDomains.isEmpty() && suffixes.isEmpty() && keywords.isEmpty() && regexes.isEmpty()

        fun toRuleJson(): JSONObject = JSONObject().apply {
            if (fullDomains.isNotEmpty()) put("domain", JSONArray(fullDomains))
            if (suffixes.isNotEmpty()) put("domain_suffix", JSONArray(suffixes))
            if (keywords.isNotEmpty()) put("domain_keyword", JSONArray(keywords))
            if (regexes.isNotEmpty()) put("domain_regex", JSONArray(regexes))
        }
    }

    private fun parseSingBoxDomainBuckets(rawCsv: String): DomainRuleBuckets {
        val full = mutableListOf<String>()
        val suffix = mutableListOf<String>()
        val keyword = mutableListOf<String>()
        val regex = mutableListOf<String>()

        rawCsv.split(',', '\n', ';', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.contains("..") && it.length <= 180 }
            .distinct()
            .forEach { token ->
                when {
                    token.startsWith("full:") -> full.add(token.removePrefix("full:"))
                    token.startsWith("domain:") -> suffix.add(token.removePrefix("domain:"))
                    token.startsWith("keyword:") -> keyword.add(token.removePrefix("keyword:"))
                    token.startsWith("regexp:") -> regex.add(token.removePrefix("regexp:"))
                    token.startsWith("geosite:") -> Unit
                    else -> suffix.add(token)
                }
            }
        return DomainRuleBuckets(full, suffix, keyword, regex)
    }

    private val LAN_ROUTE_EXCLUDE_CIDRS: List<String> = listOf(
        "10.0.0.0/8",
        "100.64.0.0/10",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "fc00::/7",
        "fe80::/10"
    )

    val IRAN_DOMAIN_SUFFIXES: List<String> = listOf(
        "ir",
        "shaparak.ir",
        "snapp.ir",
        "digikala.com",
        "divar.ir",
        "aparat.com",
        "rubika.ir",
        "eitaa.com",
        "bale.ai",
        "mellatbank.com",
        "bmi.ir",
        "tejaratbank.ir",
        "banksepah.ir",
        "blubank.com",
        "tapsi.ir",
        "torob.com",
        "telewebion.com",
        "filimo.com",
        "namava.ir",
        "sheypoor.com",
        "alibaba.ir",
        "mci.ir",
        "irancell.ir"
    )
}
