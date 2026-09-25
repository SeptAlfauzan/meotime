package com.septaalfauzan.wanci.domain.useCase

import com.septaalfauzan.wanci.data.local.TodayKey
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepository
import com.septaalfauzan.wanci.domain.model.MonitoredApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WanciUseCases(
    private val repository: ScreenTimeRepository,
    private val todayKey: TodayKey,
) {
    fun observeMonitoredApps(): Flow<List<MonitoredApp>> = repository.observeMonitoredApps()

    fun observeOnboardingComplete(): Flow<Boolean> =
        repository.observeSyncValue("onboardingComplete").map { it == "true" }

    suspend fun addMonitoredApp(
        packageName: String,
        appName: String,
        dailyLimitMinutes: Int,
    ): Boolean = repository.addMonitoredApp(packageName, appName, dailyLimitMinutes)

    suspend fun updateDailyLimit(packageName: String, dailyLimitMinutes: Int) =
        repository.updateDailyLimit(packageName, dailyLimitMinutes)

    suspend fun setMonitoringEnabled(packageName: String, enabled: Boolean) =
        repository.setMonitoringEnabled(packageName, enabled)

    suspend fun removeMonitoredApp(packageName: String) =
        repository.removeMonitoredApp(packageName)

    suspend fun getMonitoredApp(packageName: String): MonitoredApp? =
        repository.getMonitoredApp(packageName)

    suspend fun getTodayUsage(packageName: String): Long =
        repository.getTodayUsage(packageName, todayKey.todayIso())

    suspend fun putSyncValue(key: String, value: String) =
        repository.putSyncValue(key, value)
}
