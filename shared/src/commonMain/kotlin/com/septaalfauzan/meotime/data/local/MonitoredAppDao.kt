package com.septaalfauzan.meotime.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.septaalfauzan.meotime.data.local.entity.MonitoredAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitoredAppDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(app: MonitoredAppEntity): Long

    @Update
    suspend fun update(app: MonitoredAppEntity)

    @Query("SELECT * FROM monitored_apps ORDER BY createdAt")
    fun observeAll(): Flow<List<MonitoredAppEntity>>

    @Query("SELECT * FROM monitored_apps WHERE packageName = :packageName")
    suspend fun getByPackage(packageName: String): MonitoredAppEntity?

    @Query("DELETE FROM monitored_apps WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)

    @Query("UPDATE monitored_apps SET enabled = :enabled, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun setEnabled(packageName: String, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE monitored_apps SET dailyLimitMinutes = :limit, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun updateLimit(packageName: String, limit: Int, updatedAt: Long)
}
