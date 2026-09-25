package com.septaalfauzan.wanci.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import com.septaalfauzan.wanci.data.local.entity.DailyUsageEntity
import com.septaalfauzan.wanci.data.local.entity.MonitoredAppEntity
import com.septaalfauzan.wanci.data.local.entity.SyncStateEntity

@ConstructedBy(WanciDatabaseConstructor::class)
@Database(
    entities = [
        MonitoredAppEntity::class,
        DailyUsageEntity::class,
        SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class WanciDatabase : RoomDatabase() {
    abstract fun monitoredAppDao(): MonitoredAppDao
    abstract fun dailyUsageDao(): DailyUsageDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val DB_NAME = "wanci.db"
    }
}
