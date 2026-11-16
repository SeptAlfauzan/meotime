package com.septaalfauzan.meotime.presentation.format

import com.septaalfauzan.meotime.presentation.dashboard.ResolvedPeriod
import com.septaalfauzan.meotime.presentation.dashboard.StatsFilter
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class StatFormatTest {

    @Test
    fun compactDurationUsesTheStatsShapes() {
        assertEquals("0 sec", formatStatDuration(0L))
        assertEquals("15m", formatStatDuration(15L * 60L))
        assertEquals("1h 12m", formatStatDuration(72L * 60L))
        assertEquals("38m", formatStatDuration(38L * 60L))
        assertEquals("2h", formatStatDuration(120L * 60L))
    }

    @Test
    fun comparisonPercentKeepsOneDecimalAndDropsTrailingZero() {
        assertEquals("+8.5%", formatComparisonPercent(8.5))
        assertEquals("-4.2%", formatComparisonPercent(-4.2))
        assertEquals("+8%", formatComparisonPercent(8.0))
        assertEquals("0%", formatComparisonPercent(0.0))
        assertEquals("—", formatComparisonPercent(null))
    }

    @Test
    fun periodLabelsFollowTheApprovedCopy() {
        val today = LocalDate(2026, 11, 5)

        assertEquals("Today", formatPeriodButton(StatsFilter.Today, today))
        assertEquals("Today", formatPeriodSubtitle(StatsFilter.Today, today))

        assertEquals("Nov 4", formatPeriodButton(StatsFilter.SingleDay(LocalDate(2026, 11, 4)), today))
        assertEquals(
            "Nov 4, 2026",
            formatPeriodSubtitle(StatsFilter.SingleDay(LocalDate(2026, 11, 4)), today),
        )

        val range = StatsFilter.DateRange(LocalDate(2026, 11, 1), LocalDate(2026, 11, 5))
        assertEquals("Nov 1 – 5", formatPeriodButton(range, today))
        assertEquals("Nov 1 – Nov 5, 2026", formatPeriodSubtitle(range, today))
        assertEquals("Nov 1 – Nov 5 · 5 days", formatPeriodSummary(range, today))
    }

    @Test
    fun crossMonthAndCrossYearRangesIncludeTheNeededEndpoints() {
        val today = LocalDate(2026, 11, 5)
        val crossMonth = StatsFilter.DateRange(LocalDate(2026, 11, 28), LocalDate(2026, 12, 3))
        val crossYear = StatsFilter.DateRange(LocalDate(2025, 12, 30), LocalDate(2026, 1, 3))

        assertEquals("Nov 28 – Dec 3", formatPeriodButton(crossMonth, today))
        assertEquals("Nov 28 – Dec 3, 2026", formatPeriodSubtitle(crossMonth, today))
        assertEquals("Dec 30, 2025 – Jan 3, 2026", formatPeriodButton(crossYear, today))
        assertEquals("Dec 30, 2025 – Jan 3, 2026", formatPeriodSubtitle(crossYear, today))
    }

    @Test
    fun cardRightLabelIsTheWeekdayForOneDayAndCountForARange() {
        val today = LocalDate(2026, 11, 5)
        assertEquals(
            "Thu, Nov 5",
            formatCardPeriodLabel(StatsFilter.SingleDay(today), ResolvedPeriod(today, today)),
        )
        assertEquals(
            "5 days",
            formatCardPeriodLabel(
                StatsFilter.DateRange(LocalDate(2026, 11, 1), today),
                ResolvedPeriod(LocalDate(2026, 11, 1), today),
            ),
        )
    }

    @Test
    fun weekdayInitialIsSingleLetter() {
        assertEquals("S", formatWeekdayInitial(LocalDate(2026, 11, 1)))
        assertEquals("M", formatWeekdayInitial(LocalDate(2026, 11, 2)))
    }
}
