package com.septaalfauzan.wanci.data.local.entity

import androidx.room.Entity

/**
 * One app's recorded screen time on one date, in **seconds**.
 *
 * Seconds rather than minutes because usage is now written exactly once, when a session closes,
 * and a session can be shorter than a minute — forty sub-minute check-ins used to round to zero
 * and a limit built on them never tripped. The `dailyLimitMinutes` on a monitored app stays in
 * minutes; that is the unit the user authors a limit in, and it is converted at the one place
 * that compares the two.
 */
@Entity(tableName = "daily_usage", primaryKeys = ["packageName", "date"])
data class DailyUsageEntity(
    val packageName: String,
    val date: String,
    val usageSeconds: Long,
    val updatedAt: Long,
)
