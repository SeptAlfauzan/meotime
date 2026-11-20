package com.septaalfauzan.meotime.domain.model

import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeeklyLimitsTest {

    @Test
    fun freshScheduleHasNoDayLimited() {
        assertNull(WeeklyLimits().forDay(DayOfWeek.WEDNESDAY))
    }

    @Test
    fun withDaySetsTheDay() {
        assertEquals(45, WeeklyLimits().withDay(DayOfWeek.WEDNESDAY, 45).forDay(DayOfWeek.WEDNESDAY))
    }

    @Test
    fun withAllLimitsEveryDayAndCountsThem() {
        val limits = WeeklyLimits().withAll(60)
        assertEquals(7, limits.limitedDays())
        DayOfWeek.entries.forEach { assertEquals(60, limits.forDay(it)) }
    }

    @Test
    fun disabledAllCountsNoLimitedDays() {
        assertEquals(0, WeeklyLimits().disabledAll().limitedDays())
    }

    @Test
    fun withAllWeeklyTotalMinutes() {
        assertEquals(420L, WeeklyLimits().withAll(60).weeklyTotalMinutes())
    }

    @Test
    fun withDayClampsLowValuesToTheFloor() {
        assertEquals(1, WeeklyLimits().withDay(DayOfWeek.MONDAY, 0).forDay(DayOfWeek.MONDAY))
    }

    @Test
    fun withDayClampsHighValuesToTheCeiling() {
        assertEquals(1440, WeeklyLimits().withDay(DayOfWeek.MONDAY, 5000).forDay(DayOfWeek.MONDAY))
    }

    @Test
    fun withDayWithNullClearsTheDay() {
        val limits = WeeklyLimits().withAll(60).withDay(DayOfWeek.MONDAY, null)
        assertNull(limits.forDay(DayOfWeek.MONDAY))
    }

    @Test
    fun dayDisplayNames() {
        assertEquals("Sunday", with(WeeklyLimits) { DayOfWeek.SUNDAY.displayName() })
    }
}
