package com.septaalfauzan.wanci.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "monitored_apps",
    indices = [Index(value = ["packageName"], unique = true)],
)
data class MonitoredAppEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val dailyLimitMinutes: Int,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)
