package com.example.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Protects Subscription HTTP fetches against SSRF, private-network redirects,
 * non-HTTPS downgrade, and oversized payloads.
 */
class NetworkSecurityGuard(
    private val dnsResolver: (String) -> List<InetAddress> = { host ->
        InetAddress.getAllByName(host).toList()
    }
) {
    sealed interface ValidationResult {
        data class Allowed(val normalizedUrl: String, val host: String) : ValidationResult
        data class Rejected(val reasonFa: String, val reasonEn: String) : ValidationResult
    }

    fun validateSubscriptionUrl(rawUrl: String): ValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) {
            return ValidationResult.Rejected(
                reasonFa = "لینک اشتراک خالی یا بیش از حد طولانی است.",
                reasonEn = "Subscription URL is empty or too long."
            )
        }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return ValidationResult.Rejected(
                reasonFa = "ساختار آدرس URL نامعتبر است.",
                reasonEn = "Malformed URL syntax."
            )
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") {
            return ValidationResult.Rejected(
                reasonFa = "برای امنیت، فقط آدرس‌های HTTPS مجاز هستند.",
                reasonEn = "Only HTTPS URLs are permitted for subscriptions."
            )
        }

        if (!uri.userInfo.isNullOrEmpty()) {
            return ValidationResult.Rejected(
                reasonFa = "قرار دادن نام کاربری یا رمز در آدرس URL مجاز نیست.",
                reasonEn = "Embedded credentials in URL are not allowed."
            )
        }

        val host = uri.host?.lowercase()?.trimEnd('.')
        if (host.isNullOrBlank()) {
            return ValidationResult.Rejected(
                reasonFa = "نام میزبان (Host) در لینک اشتراک یافت نشد.",
                reasonEn = "URL host is missing."
            )
        }

        if (isBlockedHostname(host)) {
            return ValidationResult.Rejected(
                reasonFa = "دسترسی به آدرس‌های محلی و داخلی مسدود است.",
                reasonEn = "Access to local or internal hostnames is blocked."
            )
        }

        val port = uri.port
        if (port != -1 && port !in 1..65535) {
            return ValidationResult.Rejected(
                reasonFa = "شماره پورت نامعتبر است.",
                reasonEn = "Invalid port number."
            )
        }

        return ValidationResult.Allowed(trimmed, host)
    }

    /**
     * Resolves the target hostname and ensures every resolved IP is globally routable
     * (blocks loopback, RFC1918 private, link-local metadata 169.254.169.254, CGNAT, ULA).
     */
    fun validateHostResolution(host: String): ValidationResult {
        if (isBlockedHostname(host)) {
            return ValidationResult.Rejected(
                reasonFa = "دسترسی به میزبان داخلی مسدود است.",
                reasonEn = "Internal hostname blocked."
            )
        }
        val addresses = try {
            dnsResolver(host)
        } catch (_: Exception) {
            return ValidationResult.Rejected(
                reasonFa = "امکان تبدیل دامنه (DNS) وجود ندارد.",
                reasonEn = "Could not resolve hostname."
            )
        }

        if (addresses.isEmpty()) {
            return ValidationResult.Rejected(
                reasonFa = "هیچ آدرس IP برای میزبان یافت نشد.",
                reasonEn = "No IP addresses resolved for host."
            )
        }

        for (addr in addresses) {
            if (isForbiddenIp(addr)) {
                return ValidationResult.Rejected(
                    reasonFa = "اتصال به آدرس IP خصوصی یا داخلی (محافظت SSRF) مسدود شد.",
                    reasonEn = "Connection to private/internal IP blocked (SSRF protection)."
                )
            }
        }
        return ValidationResult.Allowed(host, host)
    }

    fun isBlockedHostname(host: String): Boolean {
        val h = host.lowercase().trim('[', ']', '.')
        return h == "localhost" ||
            h.endsWith(".localhost") ||
            h.endsWith(".local") ||
            h.endsWith(".internal") ||
            h == "metadata.google.internal"
    }

    fun isForbiddenIp(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }

        return when (address) {
            is Inet4Address -> {
                val b = address.address
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                when {
                    b0 == 0 -> true                                  // 0.0.0.0/8
                    b0 == 10 -> true                                 // 10.0.0.0/8
                    b0 == 100 && b1 in 64..127 -> true               // 100.64.0.0/10 CGNAT
                    b0 == 127 -> true                                // 127.0.0.0/8 loopback
                    b0 == 169 && b1 == 254 -> true                   // 169.254.0.0/16 metadata/link-local
                    b0 == 172 && b1 in 16..31 -> true                // 172.16.0.0/12
                    b0 == 192 && b1 == 168 -> true                   // 192.168.0.0/16
                    b0 == 192 && b1 == 0 -> true                     // 192.0.0.0/24 & TEST-NET
                    b0 >= 224 -> true                                // multicast & reserved
                    else -> false
                }
            }
            is Inet6Address -> {
                val b = address.address
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                val isUla = (b0 and 0xFE) == 0xFC                    // fc00::/7
                val isLinkLocal = b0 == 0xFE && (b1 and 0xC0) == 0x80 // fe80::/10
                val isIpv4Mapped = (0..9).all { b[it].toInt() == 0 } &&
                    (b[10].toInt() and 0xFF) == 0xFF &&
                    (b[11].toInt() and 0xFF) == 0xFF
                if (isIpv4Mapped) {
                    val v4 = InetAddress.getByAddress(b.copyOfRange(12, 16))
                    return isForbiddenIp(v4)
                }
                isUla || isLinkLocal
            }
            else -> true
        }
    }

    companion object {
        const val MAX_URL_LENGTH = 2048
        const val MAX_SUBSCRIPTION_BYTES = 5 * 1024 * 1024 // 5 MB strict ceiling
        const val MAX_REDIRECTS = 3
    }
}
