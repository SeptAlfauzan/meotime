@file:OptIn(ExperimentalCoroutinesApi::class)

package com.septaalfauzan.meotime

import com.septaalfauzan.meotime.data.local.TodayKey
import com.septaalfauzan.meotime.monitoring.MonitoringCore
import com.septaalfauzan.meotime.monitoring.MonotonicClock
import com.septaalfauzan.meotime.monitoring.SessionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class FakeTodayKey(private val date: String) : TodayKey {
    override fun todayIso(): String = date
    override fun dateFlow(): Flow<String> = flowOf(date)
}

private const val INSTAGRAM = "com.instagram.android"
private const val YOUTUBE = "com.youtube.android"
private const val LAUNCHER = "launcher.pkg"

/**
 * Every `advanceTimeBy` below is in **milliseconds** because the clock is: 1_800_000 is the 30
 * minutes a 30-minute limit takes, and 1_800 would be a second and a half. Assertions are in
 * seconds because the core is.
 *
 * `advanceTimeBy` stops *before* the instant it lands on — it runs what is scheduled strictly
 * earlier — so a tick due at exactly the instant a test advances to has not run yet. [runCurrent]
 * is how the crossing tick is then executed without moving the clock past it, which is what keeps
 * "fires at 1800, not at 1799 and not at 1801" an assertion rather than an approximation.
 */
class MonitoringCoreTest {

    private val today = FakeTodayKey("2026-09-25")
    private val selfPkg = "com.septaalfauzan.meotime"
    private val decisions = mutableListOf<MonitoringCore.OverlayDecision>()

    /** 30 minutes, which at seconds precision is the boundary every test below is probing. */
    private fun repository(): FakeRepository = FakeRepository().apply {
        addMonitoredAppBlocking(INSTAGRAM, "Instagram", 30)
        addMonitoredAppBlocking(YOUTUBE, "YouTube", 30)
    }

    private fun TestScope.core(
        repository: FakeRepository,
        onSessionChanged: ((SessionSnapshot?) -> Unit)? = null,
    ) = MonitoringCore(
        repository = repository,
        todayKey = today,
        selfPackageName = selfPkg,
        clock = MonotonicClock { currentTime },
        scope = this,
        onOverlayDecision = { decisions += it },
        onSessionChanged = onSessionChanged,
    ).apply {
        // The core is inert until a service attaches it, exactly as AppContainer builds it. A
        // test that skipped this would watch a core that ignores every event and pass vacuously.
        attach()
    }

