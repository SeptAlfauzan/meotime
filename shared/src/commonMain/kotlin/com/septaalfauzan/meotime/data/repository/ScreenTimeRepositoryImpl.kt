package com.septaalfauzan.meotime.data.repository

import com.septaalfauzan.meotime.data.local.DailyUsageDao
import com.septaalfauzan.meotime.data.local.HourlyUsageDao
import com.septaalfauzan.meotime.data.local.MonitoredAppDao
import com.septaalfauzan.meotime.data.local.SyncStateDao
import com.septaalfauzan.meotime.data.local.TodayKey
import com.septaalfauzan.meotime.data.local.entity.DailyUsageEntity
import com.septaalfauzan.meotime.data.local.entity.MonitoredAppEntity
import com.septaalfauzan.meotime.data.local.entity.SyncStateEntity
import com.septaalfauzan.meotime.domain.model.DailyUsagePoint
import com.septaalfauzan.meotime.domain.model.HourlyUsagePoint
import com.septaalfauzan.meotime.domain.limit.DEFAULT_LIMIT_MINUTES
import com.septaalfauzan.meotime.domain.model.MonitoredApp
import com.septaalfauzan.meotime.domain.model.WeeklyLimits
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
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
    private val hourlyUsageDao: HourlyUsageDao,
    private val syncStateDao: SyncStateDao,
    private val todayKey: TodayKey,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
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
                usesWeeklyLimits = false,
                mondayLimitMinutes = dailyLimitMinutes,
                tuesdayLimitMinutes = dailyLimitMinutes,
                wednesdayLimitMinutes = dailyLimitMinutes,
                thursdayLimitMinutes = dailyLimitMinutes,
                fridayLimitMinutes = dailyLimitMinutes,
                saturdayLimitMinutes = dailyLimitMinutes,
                sundayLimitMinutes = dailyLimitMinutes,
                createdAt = now,
                updatedAt = now,
            )
        )
        return id != -1L
    }

    override suspend fun addMonitoredAppWithWeeklyLimits(
        packageName: String,
        appName: String,
        weeklyLimits: WeeklyLimits,
    ): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        val id = monitoredAppDao.insert(
            MonitoredAppEntity(
                packageName = packageName,
                appName = appName,
                dailyLimitMinutes = DEFAULT_LIMIT_MINUTES,
                usesWeeklyLimits = true,
                mondayLimitMinutes = weeklyLimits.monday,
                tuesdayLimitMinutes = weeklyLimits.tuesday,
                wednesdayLimitMinutes = weeklyLimits.wednesday,
                thursdayLimitMinutes = weeklyLimits.thursday,
                fridayLimitMinutes = weeklyLimits.friday,
                saturdayLimitMinutes = weeklyLimits.saturday,
                sundayLimitMinutes = weeklyLimits.sunday,
                createdAt = now,
                updatedAt = now,
            )
        )
        return id != -1L
    }

    override suspend fun updateWeeklyLimits(packageName: String, weeklyLimits: WeeklyLimits) {
        monitoredAppDao.updateWeeklyLimits(
            packageName = packageName,
            monday = weeklyLimits.monday,
            tuesday = weeklyLimits.tuesday,
            wednesday = weeklyLimits.wednesday,
            thursday = weeklyLimits.thursday,
            friday = weeklyLimits.friday,
            saturday = weeklyLimits.saturday,
            sunday = weeklyLimits.sunday,
            updatedAt = Clock.System.now().toEpochMilliseconds(),
        )
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

    override suspend fun recordUsageSession(
        packageName: String,
        deltaSeconds: Long,
        startedAtEpochMillis: Long,
        endedAtEpochMillis: Long,
    ) {
        val buckets = splitSession(
            deltaSeconds = deltaSeconds,
            startedAtEpochMillis = startedAtEpochMillis,
            endedAtEpochMillis = endedAtEpochMillis,
            timeZone = timeZone,
        )
        if (buckets.isEmpty()) return
        hourlyUsageDao.recordBuckets(
            packageName = packageName,
            buckets = buckets.map { HourlyUsageDao.Bucket(it.date.toString(), it.hour, it.seconds) },
            updatedAt = Clock.System.now().toEpochMilliseconds(),
        )
    }

    override fun observeDailyUsage(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Flow<List<DailyUsagePoint>> =
        dailyUsageDao.observeForRange(startDate.toString(), endDate.toString()).map { rows ->
            rows.map { row ->
                DailyUsagePoint(
                    packageName = row.packageName,
                    date = LocalDate.parse(row.date),
                    usageSeconds = row.usageSeconds,
                )
            }
        }

    override fun observeHourlyUsage(date: LocalDate): Flow<List<HourlyUsagePoint>> =
        hourlyUsageDao.observeForDate(date.toString()).map { rows ->
            rows.map { row ->
                HourlyUsagePoint(
                    packageName = row.packageName,
                    date = date,
                    hour = row.hour,
                    usageSeconds = row.usageSeconds,
                )
            }
        }

    override suspend fun getSyncValue(key: String): String? = syncStateDao.get(key)

    override suspend fun putSyncValue(key: String, value: String) {
        syncStateDao.put(SyncStateEntity(key, value))
    }

    override fun observeSyncValue(key: String): Flow<String?> =
        syncStateDao.observe(key).map { it?.value }

    override suspend fun deleteUsageBefore(date: String) {
        hourlyUsageDao.deleteBefore(date)
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
            usesWeeklyLimits = usesWeeklyLimits,
            weeklyLimits = WeeklyLimits(
                monday = mondayLimitMinutes,
                tuesday = tuesdayLimitMinutes,
                wednesday = wednesdayLimitMinutes,
                thursday = thursdayLimitMinutes,
                friday = fridayLimitMinutes,
                saturday = saturdayLimitMinutes,
                sunday = sundayLimitMinutes,
            ),
        )
}
