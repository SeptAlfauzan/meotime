@file:OptIn(ExperimentalCoroutinesApi::class)

package com.septaalfauzan.meotime.monitoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private class FakeRenderer : TrackerRenderer {
    val frames = mutableListOf<TrackerFrame?>()

    override fun render(frame: TrackerFrame?) {
        frames += frame
    }
}

class UsageTrackerControllerTest {
    private val renderer = FakeRenderer()

    private fun snapshot(
        stored: Long = 600L,
        limit: Long = 1_800L,
        startedAt: Long = 0L,
    ) = SessionSnapshot(
        packageName = "com.instagram.android",
        appName = "Instagram",
        startedAtMillis = startedAt,
        storedSeconds = stored,
        limitSeconds = limit,
    )

    private fun TestScope.controller() =
        UsageTrackerController(renderer, MonotonicClock { currentTime }, backgroundScope)

    @Test
    fun openPublishesCompactFrame() = runTest {
        val controller = controller()
        controller.onSessionOpened(snapshot(stored = 600L))

        val frame = renderer.frames.last()!!
        assertEquals(600L, frame.usageSeconds)
        assertEquals(1_800L, frame.limitSeconds)
        assertEquals(33, frame.percent)
        assertFalse(frame.nearLimit)
        assertFalse(frame.expanded)
    }

    @Test
    fun tenPercentRemainingMarksNearLimit() = runTest {
        val controller = controller()
        controller.onSessionOpened(snapshot(stored = 1_620L))
        assertTrue(renderer.frames.last()!!.nearLimit)

        controller.onSessionOpened(snapshot(stored = 1_601L))
        assertFalse(renderer.frames.last()!!.nearLimit)
    }

    @Test
    fun tapExpandsThenAutoCollapses() = runTest {
        val controller = controller()
        controller.onSessionOpened(snapshot())
        controller.onPillTapped()
        assertTrue(renderer.frames.last()!!.expanded)

        advanceTimeBy(3_000L)
        runCurrent()
        assertFalse(renderer.frames.last()!!.expanded)
    }

    @Test
    fun secondTapCollapsesImmediatelyAndCancelsTimer() = runTest {
        val controller = controller()
        controller.onSessionOpened(snapshot())
        controller.onPillTapped()
        controller.onPillTapped()
        assertFalse(renderer.frames.last()!!.expanded)

        val countAfterSecondTap = renderer.frames.size
        advanceTimeBy(3_000L)
        runCurrent()
        // Three compact tick frames are expected; a fourth would be the cancelled collapse timer.
        assertTrue(renderer.frames.drop(countAfterSecondTap).none { it?.expanded == true })
        assertEquals(countAfterSecondTap + 3, renderer.frames.size)
    }

    @Test
    fun compactFrameUpdatesUsageOnTick() = runTest {
        val controller = controller()
        controller.onSessionOpened(snapshot(stored = 600L))

        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(602L, renderer.frames.last()!!.usageSeconds)
        assertFalse(renderer.frames.last()!!.expanded)
        assertTrue(renderer.frames.size > 1)
    }

    @Test
    fun limitCrossedHidesAndStopsTicker() = runTest {
        val controller = controller()
        controller.onSessionOpened(snapshot())
        controller.onPillTapped()

        controller.onLimitCrossed()
        assertNull(renderer.frames.last())

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, renderer.frames.count { it == null })
    }

    @Test
    fun sessionClosedHidesAndStopsTicker() = runTest {
        val controller = controller()
        controller.onSessionOpened(snapshot())

        controller.onSessionClosed()
        assertNull(renderer.frames.last())

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, renderer.frames.count { it == null })
    }
}