    @Test
    fun overlayFiresOnTheTickTheTotalReachesTheLimit() = runTest {
        val repo = repository()
        repo.recordUsageSeconds(INSTAGRAM, 29 * 60L, today.todayIso())
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        assertTrue(decisions.isEmpty(), "one minute under the limit is not over the limit")

        advanceTimeBy(59_000L)
        assertTrue(decisions.isEmpty(), "still under one second before the crossing")

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, decisions.size)
        assertEquals(INSTAGRAM, decisions[0].packageName)
        assertEquals(1_800L, decisions[0].usageSeconds)
        assertEquals(1_800L, decisions[0].limitSeconds)
    }

    @Test
    fun enteringAnAlreadyOverAppFiresImmediatelyAndStartsNoTicker() = runTest {
        val repo = repository()
        repo.recordUsageSeconds(INSTAGRAM, 30 * 60L, today.todayIso())
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)

        assertEquals(1, decisions.size, "an app over its limit must be announced on entry")
        assertNull(core.activeTick, "there is nothing left to watch for, so nothing should tick")
    }

    @Test
    fun usageKeepsAccruingAfterTheOverlayFires() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(1_800_000L)
        runCurrent()
        assertEquals(1, decisions.size)

        advanceTimeBy(2_400_000L)
        core.onForegroundChange(LAUNCHER)

        assertEquals(4_200L, repo.usage.value[INSTAGRAM], "the 40 minutes after the overlay are usage too")
        assertEquals(1, decisions.size)
    }

    @Test
    fun oneOverlayPerEntry() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(1_800_000L)
        advanceTimeBy(3_600_000L)
        assertEquals(1, decisions.size, "an hour past the limit is not a second notice")

        core.onForegroundChange(LAUNCHER)
        core.onForegroundChange(INSTAGRAM)
        assertEquals(2, decisions.size, "re-entering an over-limit app announces it again")
    }

    @Test
    fun aSubMinuteSessionIsRecordedExactly() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(30_000L)
        core.onForegroundChange(LAUNCHER)

        assertEquals(30L, repo.usage.value[INSTAGRAM])
        assertEquals(1, repo.recorded.size)
    }

    @Test
    fun anInAppDialogDoesNotSplitTheSession() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(20_000L)
        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(10_000L)
        core.onForegroundChange(LAUNCHER)

        assertEquals(30L, repo.usage.value[INSTAGRAM], "the time before the dialog is still this session's")
        assertEquals(1, repo.recorded.size, "one contiguous run is one write")
    }

    @Test
    fun screenOffEndsTheSessionAndRecordsElapsed() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(45_000L)
        core.onForegroundChange(null)

        assertEquals(45L, repo.usage.value[INSTAGRAM])
    }

    @Test
    fun theClockAloneDecidesTheTotalNotTheTickCount() = runTest {
        // The deep-sleep guard. A doze stalls the ticker without moving the clock this core reads,
        // so the total must come from the clock and never from how many ticks have gone by. A
        // counter-based implementation fails this; a start-timestamp one cannot.
        val repo = repository()
        var now = 0L
        val core = MonitoringCore(
            repository = repo,
            todayKey = today,
            selfPackageName = selfPkg,
            clock = MonotonicClock { now },
            scope = this,
            onOverlayDecision = { decisions += it },
            tickIntervalMillis = 1_000L,
        ).apply { attach() }

        core.onForegroundChange(INSTAGRAM)
        now = 29 * 60_000L
        advanceTimeBy(1_000L)
        assertTrue(decisions.isEmpty(), "the clock says 29 minutes, so it is 29 minutes")

        now = 30 * 60_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, decisions.size)
    }

    @Test
    fun unmonitoredAndDisabledAppsOpenNoSession() = runTest {
        val repo = repository()
        repo.setMonitoringEnabledBlocking(INSTAGRAM, false)
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        assertNull(core.activeTick)

        core.onForegroundChange(LAUNCHER)
        advanceTimeBy(60_000L)
        core.onForegroundChange(null)

        assertTrue(repo.recorded.isEmpty())
    }

    @Test
    fun theSelfPackageClosesThePreviousSessionAndOpensNone() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(60_000L)
        core.onForegroundChange(selfPkg)

        assertEquals(60L, repo.usage.value[INSTAGRAM], "leaving for Meotime is still leaving")
        assertNull(repo.usage.value[selfPkg], "Meotime never counts itself")
        assertNull(core.activeTick)
    }

    @Test
    fun aFailedWriteStillLetsTheNextAppOpenItsSession() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(60_000L)
        repo.failOnRecord = true
        core.onForegroundChange(LAUNCHER)

        assertTrue(repo.usage.value.isEmpty(), "the failed write persisted nothing")

        core.onForegroundChange(INSTAGRAM)
        assertNotNull(core.activeTick, "one database error must not stop tracking the next app")
    }

    @Test
    fun aLimitRaisedBeforeReentryIsHonoured() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(20 * 60_000L)
        core.onForegroundChange(LAUNCHER)
        assertEquals(1_200L, repo.usage.value[INSTAGRAM])

        repo.updateDailyLimitBlocking(INSTAGRAM, 60)
        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(25 * 60_000L)

        assertTrue(decisions.isEmpty(), "1200 + 1500 is under the new 3600-second limit")
    }

    @Test
    fun detachStopsEverythingAndAttachResumes() = runTest {
        val repo = repository()
        val seen = mutableListOf<SessionSnapshot?>()
        val core = core(repo, onSessionChanged = { seen += it })

        core.onForegroundChange(INSTAGRAM)
        assertEquals(1, seen.size, "the session detach() abandons opened normally")

        core.detach()
        assertNull(core.activeTick, "teardown must be able to stop the ticker synchronously")
        // The documented contract on onSessionChanged: detach() is teardown, so the listener is
        // going away with the service and its notification. A close reported here would be received
        // by something that is already being destroyed.
        assertEquals(1, seen.size, "detach() must not publish the close it skips")

        advanceTimeBy(3_600L)
        core.onForegroundChange(LAUNCHER)
        assertTrue(decisions.isEmpty(), "a detached core ignores foreground changes")

        core.attach()
        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(1_800_000L)
        runCurrent()
        assertEquals(1, decisions.size, "the service can reconnect to the same core")
    }

    @Test
    fun sessionOpenedFiresTheSnapshot() = runTest {
        val repo = repository()
        repo.recordUsageSeconds(INSTAGRAM, 600L, today.todayIso())
        val seen = mutableListOf<SessionSnapshot?>()
        val core = core(repo, onSessionChanged = { seen += it })

        core.onForegroundChange(INSTAGRAM)

        assertEquals(1, seen.size)
        val snapshot = seen[0]
        assertNotNull(snapshot)
        assertEquals(INSTAGRAM, snapshot.packageName)
        assertEquals("Instagram", snapshot.appName)
        // Pinned because every liveSeconds assertion below is made at zero elapsed, where a wrong
        // start time cancels out and cannot be observed. The field is the base every running total
        // a notification shows is measured from, so it is worth one assertion of its own.
        assertEquals(currentTime, snapshot.startedAtMillis)
        assertEquals(600L, snapshot.storedSeconds)
        assertEquals(1_800L, snapshot.limitSeconds)
        // Zero elapsed, so the live total is exactly the stored total: liveSeconds is a running
        // total that already includes today's earlier usage, not just time spent in this session.
        assertEquals(600L, snapshot.liveSeconds(currentTime))
    }

    @Test
    fun theSnapshotCountsTheSameSecondsTheCoreEnforced() = runTest {
        // SessionSnapshot.liveSeconds is a copy of the core's own private arithmetic, and the only
        // way to tell the copy from the original is to compare the two at a moment when the clock
        // has moved. The overlay decision is that moment: it carries the total the core actually
        // enforced, so if the copy ever drifts, a notification built from the snapshot would report
        // a running total the limit was never applied to.
        val repo = repository()
        repo.recordUsageSeconds(INSTAGRAM, 29 * 60L, today.todayIso())
        val seen = mutableListOf<SessionSnapshot?>()
        val core = core(repo, onSessionChanged = { seen += it })

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(1, decisions.size)
        assertEquals(decisions[0].usageSeconds, seen[0]!!.liveSeconds(currentTime))
    }

    @Test
    fun anOverLimitEntryStillFiresTheSnapshot() = runTest {
        val repo = repository()
        repo.recordUsageSeconds(INSTAGRAM, 30 * 60L, today.todayIso())
        val seen = mutableListOf<SessionSnapshot?>()
        val core = core(repo, onSessionChanged = { seen += it })

        core.onForegroundChange(INSTAGRAM)

        assertEquals(1, seen.size, "the immediate over-limit branch must not swallow the open")
        assertEquals(1_800L, seen[0]!!.storedSeconds)
    }

    @Test
    fun sessionClosedFiresNull() = runTest {
        val repo = repository()
        val seen = mutableListOf<SessionSnapshot?>()
        val core = core(repo, onSessionChanged = { seen += it })

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(20_000L)
        core.onForegroundChange(LAUNCHER)

        assertEquals(listOf(true, false), seen.map { it != null })
    }

    @Test
    fun aCloseWithNoOpenSessionFiresNothing() = runTest {
        val repo = repository()
        val seen = mutableListOf<SessionSnapshot?>()
        val core = core(repo, onSessionChanged = { seen += it })

        core.onForegroundChange(LAUNCHER)
        advanceTimeBy(1_000L)
        core.onForegroundChange(LAUNCHER)

        assertTrue(seen.isEmpty(), "no session was open, so nothing changed")
    }

    @Test
    fun aSubSecondCloseStillFiresTheClose() = runTest {
        val repo = repository()
        val seen = mutableListOf<SessionSnapshot?>()
        val core = core(repo, onSessionChanged = { seen += it })

        core.onForegroundChange(INSTAGRAM)
        core.onForegroundChange(LAUNCHER)

        assertEquals(listOf(true, false), seen.map { it != null }, "zero elapsed is still a state change")
        assertTrue(repo.usage.value.isEmpty())
    }
}
