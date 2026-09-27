package com.septaalfauzan.wanci

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
}
