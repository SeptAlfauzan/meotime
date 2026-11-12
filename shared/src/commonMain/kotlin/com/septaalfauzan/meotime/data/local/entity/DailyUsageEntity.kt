package com.septaalfauzan.meotime.data.local.entity

import androidx.room.Entity

/**
 * One app's recorded screen time on one date, in **seconds**.
 *
 * Seconds rather than minutes so that a recorded session is not rounded to the nearest minute on
 * the way in: `MonitoringCore` still discards sub-minute deltas and will keep doing so until the
 * unit that rewrites it, but once that gate is lifted the write it produces lands whole instead of
 * rounding away to zero, and a limit built out of those sessions can trip.
 *
 * The `dailyLimitMinutes` on a monitored app stays in minutes; that is the unit the user authors a
 * limit in, and it is converted at the one place that compares the two.
 */
@Entity(tableName = "daily_usage", primaryKeys = ["packageName", "date"])
data class DailyUsageEntity(
    val packageName: String,
    val date: String,
    val usageSeconds: Long,
    val updatedAt: Long,
)
