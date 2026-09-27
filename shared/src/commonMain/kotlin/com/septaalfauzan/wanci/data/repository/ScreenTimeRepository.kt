package com.septaalfauzan.wanci.data.repository

import com.septaalfauzan.wanci.domain.model.MonitoredApp
import kotlinx.coroutines.flow.Flow

interface ScreenTimeRepository {
    fun observeMonitoredApps(): Flow<List<MonitoredApp>>
    fun observeTodayUsageSeconds(packageName: String): Flow<Long>
    suspend fun getMonitoredApp(packageName: String): MonitoredApp?
    suspend fun getTodayUsageSeconds(packageName: String, date: String): Long
    suspend fun addMonitoredApp(packageName: String, appName: String, dailyLimitMinutes: Int): Boolean
    suspend fun updateDailyLimit(packageName: String, dailyLimitMinutes: Int)
    suspend fun setMonitoringEnabled(packageName: String, enabled: Boolean)
    suspend fun removeMonitoredApp(packageName: String)
    suspend fun recordUsageSeconds(packageName: String, deltaSeconds: Long, date: String)
    suspend fun reconcileDailyUsage(deltaMinutesByPackage: Map<String, Long>, date: String)
    suspend fun getSyncValue(key: String): String?
    suspend fun putSyncValue(key: String, value: String)
    fun observeSyncValue(key: String): Flow<String?>
    suspend fun deleteUsageBefore(date: String)
}
