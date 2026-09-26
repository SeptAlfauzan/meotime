package com.septaalfauzan.wanci.monitoring

import com.septaalfauzan.wanci.data.local.TodayKey
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitoringCore(
    private val repository: ScreenTimeRepository,
    private val todayKey: TodayKey,
    private val selfPackageName: String,
    private val scope: CoroutineScope,
    private val onOverlayDecision: (OverlayDecision) -> Unit,
) {
    data class OverlayDecision(
        val packageName: String,
        val appName: String,
        val usageMinutes: Long,
        val limitMinutes: Int,
    )

    private var lastPackage: String? = null
    private var lastEventTime: Long = 0L
    private val triggeredByDate = mutableMapOf<String, MutableSet<String>>()
    internal val activeTimers = mutableMapOf<String, Job>()

    suspend fun checkCurrentApp(packageName: String?): OverlayDecision? {
        if (packageName == null || packageName == selfPackageName) return null
        val app = repository.getMonitoredApp(packageName)
        if (app == null || !app.enabled) return null
        val date = todayKey.todayIso()
        val usage = repository.getTodayUsage(packageName, date)
        if (!LimitChecker.exceeds(usage, app.dailyLimitMinutes)) return null
        val triggered = triggeredByDate.getOrPut(date) { mutableSetOf() }
        if (packageName in triggered) return null
        triggered += packageName
        return OverlayDecision(
            packageName = packageName,
            appName = app.appName,
            usageMinutes = usage,
            limitMinutes = app.dailyLimitMinutes,
        )
    }

    suspend fun startUsageTimer(packageName: String) {
        if (packageName in activeTimers) return
        val decision = checkCurrentApp(packageName)
        if (decision != null) {
            onOverlayDecision(decision)
            return
        }
        val app = repository.getMonitoredApp(packageName)
        if (app == null || !app.enabled) return
        if (packageName == selfPackageName) return
        val job = scope.launch {
            while (isActive) {
                delay(5_000L)
                val usage = repository.getTodayUsage(packageName, todayKey.todayIso())
                if (LimitChecker.exceeds(usage, app.dailyLimitMinutes)) {
                    val triggered = triggeredByDate.getOrPut(todayKey.todayIso()) { mutableSetOf() }
                    if (packageName !in triggered) {
                        triggered += packageName
                        onOverlayDecision(
                            OverlayDecision(
                                packageName = packageName,
                                appName = app.appName,
                                usageMinutes = usage,
                                limitMinutes = app.dailyLimitMinutes,
                            )
                        )
                    }
                    activeTimers.remove(packageName)
                    return@launch
                }
            }
            activeTimers.remove(packageName)
        }
        activeTimers[packageName] = job
    }

    fun stopUsageTimer(packageName: String) {
        activeTimers[packageName]?.cancel()
        activeTimers.remove(packageName)
    }

    suspend fun onForegroundChange(packageName: String?, eventTimeMillis: Long): OverlayDecision? {
        val previousPackage = lastPackage
        val previousTime = lastEventTime

        lastPackage = packageName
        if (packageName != null) lastEventTime = eventTimeMillis

        if (previousPackage != null && previousPackage != packageName) {
            stopUsageTimer(previousPackage)
        }
        if (packageName != null) {
            startUsageTimer(packageName)
        }

        if (previousPackage == null || previousPackage == packageName) return null
        if (previousPackage == selfPackageName) return null

        val elapsedMillis = (eventTimeMillis - previousTime).coerceAtLeast(0L)
        val deltaMinutes = elapsedMillis / 60_000L

        val app = repository.getMonitoredApp(previousPackage)
        if (app == null || !app.enabled) return null

        val date = todayKey.todayIso()
        if (deltaMinutes >= 1) {
            repository.recordUsage(previousPackage, deltaMinutes, date)
        }

        val usage = repository.getTodayUsage(previousPackage, date)
        if (!LimitChecker.exceeds(usage, app.dailyLimitMinutes)) return null

        val triggered = triggeredByDate.getOrPut(date) { mutableSetOf() }
        if (previousPackage in triggered) return null
        triggered += previousPackage
        return OverlayDecision(
            packageName = previousPackage,
            appName = app.appName,
            usageMinutes = usage,
            limitMinutes = app.dailyLimitMinutes,
        )
    }

    fun onOverlayDismissed(packageName: String) {
        val date = todayKey.todayIso()
        triggeredByDate[date]?.remove(packageName)
    }

    fun resetDay() {
        val today = todayKey.todayIso()
        triggeredByDate.keys
            .filter { it != today }
            .forEach { triggeredByDate.remove(it) }
    }
}
