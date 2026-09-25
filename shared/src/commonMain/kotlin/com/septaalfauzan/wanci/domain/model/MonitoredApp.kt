package com.septaalfauzan.wanci.domain.model

data class MonitoredApp(
    val id: Long,
    val packageName: String,
    val appName: String,
    val dailyLimitMinutes: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val todayUsageMinutes: Long = 0,
)
