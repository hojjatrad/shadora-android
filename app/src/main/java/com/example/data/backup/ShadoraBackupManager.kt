package com.example.data.backup

import com.example.data.local.AppSettingsEntity
import com.example.data.local.SubscriptionEntity
import org.json.JSONArray
import org.json.JSONObject

data class BackupSubscriptionItem(
    val name: String,
    val url: String,
    val refreshIntervalHours: Int
)

data class BackupSettingsPayload(
    val languageCode: String,
    val themeMode: String,
    val primaryDns: String,
    val secondaryDns: String,
    val ipv6Enabled: Boolean,
    val splitTunnelMode: String,
    val splitTunnelPackagesCsv: String,
    val autoConnect: Boolean,
    val killSwitchEnabled: Boolean,
    val notificationsEnabled: Boolean,
    val bypassIranDomains: Boolean = true,
    val bypassLanCidrs: Boolean = true,
    val customDirectDomainsCsv: String = "",
    val customBlockDomainsCsv: String = "",
    val autoFailoverEnabled: Boolean = false
)

data class ShadoraBackupBundle(
    val version: Int = CURRENT_VERSION,
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val settings: BackupSettingsPayload?,
    val subscriptions: List<BackupSubscriptionItem>,
    val manualConfigs: List<String>
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class BackupRestoreSummary(
    val restoredSettings: Boolean,
    val restoredSubscriptions: Int,
    val restoredManualConfigs: Int,
    val skippedDuplicates: Int
)

/**
 * Versioned JSON serializer/deserializer for local SAF backup & restore of
 * SHADORA preferences, subscriptions, and manual configs.
 */
object ShadoraBackupCodec {

    fun encode(
        settings: AppSettingsEntity,
        subscriptions: List<SubscriptionEntity>,
        manualRawConfigs: List<String>
    ): String {
        val root = JSONObject()
        root.put("format", "shadora_backup")
        root.put("version", ShadoraBackupBundle.CURRENT_VERSION)
        root.put("exportedAtMillis", System.currentTimeMillis())

        // Intentionally never export appLockPinHash — PIN stays device-local.
        val settingsObj = JSONObject().apply {
            put("languageCode", settings.languageCode)
            put("themeMode", settings.themeMode)
            put("primaryDns", settings.primaryDns)
            put("secondaryDns", settings.secondaryDns)
            put("ipv6Enabled", settings.ipv6Enabled)
            put("splitTunnelMode", settings.splitTunnelMode)
            put("splitTunnelPackagesCsv", settings.splitTunnelPackagesCsv)
            put("autoConnect", settings.autoConnect)
            put("killSwitchEnabled", settings.killSwitchEnabled)
            put("notificationsEnabled", settings.notificationsEnabled)
            put("bypassIranDomains", settings.bypassIranDomains)
            put("bypassLanCidrs", settings.bypassLanCidrs)
            put("customDirectDomainsCsv", settings.customDirectDomainsCsv)
            put("customBlockDomainsCsv", settings.customBlockDomainsCsv)
            put("autoFailoverEnabled", settings.autoFailoverEnabled)
        }
        root.put("settings", settingsObj)

        val subsArr = JSONArray()
        subscriptions.forEach { sub ->
            subsArr.put(
                JSONObject().apply {
                    put("name", sub.name)
                    put("url", sub.url)
                    put("refreshIntervalHours", sub.refreshIntervalHours)
                }
            )
        }
        root.put("subscriptions", subsArr)

        val configsArr = JSONArray()
        manualRawConfigs.forEach { raw ->
            if (raw.isNotBlank()) configsArr.put(raw)
        }
        root.put("manualConfigs", configsArr)

        return root.toString(2)
    }

    fun decode(rawJson: String): Result<ShadoraBackupBundle> = runCatching {
        val root = JSONObject(rawJson.trim())
        val format = root.optString("format")
        if (format != "shadora_backup") {
            error("Invalid backup file format")
        }
        val version = root.optInt("version", 1)
        val exportedAt = root.optLong("exportedAtMillis", 0L)

        val settingsObj = root.optJSONObject("settings")
        val settings = settingsObj?.let { s ->
            BackupSettingsPayload(
                languageCode = s.optString("languageCode", "fa"),
                themeMode = s.optString("themeMode", "OBSIDIAN_DARK"),
                primaryDns = s.optString("primaryDns", "1.1.1.1"),
                secondaryDns = s.optString("secondaryDns", "8.8.8.8"),
                ipv6Enabled = s.optBoolean("ipv6Enabled", false),
                splitTunnelMode = s.optString("splitTunnelMode", "ALL_APPS"),
                splitTunnelPackagesCsv = s.optString("splitTunnelPackagesCsv", ""),
                autoConnect = s.optBoolean("autoConnect", false),
                killSwitchEnabled = s.optBoolean("killSwitchEnabled", false),
                notificationsEnabled = s.optBoolean("notificationsEnabled", true),
                bypassIranDomains = s.optBoolean("bypassIranDomains", true),
                bypassLanCidrs = s.optBoolean("bypassLanCidrs", true),
                customDirectDomainsCsv = s.optString("customDirectDomainsCsv", ""),
                customBlockDomainsCsv = s.optString("customBlockDomainsCsv", ""),
                autoFailoverEnabled = s.optBoolean("autoFailoverEnabled", false)
            )
        }

        val subs = mutableListOf<BackupSubscriptionItem>()
        val subsArr = root.optJSONArray("subscriptions")
        if (subsArr != null) {
            for (i in 0 until subsArr.length()) {
                val obj = subsArr.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                if (url.isNotEmpty()) {
                    subs.add(
                        BackupSubscriptionItem(
                            name = obj.optString("name").trim(),
                            url = url,
                            refreshIntervalHours = obj.optInt("refreshIntervalHours", 24).coerceAtLeast(0)
                        )
                    )
                }
            }
        }

        val manual = mutableListOf<String>()
        val configsArr = root.optJSONArray("manualConfigs")
        if (configsArr != null) {
            for (i in 0 until configsArr.length()) {
                val item = configsArr.optString(i).trim()
                if (item.isNotEmpty()) manual.add(item)
            }
        }

        ShadoraBackupBundle(
            version = version,
            exportedAtMillis = exportedAt,
            settings = settings,
            subscriptions = subs,
            manualConfigs = manual
        )
    }
}
