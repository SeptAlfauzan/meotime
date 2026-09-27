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

    /**
     * Raises the total to at least [targetSeconds] and leaves a larger total alone.
     *
     * One statement on purpose. A read-then-write cannot lower a total, but it can *over*-raise
     * one: read 120, have a concurrent writer add 300, then write 600 and land on 900. Written as
     * a `CASE` in the `SET` clause, the comparison and the write are the same atomic step, so a
     * concurrent delta is either seen or added, never both.
     */
    @Query("UPDATE daily_usage SET usageSeconds = CASE WHEN usageSeconds < :targetSeconds THEN :targetSeconds ELSE usageSeconds END, updatedAt = :updatedAt WHERE packageName = :packageName AND date = :date")
    suspend fun raiseToAtLeastSeconds(packageName: String, date: String, targetSeconds: Long, updatedAt: Long)

    @Query("SELECT * FROM daily_usage WHERE date = :date")
    fun observeForDate(date: String): Flow<List<DailyUsageEntity>>

    @Query("DELETE FROM daily_usage WHERE date < :date")
    suspend fun deleteBefore(date: String)
}
