package com.septaalfauzan.wanci

import com.septaalfauzan.wanci.data.repository.ScreenTimeRepository
import com.septaalfauzan.wanci.domain.model.MonitoredApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

class FakeRepository : ScreenTimeRepository {
    val apps = MutableStateFlow<List<MonitoredApp>>(emptyList())
    val usage = MutableStateFlow<Map<String, Long>>(emptyMap())
    var syncValues = mutableMapOf<String, String>()

    private fun app(packageName: String): MonitoredApp? = apps.value.find { it.packageName == packageName }

    override fun observeMonitoredApps(): Flow<List<MonitoredApp>> = apps
    override fun observeTodayUsage(packageName: String): Flow<Long> = flowOf(usage.value[packageName] ?: 0L)
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
