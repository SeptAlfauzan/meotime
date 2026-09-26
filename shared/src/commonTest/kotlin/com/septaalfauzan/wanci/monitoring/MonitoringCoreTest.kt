package com.septaalfauzan.wanci

import com.septaalfauzan.wanci.data.local.TodayKey
import com.septaalfauzan.wanci.monitoring.MonitoringCore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.assertTrue

class FakeTodayKey(private val date: String) : TodayKey {
    override fun todayIso(): String = date
    override fun dateFlow(): Flow<String> = flowOf(date)
}

class MonitoringCoreTest {

    private val today = FakeTodayKey("2026-09-25")
    private val selfPkg = "com.septaalfauzan.wanci"

    private suspend fun repo(): FakeRepository {
        val r = FakeRepository()
        r.addMonitoredApp("com.instagram.android", "Instagram", 30)
        return r
    }

    @Test
    fun accumulatesUsageAndTriggersOverlayAtLimit() = runTest {
        val r = repo()
        val core = MonitoringCore(r, today, selfPkg, this) { /* capture */ }

        assertNull(core.onForegroundChange("com.instagram.android", 0L))
        val leaveInstagram = core.onForegroundChange("com.google.android.youtube", 35 * 60_000L)
        assertNotNull(leaveInstagram)
        assertEquals("com.instagram.android", leaveInstagram.packageName)
        assertTrue(leaveInstagram.usageMinutes >= 30L)

        assertEquals(35L, r.usage.value["com.instagram.android"])
        val decision = core.onForegroundChange("com.instagram.android", 36 * 60_000L)
        assertNull(decision)

        core.onOverlayDismissed("com.instagram.android")
        val reenter = core.onForegroundChange("com.instagram.android", 40 * 60_000L)
        assertNull(reenter)

        val decisionAfterLeave = core.onForegroundChange("launcher.pkg", 45 * 60_000L)
        assertNotNull(decisionAfterLeave)
        assertEquals("com.instagram.android", decisionAfterLeave.packageName)
        assertTrue(decisionAfterLeave.usageMinutes >= 30)
    }

    @Test
    fun disabledMonitoringNeverIntervenes() = runTest {
        val r = repo()
        r.setMonitoringEnabled("com.instagram.android", false)
        val core = MonitoringCore(r, today, selfPkg, this) { /* capture */ }

        core.onForegroundChange("com.instagram.android", 0L)
        assertNull(core.onForegroundChange("launcher.pkg", 60 * 60_000L))

        assertEquals(0L, r.usage.value["com.instagram.android"] ?: 0L)
    }

    @Test
    fun selfPackageIsNeverCountedOrIntervened() = runTest {
        val r = repo()
        val core = MonitoringCore(r, today, selfPkg, this) { /* capture */ }

        core.onForegroundChange(selfPkg, 0L)
        assertNull(core.onForegroundChange("com.instagram.android", 5 * 60_000L))
        core.onForegroundChange("launcher.pkg", 40 * 60_000L)

        assertEquals(35L, r.usage.value["com.instagram.android"])
        assertNull(r.usage.value[selfPkg])
    }

    @Test
    fun usageDeltasUnderOneMinuteAreDropped() = runTest {
        val r = repo()
        val core = MonitoringCore(r, today, selfPkg, this) { /* capture */ }

        core.onForegroundChange("com.instagram.android", 0L)
        core.onForegroundChange("launcher.pkg", 45_000L)

        assertTrue(r.recorded.isEmpty())
    }

    @Test
    fun checkCurrentAppReturnsOverlayWhenExceeded() = runTest {
        val r = repo()
        r.recordUsage("com.instagram.android", 30L, "2026-09-25")
        val core = MonitoringCore(r, today, selfPkg, this) { /* capture */ }

        val result = core.checkCurrentApp("com.instagram.android")
        assertNotNull(result)
        assertEquals("com.instagram.android", result.packageName)
    }

    @Test
    fun checkCurrentAppReturnsNullWhenUnderLimit() = runTest {
        val r = repo()
        val core = MonitoringCore(r, today, selfPkg, this) { /* capture */ }

        val result = core.checkCurrentApp("com.instagram.android")
        assertNull(result)
    }

    @Test
    fun timerShowsOverlayImmediatelyWhenAlreadyExceeded() = runTest {
        val decisions = mutableListOf<MonitoringCore.OverlayDecision>()
        val r = repo()
        r.recordUsage("com.instagram.android", 30L, "2026-09-25")
        val core = MonitoringCore(r, today, selfPkg, this) { decision ->
            decisions.add(decision)
        }

        core.startUsageTimer("com.instagram.android")
        assertEquals(1, decisions.size)
        assertEquals("com.instagram.android", decisions[0].packageName)
    }

    @Test
    fun timerStartsAndStopsOnAppSwitch() = runTest {
        val r = repo()
        val core = MonitoringCore(r, today, selfPkg, this) { /* capture */ }

        core.onForegroundChange("com.instagram.android", 0L)
        assertNotNull(core.activeTimers["com.instagram.android"])

        core.onForegroundChange("com.youtube.android", 30 * 60_000L)
        assertNull(core.activeTimers["com.instagram.android"])
    }

    @Test
    fun stopUsageTimerCancelsJob() = runTest {
        val r = repo()
        val core = MonitoringCore(r, today, selfPkg, this) { /* capture */ }

        core.startUsageTimer("com.instagram.android")
        assertNotNull(core.activeTimers["com.instagram.android"])
        core.stopUsageTimer("com.instagram.android")
        assertNull(core.activeTimers["com.instagram.android"])
    }
}
