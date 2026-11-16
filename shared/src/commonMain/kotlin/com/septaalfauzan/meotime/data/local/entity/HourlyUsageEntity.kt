package com.septaalfauzan.meotime.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One app's recorded screen time in one local hour, in seconds.
 *
 * The hour is local civil time: the same hour number can occur twice on a daylight-saving
 * fall-back day. Those two physical hours intentionally aggregate into the same row because the
 * chart has one bar per civil hour, not per offset.
 *
 * The `date` index serves the date-scoped reads; the primary key starts with `packageName`, so it
 * cannot serve a query that filters only on `date`.
 */
@Entity(
    tableName = "hourly_usage",
    primaryKeys = ["packageName", "date", "hour"],
    indices = [Index(value = ["date"])],
)
data class HourlyUsageEntity(
    val packageName: String,
    val date: String,
    val hour: Int,
    val usageSeconds: Long,
    val updatedAt: Long,
)
