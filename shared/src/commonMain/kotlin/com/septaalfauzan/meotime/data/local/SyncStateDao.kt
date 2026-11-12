package com.septaalfauzan.meotime.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.septaalfauzan.meotime.data.local.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM sync_state WHERE key = :key")
    fun observe(key: String): Flow<SyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncStateEntity)
}
