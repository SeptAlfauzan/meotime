package com.septaalfauzan.meotime.presentation.dashboard

import com.septaalfauzan.meotime.domain.model.DailyUsagePoint
import com.septaalfauzan.meotime.domain.model.HourlyUsagePoint
import com.septaalfauzan.meotime.domain.model.MonitoredApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

private fun app(packageName: String, name: String, limit: Int = 60) = MonitoredApp(
    id = packageName.hashCode().toLong(),
    packageName = packageName,
    appName = name,
    dailyLimitMinutes = limit,
    enabled = true,
    createdAt = 0,
    updatedAt = 0,
    todayUsageSeconds = 0,
)

class ScreenTimeStatsBuilderTest {

    private val today = LocalDate(2026, 11, 5)
    private val builder = ScreenTimeStatsBuilder()

    @Test
    fun singleDayBuildsSixColumnsTopThreeAndOthers() {
        val apps = listOf(
            app("a", "Alpha"),
            app("b", "Bravo"),
            app("c", "Charlie"),
            app("d", "Delta"),
        )
        val current = listOf(
            DailyUsagePoint("a", today, 3_600L),
            DailyUsagePoint("b", today, 1_800L),
            DailyUsagePoint("c", today, 1_200L),
            DailyUsagePoint("d", today, 600L),
        )
        val hourly = listOf(
            HourlyUsagePoint("a", today, 0, 100L),
            HourlyUsagePoint("b", today, 4, 200L),
            HourlyUsagePoint("c", today, 8, 300L),
            HourlyUsagePoint("d", today, 20, 400L),
        )

        val stats = builder.build(
            filter = StatsFilter.Today,
            today = today,
            apps = apps,
            current = current,
            previous = listOf(DailyUsagePoint("a", LocalDate(2026, 11, 4), 3_600L)),
            hourly = hourly,
        )

        assertEquals("Total today", stats.eyebrow)
        assertEquals("Thu, Nov 5", stats.periodLabel)
        assertEquals(7_200L, stats.totalSeconds)
        assertEquals(7_200L, stats.primarySeconds)
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Others"), stats.slices.map { it.label })
        assertEquals(ScreenTimeTokens.chartFirst, stats.slices[0].color)
        assertEquals(ScreenTimeTokens.chartOthers, stats.slices[3].color)
        assertEquals(600L, stats.slices[3].usageSeconds)
        assertEquals(6, stats.columns.size)
        assertEquals(listOf(100L, 0L, 0L, 0L), stats.columns[0].segments.map { it.seconds })
        assertEquals(400L, stats.columns[5].segments[3].seconds)
        assertEquals(3_600L, stats.appUsage["a"]?.totalSeconds)
    }

    @Test
    fun threeOrFewerAppsDoNotGetAnOthersSlice() {
        val apps = listOf(app("a", "Alpha"), app("b", "Bravo"))
        val stats = builder.build(
            filter = StatsFilter.Today,
            today = today,
            apps = apps,
            current = listOf(DailyUsagePoint("a", today, 60L)),
            previous = emptyList(),
            hourly = emptyList(),
        )

        assertEquals(listOf("Alpha", "Bravo"), stats.slices.map { it.label })
        assertEquals(0L, stats.slices[1].usageSeconds)
    }

    @Test
    fun rangeUsesDailyAverageAndEqualLengthPriorPeriod() {
        val apps = listOf(app("a", "Alpha"), app("b", "Bravo"))
        val start = LocalDate(2026, 11, 1)
        val end = LocalDate(2026, 11, 2)
        val current = listOf(
            DailyUsagePoint("a", start, 100L),
            DailyUsagePoint("b", start, 50L),
            DailyUsagePoint("a", end, 200L),
            DailyUsagePoint("b", end, 50L),
        )
        val previous = listOf(
            DailyUsagePoint("a", LocalDate(2026, 10, 30), 100L),
            DailyUsagePoint("b", LocalDate(2026, 10, 31), 200L),
        )

        val stats = builder.build(
            filter = StatsFilter.DateRange(start, end),
            today = today,
            apps = apps,
            current = current,
            previous = previous,
            hourly = emptyList(),
        )

        assertEquals("Daily average", stats.eyebrow)
        assertEquals("2 days", stats.periodLabel)
        assertEquals(400L, stats.totalSeconds)
        assertEquals(200L, stats.primarySeconds)
        assertEquals(2, stats.columns.size)
        assertEquals(150L, stats.columns[0].segments.sumOf { it.seconds })
        assertEquals(250L, stats.columns[1].segments.sumOf { it.seconds })
        assertEquals(50L, stats.appUsage["b"]?.dailyAverageSeconds)
        assertEquals(33.333333333333336, stats.comparisonPercent!!, absoluteTolerance = 0.0001)
    }

    @Test
    fun zeroPriorPeriodProducesNoInventedPercentage() {
        val stats = builder.build(
            filter = StatsFilter.SingleDay(LocalDate(2026, 11, 4)),
            today = today,
            apps = listOf(app("a", "Alpha")),
            current = listOf(DailyUsagePoint("a", LocalDate(2026, 11, 4), 60L)),
            previous = emptyList(),
            hourly = emptyList(),
        )

        assertEquals("vs prior day", stats.comparisonLabel)
        assertNull(stats.comparisonPercent)
        assertEquals(ComparisonDirection.FLAT, stats.comparisonDirection)
    }
}
