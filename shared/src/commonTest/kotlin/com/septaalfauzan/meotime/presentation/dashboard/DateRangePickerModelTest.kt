package com.septaalfauzan.meotime.presentation.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class DateRangePickerModelTest {

    private val today = LocalDate(2026, 11, 5)

    @Test
    fun presetsReplaceTheDraftSelection() {
        val initial = initialDatePickerState(StatsFilter.Today, today)

        val yesterday = initial.selectPreset(PickerPreset.YESTERDAY, today)
        assertEquals(PickerMode.SINGLE_DAY, yesterday.mode)
        assertEquals(LocalDate(2026, 11, 4), yesterday.start)
        assertEquals(LocalDate(2026, 11, 4), yesterday.end)
        assertEquals(StatsFilter.SingleDay(LocalDate(2026, 11, 4)), yesterday.toFilter(today))

        val lastWeek = initial.selectPreset(PickerPreset.LAST_7_DAYS, today)
        assertEquals(PickerMode.DATE_RANGE, lastWeek.mode)
        assertEquals(LocalDate(2026, 10, 30), lastWeek.start)
        assertEquals(today, lastWeek.end)
        assertEquals(StatsFilter.DateRange(LocalDate(2026, 10, 30), today), lastWeek.toFilter(today))
    }

    @Test
    fun singleModeTapSelectsOneDateAndTodayStaysRelative() {
        val state = initialDatePickerState(StatsFilter.SingleDay(LocalDate(2026, 11, 2)), today)
            .tapDate(today, today)

        assertEquals(PickerMode.SINGLE_DAY, state.mode)
        assertEquals(today, state.start)
        assertEquals(today, state.end)
        assertEquals(StatsFilter.Today, state.toFilter(today))
    }

    @Test
    fun rangeModeSecondTapCompletesAndSwapsReversedEndpoints() {
        val anchored = initialDatePickerState(StatsFilter.Today, today)
            .selectMode(PickerMode.DATE_RANGE, today)
            .tapDate(LocalDate(2026, 11, 4), today)

        assertEquals(LocalDate(2026, 11, 4), anchored.anchor)
        assertEquals(LocalDate(2026, 11, 4), anchored.start)
        assertEquals(LocalDate(2026, 11, 4), anchored.end)

        val completed = anchored.tapDate(LocalDate(2026, 11, 1), today)
        assertNull(completed.anchor)
        assertEquals(LocalDate(2026, 11, 1), completed.start)
        assertEquals(LocalDate(2026, 11, 4), completed.end)
    }

    @Test
    fun futureDatesAreNotSelectable() {
        val state = initialDatePickerState(StatsFilter.Today, today)

        assertFalse(state.isSelectable(LocalDate(2026, 11, 6), today))
        assertTrue(state.isSelectable(today, today))
        assertEquals(state, state.tapDate(LocalDate(2026, 11, 6), today))
    }

    @Test
    fun switchingToSingleModeKeepsTheRangeStart() {
        val range = initialDatePickerState(
            StatsFilter.DateRange(LocalDate(2026, 11, 1), LocalDate(2026, 11, 3)),
            today,
        ).selectMode(PickerMode.SINGLE_DAY, today)

        assertEquals(LocalDate(2026, 11, 1), range.start)
        assertEquals(LocalDate(2026, 11, 1), range.end)
        assertNull(range.anchor)
    }

    @Test
    fun monthNavigationCannotPassTheCurrentMonth() {
        val thisMonth = initialDatePickerState(StatsFilter.Today, today)

        assertEquals(thisMonth, thisMonth.nextMonth(today))
        assertEquals(LocalDate(2026, 10, 1), thisMonth.previousMonth(today).visibleMonth)
        assertEquals(
            LocalDate(2026, 11, 1),
            thisMonth.previousMonth(today).nextMonth(today).visibleMonth,
        )
    }

    @Test
    fun caldendarIsSundayFirstAndOnlyContainsRealDays() {
        val grid = monthGrid(LocalDate(2026, 11, 1))

        assertEquals(35, grid.size)
        assertEquals(LocalDate(2026, 11, 1), grid[0])
        assertEquals(LocalDate(2026, 11, 30), grid[29])
        assertNull(grid[30])
        assertNull(grid[34])
    }

    @Test
    fun pickerMonthLabelIncludesTheYear() {
        assertEquals("November 2026", formatPickerMonth(LocalDate(2026, 11, 1)))
    }
}
