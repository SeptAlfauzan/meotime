package com.septaalfauzan.meotime

import com.septaalfauzan.meotime.presentation.format.formatShortDuration
import com.septaalfauzan.meotime.presentation.format.formatUsage
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageFormatTest {
    @Test
    fun formatsBelowAMinuteInSeconds() {
        assertEquals("0 sec", formatUsage(0L))
        assertEquals("8 sec", formatUsage(8L))
        assertEquals("45 sec", formatUsage(45L))
    }

    @Test
    fun doesNotClampADayThatOverranItsLimit() {
        // The no-clamp invariant. A day can overrun its limit by more than 24 hours, and a
        // formatter that clamped into 1..1440 the way formatLimitMinutes does would render a
        // 25-hour day as "24 hours" — a wrong number, in the one place the user is reading their
        // own usage back.
        assertEquals("25 hours", formatUsage(90_000L))
        assertEquals("25 hours 1 min", formatUsage(90_060L))
    }

    @Test
    fun formatsMinutesAndHours() {
        assertEquals("1 min", formatUsage(60L))
        assertEquals("12 min", formatUsage(720L))
        assertEquals("1 hour", formatUsage(3_600L))
        assertEquals("1 hour 1 min", formatUsage(3_660L))
        assertEquals("1 hour 4 min", formatUsage(3_840L))
        assertEquals("2 hours 30 min", formatUsage(9_000L))
    }

    /** The opt-in day-aware rendering, for call sites that speak days — the Pro weekly footer. */
    @Test
    fun optInDaysRenderingReadsWholeDays() {
        // 1680 minutes is 28 hours: the whole-days rendering reads "1 day 4 hours", and the
        // default (`includeDays = false`) keeps every other call site on the hours-only path.
        assertEquals("1 day 4 hours", formatUsage(1680 * 60L, includeDays = true))
        assertEquals("2 days", formatUsage(2 * 24 * 60 * 60L, includeDays = true))
        assertEquals("1 day 4 hours 1 min", formatUsage(28 * 60 * 60L + 60L, includeDays = true))
        // The default path is unchanged: same inputs, same hours-only strings as before.
        assertEquals("28 hours", formatUsage(1680 * 60L))
        assertEquals("25 hours", formatUsage(90_000L))
    }

    @Test
    fun floorsNegativeReadingsAtZero() {
        assertEquals("0 sec", formatUsage(-5L))
    }

    @Test
    fun shortDurationStaysCompactForANotification() {
        assertEquals("0s", formatShortDuration(0L))
        assertEquals("45s", formatShortDuration(45L))
        assertEquals("1m", formatShortDuration(60L))
        assertEquals("12m", formatShortDuration(12 * 60L))
        assertEquals("59m", formatShortDuration(59 * 60L + 59L))
        assertEquals("1h", formatShortDuration(60 * 60L))
        assertEquals("1h 5m", formatShortDuration(65 * 60L))
        assertEquals("0s", formatShortDuration(-5L), "a negative reading floors at zero")
        // No 24h clamp, unlike formatUsage: a day can overrun its limit by more than a day, and
        // "25h" is the honest reading. The clamp formatUsage applies is for limits, not readings.
        assertEquals("25h 1m", formatShortDuration(25 * 60 * 60L + 60L))
    }
}
