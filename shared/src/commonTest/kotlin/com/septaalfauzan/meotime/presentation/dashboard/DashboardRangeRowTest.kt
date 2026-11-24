package com.septaalfauzan.meotime.presentation.dashboard

import com.septaalfauzan.meotime.domain.model.MonitoredApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun monitoredApp(limitMinutes: Int = 30, enabled: Boolean = true) = MonitoredApp(
    id = 1,
    packageName = "com.instagram.android",
    appName = "Instagram",
    dailyLimitMinutes = limitMinutes,
    enabled = enabled,
    createdAt = 0,
    updatedAt = 0,
)

class DashboardRangeRowTest {

    @Test
    fun averageAgainstTheLimitDrivesTheBarAndCopy() {
        val row = dashboardRangeRow(
            monitoredApp(limitMinutes = 30),
            AppUsageSummary(totalSeconds = 6_000L, dailyAverageSeconds = 1_200L),
        )

        assertEquals(1_200f / 1_800f, row.progress, absoluteTolerance = 0.0001f)
        assertEquals("20m per day on average · limit 30 min", row.status)
    }

    @Test
    fun pausedAppKeepsAnExplicitMarkerAndAnEmptyBar() {
        val row = dashboardRangeRow(
            monitoredApp(limitMinutes = 30, enabled = false),
            AppUsageSummary(totalSeconds = 6_000L, dailyAverageSeconds = 1_200L),
        )

        assertEquals(0f, row.progress)
        assertTrue(row.status.startsWith("Monitoring paused · "))
        assertTrue(row.status.endsWith("per day on average · limit 30 min"))
    }

    @Test
    fun nonPositiveLimitDoesNotProduceNaN() {
        val row = dashboardRangeRow(
            monitoredApp(limitMinutes = 0),
            AppUsageSummary(totalSeconds = 100L, dailyAverageSeconds = 100L),
        )

        assertEquals(1f, row.progress)
        assertFalse(row.progress.isNaN())
    }

    /**
     * A weekly app's range row is about the week: it states the average and nothing about a daily
     * budget — the daily-limit copy and its progress arithmetic are the Free row's, not this one's.
     */
    @Test
    fun weeklyRangeOmitsTheDailyLimit() {
        val row = dashboardRangeRow(
            monitoredApp().copy(usesWeeklyLimits = true),
            AppUsageSummary(totalSeconds = 6_000L, dailyAverageSeconds = 1_200L),
        )

        assertEquals("20m per day on average", row.status)
        assertEquals(0f, row.progress)
    }
}
