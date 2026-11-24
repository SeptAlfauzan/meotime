package com.septaalfauzan.meotime.presentation.dashboard

import com.septaalfauzan.meotime.domain.model.MonitoredApp
import com.septaalfauzan.meotime.domain.model.WeeklyLimits
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Monday of the week the fixtures speak in: a weekly app resolves its limit by the weekday
 * [dashboardRow] is handed, so the fixture must name a Monday for `WeeklyLimits(monday = ...)` to
 * be the value that answers.
 */
private val monday = LocalDate(2026, 11, 16)

private fun weeklyApp(usageMinutes: Long, weeklyLimits: WeeklyLimits) = MonitoredApp(
    id = 1,
    packageName = "com.instagram.android",
    appName = "Instagram",
    dailyLimitMinutes = 30,
    enabled = true,
    createdAt = 0,
    updatedAt = 0,
    todayUsageSeconds = usageMinutes * 60,
    usesWeeklyLimits = true,
    weeklyLimits = weeklyLimits,
)

class DashboardRowWeeklyTest {

    @Test
    fun weeklyAppWithALimitOnThatDayUsesTheWeeklyValue() {
        val row = dashboardRow(weeklyApp(usageMinutes = 30, weeklyLimits = WeeklyLimits(monday = 30)), monday)

        assertFalse(row.noLimitToday)
        assertTrue(row.limitReached)
        assertEquals(0L, row.remainingMinutes)
        assertEquals(1f, row.progress)
    }

    @Test
    fun weeklyAppWithAnAllNullScheduleIsANoLimitDay() {
        val row = dashboardRow(weeklyApp(usageMinutes = 0, weeklyLimits = WeeklyLimits()), monday)

        assertTrue(row.noLimitToday)
        assertFalse(row.limitReached)
        assertEquals(0f, row.progress)
        assertEquals(0L, row.remainingMinutes)
        assertEquals("No limit today", appStatusLabel(row))
    }

    @Test
    fun dailyAppKeepsTheValuesTheOneArgumentRowGave() {
        val app = weeklyApp(usageMinutes = 30, weeklyLimits = WeeklyLimits()).copy(usesWeeklyLimits = false)

        val row = dashboardRow(app, monday)

        assertEquals(
            DashboardAppRow(limitReached = true, remainingMinutes = 0L, progress = 1f, paused = false, noLimitToday = false),
            row,
        )
    }
}
