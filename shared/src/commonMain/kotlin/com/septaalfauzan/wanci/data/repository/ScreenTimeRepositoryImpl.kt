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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class ScreenTimeRepositoryImpl(
    private val monitoredAppDao: MonitoredAppDao,
    private val dailyUsageDao: DailyUsageDao,
    private val syncStateDao: SyncStateDao,
    private val todayKey: TodayKey,
) : ScreenTimeRepository {

    override fun observeMonitoredApps(): Flow<List<MonitoredApp>> {
        val apps = monitoredAppDao.observeAll()
        val usage = dailyUsageDao.observeForDate(todayKey.todayIso())
        return combine(apps, usage) { appEntities, usageEntities ->
            val usageByPackage = usageEntities.associateBy { it.packageName }
            appEntities.map { it.toDomain(usageByPackage[it.packageName]?.usageMinutes ?: 0L) }
        }
    }

    override fun observeTodayUsage(packageName: String): Flow<Long> =
        dailyUsageDao.observeForDate(todayKey.todayIso()).map { usageEntities ->
            usageEntities.firstOrNull { it.packageName == packageName }?.usageMinutes ?: 0L
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
        if (monitoredAppDao.getByPackage(packageName) != null) return false
        val now = Clock.System.now().toEpochMilliseconds()
        return try {
            monitoredAppDao.insert(
                MonitoredAppEntity(
                    packageName = packageName,
                    appName = appName,
                    dailyLimitMinutes = dailyLimitMinutes,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            true
        } catch (_: Exception) {
            false
        }
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
        val existing = dailyUsageDao.getUsage(packageName, date) ?: 0L
        dailyUsageDao.upsert(
            DailyUsageEntity(packageName, date, existing + deltaMinutes, Clock.System.now().toEpochMilliseconds())
        )
    }

    override suspend fun reconcileDailyUsage(
        deltaMinutesByPackage: Map<String, Long>,
        date: String,
    ) {
        deltaMinutesByPackage.forEach { (packageName, statsMinutes) ->
            val existing = dailyUsageDao.getUsage(packageName, date) ?: 0L
            if (statsMinutes > existing) {
                dailyUsageDao.upsert(
                    DailyUsageEntity(packageName, date, statsMinutes, Clock.System.now().toEpochMilliseconds())
                )
            }
        }
    }

    override suspend fun getSyncValue(key: String): String? = syncStateDao.get(key)

    override suspend fun putSyncValue(key: String, value: String) {
        syncStateDao.put(SyncStateEntity(key, value))
    }

    override fun observeSyncValue(key: String): Flow<String?> =
        syncStateDao.observe(key).map { it?.value }

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
