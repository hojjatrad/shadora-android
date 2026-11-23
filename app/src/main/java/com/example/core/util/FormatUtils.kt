package com.example.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {

    fun formatDuration(totalSeconds: Long): String {
        val clamped = totalSeconds.coerceAtLeast(0L)
        val hours = clamped / 3600
        val minutes = (clamped % 3600) / 60
        val seconds = clamped % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L).toDouble()
        return when {
            b < 1024.0 -> String.format(Locale.US, "%.0f B", b)
            b < 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            b < 1024.0 * 1024.0 * 1024.0 -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

    fun formatTimestamp(epochMillis: Long): String {
        if (epochMillis <= 0L) return "—"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return fmt.format(Date(epochMillis))
    }
}
