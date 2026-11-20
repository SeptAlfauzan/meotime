package com.septaalfauzan.meotime.data.local.entity

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
    /** Marker: only rows written by v4 or later carry a weekly schedule. Existing rows are false. */
    val usesWeeklyLimits: Boolean = false,
    val mondayLimitMinutes: Int? = null,
    val tuesdayLimitMinutes: Int? = null,
    val wednesdayLimitMinutes: Int? = null,
    val thursdayLimitMinutes: Int? = null,
    val fridayLimitMinutes: Int? = null,
    val saturdayLimitMinutes: Int? = null,
    val sundayLimitMinutes: Int? = null,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)
