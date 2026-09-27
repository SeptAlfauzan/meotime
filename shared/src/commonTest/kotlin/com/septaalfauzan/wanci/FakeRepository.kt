package com.septaalfauzan.wanci

import com.septaalfauzan.wanci.data.repository.ScreenTimeRepository
import com.septaalfauzan.wanci.domain.model.MonitoredApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory stand-in for [com.septaalfauzan.wanci.data.repository.ScreenTimeRepositoryImpl].
 *
 * Task 10 widened the two observed read paths so the fake honours the same contract the real
 * repository does: the monitored-app list already carries *today's* usage joined in (the real
 * implementation combines the app and `daily_usage` queries), and the usage flow is reactive
 * rather than a one-shot snapshot. Before that, `observeMonitoredApps` handed out rows whose
 * `todayUsageSeconds` was always the default `0`, so any test of a usage-reading screen would
 * have been asserting against a fake that could never produce usage.
 */
class FakeRepository : ScreenTimeRepository {
    val apps = MutableStateFlow<List<MonitoredApp>>(emptyList())
    val usage = MutableStateFlow<Map<String, Long>>(emptyMap())
    val recorded = mutableListOf<Triple<String, Long, String>>()
    var syncValues = mutableMapOf<String, String>()

    private fun app(packageName: String): MonitoredApp? = apps.value.find { it.packageName == packageName }

    /**
     * Stands in for midnight: the real repository re-queries `daily_usage` for the new date when
     * `TodayKey.dateFlow()` emits (asserted in `ScreenTimeRepositoryImplTest`), which surfaces here
     * as today's usage rows emptying. Callers that need the rollover to reach their own collectors
     * must already be collecting the observed flows.
     */
    fun rollOverToNewDay() {
        usage.value = emptyMap()
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
        apps.update {
            it + MonitoredApp(
                id = it.size.toLong(),
                packageName = packageName,
                appName = appName,
                dailyLimitMinutes = dailyLimitMinutes,
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
            )
        }
        return true
    }
    override suspend fun updateDailyLimit(packageName: String, dailyLimitMinutes: Int) {
        apps.update { list ->
            list.map {
                if (it.packageName == packageName) it.copy(dailyLimitMinutes = dailyLimitMinutes) else it
            }
        }
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
    }

    /** In minutes, like the real implementation: this method is on its way out. */
    override suspend fun reconcileDailyUsage(deltaMinutesByPackage: Map<String, Long>, date: String) {
        deltaMinutesByPackage.forEach { (packageName, minutes) ->
            usage.update { it + (packageName to maxOf(it[packageName] ?: 0L, minutes)) }
        }
    }
    override suspend fun getSyncValue(key: String): String? = syncValues[key]
    override fun observeSyncValue(key: String): Flow<String?> = flowOf(syncValues[key])
    override suspend fun putSyncValue(key: String, value: String) {
        syncValues[key] = value
    }
    override suspend fun deleteUsageBefore(date: String) {}
}
