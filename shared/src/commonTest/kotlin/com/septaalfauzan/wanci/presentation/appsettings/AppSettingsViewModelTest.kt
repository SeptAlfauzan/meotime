@file:OptIn(ExperimentalCoroutinesApi::class)

package com.septaalfauzan.wanci.presentation.appsettings

import com.septaalfauzan.wanci.FakeRepository
import com.septaalfauzan.wanci.FakeTodayKey
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepository
import com.septaalfauzan.wanci.domain.InstalledAppCheck
import com.septaalfauzan.wanci.domain.model.MonitoredApp
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import com.septaalfauzan.wanci.presentation.dashboard.appStatusLabel
import com.septaalfauzan.wanci.presentation.dashboard.dashboardRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PACKAGE = "com.instagram.android"
private const val LABEL = "Instagram"
private const val OTHER_PACKAGE = "com.google.android.youtube"
private const val DATE = "2026-09-25"

/**
 * Parks the single-row read on [gate] so the state *before* that read answers can be observed.
 *
 * Without the gate the unconfined dispatcher runs `init` to completion before the first assertion,
 * and the loading state would be asserted against a view model that has already resolved — the test
 * would pass while pinning nothing.
 */
private class GatedReadRepository(private val delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    val gate = CompletableDeferred<Unit>()

    override suspend fun getMonitoredApp(packageName: String): MonitoredApp? {
        gate.await()
        return delegate.getMonitoredApp(packageName)
    }
}

/** Fails the row read while [failing] is set; the screen must survive and offer a retry. */
private class FlakyReadRepository(private val delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    var failing = true

    override suspend fun getMonitoredApp(packageName: String): MonitoredApp? {
        if (failing) throw IllegalStateException("database unavailable")
        return delegate.getMonitoredApp(packageName)
    }
}

/** Throws on the write while [failing] is set, so "the write itself blew up" is testable. */
private class ThrowingWriteRepository(private val delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    var failToggle = true
    var failRemove = true

    override suspend fun setMonitoringEnabled(packageName: String, enabled: Boolean) {
        if (failToggle) throw IllegalStateException("disk full")
        delegate.setMonitoringEnabled(packageName, enabled)
    }

    override suspend fun removeMonitoredApp(packageName: String) {
        if (failRemove) throw IllegalStateException("disk full")
        delegate.removeMonitoredApp(packageName)
    }
}

/**
 * Accepts the write and returns normally without touching the row — the silent no-op, and the case
 * a re-read exists to catch. Room reports "matched no row" exactly like "changed one row", so the
 * caller cannot tell the difference without reading the row back.
 */
private class IgnoredWriteRepository(private val delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    override suspend fun setMonitoringEnabled(packageName: String, enabled: Boolean) = Unit
    override suspend fun removeMonitoredApp(packageName: String) = Unit
}

/** Counts probes so "one probe per resolve" can be pinned rather than assumed. */
private class CountingInstalledAppCheck(private val answer: (String) -> Boolean = { true }) : InstalledAppCheck {
    val probed = mutableListOf<String>()

    override fun isInstalled(packageName: String): Boolean {
        probed += packageName
        return answer(packageName)
    }
}

class AppSettingsViewModelTest {

