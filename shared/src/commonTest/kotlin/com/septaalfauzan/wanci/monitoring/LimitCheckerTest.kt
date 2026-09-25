package com.septaalfauzan.wanci

import com.septaalfauzan.wanci.monitoring.LimitChecker
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitCheckerTest {
    @Test
    fun exceedsAtLimitBoundary() {
        assertFalse(LimitChecker.exceeds(42, 60))
        assertFalse(LimitChecker.exceeds(29, 30))
        assertTrue(LimitChecker.exceeds(30, 30))
        assertTrue(LimitChecker.exceeds(75, 60))
    }
}
