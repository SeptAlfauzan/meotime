package com.septaalfauzan.meotime

import com.septaalfauzan.meotime.monitoring.LimitChecker
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitCheckerTest {
    @Test
    fun oneSecondUnderIsNotOver() = assertTrue(!LimitChecker.exceeds(1_799L, 1_800L))

    @Test
    fun exactlyAtTheLimitIsOver() = assertTrue(LimitChecker.exceeds(1_800L, 1_800L))

    @Test
    fun exceedsAtLimitBoundary() {
        assertFalse(LimitChecker.exceeds(2_520L, 3_600L))
        assertFalse(LimitChecker.exceeds(1_740L, 1_800L))
        assertTrue(LimitChecker.exceeds(1_800L, 1_800L))
        assertTrue(LimitChecker.exceeds(4_500L, 3_600L))
    }
}
