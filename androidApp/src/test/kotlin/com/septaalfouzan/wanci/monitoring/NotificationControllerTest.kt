package com.septaalfauzan.wanci.monitoring

import com.septaalfauzan.wanci.monitoring.MonotonicClock
import com.septaalfauzan.wanci.monitoring.NotificationController
import com.septaalfauzan.wanci.monitoring.NotificationState
import com.septaalfauzan.wanci.monitoring.SessionSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeNotifier : Notifier {
    val updates = mutableListOf<Pair<String, String>>()

    override fun update(title: String, text: String) {
        updates += title to text
    }
}

private fun snapshot(
    stored: Long = 600L,
    limit: Long = 1_800L,
    startedAt: Long = 0L,
) = SessionSnapshot(
    packageName = "com.foo",
    appName = "Foo",
    startedAtMillis = startedAt,
    storedSeconds = stored,
    limitSeconds = limit,
)

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationControllerTest {

    private val notifier = FakeNotifier()

    private fun TestScope.controller(rollover: Long = 60_000L) = NotificationController(
        notifier = notifier,
        clock = MonotonicClock { currentTime },
        // backgroundScope, not `this`: the rollover is a `while (isActive) delay(...)` loop that
        // never completes on its own, and runTest refuses to finish while a child of its own scope
        // is still active (UncompletedCoroutinesError). backgroundScope shares this scope's virtual
        // clock — so advanceTimeBy still drives it — but is cancelled when the test body ends.
        scope = backgroundScope,
        rolloverIntervalMillis = rollover,
    )

    @Test
    fun startPublishesTheIdleState() = runTest {
        val controller = controller()

        controller.start()

        assertEquals(NotificationState.Idle, controller.state)
        assertEquals("Monitoring active", notifier.updates.single().first)
    }

    @Test
    fun anOpenSessionRendersAppUsageOverLimit() = runTest {
        val controller = controller()

        controller.onSessionChanged(snapshot(stored = 720L, limit = 1_800L))

        val (title, text) = notifier.updates.last()
        assertEquals("Foo", title)
        assertEquals("Foo \u00B7 12m / 30m", text)
    }

    @Test
    fun theRenderedTotalFollowsTheSnapshotStartNotAPublishCount() = runTest {
        val controller = controller()
        controller.start()
        // Republished three times inside one second: nothing may move except the clock.
        controller.onSessionChanged(snapshot(stored = 0L, limit = 1_800L))
        controller.onLimitCrossed()
        controller.onLimitCrossed()

        assertTrue(notifier.updates.last().second.endsWith("/ 30m"))
        assertTrue(notifier.updates.last().second.contains("0s"), "no time passed: ${notifier.updates.last()}")
    }

    @Test
    fun aClosedSessionRevertsToIdle() = runTest {
        val controller = controller()
        controller.start()
        controller.onSessionChanged(snapshot())

        controller.onSessionChanged(null)

        assertEquals(NotificationState.Idle, controller.state)
        assertEquals("Monitoring active", notifier.updates.last().first)
    }

    @Test
    fun theRolloverRefreshesTheRunningTotalWhileASessionIsOpen() = runTest {
        val controller = controller()
        controller.start()
        controller.onSessionChanged(snapshot(stored = 0L, limit = 1_800L))
        val afterOpen = notifier.updates.size

        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(afterOpen + 1, notifier.updates.size, "one rollover refresh per minute")
        assertTrue(notifier.updates.last().second.contains("1m / 30m"), "a minute has passed: ${notifier.updates.last()}")
    }

    @Test
    fun theRolloverStopsWithTheSession() = runTest {
        val controller = controller()
        controller.start()
        controller.onSessionChanged(snapshot())
        controller.onSessionChanged(null)
        val afterClose = notifier.updates.size

        advanceTimeBy(600_000L)
        runCurrent()

        assertEquals(afterClose, notifier.updates.size, "an idle controller must not wake up every minute")
    }

    @Test
    fun aLimitCrossingRepublishesImmediately() = runTest {
        val controller = controller()
        controller.start()
        controller.onSessionChanged(snapshot(stored = 1_790L, limit = 1_800L))
        val before = notifier.updates.size

        controller.onLimitCrossed()

        assertEquals(before + 1, notifier.updates.size)
        assertTrue(notifier.updates.last().second.contains("/ 30m"))
    }

    @Test
    fun aRevokedUsageAccessPublishesTheReentryStateAndStopsTheRollover() = runTest {
        val controller = controller()
        controller.start()
        controller.onSessionChanged(snapshot())

        controller.onUsageAccessRevoked()

        assertIs<NotificationState.UsageAccessNeeded>(controller.state)
        assertEquals("Usage Access needed", notifier.updates.last().first)
        val published = notifier.updates.size
        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals(published, notifier.updates.size, "no session to count, so no rollover")
    }

    @Test
    fun aSecondStartRepublishesTheIdleState() = runTest {
        // startForegroundService can be delivered more than once — onboarding, the boot broadcast
        // and MainActivity.onCreate all call it. Republishing is correct: a startForeground with a
        // notification the controller has not seen would build from a stale snapshot.
        val controller = controller()

        controller.start()
        controller.start()

        assertEquals(2, notifier.updates.size)
        assertEquals(NotificationState.Idle, controller.state)
    }

    @Test
    fun aSessionOpeningOverTheIdleStateLeavesNoRolloverFromAPreviousSession() = runTest {
        val controller = controller()
        controller.start()
        controller.onSessionChanged(snapshot(stored = 0L, limit = 1_800L))
        controller.onSessionChanged(null)
        controller.onSessionChanged(snapshot(stored = 0L, limit = 1_800L))
        val afterReopen = notifier.updates.size

        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(afterReopen + 1, notifier.updates.size, "exactly one rollover for the open session")
    }
}
