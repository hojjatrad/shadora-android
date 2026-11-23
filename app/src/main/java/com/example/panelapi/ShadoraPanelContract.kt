package com.example.panelapi

/**
 * Extensible contract for future SHADORA Panel API integration.
 *
 * Version 1 operates completely standalone with zero mandatory dependency on any
 * central backend (`StandaloneOfflinePanelClient`). Future versions can supply a
 * concrete HTTPS implementation bound to a user's SHADORA Panel without altering
 * core config/engine/UI layers.
 */
data class PanelEndpointConfig(
    val baseUrl: String,
    val apiVersion: String = "v1"
)

data class PanelAccountInfo(
    val accountId: String,
    val planName: String,
    val subscriptionUrls: List<String>,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireEpochSeconds: Long = 0L
)

sealed interface PanelSyncResult {
    data class Synced(val account: PanelAccountInfo) : PanelSyncResult
    data object StandaloneMode : PanelSyncResult
    data class Error(val messageFa: String, val messageEn: String) : PanelSyncResult
}

interface ShadoraPanelClient {
    val isConfigured: Boolean
    val endpoint: PanelEndpointConfig?

    suspend fun syncAccountSubscriptions(): PanelSyncResult
}

/**
 * Default v1 standalone implementation — zero network calls, zero backend lock-in.
 */
class StandaloneOfflinePanelClient : ShadoraPanelClient {
    override val isConfigured: Boolean = false
    override val endpoint: PanelEndpointConfig? = null

    override suspend fun syncAccountSubscriptions(): PanelSyncResult =
        PanelSyncResult.StandaloneMode
}
