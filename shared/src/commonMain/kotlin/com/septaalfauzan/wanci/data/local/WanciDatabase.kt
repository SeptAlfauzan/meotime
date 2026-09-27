package com.septaalfauzan.wanci.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import com.septaalfauzan.wanci.data.local.entity.DailyUsageEntity
import com.septaalfauzan.wanci.data.local.entity.MonitoredAppEntity
import com.septaalfauzan.wanci.data.local.entity.SyncStateEntity

/**
 * The app's only database: monitored apps, per-day usage and the sync key/value pairs.
 *
 * `exportSchema` is on and `schemas/` is committed, so a future version's migration can be
 * generated from the recorded schema instead of written from memory — which is the only way to
 * catch a column that was renamed in the entity but not in the SQL.
 */
@ConstructedBy(WanciDatabaseConstructor::class)
@Database(
    entities = [
        MonitoredAppEntity::class,
        DailyUsageEntity::class,
        SyncStateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class WanciDatabase : RoomDatabase() {
    abstract fun monitoredAppDao(): MonitoredAppDao
    abstract fun dailyUsageDao(): DailyUsageDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val DB_NAME = "wanci.db"
    }
}
