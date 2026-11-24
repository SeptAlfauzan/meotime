package com.septaalfauzan.meotime.domain.model

/**
 * A monitored app as the dashboard and the settings screen read it.
 *
 * [todayUsageSeconds] is seconds for *today* and is filled in by the observed read path, which is
 * keyed on the current date, so it drops back to 0 by itself at midnight. [installed] is a
 * read-time fact about the device, not stored state: configuration survives an uninstall (the user
 * may reinstall the app and keep the limit), so the dashboard probes [com.septaalfauzan.meotime.domain.InstalledAppCheck]
 * and labels the row instead of dropping it.
 */
data class MonitoredApp(
    val id: Long,
    val packageName: String,
    val appName: String,
    val dailyLimitMinutes: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val todayUsageSeconds: Long = 0,
    val installed: Boolean = true,
    /** True when this app follows its [weeklyLimits] schedule instead of the daily limit. */
    val usesWeeklyLimits: Boolean = false,
    /**
     * The per-weekday limits. Only read when [usesWeeklyLimits] is true; [WeeklyLimits] is the
     * schedule type, so the default is the all-`null` schedule.
     */
    val weeklyLimits: WeeklyLimits = WeeklyLimits(),
)