    private val repository = FakeRepository()
    private val today = FakeTodayKey(DATE)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        packageName: String? = PACKAGE,
        repository: ScreenTimeRepository = this.repository,
        installedAppCheck: InstalledAppCheck = InstalledAppCheck { true },
    ) = AppSettingsViewModel(
        requestedPackageName = packageName,
        useCases = WanciUseCases(repository, today),
        installedAppCheck = installedAppCheck,
    )

    private suspend fun monitor(limit: Int = 30, enabled: Boolean = true) {
        repository.addMonitoredApp(PACKAGE, LABEL, limit)
        repository.apps.value = repository.apps.value.map {
            if (it.packageName == PACKAGE) it.copy(enabled = enabled) else it
        }
    }

    // ---------------------------------------------------------------- resolve

    /**
     * The deep-link path, end to end at the view-model level: `MainActivity` reads
     * `EXTRA_REQUESTED_PACKAGE` into `DeepLinkRequest.packageName`, the nav host builds
     * `appsettings/{packageName}`, and this view model is handed the decoded argument as its
     * constructor value. Constructing it with exactly that string is the only part of the chain this
     * test can reach, and it pins that the argument alone decides which app the screen is about.
     */
    @Test
    fun resolvesTheAppTheDeepLinkNamed() = runTest {
        repository.addMonitoredApp(PACKAGE, LABEL, 30)
        repository.addMonitoredApp(OTHER_PACKAGE, "YouTube", 60)
        repository.recordUsageSeconds(PACKAGE, 42L, DATE)

        val state = newViewModel(packageName = PACKAGE).state.value

        val app = assertNotNull(state.app)
        assertEquals(PACKAGE, app.packageName)
        assertEquals(LABEL, app.appName)
        assertEquals(30, app.dailyLimitMinutes)
        assertEquals(42L, state.usageSeconds)
        assertTrue(app.enabled)
        assertFalse(state.loading)
        assertFalse(state.error)
        assertFalse(state.missingArgument)
        assertFalse(state.notMonitored)
    }

    /**
     * The label is the row's own, read from the repository rather than from a launcher lookup: this
     * screen is about the *monitored row*, and the string it shows is the same one
     * `MonitoringCore` put in the overlay, so the deep link lands on a screen that names the app the
     * same way the overlay did.
     */
    @Test
    fun aBlankStoredLabelFallsBackToThePackageId() = runTest {
        repository.addMonitoredApp(PACKAGE, "", 30)

        val state = newViewModel().state.value

        assertEquals("", assertNotNull(state.app).appName)
        // The screen's own label, not the stored one: a blank label would render a screen with no
        // title, and the package id is a worse label but a truthful one.
        assertEquals(PACKAGE, state.displayName)
    }

    @Test
    fun displayNameIsTheStoredLabelForAMonitoredApp() = runTest {
        monitor()

        assertEquals(LABEL, newViewModel().state.value.displayName)
    }

    /** Silence is not failure: an unanswered read keeps the skeleton up rather than raising an error. */
    @Test
    fun staysInLoadingUntilTheFirstAnswer() = runTest {
        monitor()
        val gated = GatedReadRepository(repository)
        val viewModel = newViewModel(repository = gated)

        val whileUnanswered = viewModel.state.value
        assertTrue(whileUnanswered.loading)
        assertFalse(whileUnanswered.error)
        assertNull(whileUnanswered.app)

        gated.gate.complete(Unit)
        advanceUntilIdle()

        val resolved = viewModel.state.value
        assertFalse(resolved.loading)
        assertFalse(resolved.error)
        assertEquals(PACKAGE, assertNotNull(resolved.app).packageName)
    }

    @Test
    fun aFailedReadIsARetryableErrorNotACrash() = runTest {
        monitor()
        val flaky = FlakyReadRepository(repository)
        val viewModel = newViewModel(repository = flaky)

        assertTrue(viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)
        assertNull(viewModel.state.value.app)

        flaky.failing = false
        viewModel.retry()

        val state = viewModel.state.value
        assertFalse(state.error)
        assertFalse(state.loading)
        assertEquals(LABEL, assertNotNull(state.app).appName)
    }

    /**
     * A `PackageManager` that throws must not take the screen down with it. The probe is a
     * read-time fact about the device, and a failure to establish it is a retryable state, not a
     * licence to report "installed" as though it were known.
     */
    @Test
    fun aThrowingInstalledProbeIsARetryableErrorNotACrash() = runTest {
        monitor()
        var failing = true
        val viewModel = newViewModel(
            installedAppCheck = InstalledAppCheck {
                if (failing) throw IllegalStateException("package manager unavailable")
                true
            },
        )

        assertTrue(viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)

        failing = false
        viewModel.retry()

        assertFalse(viewModel.state.value.error)
        assertTrue(assertNotNull(viewModel.state.value.app).installed)
    }

    /**
     * A missing argument is navigation corruption, not a failed read: there is nothing to retry, so
     * the screen says so and writes nothing.
     */
    @Test
    fun aMissingArgumentIsATerminalStateAndWritesNothing() = runTest {
        monitor()
        var finished = false
        val viewModel = newViewModel(packageName = null)

        viewModel.requestRemove()
        viewModel.setMonitoringEnabled(false)
        viewModel.remove { finished = true }

        val state = viewModel.state.value
        assertTrue(state.missingArgument)
        assertFalse(state.loading)
        assertFalse(state.error)
        assertTrue(assertNotNull(repository.getMonitoredApp(PACKAGE)).enabled)
        assertFalse(finished)
    }

    @Test
    fun aBlankArgumentIsTreatedAsMissing() {
        listOf("", "   ").forEach { argument ->
            val state = newViewModel(packageName = argument).state.value
            assertTrue(state.missingArgument, "blank argument '$argument' must not be treated as a package")
            assertFalse(state.loading)
        }
    }

    /**
     * The deep link can name an app that is not monitored — a package the overlay showed for a row
     * that has since been deleted, or one the user never added. That is an answer, not a crash, and
     * the screen says so instead of rendering an editor for a row that does not exist.
     */
    @Test
    fun anAppThatIsNotMonitoredIsAStateNotACrash() = runTest {
        var finished = false
        val viewModel = newViewModel(packageName = "com.example.never.added")

        val state = viewModel.state.value
        assertTrue(state.notMonitored)
        assertFalse(state.loading)
        assertFalse(state.error)
        assertNull(state.app)

        viewModel.setMonitoringEnabled(false)
        viewModel.requestRemove()
        viewModel.remove { finished = true }

        assertNull(viewModel.state.value.app)
        assertFalse(viewModel.state.value.removeRequested)
        assertFalse(finished)
    }

    // ---------------------------------------------------------------- installed

    /**
     * The repository never populates `installed` (it has no platform access), so the probe is the
     * only thing that can answer it. An uninstalled app keeps its row — the user may reinstall it —
     * and this screen has to be able to say so.
     */
    @Test
    fun resolvesWhetherTheAppIsStillInstalled() = runTest {
        monitor()

        val installed = newViewModel(installedAppCheck = InstalledAppCheck { true }).state.value
        val missing = newViewModel(installedAppCheck = InstalledAppCheck { false }).state.value

        assertTrue(assertNotNull(installed.app).installed)
        assertFalse(assertNotNull(missing.app).installed)
        assertTrue(missing.showsUninstallNotice)
        assertFalse(installed.showsUninstallNotice)
    }

    /**
     * One probe per resolve, for one package. The dashboard has to memoise its probe against the
     * package set because it re-derives on every usage emission; this screen resolves once per
     * resume and is not re-driven by usage, so a per-resolve probe is the whole cost. Pinned so a
     * future change cannot quietly turn it into one-per-emission or one-per-write.
     */
    @Test
    fun probesInstalledOncePerResolveAndNotOnWrites() = runTest {
        monitor(limit = 30)
        val check = CountingInstalledAppCheck()
        val viewModel = newViewModel(installedAppCheck = check)
        assertEquals(listOf(PACKAGE), check.probed)

        viewModel.setMonitoringEnabled(false)
        viewModel.requestRemove()
        viewModel.cancelRemove()
        assertEquals(listOf(PACKAGE), check.probed)

        viewModel.refresh()
        assertEquals(listOf(PACKAGE, PACKAGE), check.probed)
    }

    /** A reinstall while the screen is open is picked up by the next resolve, not for ever. */
    @Test
    fun refreshPicksUpAnAppThatWasReinstalled() = runTest {
        monitor()
        var installed = false
        val viewModel = newViewModel(installedAppCheck = InstalledAppCheck { installed })
        assertFalse(assertNotNull(viewModel.state.value.app).installed)

        installed = true
        viewModel.refresh()

        assertTrue(assertNotNull(viewModel.state.value.app).installed)
    }

    // ---------------------------------------------------------------- pause / resume

    /** The brief's core behaviour: pausing is a persisted write, and the row says so afterwards. */
    @Test
    fun pausingStopsMonitoringAndResumingStartsItAgain() = runTest {
        monitor()
        val viewModel = newViewModel()
        assertTrue(assertNotNull(viewModel.state.value.app).enabled)

        viewModel.setMonitoringEnabled(false)

        val paused = viewModel.state.value
        assertFalse(assertNotNull(paused.app).enabled)
        assertTrue(paused.paused)
        assertFalse(paused.updateFailed)
        assertFalse(paused.updatingEnabled)
        assertFalse(assertNotNull(repository.getMonitoredApp(PACKAGE)).enabled)

        viewModel.setMonitoringEnabled(true)

        assertTrue(assertNotNull(viewModel.state.value.app).enabled)
        assertFalse(viewModel.state.value.paused)
        assertTrue(assertNotNull(repository.getMonitoredApp(PACKAGE)).enabled)
    }

    /**
     * A paused app is not being measured, so this screen has to read the same way the dashboard row
     * does — same words, same comparison, from the same function. A settings screen that reported a
     * live-looking budget for a paused app would contradict the list one tap away.
     */
    @Test
    fun aPausedAppIsLabelledTheWayTheDashboardLabelsIt() = runTest {
        monitor(enabled = false)
        val state = newViewModel().state.value
        val app = assertNotNull(state.app)

        val row = dashboardRow(app.copy(todayUsageSeconds = state.usageSeconds))

        assertTrue(row.paused)
        assertEquals("Monitoring paused", appStatusLabel(row))
    }

    /**
     * `setMonitoringEnabled` is an `UPDATE ... WHERE package_name = ?`: a row deleted between the
     * read and the toggle writes nothing and still returns normally. Claiming a pause there would be
     * a claim about a row that no longer exists, so the screen falls back to its not-monitored state.
     */
    @Test
    fun aToggleThatWritesNoRowDoesNotClaimSuccess() = runTest {
        monitor()
        val viewModel = newViewModel()
        repository.removeMonitoredApp(PACKAGE)

        viewModel.setMonitoringEnabled(false)

        val state = viewModel.state.value
        assertTrue(state.notMonitored)
        assertNull(state.app)
        assertFalse(state.paused)
    }

    /** A write that threw leaves the row as it was: the switch must not show the requested state. */
    @Test
    fun aToggleThatThrowsIsAStateAndKeepsTheRow() = runTest {
        monitor()
        val flaky = ThrowingWriteRepository(repository)
        val viewModel = newViewModel(repository = flaky)

        viewModel.setMonitoringEnabled(false)

        val afterFailure = viewModel.state.value
        assertTrue(afterFailure.updateFailed)
        assertFalse(afterFailure.updatingEnabled)
        // The verb in the failure notice has to come from the request: the row still reads
        // "monitoring on", so a notice derived from the row would report a failed *resume* for a
        // failed pause.
        assertEquals(false, afterFailure.requestedEnabled)
        assertTrue(assertNotNull(afterFailure.app).enabled)
        assertTrue(assertNotNull(repository.getMonitoredApp(PACKAGE)).enabled)

        flaky.failToggle = false
        viewModel.setMonitoringEnabled(false)

        assertFalse(viewModel.state.value.updateFailed)
        assertNull(viewModel.state.value.requestedEnabled)
        assertFalse(assertNotNull(viewModel.state.value.app).enabled)
    }

    /** A read is the truth a pending notice is about, so a re-read drops a stale one. */
    @Test
    fun aRefreshDropsAStaleFailureNotice() = runTest {
        monitor()
        val flaky = ThrowingWriteRepository(repository)
        val viewModel = newViewModel(repository = flaky)

        viewModel.setMonitoringEnabled(false)
        assertTrue(viewModel.state.value.updateFailed)

        flaky.failToggle = false
        viewModel.refresh()

        assertFalse(viewModel.state.value.updateFailed)
        assertNull(viewModel.state.value.requestedEnabled)
    }

    /** A refresh drops a stale removal failure too: a read is the truth the notice is about. */
    @Test
    fun aRefreshDropsAStaleRemoveFailure() = runTest {
        monitor()
        val flaky = ThrowingWriteRepository(repository)
        val viewModel = newViewModel(repository = flaky)

        viewModel.requestRemove()
        viewModel.remove { }
        advanceUntilIdle()
        assertTrue(viewModel.state.value.removeFailed)

        flaky.failRemove = false
        viewModel.refresh()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.removeFailed)
    }

    /**
     * The write returned normally and the row still disagrees: nothing was written. Reporting the
     * requested state would leave a switch showing "paused" over a row that is still counting.
     */
    @Test
    fun aToggleTheRepositoryDidNotPerformIsAFailureNotASuccess() = runTest {
        monitor()
        val viewModel = newViewModel(repository = IgnoredWriteRepository(repository))

        viewModel.setMonitoringEnabled(false)

        val state = viewModel.state.value
        assertTrue(state.updateFailed)
        assertTrue(assertNotNull(state.app).enabled)
    }

    /** One write at a time, so a double tap on the switch cannot interleave two updates. */
    @Test
    fun aSecondToggleWhileTheFirstIsInFlightIsIgnored() = runTest {
        monitor()
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val slow = object : ScreenTimeRepository by repository {
            override suspend fun setMonitoringEnabled(packageName: String, enabled: Boolean) {
                calls++
                gate.await()
                repository.setMonitoringEnabled(packageName, enabled)
            }
        }
        val viewModel = newViewModel(repository = slow)

        viewModel.setMonitoringEnabled(false)
        assertTrue(viewModel.state.value.updatingEnabled)
        viewModel.setMonitoringEnabled(true)
        assertEquals(1, calls)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, calls)
        assertFalse(viewModel.state.value.updatingEnabled)
    }

    // ---------------------------------------------------------------- remove

    /**
     * Removal is destructive and irreversible from this screen, so it takes a confirmation step. The
     * step is not cosmetic: a view model talked into removing without it writes nothing at all, and
     * cancelling it leaves the row exactly as it was.
     */
    @Test
    fun removingAsksForConfirmationFirstAndCancellingKeepsTheRow() = runTest {
        monitor()
        val viewModel = newViewModel()
        var finished = false

        viewModel.remove { finished = true }

        assertNotNull(repository.getMonitoredApp(PACKAGE))
        assertFalse(viewModel.state.value.removeRequested)
        assertFalse(finished)

        viewModel.requestRemove()
        assertTrue(viewModel.state.value.removeRequested)
        assertFalse(viewModel.state.value.removeFailed)

        viewModel.cancelRemove()
        val state = viewModel.state.value
        assertFalse(state.removeRequested)
        assertNotNull(state.app)
        assertNotNull(repository.getMonitoredApp(PACKAGE))
        assertFalse(finished)
    }

    @Test
    fun confirmingTheRemovalDeletesTheRowAndFinishesOnce() = runTest {
        monitor()
        val viewModel = newViewModel()
        var finishes = 0

        viewModel.requestRemove()
        viewModel.remove { finishes++ }

        assertNull(repository.getMonitoredApp(PACKAGE))
        assertEquals(1, finishes)
        // The latch stays raised on success: navigation is not instantaneous, and a second tap in
        // that window must not fire the finish again.
        assertTrue(viewModel.state.value.removing)
    }

    @Test
    fun aRemovalThatThrowsKeepsTheRowAndStaysOnScreen() = runTest {
        monitor()
        val flaky = ThrowingWriteRepository(repository)
        val viewModel = newViewModel(repository = flaky)
        var finished = false

        viewModel.requestRemove()
        viewModel.remove { finished = true }

        val state = viewModel.state.value
        assertTrue(state.removeFailed)
        assertFalse(state.removing)
        assertTrue(state.removeRequested)
        assertNotNull(state.app)
        assertNotNull(repository.getMonitoredApp(PACKAGE))
        assertFalse(finished)

        flaky.failRemove = false
        viewModel.remove { finished = true }

        assertNull(repository.getMonitoredApp(PACKAGE))
        assertTrue(finished)
    }

    /** The silent no-op again, on the other write: the row is still there, so this is not a removal. */
    @Test
    fun aRemovalTheRepositoryDidNotPerformIsAFailureNotASuccess() = runTest {
        monitor()
        val viewModel = newViewModel(repository = IgnoredWriteRepository(repository))
        var finished = false

        viewModel.requestRemove()
        viewModel.remove { finished = true }

        val state = viewModel.state.value
        assertTrue(state.removeFailed)
        assertFalse(state.removing)
        assertNotNull(state.app)
        assertNotNull(repository.getMonitoredApp(PACKAGE))
        assertFalse(finished)
    }

    /**
     * A row that was already gone when the delete ran is still the state the user asked for — the
     * app is not monitored — so this path finishes instead of reporting a failure for a delete that
     * had nothing left to remove.
     */
    @Test
    fun aRemovalOfARowThatVanishedBehindTheScreenStillFinishes() = runTest {
        monitor()
        val viewModel = newViewModel()
        var finished = false
        viewModel.requestRemove()
        repository.removeMonitoredApp(PACKAGE)

        viewModel.remove { finished = true }

        assertTrue(finished)
        assertFalse(viewModel.state.value.removeFailed)
    }

    // ---------------------------------------------------------------- limit hand-off

    /**
     * The limit is edited on the set-limit screen, so this view model never writes it — what it has
     * to guarantee is that a limit changed *elsewhere* is not shown stale when the user comes back
     * (Back out of the editor without saving). The refresh is also the one that keeps the screen's
     * row honest after any out-of-band write.
     */
    @Test
    fun refreshPicksUpALimitChangedElsewhereWithoutReshowingTheSkeleton() = runTest {
        monitor(limit = 30)
        val viewModel = newViewModel()
        assertEquals(30, assertNotNull(viewModel.state.value.app).dailyLimitMinutes)

        repository.updateDailyLimit(PACKAGE, 45)
        viewModel.refresh()

        val state = viewModel.state.value
        assertEquals(45, assertNotNull(state.app).dailyLimitMinutes)
        // A refresh over good data must not blank the screen it already had.
        assertFalse(state.loading)
        assertFalse(state.error)
    }

    /** A refresh that fails leaves the row on screen and reports the failure, rather than blanking. */
    @Test
    fun aRefreshThatFailsKeepsTheResolvedRow() = runTest {
        monitor()
        val flaky = FlakyReadRepository(repository)
        flaky.failing = false
        val viewModel = newViewModel(repository = flaky)
        assertNotNull(viewModel.state.value.app)

        flaky.failing = true
        viewModel.refresh()

        val state = viewModel.state.value
        assertTrue(state.error)
        assertNotNull(state.app)
    }
}
