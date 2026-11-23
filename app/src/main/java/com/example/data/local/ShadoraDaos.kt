package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigurationDao {
    @Query("SELECT * FROM configurations ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ConfigurationEntity>>

    @Query("SELECT * FROM configurations WHERE id = :id")
    suspend fun getById(id: Long): ConfigurationEntity?

    @Query("SELECT * FROM configurations WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): ConfigurationEntity?

    @Query("SELECT fingerprint FROM configurations")
    suspend fun getAllFingerprints(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ConfigurationEntity): Long

    @Query("DELETE FROM configurations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM configurations WHERE isManual = 1 ORDER BY createdAt ASC")
    suspend fun getManualConfigs(): List<ConfigurationEntity>

    @Query("DELETE FROM configurations WHERE subscriptionId = :subscriptionId AND isManual = 0")
    suspend fun deleteNonManualBySubscription(subscriptionId: Long)
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SubscriptionEntity): Long

    @Update
    suspend fun update(entity: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY isFavorite DESC, createdAt DESC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getById(id: Long): ServerEntity?

    @Query("SELECT * FROM servers")
    suspend fun getAll(): List<ServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ServerEntity): Long

    @Query("UPDATE servers SET latencyMs = :latencyMs, lastPingAt = :timestamp WHERE id = :serverId")
    suspend fun updateLatency(serverId: Long, latencyMs: Int, timestamp: Long)

    @Query("UPDATE servers SET isFavorite = :favorite WHERE id = :serverId")
    suspend fun updateFavorite(serverId: Long, favorite: Boolean)

    @Query("UPDATE servers SET name = :newName WHERE id = :serverId")
    suspend fun updateName(serverId: Long, newName: String)

    @Query("SELECT configurationId FROM servers WHERE latencyMs = -2")
    suspend fun getUnreachableConfigurationIds(): List<Long>

    @Query("DELETE FROM servers WHERE id = :serverId")
    suspend fun deleteById(serverId: Long)

    @Query("SELECT COUNT(*) FROM servers WHERE subscriptionId = :subscriptionId")
    suspend fun countBySubscription(subscriptionId: Long): Int

    @Transaction
    suspend fun insertServerWithConfig(
        configDao: ConfigurationDao,
        config: ConfigurationEntity,
        serverBuilder: (Long) -> ServerEntity
    ): Long {
        val configId = configDao.insert(config)
        if (configId == -1L) return -1L
        return insert(serverBuilder(configId))
    }
}

@Dao
interface ConnectionHistoryDao {
    @Query("SELECT * FROM connection_history ORDER BY startedAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<ConnectionHistoryEntity>>

    @Insert
    suspend fun insert(entity: ConnectionHistoryEntity): Long

    @Query("DELETE FROM connection_history")
    suspend fun clearAll()
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observeSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AppSettingsEntity)
}

@Dao
interface EngineSettingsDao {
    @Query("SELECT * FROM engine_settings")
    fun observeAll(): Flow<List<EngineSettingsEntity>>

    @Query("SELECT * FROM engine_settings WHERE engineId = :engineId")
    suspend fun getByEngine(engineId: String): EngineSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: EngineSettingsEntity)
}
