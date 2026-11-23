package com.example.configmanager

import com.example.core.model.VpnProtocol

enum class ConfigFormat {
    URI_LINK,
    BASE64_JSON,
    XRAY_JSON,
    OVPN_FILE,
    WIREGUARD_INI,
    AMNEZIA_WG_INI
}

data class ParsedVpnConfig(
    val protocol: VpnProtocol,
    val format: ConfigFormat,
    val displayName: String,
    val host: String,
    val port: Int,
    val transport: String,
    val security: String,
    val countryCode: String,
    val countryName: String,
    val rawPayload: String,
    val credentialsMap: Map<String, String>,
    val parameters: Map<String, String>,
    val fingerprint: String
)

sealed interface ParseOutcome {
    data class Success(val config: ParsedVpnConfig) : ParseOutcome
    data class Failure(
        val rawSnippet: String,
        val reasonFa: String,
        val reasonEn: String,
        val detectedProtocol: VpnProtocol? = null
    ) : ParseOutcome
}

data class BatchImportReport(
    val importedCount: Int = 0,
    val duplicateCount: Int = 0,
    val failedCount: Int = 0,
    val errors: List<String> = emptyList(),
    val importedServerIds: List<Long> = emptyList()
)

object CountryDetector {
    private val flagToCountry = mapOf(
        "🇩🇪" to ("DE" to "Germany"),
        "🇺🇸" to ("US" to "United States"),
        "🇳🇱" to ("NL" to "Netherlands"),
        "🇬🇧" to ("GB" to "United Kingdom"),
        "🇫🇷" to ("FR" to "France"),
        "🇹🇷" to ("TR" to "Turkey"),
        "🇫🇮" to ("FI" to "Finland"),
        "🇸🇪" to ("SE" to "Sweden"),
        "🇨🇭" to ("CH" to "Switzerland"),
        "🇸🇬" to ("SG" to "Singapore"),
        "🇯🇵" to ("JP" to "Japan"),
        "🇨🇦" to ("CA" to "Canada"),
        "🇦🇪" to ("AE" to "UAE"),
        "🇦🇹" to ("AT" to "Austria"),
        "🇵🇱" to ("PL" to "Poland"),
        "🇮🇹" to ("IT" to "Italy"),
        "🇪🇸" to ("ES" to "Spain"),
        "🇮🇪" to ("IE" to "Ireland"),
        "🇳🇴" to ("NO" to "Norway"),
        "🇰🇷" to ("KR" to "South Korea"),
        "🇦🇺" to ("AU" to "Australia"),
        "🇮🇳" to ("IN" to "India"),
        "🇧🇷" to ("BR" to "Brazil"),
        "🇭🇰" to ("HK" to "Hong Kong"),
        "🇹🇼" to ("TW" to "Taiwan"),
        "🇷🇴" to ("RO" to "Romania"),
        "🇧🇬" to ("BG" to "Bulgaria"),
        "🇨🇿" to ("CZ" to "Czechia"),
        "🇺🇦" to ("UA" to "Ukraine"),
        "🇱🇹" to ("LT" to "Lithuania"),
        "🇱🇻" to ("LV" to "Latvia"),
        "🇪🇪" to ("EE" to "Estonia"),
        "🇲🇩" to ("MD" to "Moldova"),
        "🇮🇷" to ("IR" to "Iran")
    )

    private val keywordToCountry = listOf(
        listOf("germany", "frankfurt", "munich", "berlin", "nuremberg", "falkenstein", "آلمان") to ("DE" to "Germany"),
        listOf("netherlands", "amsterdam", "rotterdam", "هلند") to ("NL" to "Netherlands"),
        listOf("united states", "usa", "new york", "dallas", "miami", "los angeles", "ashburn", "chicago", "آمریکا") to ("US" to "United States"),
        listOf("united kingdom", "london", "manchester", "انگلیس") to ("GB" to "United Kingdom"),
        listOf("france", "paris", "strasbourg", "فرانسه") to ("FR" to "France"),
        listOf("turkey", "istanbul", "ankara", "ترکیه") to ("TR" to "Turkey"),
        listOf("finland", "helsinki", "فنلاند") to ("FI" to "Finland"),
        listOf("sweden", "stockholm", "سوئد") to ("SE" to "Sweden"),
        listOf("switzerland", "zurich", "geneva", "سوئیس") to ("CH" to "Switzerland"),
        listOf("singapore", "سنگاپور") to ("SG" to "Singapore"),
        listOf("japan", "tokyo", "osaka", "ژاپن") to ("JP" to "Japan"),
        listOf("canada", "toronto", "montreal", "کانادا") to ("CA" to "Canada"),
        listOf("dubai", "uae", "emirates", "امارات") to ("AE" to "UAE"),
        listOf("austria", "vienna", "اتریش") to ("AT" to "Austria"),
        listOf("poland", "warsaw", "لهستان") to ("PL" to "Poland"),
        listOf("italy", "milan", "rome", "ایتالیا") to ("IT" to "Italy"),
        listOf("spain", "madrid", "اسپانیا") to ("ES" to "Spain"),
        listOf("hong kong", "هنگ کنگ") to ("HK" to "Hong Kong"),
        listOf("korea", "seoul", "کره") to ("KR" to "South Korea"),
        listOf("australia", "sydney", "استرالیا") to ("AU" to "Australia")
    )

    private val isoCodeToken = Regex("""(?i)(?:^|[\s\-_\[\(])(DE|NL|US|GB|UK|FR|TR|FI|SE|CH|SG|JP|CA|AE|AT|PL|IT|ES|IE|NO|KR|AU|IN|BR|HK|TW|RO|BG|CZ|UA|LT|LV|EE)(?:$|[\s\-_\]\)\d])""")

    private val codeToName = mapOf(
        "DE" to "Germany", "NL" to "Netherlands", "US" to "United States",
        "GB" to "United Kingdom", "UK" to "United Kingdom", "FR" to "France",
        "TR" to "Turkey", "FI" to "Finland", "SE" to "Sweden", "CH" to "Switzerland",
        "SG" to "Singapore", "JP" to "Japan", "CA" to "Canada", "AE" to "UAE",
        "AT" to "Austria", "PL" to "Poland", "IT" to "Italy", "ES" to "Spain",
        "IE" to "Ireland", "NO" to "Norway", "KR" to "South Korea", "AU" to "Australia",
        "IN" to "India", "BR" to "Brazil", "HK" to "Hong Kong", "TW" to "Taiwan",
        "RO" to "Romania", "BG" to "Bulgaria", "CZ" to "Czechia", "UA" to "Ukraine",
        "LT" to "Lithuania", "LV" to "Latvia", "EE" to "Estonia"
    )

    fun detect(remark: String, host: String): Pair<String, String> {
        for ((flag, info) in flagToCountry) {
            if (remark.contains(flag)) return info
        }
        val combined = "$remark $host".lowercase()
        for ((keywords, info) in keywordToCountry) {
            if (keywords.any { combined.contains(it) }) return info
        }
        val match = isoCodeToken.find(remark)
        if (match != null) {
            val rawCode = match.groupValues[1].uppercase()
            val normalized = if (rawCode == "UK") "GB" else rawCode
            val name = codeToName[rawCode] ?: normalized
            return normalized to name
        }
        return "UN" to "Global"
    }

    fun flagForCode(countryCode: String): String {
        val code = countryCode.uppercase()
        if (code.length != 2 || code == "UN") return "🌐"
        val first = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
        val second = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }
}
