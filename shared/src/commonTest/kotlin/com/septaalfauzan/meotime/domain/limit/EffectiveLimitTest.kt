package com.septaalfauzan.meotime.domain.limit

import com.septaalfauzan.meotime.domain.model.MonitoredApp
import com.septaalfauzan.meotime.domain.model.WeeklyLimits
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EffectiveLimitTest {

    private fun baseApp() = MonitoredApp(
        id = 1L,
        packageName = "com.example.app",
        appName = "Example",
        dailyLimitMinutes = 30,
        enabled = true,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun dailyLimitAppliesWhenWeeklyLimitsAreOff() {
        val app = baseApp()
        assertEquals(30, effectiveLimitMinutes(app, LocalDate(2026, 11, 16)))
        assertEquals(30, effectiveLimitMinutes(app, LocalDate(2026, 11, 17)))
        assertEquals(30, effectiveLimitMinutes(app, LocalDate(2026, 11, 18)))
    }

    @Test
    fun weeklyLimitAppliesOnItsDay() {
        val app = baseApp().copy(
            usesWeeklyLimits = true,
            weeklyLimits = WeeklyLimits().withDay(DayOfWeek.MONDAY, 45),
        )
        assertEquals(45, effectiveLimitMinutes(app, LocalDate(2026, 11, 16)))
        assertNull(effectiveLimitMinutes(app, LocalDate(2026, 11, 17)))
    }

    @Test
    fun allNullWeeklyIsNoLimitOnAnyDay() {
        val app = baseApp().copy(usesWeeklyLimits = true, weeklyLimits = WeeklyLimits())
        listOf(
            LocalDate(2026, 11, 16),
            LocalDate(2026, 11, 17),
            LocalDate(2026, 11, 18),
            LocalDate(2026, 11, 19),
            LocalDate(2026, 11, 20),
            LocalDate(2026, 11, 21),
            LocalDate(2026, 11, 22),
        ).forEach { assertNull(effectiveLimitMinutes(app, it)) }
    }
}
