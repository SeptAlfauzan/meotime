package com.septaalfauzan.meotime.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.septaalfauzan.meotime.data.local.entity.DailyUsageEntity
import com.septaalfauzan.meotime.data.local.entity.HourlyUsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HourlyUsageDao {
    data class Bucket(
        val date: String,
        val hour: Int,
        val seconds: Long,
    )

    @Query("SELECT * FROM hourly_usage WHERE date = :date ORDER BY hour")
    fun observeForDate(date: String): Flow<List<HourlyUsageEntity>>

    @Query(
        "UPDATE hourly_usage SET usageSeconds = usageSeconds + :delta, updatedAt = :updatedAt " +
            "WHERE packageName = :packageName AND date = :date AND hour = :hour",
    )
    suspend fun addDelta(
        packageName: String,
        date: String,
        hour: Int,
        delta: Long,
        updatedAt: Long,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(usage: HourlyUsageEntity): Long

    @Query(
        "UPDATE daily_usage SET usageSeconds = usageSeconds + :delta, updatedAt = :updatedAt " +
            "WHERE packageName = :packageName AND date = :date",
    )
    suspend fun addDailyDelta(packageName: String, date: String, delta: Long, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDailyIfAbsent(usage: DailyUsageEntity): Long

    @Query("DELETE FROM hourly_usage WHERE date < :date")
    suspend fun deleteHourlyBefore(date: String)

    @Query("DELETE FROM daily_usage WHERE date < :date")
    suspend fun deleteDailyBefore(date: String)

    /**
     * Writes every bucket for one session and the matching daily totals in one transaction.
     *
     * Update-before-insert is deliberate: `ON CONFLICT DO UPDATE` needs SQLite 3.24, while
     * `minSdk` is 28. The update is a no-op when the row is absent, then the insert creates it
     * with the first value. A second bucket for the same day takes the update branch.
     */
    @Transaction
    suspend fun recordBuckets(packageName: String, buckets: List<Bucket>, updatedAt: Long) {
        buckets.forEach { bucket ->
            addDelta(packageName, bucket.date, bucket.hour, bucket.seconds, updatedAt)
            insertIfAbsent(
                HourlyUsageEntity(
                    packageName = packageName,
                    date = bucket.date,
                    hour = bucket.hour,
                    usageSeconds = bucket.seconds,
                    updatedAt = updatedAt,
                ),
            )
            addDailyDelta(packageName, bucket.date, bucket.seconds, updatedAt)
            insertDailyIfAbsent(
                DailyUsageEntity(
                    packageName = packageName,
                    date = bucket.date,
                    usageSeconds = bucket.seconds,
                    updatedAt = updatedAt,
                ),
            )
        }
    }

    @Transaction
    suspend fun deleteBefore(date: String) {
        deleteDailyBefore(date)
        deleteHourlyBefore(date)
    }
}
