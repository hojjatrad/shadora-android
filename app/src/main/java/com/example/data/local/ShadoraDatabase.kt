package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConfigurationEntity::class,
        SubscriptionEntity::class,
        ServerEntity::class,
        ConnectionHistoryEntity::class,
        AppSettingsEntity::class,
        EngineSettingsEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ShadoraDatabase : RoomDatabase() {
    abstract fun configurationDao(): ConfigurationDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun serverDao(): ServerDao
    abstract fun connectionHistoryDao(): ConnectionHistoryDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun engineSettingsDao(): EngineSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: ShadoraDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN bypassIranDomains INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN bypassLanCidrs INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN customDirectDomainsCsv TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN customBlockDomainsCsv TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN autoFailoverEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN appLockEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN appLockPinHash TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN appLockBiometricEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun getInstance(context: Context): ShadoraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ShadoraDatabase::class.java,
                    "shadora_vpn.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
