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
                appEntities.map { it.toDomain(usageByPackage[it.packageName]?.usageSeconds ?: 0L) }
            }
        }
    }

    override fun observeTodayUsageSeconds(packageName: String): Flow<Long> =
        todayKey.dateFlow().flatMapLatest { date ->
            dailyUsageDao.observeForDate(date).map { usageEntities ->
                usageEntities.firstOrNull { it.packageName == packageName }?.usageSeconds ?: 0L
            }
        }

    override suspend fun getMonitoredApp(packageName: String): MonitoredApp? =
        monitoredAppDao.getByPackage(packageName)?.toDomain(0L)

    override suspend fun getTodayUsageSeconds(packageName: String, date: String): Long =
        dailyUsageDao.getUsageSeconds(packageName, date) ?: 0L

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

    override suspend fun recordUsageSeconds(packageName: String, deltaSeconds: Long, date: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (dailyUsageDao.insertOrIgnore(DailyUsageEntity(packageName, date, deltaSeconds, now)) == -1L) {
            dailyUsageDao.addDeltaSeconds(packageName, date, deltaSeconds, now)
        }
    }

    /**
     * Still in **minutes**, unlike every other write here.
     *
     * That is deliberate and temporary: the only caller is `UsageStatsReader`, which reports whole
     * minutes, and it is not touched until the unit that deletes this method. Converting at the
     * boundary is what stops a second-denominated number from reaching a minute-denominated
     * caller — or being read as minutes by anything in between.
     *
     * The merge is insert, else raise, never sum, and it is unchanged: a platform total is an
     * absolute figure, so a total already above it must survive, and a session written by
     * [recordUsageSeconds] on another coroutine must not be rolled back to it. Both halves are
     * single statements, and the ordering is load-bearing — see
     * [DailyUsageDao.raiseToAtLeastSeconds] for what a read-then-write costs.
     */
    override suspend fun reconcileDailyUsage(
        deltaMinutesByPackage: Map<String, Long>,
        date: String,
    ) {
        deltaMinutesByPackage.forEach { (packageName, statsMinutes) ->
            val now = Clock.System.now().toEpochMilliseconds()
            val totalSeconds = statsMinutes * 60L
            if (
                dailyUsageDao.insertOrIgnore(DailyUsageEntity(packageName, date, totalSeconds, now)) == -1L
            ) {
                val existingSeconds = dailyUsageDao.getUsageSeconds(packageName, date) ?: 0L
                dailyUsageDao.raiseToAtLeastSeconds(
                    packageName,
                    date,
                    maxOf(existingSeconds, totalSeconds),
                    now,
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

    override suspend fun deleteUsageBefore(date: String) {
        dailyUsageDao.deleteBefore(date)
    }

    private fun MonitoredAppEntity.toDomain(todayUsageSeconds: Long) =
        MonitoredApp(
            id = id,
            packageName = packageName,
            appName = appName,
            dailyLimitMinutes = dailyLimitMinutes,
            enabled = enabled,
            createdAt = createdAt,
            updatedAt = updatedAt,
            todayUsageSeconds = todayUsageSeconds,
        )
}
