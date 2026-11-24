@file:OptIn(ExperimentalCoroutinesApi::class)

package com.septaalfauzan.meotime.monitoring

import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
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

    private fun TestScope.controller(clock: MonotonicClock = MonotonicClock { currentTime }) =
        UsageTrackerController(
            renderer = renderer,
            clock = clock,
            scope = backgroundScope,
            eventDispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        )

    @Test
    fun openPublishesCompactFrame() = runTest {
        val controller = controller()
        controller.onSessionOpened(snapshot(stored = 600L))
        runCurrent()

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
        runCurrent()
        assertTrue(renderer.frames.last()!!.nearLimit)

        controller.onSessionOpened(snapshot(stored = 1_601L))
        runCurrent()
        assertFalse(renderer.frames.last()!!.nearLimit)
    }

    @Test
    fun tapExpandsThenAutoCollapses() = runTest {
        val controller = controller()
        controller.onSessionOpened(snapshot())
        runCurrent()
        controller.onPillTapped()
        runCurrent()
        assertTrue(renderer.frames.last()!!.expanded)

        advanceTimeBy(3_000L)
        runCurrent()
        assertFalse(renderer.frames.last()!!.expanded)
    }

    @Test
    fun secondTapCollapsesImmediatelyAndCancelsTimer() = runTest {
        val controller = controller()
        controller.onSessionOpened(snapshot())
        runCurrent()
        controller.onPillTapped()
        runCurrent()
        controller.onPillTapped()
        runCurrent()
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
        runCurrent()

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
        runCurrent()
        controller.onPillTapped()
        runCurrent()

        controller.onLimitCrossed()
        runCurrent()
        assertNull(renderer.frames.last())

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, renderer.frames.count { it == null })
    }

    @Test
    fun sessionClosedHidesAndStopsTicker() = runTest {
        val controller = controller()
        controller.onSessionOpened(snapshot())
        runCurrent()

        controller.onSessionClosed()
        runCurrent()
        assertNull(renderer.frames.last())

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, renderer.frames.count { it == null })
    }

    @Test
    fun openQueuedBeforeHideStaysHiddenOnPostingDispatcher() = runTest {
        // TestDispatcher always posts, as Android's plain Dispatchers.Main does. Both events are
        // queued before either runs, so FIFO puts the open first and the hide last.
        val controller = controller()
        controller.onSessionOpened(snapshot())
        controller.onSessionClosed()

        runCurrent()
        advanceTimeBy(3_000L)
        runCurrent()

        assertTrue(renderer.frames.first() != null)
        assertEquals(1, renderer.frames.count { it == null })
        val hideIndex = renderer.frames.indexOf(null)
        assertTrue(hideIndex >= 0, "hide never rendered: ${renderer.frames}")
        assertTrue(
            renderer.frames.drop(hideIndex + 1).all { it == null },
            "a frame was published after hide: ${renderer.frames}",
        )
    }

    @Test
    fun hideFromAnotherThreadBeatsQueuedTick() = runTest {
        // Gate the tick after its null check but before it renders: the clock read is the only
        // suspension-free seam in publish(). Hide is invoked from a real Default thread while the
        // tick is parked there, so the interleaving is fixed without any sleeping.
        val tickAtPublish = CompletableDeferred<Unit>()
        val hideReturned = CompletableDeferred<Unit>()
        var armClock = false
        val clock = MonotonicClock {
            if (armClock) {
                armClock = false
                tickAtPublish.complete(Unit)
                runBlocking { hideReturned.await() }
            }
            currentTime
        }
        val controller = controller(clock)
        controller.onSessionOpened(snapshot())
        runCurrent()
        renderer.frames.clear()
        armClock = true

        val hideJob = launch(Dispatchers.Default) {
            tickAtPublish.await()
            withContext(Dispatchers.Default) { controller.onSessionClosed() }
            hideReturned.complete(Unit)
        }
        advanceTimeBy(3_000L)
        runCurrent()
        hideJob.join()
        runCurrent()

        assertEquals(1, renderer.frames.count { it == null })
        val hideIndex = renderer.frames.indexOf(null)
        assertTrue(hideIndex >= 0, "hide never rendered: ${renderer.frames}")
        assertTrue(
            renderer.frames.drop(hideIndex + 1).all { it == null },
            "a tick republished after hide: ${renderer.frames}",
        )
    }
}
