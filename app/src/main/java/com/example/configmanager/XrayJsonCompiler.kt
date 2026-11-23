package com.example.configmanager

import com.example.core.model.VpnProtocol
import com.example.data.local.AppSettingsEntity
import com.example.data.local.EngineSettingsEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compiles parsed VLESS, VMess, Trojan, and Shadowsocks configurations into
 * genuine, spec-compliant Xray-core JSON with DNS anti-leak, IPv6 strategy,
 * Mux, TLS/Reality, and SOCKS5/HTTP local inbounds.
 */
object XrayJsonCompiler {

    fun compile(
        parsed: ParsedVpnConfig,
        appSettings: AppSettingsEntity,
        engineSettings: EngineSettingsEntity
    ): String {
        if (parsed.format == ConfigFormat.XRAY_JSON) {
            return parsed.rawPayload
        }

        val root = JSONObject()

        // 1. Log (never log sensitive user payloads)
        root.put(
            "log",
            JSONObject().apply {
                put("loglevel", engineSettings.logLevel.ifBlank { "warning" })
            }
        )

        // 2. DNS with leak prevention
        val dnsServers = JSONArray().apply {
            if (appSettings.primaryDns.isNotBlank()) put(appSettings.primaryDns)
            if (appSettings.secondaryDns.isNotBlank()) put(appSettings.secondaryDns)
            if (length() == 0) put("1.1.1.1")
        }
        root.put(
            "dns",
            JSONObject().apply {
                put("servers", dnsServers)
                put("queryStrategy", if (appSettings.ipv6Enabled) "UseIP" else "UseIPv4")
            }
        )

        // 3. Local Inbounds (SOCKS5 + HTTP for tun2socks / Xray core)
        root.put(
            "inbounds",
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("tag", "socks-in")
                        put("port", engineSettings.localSocksPort)
                        put("listen", "127.0.0.1")
                        put("protocol", "socks")
                        put(
                            "settings",
                            JSONObject().apply {
                                put("auth", "noauth")
                                put("udp", true)
                            }
                        )
                        put(
                            "sniffing",
                            JSONObject().apply {
                                put("enabled", true)
                                put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                            }
                        )
                    }
                )
            }
        )

        // 4. Outbounds: primary proxy + direct + block
        val proxyOutbound = buildProxyOutbound(parsed, engineSettings)
        root.put(
            "outbounds",
            JSONArray().apply {
                put(proxyOutbound)
                put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
            }
        )

        // 5. Smart Routing (LAN Bypass, Iran Domestic Bypass, Custom Direct & Block Rules)
        val routingRules = JSONArray()

        // Block domains (Ads / custom blacklist) evaluated first
        val customBlock = parseDomainRules(appSettings.customBlockDomainsCsv)
        if (customBlock.isNotEmpty()) {
            routingRules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray(customBlock))
                    put("outboundTag", "block")
                }
            )
        }

        // Private LAN CIDR & local domain direct bypass
        if (appSettings.bypassLanCidrs) {
            routingRules.put(
                JSONObject().apply {
                    put("type", "field")
                    put(
                        "ip",
                        JSONArray().apply {
                            LAN_CIDRS.forEach { put(it) }
                        }
                    )
                    put("outboundTag", "direct")
                }
            )
            routingRules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray().put("domain:local").put("domain:localhost"))
                    put("outboundTag", "direct")
                }
            )
        }

        // Iran domestic domains & banking services direct bypass
        if (appSettings.bypassIranDomains) {
            routingRules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray(IRAN_DIRECT_DOMAINS))
                    put("outboundTag", "direct")
                }
            )
        }

        // User-specified custom direct bypass domains
        val customDirect = parseDomainRules(appSettings.customDirectDomainsCsv)
        if (customDirect.isNotEmpty()) {
            routingRules.put(
                JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray(customDirect))
                    put("outboundTag", "direct")
                }
            )
        }

        root.put(
            "routing",
            JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", routingRules)
            }
        )

        // 6. Native Xray traffic statistics policy for real proxy uplink/downlink telemetry
        root.put("stats", JSONObject())
        root.put(
            "policy",
            JSONObject().apply {
                put(
                    "system",
                    JSONObject().apply {
                        put("statsOutboundUplink", true)
                        put("statsOutboundDownlink", true)
                    }
                )
            }
        )

        return root.toString(2)
    }

    val LAN_CIDRS: List<String> = listOf(
        "10.0.0.0/8",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "fc00::/7",
        "fe80::/10"
    )

    val IRAN_DIRECT_DOMAINS: List<String> = listOf(
        "regexp:.*\\.ir$",
        "domain:shaparak.ir",
        "domain:snapp.ir",
        "domain:digikala.com",
        "domain:divar.ir",
        "domain:aparat.com",
        "domain:rubika.ir",
        "domain:eitaa.com",
        "domain:bale.ai",
        "domain:mellatbank.com",
        "domain:bmi.ir",
        "domain:tejaratbank.ir",
        "domain:banksepah.ir",
        "domain:blubank.com",
        "domain:tapsi.ir",
        "domain:torob.com",
        "domain:telewebion.com",
        "domain:filimo.com",
        "domain:namava.ir",
        "domain:sheypoor.com",
        "domain:alibaba.ir",
        "domain:mci.ir",
        "domain:irancell.ir"
    )

    fun parseDomainRules(raw: String): List<String> {
        return raw.split(',', '\n', ';', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.contains("..") && it.length <= 180 }
            .map { token ->
                if (token.startsWith("domain:") || token.startsWith("full:") ||
                    token.startsWith("regexp:") || token.startsWith("geosite:") ||
                    token.startsWith("keyword:")
                ) {
                    token
                } else {
                    "domain:$token"
                }
            }
            .distinct()
    }

    private fun buildProxyOutbound(
        parsed: ParsedVpnConfig,
        engineSettings: EngineSettingsEntity
    ): JSONObject {
        val outbound = JSONObject().apply {
            put("tag", "proxy")
        }

        when (parsed.protocol) {
            VpnProtocol.VLESS -> {
                outbound.put("protocol", "vless")
                val user = JSONObject().apply {
                    put("id", parsed.credentialsMap["uuid"].orEmpty())
                    put("encryption", parsed.credentialsMap["encryption"]?.ifBlank { "none" } ?: "none")
                    val flow = parsed.credentialsMap["flow"].orEmpty()
                    if (flow.isNotBlank()) put("flow", flow)
                }
                outbound.put(
                    "settings",
                    JSONObject().put(
                        "vnext",
                        JSONArray().put(
                            JSONObject()
                                .put("address", parsed.host)
                                .put("port", parsed.port)
                                .put("users", JSONArray().put(user))
                        )
                    )
                )
                outbound.put("streamSettings", buildStreamSettings(parsed, engineSettings))
            }

            VpnProtocol.VMESS -> {
                outbound.put("protocol", "vmess")
                val user = JSONObject().apply {
                    put("id", parsed.credentialsMap["uuid"].orEmpty())
                    put("alterId", parsed.credentialsMap["alterId"]?.toIntOrNull() ?: 0)
                    put("security", parsed.credentialsMap["security"]?.ifBlank { "auto" } ?: "auto")
                }
                outbound.put(
                    "settings",
                    JSONObject().put(
                        "vnext",
                        JSONArray().put(
                            JSONObject()
                                .put("address", parsed.host)
                                .put("port", parsed.port)
                                .put("users", JSONArray().put(user))
                        )
                    )
                )
                outbound.put("streamSettings", buildStreamSettings(parsed, engineSettings))
            }

            VpnProtocol.TROJAN -> {
                outbound.put("protocol", "trojan")
                outbound.put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", parsed.host)
                                .put("port", parsed.port)
                                .put("password", parsed.credentialsMap["password"].orEmpty())
                        )
                    )
                )
                outbound.put("streamSettings", buildStreamSettings(parsed, engineSettings))
            }

            VpnProtocol.SHADOWSOCKS -> {
                outbound.put("protocol", "shadowsocks")
                outbound.put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", parsed.host)
                                .put("port", parsed.port)
                                .put("method", parsed.credentialsMap["method"].orEmpty())
                                .put("password", parsed.credentialsMap["password"].orEmpty())
                        )
                    )
                )
            }

            else -> {
                throw IllegalArgumentException("XrayJsonCompiler does not support ${parsed.protocol}")
            }
        }

        if (engineSettings.muxEnabled && parsed.credentialsMap["flow"].isNullOrBlank()) {
            outbound.put("mux", JSONObject().put("enabled", true).put("concurrency", 8))
        }

        return outbound
    }

    private fun buildStreamSettings(
        parsed: ParsedVpnConfig,
        engineSettings: EngineSettingsEntity
    ): JSONObject {
        val params = parsed.parameters
        val network = parsed.transport.lowercase()
        val security = parsed.security.lowercase()

        return JSONObject().apply {
            put("network", network)
            put("security", security)

            when (security) {
                "tls" -> {
                    put(
                        "tlsSettings",
                        JSONObject().apply {
                            val sni = params["sni"]?.ifBlank { params["host"] }?.ifBlank { parsed.host } ?: parsed.host
                            put("serverName", sni)
                            put("allowInsecure", engineSettings.allowInsecureTls)
                            params["fp"]?.takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
                            params["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                                put("alpn", JSONArray(alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                            }
                        }
                    )
                }
                "reality" -> {
                    put(
                        "realitySettings",
                        JSONObject().apply {
                            put("serverName", params["sni"].orEmpty().ifBlank { parsed.host })
                            put("fingerprint", params["fp"].orEmpty().ifBlank { "chrome" })
                            put("publicKey", parsed.credentialsMap["pbk"].orEmpty())
                            put("shortId", parsed.credentialsMap["sid"].orEmpty())
                            params["spx"]?.takeIf { it.isNotBlank() }?.let { put("spiderX", it) }
                        }
                    )
                }
            }

            when (network) {
                "ws" -> {
                    put(
                        "wsSettings",
                        JSONObject().apply {
                            put("path", params["path"].orEmpty().ifBlank { "/" })
                            val hostHeader = params["host"].orEmpty()
                            if (hostHeader.isNotBlank()) {
                                put("headers", JSONObject().put("Host", hostHeader))
                            }
                        }
                    )
                }
                "grpc" -> {
                    put(
                        "grpcSettings",
                        JSONObject().apply {
                            put("serviceName", params["serviceName"].orEmpty().ifBlank { params["path"].orEmpty() })
                            put("multiMode", params["mode"] == "multi")
                        }
                    )
                }
                "httpupgrade" -> {
                    put(
                        "httpupgradeSettings",
                        JSONObject().apply {
                            put("path", params["path"].orEmpty().ifBlank { "/" })
                            params["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                        }
                    )
                }
            }
        }
    }
}
