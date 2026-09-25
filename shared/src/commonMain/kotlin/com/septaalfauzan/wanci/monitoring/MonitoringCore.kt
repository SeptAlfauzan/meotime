package com.septaalfauzan.wanci.monitoring

import com.septaalfauzan.wanci.data.local.TodayKey
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepository

class MonitoringCore(
    private val repository: ScreenTimeRepository,
    private val todayKey: TodayKey,
    private val selfPackageName: String,
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

    suspend fun onForegroundChange(packageName: String?, eventTimeMillis: Long): OverlayDecision? {
        val previousPackage = lastPackage
        val previousTime = lastEventTime

        lastPackage = packageName
        if (packageName != null) lastEventTime = eventTimeMillis

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
