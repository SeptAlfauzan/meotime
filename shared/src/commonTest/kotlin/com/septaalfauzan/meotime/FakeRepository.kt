package com.septaalfauzan.meotime

import com.septaalfauzan.meotime.data.repository.ScreenTimeRepository
import com.septaalfauzan.meotime.domain.limit.DEFAULT_LIMIT_MINUTES
import com.septaalfauzan.meotime.domain.model.DailyUsagePoint
import com.septaalfauzan.meotime.domain.model.HourlyUsagePoint
import com.septaalfauzan.meotime.domain.model.MonitoredApp
import com.septaalfauzan.meotime.domain.model.WeeklyLimits
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory stand-in for [com.septaalfauzan.meotime.data.repository.ScreenTimeRepositoryImpl].
 *
 * Task 10 widened the two observed read paths so the fake honours the same contract the real
 * repository does: the monitored-app list already carries *today's* usage joined in (the real
 * implementation combines the app and `daily_usage` queries), and the usage flow is reactive
 * rather than a one-shot snapshot. Before that, `observeMonitoredApps` handed out rows whose
 * `todayUsageSeconds` was always the default `0`, so any test of a usage-reading screen would
 * have been asserting against a fake that could never produce usage.
 */
data class RecordedSession(
    val packageName: String,
    val deltaSeconds: Long,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
)

class FakeRepository : ScreenTimeRepository {
    val apps = MutableStateFlow<List<MonitoredApp>>(emptyList())
    val usage = MutableStateFlow<Map<String, Long>>(emptyMap())
    val recorded = mutableListOf<Triple<String, Long, String>>()
    val sessions = mutableListOf<RecordedSession>()
    val dailyUsage = MutableStateFlow<Map<LocalDate, Map<String, Long>>>(emptyMap())
    val hourlyUsage = MutableStateFlow<Map<LocalDate, Map<Int, Map<String, Long>>>>(emptyMap())
    var syncValues = mutableMapOf<String, String>()

    private fun app(packageName: String): MonitoredApp? = apps.value.find { it.packageName == packageName }

    /** The free-row shape the real repository reads back: every weekday carries [limit]. */
    private fun dailySchedule(limit: Int) = WeeklyLimits(
        monday = limit,
        tuesday = limit,
        wednesday = limit,
        thursday = limit,
        friday = limit,
        saturday = limit,
        sunday = limit,
    )

    /**
     * Stands in for midnight: the real repository re-queries `daily_usage` for the new date when
     * `TodayKey.dateFlow()` emits (asserted in `ScreenTimeRepositoryImplTest`), which surfaces here
     * as today's usage rows emptying. Callers that need the rollover to reach their own collectors
     * must already be collecting the observed flows.
     */
    fun rollOverToNewDay() {
        usage.value = emptyMap()
    }

    /**
     * The three setters above are `suspend`, so a test that builds its fixtures outside a
     * coroutine — a `repository()` helper, or a `@Before` — has no way to call them. These are the
     * same mutations with the suspension dropped, so a fixture reads the same either way.
     */
    fun addMonitoredAppBlocking(packageName: String, appName: String, dailyLimitMinutes: Int) {
        apps.update {
            it + MonitoredApp(
                id = it.size.toLong(),
                packageName = packageName,
                appName = appName,
                dailyLimitMinutes = dailyLimitMinutes,
                usesWeeklyLimits = false,
                weeklyLimits = dailySchedule(dailyLimitMinutes),
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
            )
        }
    }

    /**
     * The weekly counterpart of [addMonitoredAppBlocking]: marker `true` and the schedule on the
     * stored row, with the legacy [MonitoredApp.dailyLimitMinutes] left at the default-limit value
     * the real repository writes.
     */
    fun addMonitoredAppWithWeeklyLimitsBlocking(
        packageName: String,
        appName: String,
        weeklyLimits: WeeklyLimits,
    ) {
        apps.update {
            it + MonitoredApp(
                id = it.size.toLong(),
                packageName = packageName,
                appName = appName,
                dailyLimitMinutes = DEFAULT_LIMIT_MINUTES,
                usesWeeklyLimits = true,
                weeklyLimits = weeklyLimits,
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
            )
        }
    }

    fun setMonitoringEnabledBlocking(packageName: String, enabled: Boolean) {
        apps.update { list -> list.map { if (it.packageName == packageName) it.copy(enabled = enabled) else it } }
    }

    fun updateDailyLimitBlocking(packageName: String, dailyLimitMinutes: Int) {
        apps.update { list ->
            list.map {
                if (it.packageName == packageName) it.copy(
                    dailyLimitMinutes = dailyLimitMinutes,
                    usesWeeklyLimits = false,
                    weeklyLimits = dailySchedule(dailyLimitMinutes),
                ) else it
            }
        }
    }

