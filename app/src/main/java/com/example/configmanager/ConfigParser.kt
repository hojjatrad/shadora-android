package com.example.configmanager

import com.example.core.model.VpnProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/**
 * Multi-protocol VPN configuration parser, validator, Base64 decoder, and canonical
 * deduplication fingerprinter.
 *
 * Supports:
 * - VLESS (`vless://`)
 * - VMess (`vmess://` Base64 JSON)
 * - Trojan (`trojan://`)
 * - Shadowsocks (`ss://` SIP002 & legacy, including `shadow-tls` and `ck-client` plugins)
 * - Hysteria2 (`hy2://`, `hysteria2://`)
 * - AnyTLS (`anytls://`)
 * - ShadowTLS (`shadowtls://`)
 * - TUIC v5 (`tuic://`)
 * - WireGuard & AmneziaWG (INI `.conf` including `Jc`/`Jmin`/`Jmax`/`S1`/`S2`/`H1..H4`)
 * - OpenVPN (`.ovpn` with security blocking of script/shell directives)
 * - Mieru (`mieru://`), Cloak (`cloak://`), Lightway (`lightway://`)
 * - Raw Xray JSON (`outbounds[]`)
 * - Multi-line and Base64-encoded subscription payloads
 */
class ConfigParser {

    fun isSubscriptionUrl(rawInput: String): Boolean {
        val cleaned = sanitizeInvisibleChars(rawInput).trim()
        if (cleaned.isEmpty() || cleaned.contains('\n') || cleaned.contains(' ')) return false
        val lower = cleaned.lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://")
    }

    fun parseBatch(rawInput: String): List<ParseOutcome> {
        val trimmed = sanitizeInvisibleChars(rawInput).trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. Check for OpenVPN document
        if (looksLikeOpenVpn(trimmed)) {
            return listOf(parseOpenVpn(trimmed))
        }

        // 2. Check for WireGuard / AmneziaWG INI document
        if (looksLikeWireGuardIni(trimmed)) {
            return listOf(parseWireGuardOrAmnezia(trimmed))
        }

        // 3. Check for raw JSON (Xray config or array)
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val jsonResults = parseXrayJson(trimmed)
            if (jsonResults.isNotEmpty()) return jsonResults
        }

