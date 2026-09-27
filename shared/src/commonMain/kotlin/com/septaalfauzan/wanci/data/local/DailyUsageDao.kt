package com.septaalfauzan.wanci.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.septaalfauzan.wanci.data.local.entity.DailyUsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyUsageDao {
    @Query("SELECT usageSeconds FROM daily_usage WHERE packageName = :packageName AND date = :date")
    suspend fun getUsageSeconds(packageName: String, date: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(usage: DailyUsageEntity): Long

    @Query("UPDATE daily_usage SET usageSeconds = usageSeconds + :delta, updatedAt = :updatedAt WHERE packageName = :packageName AND date = :date")
    suspend fun addDeltaSeconds(packageName: String, date: String, delta: Long, updatedAt: Long)

    @Query("SELECT * FROM daily_usage WHERE date = :date")
    fun observeForDate(date: String): Flow<List<DailyUsageEntity>>

    @Query("DELETE FROM daily_usage WHERE date < :date")
    suspend fun deleteBefore(date: String)
}
