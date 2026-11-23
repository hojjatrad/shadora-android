package com.example.subscriptionmanager

import com.example.data.local.SubscriptionEntity

/**
 * Evaluates subscription auto-refresh intervals (`refreshIntervalHours`) and determines
 * which feeds are due for automatic synchronization.
 */
object SubscriptionAutoRefreshScheduler {

    private const val HOUR_MILLIS = 3_600_000L

    fun isDueForRefresh(sub: SubscriptionEntity, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (sub.refreshIntervalHours <= 0) return false
        if (sub.lastUpdatedAt <= 0L) return true
        val intervalMillis = sub.refreshIntervalHours * HOUR_MILLIS
        return (nowMillis - sub.lastUpdatedAt) >= intervalMillis
    }

    fun nextRefreshMillis(sub: SubscriptionEntity): Long? {
        if (sub.refreshIntervalHours <= 0) return null
        val base = if (sub.lastUpdatedAt > 0L) sub.lastUpdatedAt else sub.createdAt
        return base + (sub.refreshIntervalHours * HOUR_MILLIS)
    }

    fun findDueSubscriptions(
        subscriptions: List<SubscriptionEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<SubscriptionEntity> = subscriptions.filter { isDueForRefresh(it, nowMillis) }
}
