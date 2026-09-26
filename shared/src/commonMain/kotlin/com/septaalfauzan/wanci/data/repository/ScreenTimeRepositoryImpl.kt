package com.septaalfauzan.wanci.data.repository

import com.septaalfauzan.wanci.data.local.DailyUsageDao
import com.septaalfauzan.wanci.data.local.MonitoredAppDao
import com.septaalfauzan.wanci.data.local.SyncStateDao
import com.septaalfauzan.wanci.data.local.TodayKey
import com.septaalfauzan.wanci.data.local.entity.DailyUsageEntity
import com.septaalfauzan.wanci.data.local.entity.MonitoredAppEntity
import com.septaalfauzan.wanci.data.local.entity.SyncStateEntity
import com.septaalfauzan.wanci.domain.model.MonitoredApp
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenTimeRepositoryImpl(
    private val monitoredAppDao: MonitoredAppDao,
    private val dailyUsageDao: DailyUsageDao,
    private val syncStateDao: SyncStateDao,
    private val todayKey: TodayKey,
) : ScreenTimeRepository {

    override fun observeMonitoredApps(): Flow<List<MonitoredApp>> {
        val apps = monitoredAppDao.observeAll()
        return todayKey.dateFlow().flatMapLatest { date ->
            val usage = dailyUsageDao.observeForDate(date)
            combine(apps, usage) { appEntities, usageEntities ->
                val usageByPackage = usageEntities.associateBy { it.packageName }
                appEntities.map { it.toDomain(usageByPackage[it.packageName]?.usageMinutes ?: 0L) }
            }
        }
    }

    override fun observeTodayUsage(packageName: String): Flow<Long> =
        todayKey.dateFlow().flatMapLatest { date ->
            dailyUsageDao.observeForDate(date).map { usageEntities ->
                usageEntities.firstOrNull { it.packageName == packageName }?.usageMinutes ?: 0L
            }
        }

    override suspend fun getMonitoredApp(packageName: String): MonitoredApp? =
        monitoredAppDao.getByPackage(packageName)?.toDomain(0L)

    override suspend fun getTodayUsage(packageName: String, date: String): Long =
        dailyUsageDao.getUsage(packageName, date) ?: 0L

    override suspend fun addMonitoredApp(
        packageName: String,
        appName: String,
        dailyLimitMinutes: Int,
    ): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        val id = monitoredAppDao.insert(
            MonitoredAppEntity(
                packageName = packageName,
                appName = appName,
                dailyLimitMinutes = dailyLimitMinutes,
                createdAt = now,
                updatedAt = now,
            )
        )
        return id != -1L
    }

    override suspend fun updateDailyLimit(packageName: String, dailyLimitMinutes: Int) {
        monitoredAppDao.updateLimit(packageName, dailyLimitMinutes, Clock.System.now().toEpochMilliseconds())
    }

    override suspend fun setMonitoringEnabled(packageName: String, enabled: Boolean) {
        monitoredAppDao.setEnabled(packageName, enabled, Clock.System.now().toEpochMilliseconds())
    }

    override suspend fun removeMonitoredApp(packageName: String) {
        monitoredAppDao.deleteByPackage(packageName)
    }

    override suspend fun recordUsage(packageName: String, deltaMinutes: Long, date: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (dailyUsageDao.insertOrIgnore(DailyUsageEntity(packageName, date, deltaMinutes, now)) == -1L) {
            dailyUsageDao.addDelta(packageName, date, deltaMinutes, now)
        }
    }

    override suspend fun reconcileDailyUsage(
        deltaMinutesByPackage: Map<String, Long>,
        date: String,
    ) {
        deltaMinutesByPackage.forEach { (packageName, statsMinutes) ->
            val now = Clock.System.now().toEpochMilliseconds()
            if (dailyUsageDao.insertOrIgnore(DailyUsageEntity(packageName, date, statsMinutes, now)) == -1L) {
                dailyUsageDao.raiseToAtLeast(packageName, date, statsMinutes, now)
            }
        }
    }

    override suspend fun getSyncValue(key: String): String? = syncStateDao.get(key)

    override suspend fun putSyncValue(key: String, value: String) {
        syncStateDao.put(SyncStateEntity(key, value))
    }

    override fun observeSyncValue(key: String): Flow<String?> =
        syncStateDao.observe(key).map { it?.value }

    override suspend fun deleteUsageBefore(date: String) {
        dailyUsageDao.deleteBefore(date)
    }

    private fun MonitoredAppEntity.toDomain(todayUsageMinutes: Long) =
        MonitoredApp(
            id = id,
            packageName = packageName,
            appName = appName,
            dailyLimitMinutes = dailyLimitMinutes,
            enabled = enabled,
            createdAt = createdAt,
            updatedAt = updatedAt,
            todayUsageMinutes = todayUsageMinutes,
        )
}
