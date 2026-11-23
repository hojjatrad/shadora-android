package com.example.security

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SafeLogEntry(
    val timestampMillis: Long,
    val level: String,
    val tag: String,
    val message: String
)

/**
 * Redacts sensitive VPN credentials (UUIDs, private keys, passwords, raw config URIs,
 * bearer tokens, and PEM blocks) before emitting diagnostic logs or recording into
 * the in-memory ring buffer.
 */
object SafeLogger {
    private const val MAX_BUFFER_ENTRIES = 150
    private val lock = Any()
    private val ringBuffer = ArrayDeque<SafeLogEntry>(MAX_BUFFER_ENTRIES)

    private val sensitivePatterns = listOf(
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
        Regex("(?i)(PrivateKey|PresharedKey|password|pass|token|secret|pbk|sid|authorization)\\s*[:=]\\s*[^\\s,&;]+"),
        Regex("(?i)(vless|vmess|trojan|ss|tuic|mieru|lightway|cloak)://[^\\s]+"),
        Regex("(?s)-----BEGIN [A-Z ]+-----.*?-----END [A-Z ]+-----")
    )

    fun sanitize(message: String): String {
        var cleaned = message
        for (pattern in sensitivePatterns) {
            cleaned = pattern.replace(cleaned, "[REDACTED]")
        }
        return cleaned
    }

    private fun appendToBuffer(level: String, tag: String, sanitizedMessage: String) {
        synchronized(lock) {
            if (ringBuffer.size >= MAX_BUFFER_ENTRIES) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(
                SafeLogEntry(
                    timestampMillis = System.currentTimeMillis(),
                    level = level,
                    tag = tag,
                    message = sanitizedMessage
                )
            )
        }
    }

    fun getRecentLogs(): List<SafeLogEntry> = synchronized(lock) {
        ringBuffer.toList().reversed()
    }

    fun clearLogs() = synchronized(lock) {
        ringBuffer.clear()
    }

    fun exportSanitizedText(): String {
        val entries = synchronized(lock) { ringBuffer.toList() }
        if (entries.isEmpty()) return ""
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        return entries.joinToString("\n") { entry ->
            "[${fmt.format(Date(entry.timestampMillis))}] ${entry.level}/${entry.tag}: ${entry.message}"
        }
    }

    fun i(tag: String, message: String) {
        val safe = sanitize(message)
        appendToBuffer("I", tag, safe)
        try {
            Log.i(tag, safe)
        } catch (_: Throwable) {
            // Ignored in pure JVM unit tests
        }
    }

    fun d(tag: String, message: String) {
        val safe = sanitize(message)
        appendToBuffer("D", tag, safe)
        try {
            Log.d(tag, safe)
        } catch (_: Throwable) {
            // Ignored in pure JVM unit tests
        }
    }

    fun w(tag: String, message: String) {
        val safe = sanitize(message)
        appendToBuffer("W", tag, safe)
        try {
            Log.w(tag, safe)
        } catch (_: Throwable) {
            // Ignored in pure JVM unit tests
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val safeError = throwable?.message?.let { " (${sanitize(it)})" }.orEmpty()
        val combined = sanitize(message) + safeError
        appendToBuffer("E", tag, combined)
        try {
            Log.e(tag, combined)
        } catch (_: Throwable) {
            // Ignored in pure JVM unit tests
        }
    }
}
