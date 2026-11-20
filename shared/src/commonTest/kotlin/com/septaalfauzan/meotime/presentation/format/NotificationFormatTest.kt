package com.septaalfauzan.meotime.presentation.format

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationFormatTest {

    @Test
    fun nonNullLimitKeepsTheExistingCopy() {
        assertEquals("Calendar \u00B7 2h / 30m", formatSessionNotificationText("Calendar", 7200L, 1800L))
    }

    @Test
    fun nullLimitDropsTheSlashSegment() {
        assertEquals("Calendar \u00B7 2h", formatSessionNotificationText("Calendar", 7200L, null))
    }
}
