package com.septaalfauzan.wanci.data.local.entity

import androidx.room.Entity

@Entity(tableName = "daily_usage", primaryKeys = ["packageName", "date"])
data class DailyUsageEntity(
    val packageName: String,
    val date: String,
    val usageMinutes: Long,
    val updatedAt: Long,
)
