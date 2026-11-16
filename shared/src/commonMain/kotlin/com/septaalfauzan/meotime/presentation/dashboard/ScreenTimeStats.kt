package com.septaalfauzan.meotime.presentation.dashboard

import androidx.compose.ui.graphics.Color
import kotlinx.datetime.LocalDate

sealed interface StatsFilter {
    data object Today : StatsFilter
    data class SingleDay(val date: LocalDate) : StatsFilter
    data class DateRange(val start: LocalDate, val end: LocalDate) : StatsFilter
}

data class ResolvedPeriod(val start: LocalDate, val end: LocalDate) {
    val days: Int get() = (end.toEpochDays() - start.toEpochDays() + 1L).toInt()
    val isRange: Boolean get() = start != end
}

data class AppUsageSummary(
    val totalSeconds: Long,
    val dailyAverageSeconds: Long,
)

data class ScreenTimeSlice(
    val packageName: String?,
    val label: String,
    val color: Color,
    val usageSeconds: Long,
)

data class ScreenTimeChartSegment(
    val color: Color,
    val seconds: Long,
)

data class ScreenTimeChartColumn(
    val date: LocalDate,
    val segments: List<ScreenTimeChartSegment>,
)

enum class ComparisonDirection {
    UP,
    DOWN,
    FLAT,
}

data class ScreenTimeStats(
    val filter: StatsFilter,
    val period: ResolvedPeriod,
    val eyebrow: String,
    val periodLabel: String,
    val totalSeconds: Long,
    val primarySeconds: Long,
    val comparisonLabel: String,
    val comparisonPercent: Double?,
    val comparisonDirection: ComparisonDirection,
    val slices: List<ScreenTimeSlice>,
    val columns: List<ScreenTimeChartColumn>,
    val appUsage: Map<String, AppUsageSummary>,
)
