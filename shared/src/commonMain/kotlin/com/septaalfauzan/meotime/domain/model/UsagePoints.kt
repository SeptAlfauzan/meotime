package com.septaalfauzan.meotime.domain.model

import kotlinx.datetime.LocalDate

data class DailyUsagePoint(
    val packageName: String,
    val date: LocalDate,
    val usageSeconds: Long,
)

data class HourlyUsagePoint(
    val packageName: String,
    val date: LocalDate,
    val hour: Int,
    val usageSeconds: Long,
)
