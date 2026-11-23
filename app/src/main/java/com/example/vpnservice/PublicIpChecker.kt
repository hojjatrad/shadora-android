package com.example.vpnservice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PublicIpCheckResult(
    val ip: String,
    val countryCode: String,
    val latencyMs: Int,
    val source: String
)

/**
 * Real HTTPS connectivity & public IP checker so users can verify their active
 * external egress IP, country code, and HTTPS response latency from inside SHADORA.
 */
class PublicIpChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    suspend fun checkPublicIp(): Result<PublicIpCheckResult> = withContext(Dispatchers.IO) {
        // Primary: Cloudflare Trace (fast, returns ip + loc country code over HTTPS)
        val cfAttempt = runCatching { queryCloudflareTrace() }
        if (cfAttempt.isSuccess) return@withContext cfAttempt

        // Fallback: ipify JSON endpoint over HTTPS
        runCatching { queryIpify() }
    }

    private fun queryCloudflareTrace(): PublicIpCheckResult {
        val start = System.currentTimeMillis()
        val req = Request.Builder()
            .url("https://1.1.1.1/cdn-cgi/trace")
            .header("User-Agent", "ShadoraVPN/1.0")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val map = body.lineSequence()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
                }
                .toMap()
            val ip = map["ip"].orEmpty()
            if (ip.isBlank()) error("Missing IP in trace response")
            val loc = map["loc"].orEmpty().ifBlank { "UN" }
            val rtt = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
            return PublicIpCheckResult(
                ip = ip,
                countryCode = loc,
                latencyMs = rtt,
                source = "Cloudflare"
            )
        }
    }

    private fun queryIpify(): PublicIpCheckResult {
        val start = System.currentTimeMillis()
        val req = Request.Builder()
            .url("https://api.ipify.org?format=json")
            .header("User-Agent", "ShadoraVPN/1.0")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val ip = json.optString("ip").trim()
            if (ip.isBlank()) error("Missing IP in ipify response")
            val rtt = (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
            return PublicIpCheckResult(
                ip = ip,
                countryCode = "UN",
                latencyMs = rtt,
                source = "ipify"
            )
        }
    }
}