    /** The weekly counterpart of [updateDailyLimitBlocking]: marker `true`, schedule set. */
    fun updateWeeklyLimitsBlocking(packageName: String, weeklyLimits: WeeklyLimits) {
        apps.update { list ->
            list.map {
                if (it.packageName == packageName) it.copy(usesWeeklyLimits = true, weeklyLimits = weeklyLimits) else it
            }
        }
    }

    override fun observeMonitoredApps(): Flow<List<MonitoredApp>> =
        combine(apps, usage) { monitored, usageByPackage ->
            monitored.map { it.copy(todayUsageSeconds = usageByPackage[it.packageName] ?: 0L) }
        }

    override fun observeTodayUsageSeconds(packageName: String): Flow<Long> =
        usage.map { it[packageName] ?: 0L }

    override suspend fun getMonitoredApp(packageName: String): MonitoredApp? = app(packageName)
    override suspend fun getTodayUsageSeconds(packageName: String, date: String): Long = usage.value[packageName] ?: 0L
    override suspend fun addMonitoredApp(packageName: String, appName: String, dailyLimitMinutes: Int): Boolean {
        if (app(packageName) != null) return false
        addMonitoredAppBlocking(packageName, appName, dailyLimitMinutes)
        return true
    }
    override suspend fun addMonitoredAppWithWeeklyLimits(
        packageName: String,
        appName: String,
        weeklyLimits: WeeklyLimits,
    ): Boolean {
        if (app(packageName) != null) return false
        addMonitoredAppWithWeeklyLimitsBlocking(packageName, appName, weeklyLimits)
        return true
    }
    override suspend fun updateDailyLimit(packageName: String, dailyLimitMinutes: Int) {
        updateDailyLimitBlocking(packageName, dailyLimitMinutes)
    }
    override suspend fun updateWeeklyLimits(packageName: String, weeklyLimits: WeeklyLimits) {
        updateWeeklyLimitsBlocking(packageName, weeklyLimits)
    }
    override suspend fun setMonitoringEnabled(packageName: String, enabled: Boolean) {
        apps.update { list ->
            list.map {
                if (it.packageName == packageName) it.copy(enabled = enabled) else it
            }
        }
    }
    override suspend fun removeMonitoredApp(packageName: String) {
        apps.update { list -> list.filterNot { it.packageName == packageName } }
    }
    /**
     * Makes [recordUsageSeconds] throw, so a test can prove that a failed write costs seconds
     * rather than costing the *next* app its session. The real failure here is a Room error;
     * what matters downstream is only that something was thrown from inside the core's close path.
     */
    var failOnRecord = false

    override suspend fun recordUsageSeconds(packageName: String, deltaSeconds: Long, date: String) {
        if (failOnRecord) throw IllegalStateException("simulated Room failure")
        recorded += Triple(packageName, deltaSeconds, date)
        usage.update { it + (packageName to ((it[packageName] ?: 0L) + deltaSeconds)) }
        dailyUsage.update { byDate ->
            val parsed = LocalDate.parse(date)
            val byPackage = byDate[parsed].orEmpty()
            byDate + (parsed to (byPackage + (packageName to ((byPackage[packageName] ?: 0L) + deltaSeconds))))
        }
    }

    override suspend fun recordUsageSession(
        packageName: String,
        deltaSeconds: Long,
        startedAtEpochMillis: Long,
        endedAtEpochMillis: Long,
    ) {
        if (failOnRecord) throw IllegalStateException("simulated Room failure")
        sessions += RecordedSession(packageName, deltaSeconds, startedAtEpochMillis, endedAtEpochMillis)
        usage.update { it + (packageName to ((it[packageName] ?: 0L) + deltaSeconds)) }
    }

    override fun observeDailyUsage(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Flow<List<DailyUsagePoint>> = dailyUsage.map { byDate ->
        byDate
            .filterKeys { it >= startDate && it <= endDate }
            .flatMap { (date, byPackage) ->
                byPackage.map { (packageName, seconds) -> DailyUsagePoint(packageName, date, seconds) }
            }
    }

    override fun observeHourlyUsage(date: LocalDate): Flow<List<HourlyUsagePoint>> = hourlyUsage.map { byDate ->
        byDate[date].orEmpty().flatMap { (hour, byPackage) ->
            byPackage.map { (packageName, seconds) -> HourlyUsagePoint(packageName, date, hour, seconds) }
        }
    }

    override suspend fun getSyncValue(key: String): String? = syncValues[key]
    override fun observeSyncValue(key: String): Flow<String?> = flowOf(syncValues[key])
    /**
     * Makes [putSyncValue] throw, so a test can prove a failed *flag* write is reported rather than
     * swallowed. The real failure is a Room upsert on `sync_values`; what the caller has to survive
     * is not a corrupt row but a screen the user cannot leave, since onboarding only ends once this
     * key is on disk.
     */
    var failOnPutSync = false

    override suspend fun putSyncValue(key: String, value: String) {
        if (failOnPutSync) throw IllegalStateException("simulated Room failure")
        syncValues[key] = value
    }
    override suspend fun deleteUsageBefore(date: String) {}
}
