package com.example.subscriptionmanager

import com.example.configmanager.BatchImportReport
import com.example.data.local.SubscriptionEntity
import com.example.data.repository.ShadoraRepository
import com.example.security.NetworkSecurityGuard
import com.example.security.SafeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

data class SubscriptionQuota(
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireEpochSeconds: Long = 0L
)

sealed interface SubscriptionFetchResult {
    data class Success(
        val subscriptionId: Long,
        val report: BatchImportReport,
        val quota: SubscriptionQuota
    ) : SubscriptionFetchResult

    data class Error(
        val messageFa: String,
        val messageEn: String
    ) : SubscriptionFetchResult
}

/**
 * Secure HTTPS Subscription Manager:
 * - Enforces HTTPS-only endpoints
 * - Blocks SSRF & redirects to private/loopback/link-local/metadata IP ranges
 * - Limits redirects to MAX_REDIRECTS (3) with per-hop SSRF re-validation
 * - Caps response downloads at 5 MB (MAX_SUBSCRIPTION_BYTES)
 * - Parses `subscription-userinfo` quota headers
 * - Preserves all manual configurations on refresh
 */
class SubscriptionManager(
    private val repository: ShadoraRepository,
    private val securityGuard: NetworkSecurityGuard = NetworkSecurityGuard(),
    private val httpClientFactory: (NetworkSecurityGuard) -> OkHttpClient = ::defaultSecureClient
) {
    private val httpClient: OkHttpClient by lazy { httpClientFactory(securityGuard) }

    suspend fun addAndRefreshSubscription(
        name: String,
        rawUrl: String,
        refreshIntervalHours: Int,
        isPersian: Boolean,
        refreshIfExists: Boolean = false
    ): SubscriptionFetchResult {
        val urlCheck = securityGuard.validateSubscriptionUrl(rawUrl)
        if (urlCheck is NetworkSecurityGuard.ValidationResult.Rejected) {
            return SubscriptionFetchResult.Error(urlCheck.reasonFa, urlCheck.reasonEn)
        }
        val normalizedUrl = (urlCheck as NetworkSecurityGuard.ValidationResult.Allowed).normalizedUrl
        val existing = repository.getSubscriptionByUrl(normalizedUrl)
        if (existing != null) {
            if (refreshIfExists) {
                return refreshSubscription(existing.id, isPersian)
            }
            return SubscriptionFetchResult.Error(
                messageFa = "این لینک اشتراک قبلاً ثبت شده است.",
                messageEn = "This subscription URL already exists."
            )
        }

        val cleanName = name.trim().ifBlank { urlCheck.host }
        val subId = repository.createSubscription(cleanName, normalizedUrl, refreshIntervalHours)
        return refreshSubscription(subId, isPersian)
    }

    suspend fun refreshSubscription(
        subscriptionId: Long,
        isPersian: Boolean
    ): SubscriptionFetchResult = withContext(Dispatchers.IO) {
        val sub = repository.getSubscription(subscriptionId)
            ?: return@withContext SubscriptionFetchResult.Error(
                messageFa = "اشتراک مورد نظر یافت نشد.",
                messageEn = "Subscription not found."
            )

        val fetchOutcome = fetchSecurePayload(sub.url)
        when (fetchOutcome) {
            is RawFetchOutcome.Failure -> {
                val errText = if (isPersian) fetchOutcome.reasonFa else fetchOutcome.reasonEn
                repository.updateSubscription(
                    sub.copy(lastError = errText)
                )
                SubscriptionFetchResult.Error(fetchOutcome.reasonFa, fetchOutcome.reasonEn)
            }

            is RawFetchOutcome.Success -> {
                val report = repository.replaceSubscriptionConfigs(
                    subscriptionId = sub.id,
                    rawPayload = fetchOutcome.body,
                    isPersian = isPersian
                )
                val serverCount = repository.countServersForSubscription(sub.id)
                val errorSummary = if (report.importedCount == 0 && report.errors.isNotEmpty()) {
                    report.errors.first()
                } else {
                    null
                }
                val resolvedName = fetchOutcome.profileTitle
                    ?.takeIf { it.isNotBlank() && sub.name == runCatching { URI(sub.url).host }.getOrNull() }
                    ?: sub.name
                repository.updateSubscription(
                    sub.copy(
                        name = resolvedName,
                        lastUpdatedAt = System.currentTimeMillis(),
                        lastError = errorSummary,
                        serverCount = serverCount,
                        uploadBytes = fetchOutcome.quota.uploadBytes,
                        downloadBytes = fetchOutcome.quota.downloadBytes,
                        totalBytes = fetchOutcome.quota.totalBytes,
                        expireTimestamp = fetchOutcome.quota.expireEpochSeconds * 1000L
                    )
                )
                SubscriptionFetchResult.Success(sub.id, report, fetchOutcome.quota)
            }
        }
    }

    suspend fun refreshAllSubscriptions(isPersian: Boolean): List<SubscriptionFetchResult> {
        val all = repository.getAllSubscriptions()
        return all.map { refreshSubscription(it.id, isPersian) }
    }

    suspend fun deleteSubscription(subscriptionId: Long) {
        repository.deleteSubscriptionAndServers(subscriptionId)
    }

    private sealed interface RawFetchOutcome {
        data class Success(
            val body: String,
            val quota: SubscriptionQuota,
            val profileTitle: String? = null
        ) : RawFetchOutcome
        data class Failure(val reasonFa: String, val reasonEn: String) : RawFetchOutcome
    }

    private fun fetchSecurePayload(initialUrl: String): RawFetchOutcome {
        var currentUrl = initialUrl
        var redirects = 0

        while (true) {
            val urlCheck = securityGuard.validateSubscriptionUrl(currentUrl)
            if (urlCheck is NetworkSecurityGuard.ValidationResult.Rejected) {
                return RawFetchOutcome.Failure(urlCheck.reasonFa, urlCheck.reasonEn)
            }
            val allowed = urlCheck as NetworkSecurityGuard.ValidationResult.Allowed
            val hostCheck = securityGuard.validateHostResolution(allowed.host)
            if (hostCheck is NetworkSecurityGuard.ValidationResult.Rejected) {
                return RawFetchOutcome.Failure(hostCheck.reasonFa, hostCheck.reasonEn)
            }

            val request = Request.Builder()
                .url(allowed.normalizedUrl)
                // v2rayNG-compatible User-Agent ensures Marzban/3x-ui/Hiddify/Speedur return raw subscription configs rather than HTML
                .header("User-Agent", "v2rayNG/1.8.23 ShadoraVPN/1.0")
                .header("Accept", "text/plain, application/json, */*")
                .get()
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code in 300..399) {
                        val location = response.header("Location")
                            ?: return RawFetchOutcome.Failure(
                                "پاسخ تغییر مسیر بدون آدرس مقصد دریافت شد.",
                                "Redirect response missing Location header."
                            )
                        if (redirects >= NetworkSecurityGuard.MAX_REDIRECTS) {
                            return RawFetchOutcome.Failure(
                                "تعداد تغییر مسیر (Redirect) بیش از حد مجاز است.",
                                "Too many redirects (max ${NetworkSecurityGuard.MAX_REDIRECTS})."
                            )
                        }
                        currentUrl = try {
                            URI(allowed.normalizedUrl).resolve(location).toString()
                        } catch (_: Exception) {
                            return RawFetchOutcome.Failure(
                                "آدرس مقصد تغییر مسیر نامعتبر است.",
                                "Invalid redirect target URL."
                            )
                        }
                        redirects++
                        return@use
                    }

                    if (!response.isSuccessful) {
                        return RawFetchOutcome.Failure(
                            "سرور اشتراک کد خطای HTTP ${response.code} برگرداند.",
                            "Subscription server returned HTTP ${response.code}."
                        )
                    }

                    val body = response.body
                        ?: return RawFetchOutcome.Failure(
                            "پاسخ سرور اشتراک خالی است.",
                            "Empty subscription response body."
                        )

                    val contentLength = body.contentLength()
                    if (contentLength > NetworkSecurityGuard.MAX_SUBSCRIPTION_BYTES) {
                        return RawFetchOutcome.Failure(
                            "حجم فایل اشتراک بیش از سقف مجاز ۵ مگابایت است.",
                            "Subscription payload exceeds the 5 MB safety limit."
                        )
                    }

                    val payloadText = readBoundedUtf8(body.byteStream(), NetworkSecurityGuard.MAX_SUBSCRIPTION_BYTES)
                        ?: return RawFetchOutcome.Failure(
                            "حجم دانلود اشتراک از سقف ۵ مگابایت فراتر رفت و متوقف شد.",
                            "Download aborted: subscription exceeded 5 MB limit."
                        )

                    val quota = parseUserInfoHeader(response.header("subscription-userinfo"))
                    val profileTitle = decodeProfileTitleHeader(response.header("profile-title"))
                    return RawFetchOutcome.Success(payloadText, quota, profileTitle)
                }
            } catch (e: IOException) {
                SafeLogger.w(TAG, "Subscription fetch IO error: ${e.message}")
                return RawFetchOutcome.Failure(
                    "خطای شبکه هنگام دریافت اشتراک: اتصال برقرار نشد.",
                    "Network error fetching subscription: ${e.message ?: "unreachable"}"
                )
            }
        }
    }

    private fun decodeProfileTitleHeader(rawHeader: String?): String? {
        if (rawHeader.isNullOrBlank()) return null
        val trimmed = rawHeader.trim()
        return if (trimmed.startsWith("base64:", ignoreCase = true)) {
            repository.configParser.decodeBase64Flexible(trimmed.substringAfter(":"))?.trim()
        } else {
            trimmed
        }
    }

    private fun readBoundedUtf8(stream: java.io.InputStream, maxBytes: Int): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    fun parseUserInfoHeader(header: String?): SubscriptionQuota {
        if (header.isNullOrBlank()) return SubscriptionQuota()
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        header.split(';').forEach { segment ->
            val parts = segment.trim().split('=')
            if (parts.size == 2) {
                val value = parts[1].trim().toLongOrNull() ?: 0L
                when (parts[0].trim().lowercase()) {
                    "upload" -> upload = value
                    "download" -> download = value
                    "total" -> total = value
                    "expire" -> expire = value
                }
            }
        }
        return SubscriptionQuota(upload, download, total, expire)
    }

    companion object {
        private const val TAG = "ShadoraSubManager"

        fun defaultSecureClient(guard: NetworkSecurityGuard): OkHttpClient {
            val ssrfSafeDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val resolved = Dns.SYSTEM.lookup(hostname)
                    if (resolved.any { guard.isForbiddenIp(it) }) {
                        throw IOException("Blocked SSRF target IP for host")
                    }
                    return resolved
                }
            }
            return OkHttpClient.Builder()
                .dns(ssrfSafeDns)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
