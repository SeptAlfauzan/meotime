package com.septaalfauzan.wanci.domain.model

/**
 * A monitored app as the dashboard and the settings screen read it.
 *
 * [todayUsageSeconds] is seconds for *today* and is filled in by the observed read path, which is
 * keyed on the current date, so it drops back to 0 by itself at midnight. [installed] is a
 * read-time fact about the device, not stored state: configuration survives an uninstall (the user
 * may reinstall the app and keep the limit), so the dashboard probes [com.septaalfauzan.wanci.domain.InstalledAppCheck]
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
)