        // 4. Split lines — if single non-URI Base64 blob, decode subscription content first
        val lines = extractConfigLines(trimmed)
        return lines.map { parseSingleLine(it) }
    }

    fun detectProtocolPreview(rawInput: String): VpnProtocol? {
        val trimmed = sanitizeInvisibleChars(rawInput).trim()
        if (trimmed.isEmpty() || isSubscriptionUrl(trimmed)) return null
        if (looksLikeOpenVpn(trimmed)) return VpnProtocol.OPENVPN
        if (looksLikeWireGuardIni(trimmed)) {
            return if (hasAmneziaObfuscationParams(trimmed)) {
                VpnProtocol.AMNEZIA_WG
            } else {
                VpnProtocol.WIREGUARD
            }
        }
        val firstLine = extractConfigLines(trimmed).firstOrNull()?.lowercase() ?: return null
        return when {
            firstLine.startsWith("vless://") -> VpnProtocol.VLESS
            firstLine.startsWith("vmess://") -> VpnProtocol.VMESS
            firstLine.startsWith("trojan://") -> VpnProtocol.TROJAN
            firstLine.startsWith("ss://") -> VpnProtocol.SHADOWSOCKS
            firstLine.startsWith("hy2://") || firstLine.startsWith("hysteria2://") -> VpnProtocol.HYSTERIA2
            firstLine.startsWith("anytls://") -> VpnProtocol.ANYTLS
            firstLine.startsWith("shadowtls://") -> VpnProtocol.SHADOWTLS
            firstLine.startsWith("tuic://") -> VpnProtocol.TUIC
            firstLine.startsWith("mieru://") -> VpnProtocol.MIERU
            firstLine.startsWith("lightway://") -> VpnProtocol.LIGHTWAY
            firstLine.startsWith("cloak://") -> VpnProtocol.CLOAK
            trimmed.startsWith("{") -> {
                val parsed = parseXrayJson(trimmed).firstOrNull() as? ParseOutcome.Success
                parsed?.config?.protocol
            }
            else -> null
        }
    }

    fun decodeBase64Flexible(input: String): String? {
        val cleaned = sanitizeInvisibleChars(input)
            .trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .replace("\t", "")
        if (cleaned.length < 8) return null
        val normalized = cleaned.replace('-', '+').replace('_', '/')
        val padded = when (normalized.length % 4) {
            2 -> "$normalized=="
            3 -> "$normalized="
            0 -> normalized
            else -> return null
        }
        return try {
            val bytes = Base64.getDecoder().decode(padded)
            String(bytes, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun sanitizeInvisibleChars(text: String): String {
        return text
            .replace("\uFEFF", "")
            .replace("\u200B", "")
            .replace("\u200E", "")
            .replace("\u200F", "")
    }

    private fun extractConfigLines(raw: String): List<String> {
        val directLines = raw.lineSequence()
            .map { sanitizeInvisibleChars(it).trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .toList()

        val knownSchemePattern = Regex(
            "^(vless|vmess|trojan|ss|hy2|hysteria2|anytls|shadowtls|tuic|mieru|lightway|cloak)://",
            RegexOption.IGNORE_CASE
        )
        if (directLines.any { knownSchemePattern.containsMatchIn(it) }) {
            return directLines.filter { knownSchemePattern.containsMatchIn(it) }
        }

        // Try Base64 subscription decode
        val decoded = decodeBase64Flexible(raw)
        if (decoded != null) {
            val decodedLines = decoded.lineSequence()
                .map { sanitizeInvisibleChars(it).trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
                .toList()
            if (decodedLines.any { knownSchemePattern.containsMatchIn(it) }) {
                return decodedLines.filter { knownSchemePattern.containsMatchIn(it) }
            }
        }
        return directLines
    }

    private fun parseSingleLine(line: String): ParseOutcome {
        val lower = line.lowercase()
        return when {
            lower.startsWith("vless://") -> parseVless(line)
            lower.startsWith("vmess://") -> parseVmess(line)
            lower.startsWith("trojan://") -> parseTrojan(line)
            lower.startsWith("ss://") -> parseShadowsocks(line)
            lower.startsWith("hy2://") || lower.startsWith("hysteria2://") -> parseHysteria2(line)
            lower.startsWith("anytls://") -> parseAnyTls(line)
            lower.startsWith("shadowtls://") -> parseShadowTls(line)
            lower.startsWith("tuic://") -> parseTuic(line)
            lower.startsWith("mieru://") -> parseMieru(line)
            lower.startsWith("cloak://") -> parseCloak(line)
            lower.startsWith("lightway://") -> parseLightway(line)
            else -> ParseOutcome.Failure(
                rawSnippet = line.take(40),
                reasonFa = "فرمت کانفیگ ناشناخته یا نامعتبر است.",
                reasonEn = "Unrecognized or invalid configuration format."
            )
        }
    }

    private data class ParsedUriParts(
        val userInfo: String,
        val host: String,
        val port: Int,
        val rawQuery: String,
        val rawFragment: String
    )

    /**
     * Tolerant URI splitter for VLESS/Trojan links so raw spaces, emojis, brackets,
     * pipes, or Persian remarks after `#` never fail strict java.net.URI parsing.
     */
    private fun splitTolerantUri(rawUri: String): ParsedUriParts? {
        val withoutScheme = rawUri.substringAfter("://", "")
        if (withoutScheme.isEmpty()) return null
        val fragmentIdx = withoutScheme.indexOf('#')
        val beforeFragment = if (fragmentIdx >= 0) withoutScheme.substring(0, fragmentIdx) else withoutScheme
        val rawFragment = if (fragmentIdx >= 0) withoutScheme.substring(fragmentIdx + 1) else ""

        val queryIdx = beforeFragment.indexOf('?')
        val authorityAndPath = if (queryIdx >= 0) beforeFragment.substring(0, queryIdx) else beforeFragment
        val rawQuery = if (queryIdx >= 0) beforeFragment.substring(queryIdx + 1) else ""

        val authority = authorityAndPath.substringBefore('/').trim()
        val atIdx = authority.lastIndexOf('@')
        if (atIdx <= 0 || atIdx >= authority.length - 1) return null

        val userInfo = authority.substring(0, atIdx).trim()
        val hostPortStr = authority.substring(atIdx + 1).trim()
        val hostPort = splitHostPort(hostPortStr) ?: return null

        return ParsedUriParts(
            userInfo = userInfo,
            host = hostPort.first,
            port = hostPort.second,
            rawQuery = rawQuery,
            rawFragment = rawFragment
        )
    }

    private fun parseVless(rawUri: String): ParseOutcome {
        val parts = splitTolerantUri(rawUri)
            ?: return invalid("vless", "ساختار لینک VLESS نامعتبر است.", "Malformed VLESS URI.", VpnProtocol.VLESS)

        val uuid = urlDecode(parts.userInfo).trim()
        val host = parts.host
        val port = parts.port

        if (!isValidUuidOrToken(uuid)) {
            return invalid("vless", "شناسه UUID در لینک VLESS معتبر نیست.", "Invalid VLESS UUID.", VpnProtocol.VLESS)
        }
        if (host.isEmpty() || port !in 1..65535) {
            return invalid("vless", "آدرس سرور یا پورت VLESS نامعتبر است.", "Invalid VLESS host or port.", VpnProtocol.VLESS)
        }

        val query = parseQueryParams(parts.rawQuery)
        val remark = decodeFragment(parts.rawFragment).ifBlank { "VLESS $host" }
        val transport = query["type"]?.lowercase()?.ifBlank { "tcp" } ?: "tcp"
        val security = query["security"]?.lowercase()?.ifBlank { "none" } ?: "none"

        if (security == "reality" && query["pbk"].isNullOrBlank()) {
            return invalid("vless", "پارامتر pbk (کلید عمومی Reality) وجود ندارد.", "VLESS Reality config is missing public key (pbk).", VpnProtocol.VLESS)
        }

        val (countryCode, countryName) = CountryDetector.detect(remark, host)
        val fingerprint = sha256("vless|$uuid|$host|$port|$transport|$security|${query["sni"].orEmpty()}|${query["path"].orEmpty()}|${query["pbk"].orEmpty()}")

        return ParseOutcome.Success(
            ParsedVpnConfig(
                protocol = VpnProtocol.VLESS,
                format = ConfigFormat.URI_LINK,
                displayName = remark,
                host = host,
                port = port,
                transport = transport,
                security = security,
                countryCode = countryCode,
                countryName = countryName,
                rawPayload = rawUri,
                credentialsMap = mapOf(
                    "uuid" to uuid,
                    "flow" to query["flow"].orEmpty(),
                    "encryption" to (query["encryption"]?.ifBlank { "none" } ?: "none"),
                    "pbk" to query["pbk"].orEmpty(),
                    "sid" to query["sid"].orEmpty()
                ),
                parameters = query,
                fingerprint = fingerprint
            )
        )
    }

    private fun parseVmess(rawUri: String): ParseOutcome {
        val body = rawUri.substringAfter("://").substringBefore("#").trim()
        val decodedJson = decodeBase64Flexible(body)
            ?: return invalid("vmess", "محتوای Base64 لینک VMess نامعتبر است.", "Invalid Base64 payload in VMess link.", VpnProtocol.VMESS)

        val json = try {
            JSONObject(decodedJson)
        } catch (_: Exception) {
            return invalid("vmess", "ساختار JSON درون لینک VMess معتبر نیست.", "Malformed JSON inside VMess link.", VpnProtocol.VMESS)
        }

        val host = json.optString("add").trim()
        val port = json.optString("port").trim().toIntOrNull() ?: json.optInt("port", -1)
        val uuid = json.optString("id").trim()
        val aid = json.optString("aid").trim().toIntOrNull() ?: json.optInt("aid", 0)
        val scy = json.optString("scy", "auto").ifBlank { "auto" }
        val net = json.optString("net", "tcp").lowercase().ifBlank { "tcp" }
        val tls = json.optString("tls", "none").lowercase().ifBlank { "none" }
        val sni = json.optString("sni").ifBlank { json.optString("host") }
        val path = json.optString("path")
        val headerType = json.optString("type", "none")
        val remark = json.optString("ps").trim().ifBlank { "VMess $host" }

        if (host.isEmpty() || port !in 1..65535 || !isValidUuidOrToken(uuid)) {
            return invalid("vmess", "اطلاعات سرور، پورت یا UUID در VMess ناقص است.", "Incomplete VMess host, port, or UUID.", VpnProtocol.VMESS)
        }

        val (countryCode, countryName) = CountryDetector.detect(remark, host)
        val fingerprint = sha256("vmess|$uuid|$host|$port|$net|$tls|$sni|$path")

        return ParseOutcome.Success(
            ParsedVpnConfig(
                protocol = VpnProtocol.VMESS,
                format = ConfigFormat.BASE64_JSON,
                displayName = remark,
                host = host,
                port = port,
                transport = net,
                security = tls,
                countryCode = countryCode,
                countryName = countryName,
                rawPayload = rawUri,
                credentialsMap = mapOf(
                    "uuid" to uuid,
                    "alterId" to aid.toString(),
                    "security" to scy
                ),
                parameters = mapOf(
                    "net" to net,
                    "tls" to tls,
                    "sni" to sni,
                    "host" to json.optString("host"),
                    "path" to path,
                    "type" to headerType,
                    "fp" to json.optString("fp"),
                    "alpn" to json.optString("alpn")
                ),
                fingerprint = fingerprint
            )
        )
    }

    private fun parseTrojan(rawUri: String): ParseOutcome {
        val parts = splitTolerantUri(rawUri)
            ?: return invalid("trojan", "ساختار لینک Trojan نامعتبر است.", "Malformed Trojan URI.", VpnProtocol.TROJAN)

        val password = urlDecode(parts.userInfo)
        val host = parts.host
        val port = parts.port

        if (password.isBlank() || host.isEmpty() || port !in 1..65535) {
            return invalid("trojan", "رمز عبور، هاست یا پورت Trojan نامعتبر است.", "Invalid Trojan password, host, or port.", VpnProtocol.TROJAN)
        }

        val query = parseQueryParams(parts.rawQuery)
        val remark = decodeFragment(parts.rawFragment).ifBlank { "Trojan $host" }
        val transport = query["type"]?.lowercase()?.ifBlank { "tcp" } ?: "tcp"
        val security = query["security"]?.lowercase()?.ifBlank { "tls" } ?: "tls"
        val (countryCode, countryName) = CountryDetector.detect(remark, host)
        val fingerprint = sha256("trojan|$password|$host|$port|$transport|$security|${query["sni"].orEmpty()}|${query["path"].orEmpty()}")

        return ParseOutcome.Success(
            ParsedVpnConfig(
                protocol = VpnProtocol.TROJAN,
                format = ConfigFormat.URI_LINK,
                displayName = remark,
                host = host,
                port = port,
                transport = transport,
                security = security,
                countryCode = countryCode,
                countryName = countryName,
                rawPayload = rawUri,
                credentialsMap = mapOf("password" to password),
                parameters = query,
                fingerprint = fingerprint
            )
        )
    }

    private fun parseShadowsocks(rawUri: String): ParseOutcome {
        val withoutScheme = rawUri.substringAfter("://")
        val fragmentPart = withoutScheme.substringAfter("#", "")
        val remark = decodeFragment(fragmentPart)
        val mainAndQuery = withoutScheme.substringBefore("#")
        val mainPart = mainAndQuery.substringBefore("?")
        val rawQuery = mainAndQuery.substringAfter("?", "")
        val query = parseQueryParams(rawQuery)

        val method: String
        val password: String
        val host: String
        val port: Int

        if (mainPart.contains("@")) {
            val userInfoRaw = mainPart.substringBefore("@")
            val hostPortRaw = mainPart.substringAfter("@").trimEnd('/')
            val decodedUserInfo = if (userInfoRaw.contains(":")) {
                urlDecode(userInfoRaw)
            } else {
                decodeBase64Flexible(userInfoRaw)
                    ?: return invalid("ss", "رمزنگاری Base64 در لینک Shadowsocks معتبر نیست.", "Invalid Base64 userinfo in Shadowsocks URI.", VpnProtocol.SHADOWSOCKS)
            }
            val colonIdx = decodedUserInfo.indexOf(':')
            if (colonIdx <= 0) {
                return invalid("ss", "متد یا رمز Shadowsocks ناقص است.", "Missing Shadowsocks cipher or password.", VpnProtocol.SHADOWSOCKS)
            }
            method = decodedUserInfo.substring(0, colonIdx)
            password = decodedUserInfo.substring(colonIdx + 1)
            val parsedHostPort = splitHostPort(hostPortRaw)
                ?: return invalid("ss", "آدرس سرور یا پورت Shadowsocks نامعتبر است.", "Invalid Shadowsocks host:port.", VpnProtocol.SHADOWSOCKS)
            host = parsedHostPort.first
            port = parsedHostPort.second
        } else {
            val decodedFull = decodeBase64Flexible(mainPart)
                ?: return invalid("ss", "ساختار Base64 لینک Shadowsocks نامعتبر است.", "Invalid legacy Shadowsocks Base64.", VpnProtocol.SHADOWSOCKS)
            val atIdx = decodedFull.lastIndexOf('@')
            if (atIdx <= 0) {
                return invalid("ss", "ساختار Shadowsocks ناقص است.", "Malformed legacy Shadowsocks payload.", VpnProtocol.SHADOWSOCKS)
            }
            val creds = decodedFull.substring(0, atIdx)
            val hostPortRaw = decodedFull.substring(atIdx + 1)
            val colonIdx = creds.indexOf(':')
            if (colonIdx <= 0) {
                return invalid("ss", "متد یا رمز Shadowsocks ناقص است.", "Missing Shadowsocks method:password.", VpnProtocol.SHADOWSOCKS)
            }
            method = creds.substring(0, colonIdx)
            password = creds.substring(colonIdx + 1)
            val parsedHostPort = splitHostPort(hostPortRaw)
                ?: return invalid("ss", "آدرس سرور یا پورت Shadowsocks نامعتبر است.", "Invalid Shadowsocks host:port.", VpnProtocol.SHADOWSOCKS)
            host = parsedHostPort.first
            port = parsedHostPort.second
        }

        if (method !in ALLOWED_SS_METHODS || password.isEmpty() || host.isBlank() || port !in 1..65535) {
            return invalid(
                "ss",
                "متد رمزنگاری ($method) یا اطلاعات Shadowsocks معتبر نیست.",
                "Unsupported Shadowsocks method ($method) or invalid endpoint.",
                VpnProtocol.SHADOWSOCKS
            )
        }

        val finalRemark = remark.ifBlank { "Shadowsocks $host" }
        val (countryCode, countryName) = CountryDetector.detect(finalRemark, host)
        val pluginRaw = query["plugin"].orEmpty()
        val pluginName = pluginRaw.substringBefore(';').lowercase()
        val pluginOpts = parseSemicolonPluginOptions(pluginRaw.substringAfter(';', ""))

        // Promote Shadowsocks + shadow-tls plugin to native ShadowTLS v3 (libbox.so)
        if (pluginName == "shadow-tls" || pluginName == "shadowtls") {
            val stPassword = pluginOpts["password"].orEmpty().ifBlank { password }
            val stSni = pluginOpts["host"].orEmpty().ifBlank { pluginOpts["sni"].orEmpty() }.ifBlank { host }
            val stVersion = pluginOpts["version"]?.ifBlank { "3" } ?: "3"
            val fp = sha256("shadowtls|$stVersion|$stPassword|$stSni|$method|$password|$host|$port")
            return ParseOutcome.Success(
                ParsedVpnConfig(
                    protocol = VpnProtocol.SHADOWTLS,
                    format = ConfigFormat.URI_LINK,
                    displayName = finalRemark,
                    host = host,
                    port = port,
                    transport = "tls-v$stVersion",
                    security = "shadowtls",
                    countryCode = countryCode,
                    countryName = countryName,
                    rawPayload = rawUri,
                    credentialsMap = mapOf(
                        "password" to stPassword,
                        "ss_method" to method,
                        "ss_password" to password
                    ),
                    parameters = query + pluginOpts + mapOf("sni" to stSni, "version" to stVersion),
                    fingerprint = fp
                )
            )
        }

        // Promote Shadowsocks + ck-client plugin to Cloak Pluggable Transport
        if (pluginName == "ck-client" || pluginName == "cloak") {
            val uid = pluginOpts["UID"].orEmpty().ifBlank { pluginOpts["uid"].orEmpty() }
            val pubKey = pluginOpts["PublicKey"].orEmpty().ifBlank { pluginOpts["publicKey"].orEmpty() }
            val sni = pluginOpts["ServerName"].orEmpty().ifBlank { host }
            val fp = sha256("cloak|$uid|$pubKey|$sni|$method|$password|$host|$port")
            return ParseOutcome.Success(
                ParsedVpnConfig(
                    protocol = VpnProtocol.CLOAK,
                    format = ConfigFormat.URI_LINK,
                    displayName = finalRemark,
                    host = host,
                    port = port,
                    transport = pluginOpts["Transport"]?.lowercase()?.ifBlank { "direct" } ?: "direct",
                    security = "cloak",
                    countryCode = countryCode,
                    countryName = countryName,
                    rawPayload = rawUri,
                    credentialsMap = mapOf(
                        "uid" to uid,
                        "publicKey" to pubKey,
                        "ss_method" to method,
                        "ss_password" to password
                    ),
                    parameters = query + pluginOpts + mapOf("ServerName" to sni, "ProxyMethod" to "shadowsocks"),
                    fingerprint = fp
                )
            )
        }

        val fingerprint = sha256("ss|$method|$password|$host|$port|$pluginRaw")

        return ParseOutcome.Success(
            ParsedVpnConfig(
                protocol = VpnProtocol.SHADOWSOCKS,
                format = ConfigFormat.URI_LINK,
                displayName = finalRemark,
                host = host,
                port = port,
                transport = if (pluginRaw.isBlank()) "tcp" else "plugin",
                security = method,
                countryCode = countryCode,
                countryName = countryName,
                rawPayload = rawUri,
                credentialsMap = mapOf("method" to method, "password" to password),
                parameters = query,
                fingerprint = fingerprint
            )
        )
    }

    private fun parseHysteria2(rawUri: String): ParseOutcome {
        val parts = splitTolerantUri(rawUri)
            ?: return invalid("hy2", "ساختار لینک Hysteria2 نامعتبر است.", "Malformed Hysteria2 URI.", VpnProtocol.HYSTERIA2)

        val auth = urlDecode(parts.userInfo).trim()
        val host = parts.host
        val port = parts.port
        if (auth.isEmpty() || host.isEmpty() || port !in 1..65535) {
            return invalid("hy2", "رمز عبور، هاست یا پورت Hysteria2 نامعتبر است.", "Invalid Hysteria2 auth, host, or port.", VpnProtocol.HYSTERIA2)
        }

        val query = parseQueryParams(parts.rawQuery)
        val remark = decodeFragment(parts.rawFragment).ifBlank { "Hysteria2 $host" }
        val obfs = query["obfs"].orEmpty().lowercase()
        val (countryCode, countryName) = CountryDetector.detect(remark, host)
        val fingerprint = sha256("hy2|$auth|$host|$port|$obfs|${query["sni"].orEmpty()}|${query["obfs-password"].orEmpty()}")

        return ParseOutcome.Success(
            ParsedVpnConfig(
                protocol = VpnProtocol.HYSTERIA2,
                format = ConfigFormat.URI_LINK,
                displayName = remark,
                host = host,
                port = port,
                transport = if (obfs.isNotBlank()) "quic+$obfs" else "quic",
                security = "tls",
                countryCode = countryCode,
                countryName = countryName,
                rawPayload = rawUri,
                credentialsMap = mapOf(
                    "password" to auth,
                    "obfsPassword" to query["obfs-password"].orEmpty()
                ),
                parameters = query,
                fingerprint = fingerprint
            )
        )
    }

    private fun parseAnyTls(rawUri: String): ParseOutcome {
        val parts = splitTolerantUri(rawUri)
            ?: return invalid("anytls", "ساختار لینک AnyTLS نامعتبر است.", "Malformed AnyTLS URI.", VpnProtocol.ANYTLS)

        val password = urlDecode(parts.userInfo).trim()
        val host = parts.host
        val port = parts.port
        if (password.isEmpty() || host.isEmpty() || port !in 1..65535) {
            return invalid("anytls", "رمز عبور، هاست یا پورت AnyTLS نامعتبر است.", "Invalid AnyTLS password, host, or port.", VpnProtocol.ANYTLS)
        }

        val query = parseQueryParams(parts.rawQuery)
        val remark = decodeFragment(parts.rawFragment).ifBlank { "AnyTLS $host" }
        val (countryCode, countryName) = CountryDetector.detect(remark, host)
        val fingerprint = sha256("anytls|$password|$host|$port|${query["sni"].orEmpty()}|${query["alpn"].orEmpty()}")

        return ParseOutcome.Success(
            ParsedVpnConfig(
                protocol = VpnProtocol.ANYTLS,
                format = ConfigFormat.URI_LINK,
                displayName = remark,
                host = host,
                port = port,
                transport = "tcp",
                security = "tls",
                countryCode = countryCode,
                countryName = countryName,
                rawPayload = rawUri,
                credentialsMap = mapOf("password" to password),
                parameters = query,
                fingerprint = fingerprint
            )
        )
    }

    private fun parseShadowTls(rawUri: String): ParseOutcome {
        val parts = splitTolerantUri(rawUri)
            ?: return invalid("shadowtls", "ساختار لینک ShadowTLS نامعتبر است.", "Malformed ShadowTLS URI.", VpnProtocol.SHADOWTLS)

        val query = parseQueryParams(parts.rawQuery)
        val rawUser = urlDecode(parts.userInfo).trim()
        val password = query["password"]?.ifBlank { rawUser } ?: rawUser
        val host = parts.host
        val port = parts.port
        if (password.isEmpty() || host.isEmpty() || port !in 1..65535) {
            return invalid("shadowtls", "اطلاعات ShadowTLS ناقص یا نامعتبر است.", "Invalid ShadowTLS credentials or endpoint.", VpnProtocol.SHADOWTLS)
        }

        val version = query["version"]?.ifBlank { "3" } ?: "3"
        val sni = query["sni"]?.ifBlank { query["host"] }?.ifBlank { host } ?: host
        val ssMethod = query["ss_method"]?.ifBlank { "2022-blake3-aes-128-gcm" } ?: "2022-blake3-aes-128-gcm"
        val ssPassword = query["ss_password"]?.ifBlank { password } ?: password
        val remark = decodeFragment(parts.rawFragment).ifBlank { "ShadowTLS $host" }
        val (countryCode, countryName) = CountryDetector.detect(remark, host)
        val fingerprint = sha256("shadowtls|$version|$password|$sni|$ssMethod|$ssPassword|$host|$port")

        return ParseOutcome.Success(
            ParsedVpnConfig(
                protocol = VpnProtocol.SHADOWTLS,
                format = ConfigFormat.URI_LINK,
                displayName = remark,
                host = host,
                port = port,
                transport = "tls-v$version",
                security = "shadowtls",
                countryCode = countryCode,
                countryName = countryName,
                rawPayload = rawUri,
                credentialsMap = mapOf(
                    "password" to password,
                    "ss_method" to ssMethod,
                    "ss_password" to ssPassword
                ),
                parameters = query + mapOf("sni" to sni, "version" to version),
                fingerprint = fingerprint
            )
        )
    }

    private fun parseMieru(rawUri: String): ParseOutcome {
        val parts = splitTolerantUri(rawUri)
            ?: return invalid("mieru", "ساختار لینک Mieru نامعتبر است (فرمت: mieru://user:pass@host:port).", "Malformed Mieru URI (expected mieru://user:pass@host:port).", VpnProtocol.MIERU)

        val decodedUser = urlDecode(parts.userInfo)
        val colonIdx = decodedUser.indexOf(':')
        if (colonIdx <= 0 || colonIdx >= decodedUser.length - 1) {
            return invalid("mieru", "نام کاربری و رمز عبور Mieru باید به‌صورت user:pass باشد.", "Mieru URI requires username:password.", VpnProtocol.MIERU)
        }
        val username = decodedUser.substring(0, colonIdx).trim()
        val password = decodedUser.substring(colonIdx + 1).trim()
        val host = parts.host
        val port = parts.port
        if (host.isEmpty() || port !in 1..65535) {
            return invalid("mieru", "آدرس سرور یا پورت Mieru نامعتبر است.", "Invalid Mieru host or port.", VpnProtocol.MIERU)
        }

        val query = parseQueryParams(parts.rawQuery)
        val transport = query["transport"]?.uppercase()?.ifBlank { "TCP" } ?: "TCP"
        val mux = query["multiplexing"]?.uppercase()?.ifBlank { "MULTIPLEXING_LOW" } ?: "MULTIPLEXING_LOW"
        val remark = decodeFragment(parts.rawFragment).ifBlank { "Mieru $host" }
        val (countryCode, countryName) = CountryDetector.detect(remark, host)
        val fingerprint = sha256("mieru|$username|$password|$host|$port|$transport|$mux")

        return ParseOutcome.Success(
            ParsedVpnConfig(
                protocol = VpnProtocol.MIERU,
                format = ConfigFormat.URI_LINK,
                displayName = remark,
                host = host,
                port = port,
                transport = transport.lowercase(),
                security = "xchacha20",
                countryCode = countryCode,
                countryName = countryName,
                rawPayload = rawUri,
                credentialsMap = mapOf(
                    "username" to username,
                    "password" to password
                ),
                parameters = query + mapOf("transport" to transport, "multiplexing" to mux),
                fingerprint = fingerprint
            )
        )
    }

    private fun parseCloak(rawUri: String): ParseOutcome {
        val parts = splitTolerantUri(rawUri)
            ?: return invalid("cloak", "ساختار لینک Cloak نامعتبر است (فرمت: cloak://UID@host:port?PublicKey=...).", "Malformed Cloak URI.", VpnProtocol.CLOAK)

        val query = parseQueryParams(parts.rawQuery)
        val uid = urlDecode(parts.userInfo).trim().ifBlank { query["UID"].orEmpty() }
        val pubKey = query["PublicKey"].orEmpty().ifBlank { query["publicKey"].orEmpty() }.ifBlank { query["pbk"].orEmpty() }
        val host = parts.host
        val port = parts.port
        if (uid.isBlank() || pubKey.isBlank() || host.isEmpty() || port !in 1..65535) {
            return invalid("cloak", "پارامترهای UID یا PublicKey در لینک Cloak ناقص است.", "Cloak URI requires valid UID, PublicKey, host, and port.", VpnProtocol.CLOAK)
        }

        val sni = query["ServerName"].orEmpty().ifBlank { query["sni"].orEmpty() }.ifBlank { host }
        val proxyMethod = query["ProxyMethod"].orEmpty().ifBlank { "shadowsocks" }
        val encryptionMethod = query["EncryptionMethod"].orEmpty().ifBlank { "plain" }
        val remark = decodeFragment(parts.rawFragment).ifBlank { "Cloak $host" }
        val (countryCode, countryName) = CountryDetector.detect(remark, host)
        val fingerprint = sha256("cloak|$uid|$pubKey|$sni|$proxyMethod|$host|$port")

        return ParseOutcome.Success(
            ParsedVpnConfig(
                protocol = VpnProtocol.CLOAK,
                format = ConfigFormat.URI_LINK,
                displayName = remark,
                host = host,
                port = port,
                transport = query["Transport"]?.lowercase()?.ifBlank { "direct" } ?: "direct",
                security = "cloak",
                countryCode = countryCode,
                countryName = countryName,
                rawPayload = rawUri,
                credentialsMap = mapOf(
                    "uid" to uid,
                    "publicKey" to pubKey,
                    "ss_method" to query["ss_method"].orEmpty().ifBlank { "aes-256-gcm" },
                    "ss_password" to query["ss_password"].orEmpty()
                ),
                parameters = query + mapOf(
                    "ServerName" to sni,
                    "ProxyMethod" to proxyMethod,
                    "EncryptionMethod" to encryptionMethod
                ),
                fingerprint = fingerprint
            )
        )
    }

    private fun parseLightway(rawUri: String): ParseOutcome {
        val withoutScheme = rawUri.substringAfter("://", "")
        if (withoutScheme.isEmpty()) {
            return invalid("lightway", "ساختار لینک Lightway نامعتبر است.", "Malformed Lightway URI.", VpnProtocol.LIGHTWAY)
        }
        val fragmentIdx = withoutScheme.indexOf('#')
        val beforeFragment = if (fragmentIdx >= 0) withoutScheme.substring(0, fragmentIdx) else withoutScheme
        val rawFragment = if (fragmentIdx >= 0) withoutScheme.substring(fragmentIdx + 1) else ""
        val queryIdx = beforeFragment.indexOf('?')
        val authority = (if (queryIdx >= 0) beforeFragment.substring(0, queryIdx) else beforeFragment).substringBefore('/').trim()
        val rawQuery = if (queryIdx >= 0) beforeFragment.substring(queryIdx + 1) else ""

        val userPart = if (authority.contains('@')) authority.substringBeforeLast('@') else ""
        val hostPortPart = if (authority.contains('@')) authority.substringAfterLast('@') else authority
        val hostPort = splitHostPort(hostPortPart)
            ?: return invalid("lightway", "آدرس سرور یا پورت Lightway نامعتبر است.", "Invalid Lightway host:port.", VpnProtocol.LIGHTWAY)

        val (host, port) = hostPort
        val query = parseQueryParams(rawQuery)
        val proto = query["protocol"]?.lowercase()?.ifBlank { "udp" } ?: "udp"
        val cipher = query["cipher"]?.lowercase()?.ifBlank { "aes" } ?: "aes"
        val remark = decodeFragment(rawFragment).ifBlank { "Lightway $host" }
        val (countryCode, countryName) = CountryDetector.detect(remark, host)
        val fingerprint = sha256("lightway|$host|$port|$proto|$cipher|$userPart")

        return ParseOutcome.Success(
            ParsedVpnConfig(
                protocol = VpnProtocol.LIGHTWAY,
                format = ConfigFormat.URI_LINK,
                displayName = remark,
                host = host,
                port = port,
                transport = proto,
                security = "dtls-$cipher",
                countryCode = countryCode,
                countryName = countryName,
                rawPayload = rawUri,
                credentialsMap = mapOf(
                    "token" to urlDecode(userPart),
                    "ca" to query["ca"].orEmpty(),
                    "cert" to query["cert"].orEmpty(),
                    "key" to query["key"].orEmpty()
                ),
                parameters = query + mapOf("protocol" to proto, "cipher" to cipher),
                fingerprint = fingerprint
            )
        )
    }

    private fun parseSemicolonPluginOptions(rawOpts: String): Map<String, String> {
        if (rawOpts.isBlank()) return emptyMap()
        val map = linkedMapOf<String, String>()
        for (part in rawOpts.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0) {
                map[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
        }
        return map
    }

    private fun parseTuic(rawUri: String): ParseOutcome {
        val parts = splitTolerantUri(rawUri)
            ?: return invalid("tuic", "ساختار لینک TUIC نامعتبر است.", "Malformed TUIC URI.", VpnProtocol.TUIC)

        val decodedUserInfo = urlDecode(parts.userInfo)
        val colonIdx = decodedUserInfo.indexOf(':')
        val uuid = if (colonIdx >= 0) decodedUserInfo.substring(0, colonIdx).trim() else decodedUserInfo.trim()
        val password = if (colonIdx >= 0) decodedUserInfo.substring(colonIdx + 1) else ""
        val host = parts.host
        val port = parts.port

        if (!isValidUuidOrToken(uuid)) {
            return invalid("tuic", "شناسه UUID در لینک TUIC معتبر نیست.", "Invalid TUIC UUID.", VpnProtocol.TUIC)
        }
        if (host.isEmpty() || port !in 1..65535) {
            return invalid("tuic", "آدرس سرور یا پورت TUIC نامعتبر است.", "Invalid TUIC host or port.", VpnProtocol.TUIC)
        }

        val query = parseQueryParams(parts.rawQuery)
        val remark = decodeFragment(parts.rawFragment).ifBlank { "TUIC $host" }
        val congestion = query["congestion_control"]?.lowercase()?.ifBlank { "bbr" } ?: "bbr"
        val (countryCode, countryName) = CountryDetector.detect(remark, host)
        val fingerprint = sha256("tuic|$uuid|$password|$host|$port|$congestion|${query["sni"].orEmpty()}|${query["alpn"].orEmpty()}")

        return ParseOutcome.Success(
            ParsedVpnConfig(
                protocol = VpnProtocol.TUIC,
                format = ConfigFormat.URI_LINK,
                displayName = remark,
                host = host,
                port = port,
                transport = "quic",
                security = "tls",
                countryCode = countryCode,
                countryName = countryName,
                rawPayload = rawUri,
                credentialsMap = mapOf(
                    "uuid" to uuid,
                    "password" to password
                ),
                parameters = query,
                fingerprint = fingerprint
            )
        )
    }

    private fun parseOpenVpn(content: String): ParseOutcome {
        val lines = content.lines().map { it.trim() }
        // Security check: reject dangerous directives that could execute local shell scripts
        val dangerousDirectives = setOf(
            "script-security", "up", "down", "route-up", "route-pre-down",
            "client-connect", "client-disconnect", "learn-address", "auth-user-pass-verify", "plugin"
        )
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            val token = line.split(Regex("\\s+")).firstOrNull()?.lowercase() ?: continue
            if (token in dangerousDirectives) {
                return invalid(
                    "ovpn",
                    "دستور ناامن '$token' در فایل OpenVPN به‌دلیل مسائل امنیتی مسدود شد.",
                    "Blocked unsafe OpenVPN directive '$token' to prevent command execution.",
                    VpnProtocol.OPENVPN
                )
            }
        }

        var host = ""
        var port = 1194
        var proto = "udp"
        var cipher = "AES-256-GCM"

        for (line in lines) {
            if (line.startsWith("#") || line.startsWith(";")) continue
            val parts = line.split(Regex("\\s+"))
            when (parts.firstOrNull()?.lowercase()) {
                "remote" -> {
                    if (parts.size >= 2) host = parts[1]
                    if (parts.size >= 3) port = parts[2].toIntOrNull() ?: port
                    if (parts.size >= 4) proto = parts[3].lowercase()
                }
                "port" -> if (parts.size >= 2) port = parts[1].toIntOrNull() ?: port
                "proto" -> if (parts.size >= 2) proto = parts[1].lowercase()
                "cipher", "data-ciphers" -> if (parts.size >= 2) cipher = parts[1]
            }
        }

        if (host.isBlank() || port !in 1..65535) {
            return invalid("ovpn", "دستور remote معتبر در فایل OVPN یافت نشد.", "Valid 'remote' endpoint not found in OVPN file.", VpnProtocol.OPENVPN)
        }

        val hasCa = content.contains("<ca>", ignoreCase = true)
        val remark = "OpenVPN $host:$port"
        val (countryCode, countryName) = CountryDetector.detect(remark, host)
        val fingerprint = sha256("ovpn|$host|$port|$proto|$cipher|${content.length}")

        return ParseOutcome.Success(
            ParsedVpnConfig(
                protocol = VpnProtocol.OPENVPN,
                format = ConfigFormat.OVPN_FILE,
                displayName = remark,
                host = host,
                port = port,
                transport = proto,
                security = cipher,
                countryCode = countryCode,
                countryName = countryName,
                rawPayload = content,
                credentialsMap = mapOf("hasCa" to hasCa.toString(), "cipher" to cipher),
                parameters = mapOf("proto" to proto, "cipher" to cipher),
                fingerprint = fingerprint
            )
        )
    }

    private fun parseWireGuardOrAmnezia(content: String): ParseOutcome {
        var section = ""
        val iface = mutableMapOf<String, String>()
        val peer = mutableMapOf<String, String>()

        for (rawLine in content.lines()) {
            val line = rawLine.substringBefore("#").substringBefore(";").trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when (section) {
                "interface" -> iface[key] = value
                "peer" -> peer[key] = value
            }
        }

        val privateKey = iface["PrivateKey"].orEmpty()
        val address = iface["Address"].orEmpty()
        val publicKey = peer["PublicKey"].orEmpty()
        val endpoint = peer["Endpoint"].orEmpty()

        val isAmnezia = AMNEZIA_KEYS.any { iface.containsKey(it) }
        val protocol = if (isAmnezia) VpnProtocol.AMNEZIA_WG else VpnProtocol.WIREGUARD

        if (!isValidWgKey(privateKey) || !isValidWgKey(publicKey)) {
            return invalid(
                protocol.name,
                "کلید خصوصی یا عمومی WireGuard/AmneziaWG نامعتبر است (باید ۳۲ بایت Base64 باشد).",
                "Invalid WireGuard/AmneziaWG PrivateKey or PublicKey (must be 32-byte Base64).",
                protocol
            )
        }

        val hostPort = splitHostPort(endpoint)
            ?: return invalid(protocol.name, "مقدار Endpoint در کانفیگ WireGuard/AmneziaWG نامعتبر است.", "Invalid Endpoint in WireGuard/AmneziaWG config.", protocol)

        if (address.isBlank()) {
            return invalid(protocol.name, "فیلد Address در بخش [Interface] وجود ندارد.", "Missing Address in [Interface] section.", protocol)
        }

        val (host, port) = hostPort
        val remark = if (isAmnezia) "AmneziaWG $host" else "WireGuard $host"
        val (countryCode, countryName) = CountryDetector.detect(remark, host)
        val fingerprint = sha256("${protocol.name.lowercase()}|$publicKey|$host|$port|$address")

        val params = mutableMapOf<String, String>()
        params["Address"] = address
        iface["DNS"]?.let { params["DNS"] = it }
        iface["MTU"]?.let { params["MTU"] = it }
        peer["AllowedIPs"]?.let { params["AllowedIPs"] = it }
        peer["PersistentKeepalive"]?.let { params["PersistentKeepalive"] = it }
        AMNEZIA_KEYS.forEach { k -> iface[k]?.let { params[k] = it } }

        return ParseOutcome.Success(
            ParsedVpnConfig(
                protocol = protocol,
                format = if (isAmnezia) ConfigFormat.AMNEZIA_WG_INI else ConfigFormat.WIREGUARD_INI,
                displayName = remark,
                host = host,
                port = port,
                transport = "udp",
                security = if (isAmnezia) "Noise_IK + AWG" else "Noise_IK",
                countryCode = countryCode,
                countryName = countryName,
                rawPayload = content,
                credentialsMap = mapOf(
                    "privateKey" to privateKey,
                    "publicKey" to publicKey,
                    "presharedKey" to peer["PresharedKey"].orEmpty()
                ),
                parameters = params,
                fingerprint = fingerprint
            )
        )
    }

    private fun parseXrayJson(rawJson: String): List<ParseOutcome> {
        val results = mutableListOf<ParseOutcome>()
        try {
            val trimmed = rawJson.trim()
            val roots = if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            } else {
                listOf(JSONObject(trimmed))
            }

            for (root in roots) {
                val outbounds: JSONArray = root.optJSONArray("outbounds") ?: continue
                for (i in 0 until outbounds.length()) {
                    val outbound = outbounds.optJSONObject(i) ?: continue
                    val protoStr = outbound.optString("protocol").lowercase()
                    val protocol = when (protoStr) {
                        "vless" -> VpnProtocol.VLESS
                        "vmess" -> VpnProtocol.VMESS
                        "trojan" -> VpnProtocol.TROJAN
                        "shadowsocks" -> VpnProtocol.SHADOWSOCKS
                        else -> continue
                    }
                    val settings = outbound.optJSONObject("settings") ?: continue
                    val (host, port, cred) = extractJsonOutboundEndpoint(protocol, settings) ?: continue
                    if (host.isBlank() || port !in 1..65535) continue
                    val stream = outbound.optJSONObject("streamSettings")
                    val network = stream?.optString("network", "tcp")?.ifBlank { "tcp" } ?: "tcp"
                    val security = stream?.optString("security", "none")?.ifBlank { "none" } ?: "none"
                    val tag = outbound.optString("tag").ifBlank { "${protocol.displayName} $host" }
                    val (countryCode, countryName) = CountryDetector.detect(tag, host)
                    val fingerprint = sha256("xrayjson|$protoStr|$host|$port|$cred|$network|$security")

                    results.add(
                        ParseOutcome.Success(
                            ParsedVpnConfig(
                                protocol = protocol,
                                format = ConfigFormat.XRAY_JSON,
                                displayName = tag,
                                host = host,
                                port = port,
                                transport = network,
                                security = security,
                                countryCode = countryCode,
                                countryName = countryName,
                                rawPayload = root.toString(),
                                credentialsMap = mapOf("credential" to cred),
                                parameters = mapOf("network" to network, "security" to security),
                                fingerprint = fingerprint
                            )
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return listOf(
                invalid("json", "ساختار JSON معتبر نیست.", "Invalid Xray JSON format.", null)
            )
        }
        return results
    }

    private fun extractJsonOutboundEndpoint(
        protocol: VpnProtocol,
        settings: JSONObject
    ): Triple<String, Int, String>? {
        return when (protocol) {
            VpnProtocol.VLESS, VpnProtocol.VMESS -> {
                val vnext = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                val user = vnext.optJSONArray("users")?.optJSONObject(0)
                Triple(
                    vnext.optString("address"),
                    vnext.optInt("port", -1),
                    user?.optString("id").orEmpty()
                )
            }
            VpnProtocol.TROJAN, VpnProtocol.SHADOWSOCKS -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                Triple(
                    server.optString("address"),
                    server.optInt("port", -1),
                    server.optString("password")
                )
            }
            else -> null
        }
    }

    private fun parseRecognizedUnsupported(rawUri: String, protocol: VpnProtocol): ParseOutcome {
        return ParseOutcome.Failure(
            rawSnippet = rawUri.take(36),
            reasonFa = "پروتکل ${protocol.displayName} شناسایی شد، اما موتور بومی این پروتکل در این نسخه نصب نشده است و به‌صورت صوری پشتیبانی‌شده اعلام نمی‌شود.",
            reasonEn = "${protocol.displayName} detected, but no native engine binary is bundled in this build.",
            detectedProtocol = protocol
        )
    }

    private fun looksLikeOpenVpn(text: String): Boolean {
        val lower = text.lowercase()
        return (lower.contains("remote ") && (lower.contains("client") || lower.contains("dev tun") || lower.contains("<ca>")))
    }

    private fun looksLikeWireGuardIni(text: String): Boolean {
        return text.contains("[Interface]", ignoreCase = true) && text.contains("[Peer]", ignoreCase = true)
    }

    private fun hasAmneziaObfuscationParams(text: String): Boolean {
        return AMNEZIA_KEYS.any { key -> Regex("(?m)^\\s*$key\\s*=").containsMatchIn(text) }
    }

    private fun isValidUuidOrToken(value: String): Boolean {
        return value.length in 8..128 && !value.any { it.isWhitespace() }
    }

    private fun isValidWgKey(key: String): Boolean {
        if (key.length != 44 || !key.endsWith("=")) return false
        return try {
            Base64.getDecoder().decode(key).size == 32
        } catch (_: Exception) {
            false
        }
    }

    private fun splitHostPort(input: String): Pair<String, Int>? {
        val trimmed = input.trim()
        return if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            if (close < 0 || close + 2 >= trimmed.length || trimmed[close + 1] != ':') return null
            val host = trimmed.substring(1, close)
            val port = trimmed.substring(close + 2).toIntOrNull() ?: return null
            if (port in 1..65535) host to port else null
        } else {
            val idx = trimmed.lastIndexOf(':')
            if (idx <= 0) return null
            val host = trimmed.substring(0, idx)
            val port = trimmed.substring(idx + 1).toIntOrNull() ?: return null
            if (host.isNotBlank() && port in 1..65535) host to port else null
        }
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isBlank()) continue
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = urlDecode(pair.substring(0, eq))
                val v = urlDecode(pair.substring(eq + 1))
                result[k] = v
            }
        }
        return result
    }

    private fun decodeFragment(fragment: String?): String {
        if (fragment.isNullOrBlank()) return ""
        return urlDecode(fragment).trim()
    }

    private fun urlDecode(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun invalid(
        snippet: String,
        fa: String,
        en: String,
        protocol: VpnProtocol?
    ): ParseOutcome.Failure = ParseOutcome.Failure(snippet, fa, en, protocol)

    companion object {
        private val AMNEZIA_KEYS = listOf("Jc", "Jmin", "Jmax", "S1", "S2", "H1", "H2", "H3", "H4")

        private val ALLOWED_SS_METHODS = setOf(
            "aes-128-gcm",
            "aes-256-gcm",
            "chacha20-ietf-poly1305",
            "2022-blake3-aes-128-gcm",
            "2022-blake3-aes-256-gcm",
            "2022-blake3-chacha20-poly1305",
            "xchacha20-ietf-poly1305",
            "aes-128-cfb",
            "aes-256-cfb",
            "chacha20-ietf",
            "none",
            "plain"
        )
    }
}
