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
 * `todayUsageMinutes` was always the default `0`, so any test of a usage-reading screen would
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
            monitored.map { it.copy(todayUsageMinutes = usageByPackage[it.packageName] ?: 0L) }
        }

    override fun observeTodayUsage(packageName: String): Flow<Long> =
        usage.map { it[packageName] ?: 0L }

    override suspend fun getMonitoredApp(packageName: String): MonitoredApp? = app(packageName)
    override suspend fun getTodayUsage(packageName: String, date: String): Long = usage.value[packageName] ?: 0L
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
    override suspend fun recordUsage(packageName: String, deltaMinutes: Long, date: String) {
        recorded += Triple(packageName, deltaMinutes, date)
        usage.update { it + (packageName to ((it[packageName] ?: 0L) + deltaMinutes)) }
    }
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
}
