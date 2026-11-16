package com.septaalfauzan.meotime.presentation.format

import com.septaalfauzan.meotime.presentation.dashboard.ResolvedPeriod
import com.septaalfauzan.meotime.presentation.dashboard.StatsFilter
import kotlin.math.roundToInt
import kotlinx.datetime.LocalDate

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun formatStatDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    if (total < 60L) return "$total sec"
    val minutes = total / 60L
    val hours = minutes / 60L
    val restMinutes = minutes % 60L
    return when {
        hours == 0L -> "${minutes}m"
        restMinutes == 0L -> "${hours}h"
        else -> "${hours}h ${restMinutes}m"
    }
}

fun formatComparisonPercent(percent: Double?): String {
    if (percent == null) return "—"
    val rounded = (percent * 10.0).roundToInt() / 10.0
    val body = if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
    return if (rounded > 0.0) "+$body%" else "$body%"
}

fun formatPeriodButton(filter: StatsFilter, today: LocalDate): String = when (filter) {
    StatsFilter.Today -> "Today"
    is StatsFilter.SingleDay -> formatMonthDay(filter.date)
    is StatsFilter.DateRange -> formatRangeButton(filter.start, filter.end)
}

fun formatPeriodSubtitle(filter: StatsFilter, today: LocalDate): String = when (filter) {
    StatsFilter.Today -> "Today"
    is StatsFilter.SingleDay -> formatMonthDayYear(filter.date)
    is StatsFilter.DateRange -> formatRangeWithYear(filter.start, filter.end)
}

fun formatPeriodSummary(filter: StatsFilter, today: LocalDate): String = when (filter) {
    StatsFilter.Today -> formatWeekdayMonthDay(today)
    is StatsFilter.SingleDay -> formatWeekdayMonthDay(filter.date)
    is StatsFilter.DateRange -> {
        val start = minOf(filter.start, filter.end)
        val end = maxOf(filter.start, filter.end)
        val period = ResolvedPeriod(start, end)
        "${formatRangeLong(start, end)} · ${period.days} days"
    }
}

fun formatCardPeriodLabel(filter: StatsFilter, period: ResolvedPeriod): String = when (filter) {
    StatsFilter.Today -> formatWeekdayMonthDay(period.start)
    is StatsFilter.SingleDay -> formatWeekdayMonthDay(period.start)
    is StatsFilter.DateRange -> "${period.days} days"
}

fun formatWeekdayInitial(date: LocalDate): String =
    WEEKDAYS[date.dayOfWeek.ordinal].take(1)

private fun formatMonthDay(date: LocalDate): String =
    "${MONTHS[date.month.ordinal]} ${date.dayOfMonth}"

private fun formatMonthDayYear(date: LocalDate): String =
    "${formatMonthDay(date)}, ${date.year}"

private fun formatWeekdayMonthDay(date: LocalDate): String =
    "${WEEKDAYS[date.dayOfWeek.ordinal]}, ${formatMonthDay(date)}"

private fun formatRangeButton(start: LocalDate, end: LocalDate): String =
    when {
        start.year != end.year -> "${formatMonthDayYear(start)} – ${formatMonthDayYear(end)}"
        start.month == end.month -> "${formatMonthDay(start)} – ${end.dayOfMonth}"
        else -> "${formatMonthDay(start)} – ${formatMonthDay(end)}"
    }

private fun formatRangeWithYear(start: LocalDate, end: LocalDate): String =
    if (start.year == end.year) {
        "${formatMonthDay(start)} – ${formatMonthDay(end)}, ${start.year}"
    } else {
        "${formatMonthDayYear(start)} – ${formatMonthDayYear(end)}"
    }

private fun formatRangeLong(start: LocalDate, end: LocalDate): String =
    if (start.year == end.year) {
        "${formatMonthDay(start)} – ${formatMonthDay(end)}"
    } else {
        "${formatMonthDayYear(start)} – ${formatMonthDayYear(end)}"
    }
