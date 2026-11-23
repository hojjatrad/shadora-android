package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Table 1: Configurations (stores encrypted raw config payload + canonical SHA-256 fingerprint).
 */
@Entity(
    tableName = "configurations",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["subscriptionId"])
    ]
)
data class ConfigurationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val protocol: String,
    val format: String,
    /** Encrypted using KeystoreCryptoManager (AES-256-GCM). Never stored in plaintext. */
    val encryptedRawPayload: String,
    /** Encrypted auth secret / UUID / private key / password summary. */
    val encryptedCredentialsJson: String,
    val fingerprint: String,
    val subscriptionId: Long? = null,
    val isManual: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Table 2: Subscriptions (manages HTTPS subscription feeds and sync status).
 */
@Entity(
    tableName = "subscriptions",
    indices = [Index(value = ["url"], unique = true)]
)
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val url: String,
    val refreshIntervalHours: Int = 0,
    val lastUpdatedAt: Long = 0L,
    val lastError: String? = null,
    val serverCount: Int = 0,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireTimestamp: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Table 3: Servers (indexed server cards linked to Configurations).
 */
@Entity(
    tableName = "servers",
    foreignKeys = [
        ForeignKey(
            entity = ConfigurationEntity::class,
            parentColumns = ["id"],
            childColumns = ["configurationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["configurationId"], unique = true),
        Index(value = ["subscriptionId"]),
        Index(value = ["countryCode"])
    ]
)
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val configurationId: Long,
    val subscriptionId: Long? = null,
    val isManual: Boolean = true,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val countryCode: String = "UN",
    val countryName: String = "Unknown",
    val transport: String = "tcp",
    val security: String = "none",
    val latencyMs: Int = -1,
    val lastPingAt: Long = 0L,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Table 4: ConnectionHistory (audit trail of VPN sessions, duration, and traffic volume).
 */
@Entity(
    tableName = "connection_history",
    indices = [Index(value = ["startedAt"])]
)
data class ConnectionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val serverId: Long,
    val serverName: String,
    val protocol: String,
    val engineId: String,
    val startedAt: Long,
    val endedAt: Long,
    val bytesDown: Long,
    val bytesUp: Long,
    val disconnectReason: String
)

/**
 * Table 5: AppSettings (singleton row id = 1 for user preferences).
 */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val languageCode: String = "fa",
    val themeMode: String = "OBSIDIAN_DARK",
    val primaryDns: String = "1.1.1.1",
    val secondaryDns: String = "8.8.8.8",
    val ipv6Enabled: Boolean = false,
    val splitTunnelMode: String = "ALL_APPS",
    val splitTunnelPackagesCsv: String = "",
    val autoConnect: Boolean = false,
    val killSwitchEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val selectedServerId: Long = -1L,
    val bypassIranDomains: Boolean = true,
    val bypassLanCidrs: Boolean = true,
    val customDirectDomainsCsv: String = "",
    val customBlockDomainsCsv: String = "",
    val autoFailoverEnabled: Boolean = false,
    val appLockEnabled: Boolean = false,
    val appLockPinHash: String = "",
    val appLockBiometricEnabled: Boolean = true
)

/**
 * Table 6: EngineSettings (per-engine tuning parameters).
 */
@Entity(tableName = "engine_settings")
data class EngineSettingsEntity(
    @PrimaryKey val engineId: String,
    val mtu: Int = 1500,
    val muxEnabled: Boolean = false,
    val allowInsecureTls: Boolean = false,
    val localSocksPort: Int = 10808,
    val localHttpPort: Int = 10809,
    val logLevel: String = "warning",
    val dnsStrategy: String = "UseIPv4"
)
