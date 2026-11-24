package com.septaalfauzan.meotime.data.repository

import com.septaalfauzan.meotime.domain.model.DailyUsagePoint
import com.septaalfauzan.meotime.domain.model.HourlyUsagePoint
import com.septaalfauzan.meotime.domain.model.MonitoredApp
import com.septaalfauzan.meotime.domain.model.WeeklyLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

interface ScreenTimeRepository {
    fun observeMonitoredApps(): Flow<List<MonitoredApp>>
    fun observeTodayUsageSeconds(packageName: String): Flow<Long>
    suspend fun getMonitoredApp(packageName: String): MonitoredApp?
    suspend fun getTodayUsageSeconds(packageName: String, date: String): Long
    suspend fun addMonitoredApp(packageName: String, appName: String, dailyLimitMinutes: Int): Boolean
    suspend fun addMonitoredAppWithWeeklyLimits(packageName: String, appName: String, weeklyLimits: WeeklyLimits): Boolean
    suspend fun updateDailyLimit(packageName: String, dailyLimitMinutes: Int)
    suspend fun updateWeeklyLimits(packageName: String, weeklyLimits: WeeklyLimits)
    suspend fun setMonitoringEnabled(packageName: String, enabled: Boolean)
    suspend fun removeMonitoredApp(packageName: String)
    suspend fun recordUsageSeconds(packageName: String, deltaSeconds: Long, date: String)
    suspend fun recordUsageSession(
        packageName: String,
        deltaSeconds: Long,
        startedAtEpochMillis: Long,
        endedAtEpochMillis: Long,
    )
    fun observeDailyUsage(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyUsagePoint>>
    fun observeHourlyUsage(date: LocalDate): Flow<List<HourlyUsagePoint>>
    suspend fun getSyncValue(key: String): String?
    suspend fun putSyncValue(key: String, value: String)
    fun observeSyncValue(key: String): Flow<String?>
    suspend fun deleteUsageBefore(date: String)
}
