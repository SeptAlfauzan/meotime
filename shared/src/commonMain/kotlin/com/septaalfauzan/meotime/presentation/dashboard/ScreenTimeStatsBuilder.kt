package com.septaalfauzan.meotime.presentation.dashboard

import com.septaalfauzan.meotime.domain.model.DailyUsagePoint
import com.septaalfauzan.meotime.domain.model.HourlyUsagePoint
import com.septaalfauzan.meotime.domain.model.MonitoredApp
import com.septaalfauzan.meotime.presentation.format.formatCardPeriodLabel
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

internal class ScreenTimeStatsBuilder {

    fun build(
        filter: StatsFilter,
        today: LocalDate,
        apps: List<MonitoredApp>,
        current: List<DailyUsagePoint>,
        previous: List<DailyUsagePoint>,
        hourly: List<HourlyUsagePoint>,
    ): ScreenTimeStats {
        val period = resolve(filter, today)
        val monitoredPackages = apps.mapTo(linkedSetOf()) { it.packageName }
        val currentRows = current.filter {
            it.packageName in monitoredPackages && it.date >= period.start && it.date <= period.end
        }
        val previousRows = previous.filter { it.packageName in monitoredPackages }
        val currentByApp = currentRows
            .groupBy { it.packageName }
            .mapValues { (_, rows) -> rows.sumOf { it.usageSeconds } }
        val total = currentByApp.values.sum()
        val previousTotal = previousRows.sumOf { it.usageSeconds }
        val primary = if (filter is StatsFilter.DateRange) total / period.days else total
        val definitions = sliceDefinitions(apps, currentByApp)

        val columns = if (filter is StatsFilter.DateRange) {
            rangeColumns(period, definitions, currentRows)
        } else {
            singleDayColumns(period.start, definitions, hourly, monitoredPackages)
        }

        val comparisonPercent = if (previousTotal <= 0L) {
            null
        } else {
            (total - previousTotal).toDouble() / previousTotal.toDouble() * 100.0
        }
        val direction = when {
            comparisonPercent == null || total == previousTotal -> ComparisonDirection.FLAT
            total > previousTotal -> ComparisonDirection.UP
            else -> ComparisonDirection.DOWN
        }

        return ScreenTimeStats(
            filter = filter,
            period = period,
            eyebrow = when (filter) {
                StatsFilter.Today -> "Total today"
                is StatsFilter.SingleDay -> "Total"
                is StatsFilter.DateRange -> "Daily average"
            },
            periodLabel = formatCardPeriodLabel(filter, period),
            totalSeconds = total,
            primarySeconds = primary,
            comparisonLabel = when (filter) {
                StatsFilter.Today -> "vs yesterday"
                is StatsFilter.SingleDay -> "vs prior day"
                is StatsFilter.DateRange -> "vs prior period"
            },
            comparisonPercent = comparisonPercent,
            comparisonDirection = direction,
            slices = definitions.map { it.slice },
            columns = columns,
            appUsage = apps.associate { app ->
                val appTotal = currentByApp[app.packageName] ?: 0L
                app.packageName to AppUsageSummary(
                    totalSeconds = appTotal,
                    dailyAverageSeconds = appTotal / period.days,
                )
            },
        )
    }

    private fun sliceDefinitions(
        apps: List<MonitoredApp>,
        currentByApp: Map<String, Long>,
    ): List<SliceDefinition> {
        val ordered = apps.sortedByDescending { currentByApp[it.packageName] ?: 0L }
        val top = if (ordered.size <= 3) ordered else ordered.take(3)
        val remainder = if (ordered.size <= 3) emptyList() else ordered.drop(3)
        val definitions = top.mapIndexed { index, app ->
            SliceDefinition(
                packageNames = setOf(app.packageName),
                slice = ScreenTimeSlice(
                    packageName = app.packageName,
                    label = app.appName,
                    color = ScreenTimeTokens.chartColors[index],
                    usageSeconds = currentByApp[app.packageName] ?: 0L,
                ),
            )
        }.toMutableList()
        if (remainder.isNotEmpty()) {
            definitions += SliceDefinition(
                packageNames = remainder.mapTo(mutableSetOf()) { it.packageName },
                slice = ScreenTimeSlice(
                    packageName = null,
                    label = "Others",
                    color = ScreenTimeTokens.chartOthers,
                    usageSeconds = remainder.sumOf { currentByApp[it.packageName] ?: 0L },
                ),
            )
        }
        return definitions
    }

    private fun singleDayColumns(
        date: LocalDate,
        definitions: List<SliceDefinition>,
        hourly: List<HourlyUsagePoint>,
        monitoredPackages: Set<String>,
    ): List<ScreenTimeChartColumn> {
        val rows = hourly.filter { it.date == date && it.packageName in monitoredPackages }
        val byPackageAndHour = rows.associate { (it.packageName to it.hour) to it.usageSeconds }
        return (0 until 6).map { bucketIndex ->
            val startHour = bucketIndex * 4
            ScreenTimeChartColumn(
                date = date,
                segments = definitions.map { definition ->
                    val seconds = definition.packageNames.sumOf { packageName ->
                        (startHour until startHour + 4).sumOf { hour ->
                            byPackageAndHour[packageName to hour] ?: 0L
                        }
                    }
                    ScreenTimeChartSegment(definition.slice.color, seconds)
                },
            )
        }
    }

    private fun rangeColumns(
        period: ResolvedPeriod,
        definitions: List<SliceDefinition>,
        currentRows: List<DailyUsagePoint>,
    ): List<ScreenTimeChartColumn> {
        val byDateAndPackage = currentRows.associate { (it.date to it.packageName) to it.usageSeconds }
        val columns = mutableListOf<ScreenTimeChartColumn>()
        var date = period.start
        while (date <= period.end) {
            columns += ScreenTimeChartColumn(
                date = date,
                segments = definitions.map { definition ->
                    val seconds = definition.packageNames.sumOf { packageName ->
                        byDateAndPackage[date to packageName] ?: 0L
                    }
                    ScreenTimeChartSegment(definition.slice.color, seconds)
                },
            )
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return columns
    }

    private fun resolve(filter: StatsFilter, today: LocalDate): ResolvedPeriod = when (filter) {
        StatsFilter.Today -> ResolvedPeriod(today, today)
        is StatsFilter.SingleDay -> ResolvedPeriod(filter.date, filter.date)
        is StatsFilter.DateRange -> if (filter.start <= filter.end) {
            ResolvedPeriod(filter.start, filter.end)
        } else {
            ResolvedPeriod(filter.end, filter.start)
        }
    }

    private data class SliceDefinition(
        val packageNames: Set<String>,
        val slice: ScreenTimeSlice,
    )
}
