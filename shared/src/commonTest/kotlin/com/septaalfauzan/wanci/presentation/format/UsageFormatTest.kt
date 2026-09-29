package com.septaalfauzan.wanci

import com.septaalfauzan.wanci.presentation.format.formatShortDuration
import com.septaalfauzan.wanci.presentation.format.formatUsage
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
