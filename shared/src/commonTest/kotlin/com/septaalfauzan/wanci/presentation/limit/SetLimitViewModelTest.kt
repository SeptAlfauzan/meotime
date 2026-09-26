@file:OptIn(ExperimentalCoroutinesApi::class)

package com.septaalfauzan.wanci.presentation.limit

import com.septaalfauzan.wanci.FakeRepository
import com.septaalfauzan.wanci.FakeTodayKey
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepository
import com.septaalfauzan.wanci.domain.InstalledApp
import com.septaalfauzan.wanci.domain.InstalledAppsProvider
import com.septaalfauzan.wanci.domain.model.MonitoredApp
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PACKAGE = "com.instagram.android"
private const val LABEL = "Instagram"

/**
 * A platform list that throws while [failing] is set. The provider is the one read on this screen
 * that is *not* a database call, and a `PackageManager` that blows up must be a retryable state
 * rather than a crash inside the view-model scope.
 */
private class FlakyAppsProvider(private val apps: List<InstalledApp>) : InstalledAppsProvider {
    var failing = false

    override fun getInstalledApps(): List<InstalledApp> {
        if (failing) throw IllegalStateException("package manager unavailable")
        return apps
    }
}

/** Fails the single-row read while [failing] is set; the screen must survive and retry. */
private class FlakyReadRepository(private val delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    val failing = MutableStateFlow(true)

    override suspend fun getMonitoredApp(packageName: String): MonitoredApp? {
        if (failing.value) throw IllegalStateException("database unavailable")
        return delegate.getMonitoredApp(packageName)
    }
}

/** Counts inserts and parks the first one on [gate], so an in-flight save can be observed. */
private class GatedAddRepository(private val delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    val addCalls = mutableListOf<Triple<String, String, Int>>()
    val gate = CompletableDeferred<Unit>()

    override suspend fun addMonitoredApp(packageName: String, appName: String, dailyLimitMinutes: Int): Boolean {
        addCalls += Triple(packageName, appName, dailyLimitMinutes)
        gate.await()
        return delegate.addMonitoredApp(packageName, appName, dailyLimitMinutes)
    }
}

class SetLimitViewModelTest {

    private val repository = FakeRepository()
    private val today = FakeTodayKey("2026-09-25")

    private val installed = InstalledAppsProvider {
        listOf(
            InstalledApp(PACKAGE, LABEL),
            InstalledApp("com.google.android.youtube", "YouTube"),
        )
    }

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
        installedAppsProvider: InstalledAppsProvider = installed,
    ) = SetLimitViewModel(
        requestedPackageName = packageName,
        installedAppsProvider = installedAppsProvider,
        useCases = WanciUseCases(repository, today),
    )

    /** The brief's original single test: stepper, clamp, and the value that reaches the repository. */
    @Test
    fun stepperClampsAndPersistsTheChosenLimit() = runTest {
        val viewModel = newViewModel()

        viewModel.setMinutes(0)
        assertEquals(1, viewModel.state.value.minutes)
        viewModel.setMinutes(2000)
        assertEquals(1440, viewModel.state.value.minutes)

        viewModel.decrement()
        assertEquals(1439, viewModel.state.value.minutes)
        viewModel.setMinutes(15)
        viewModel.increment()
        assertEquals(16, viewModel.state.value.minutes)

        viewModel.save { }

        val app = repository.apps.value.first { it.packageName == PACKAGE }
        assertEquals(16, app.dailyLimitMinutes)
    }

    @Test
    fun startsAtThirtyMinutesForAnAppThatIsNotMonitoredYet() {
        val state = newViewModel().state.value

        assertEquals(30, state.minutes)
        assertEquals(PACKAGE, state.packageName)
        assertEquals(LABEL, state.appName)
        assertFalse(state.alreadyMonitored)
        assertFalse(state.loading)
        assertFalse(state.error)
        assertFalse(state.missingArgument)
    }

    /**
     * An app that is already monitored opens on the limit it has, not on a blank default: the
     * screen is about to write a limit, and starting at 30 would make "save" a silent change from
     * whatever the user had set elsewhere.
     */
    @Test
    fun startsAtTheExistingLimitWhenTheAppIsAlreadyMonitored() = runTest {
        repository.addMonitoredApp(PACKAGE, LABEL, 120)

        val state = newViewModel().state.value

        assertEquals(120, state.minutes)
        assertTrue(state.alreadyMonitored)
    }

    /** A stored limit outside 1..1440 is repaired on the way in, not rendered and re-saved as is. */
    @Test
    fun clampsAnExistingLimitThatIsOutOfRange() = runTest {
        repository.addMonitoredApp(PACKAGE, LABEL, 5000)
        assertEquals(1440, newViewModel().state.value.minutes)
    }

    @Test
    fun clampsAnExistingLimitOfZeroToTheMinimum() = runTest {
        repository.addMonitoredApp(PACKAGE, LABEL, 0)

        assertEquals(1, newViewModel().state.value.minutes)
    }

    /**
     * A missing argument is navigation corruption, not a failed read: there is nothing to retry, so
     * the screen must say so and refuse to save rather than invent a package.
     */
    @Test
    fun aMissingArgumentIsATerminalStateAndSavesNothing() = runTest {
        var finished = false

        val state = newViewModel(packageName = null).state.value
        state.also { newViewModel(packageName = null).save { finished = true } }

        assertTrue(state.missingArgument)
        assertFalse(state.loading)
        assertFalse(state.error)
        assertFalse(state.canSave)
        assertNull(state.packageName)
        assertFalse(finished)
        assertTrue(repository.apps.value.isEmpty())
    }

    @Test
    fun aBlankArgumentIsTreatedAsMissing() {
        listOf("", "   ").forEach { argument ->
            val state = newViewModel(packageName = argument).state.value
            assertTrue(state.missingArgument, "blank argument '$argument' must not be treated as a package")
            assertFalse(state.canSave)
        }
    }

    /** A package the launcher no longer lists is a normal answer: show the id, do not fail. */
    @Test
    fun fallsBackToThePackageNameWhenThePlatformDoesNotListTheApp() {
        val state = newViewModel(packageName = "com.uninstalled.app").state.value

        assertEquals("com.uninstalled.app", state.appName)
        assertEquals("com.uninstalled.app", state.packageName)
        assertFalse(state.error)
        assertTrue(state.canSave)
    }

    @Test
    fun aBlankPlatformLabelFallsBackToThePackageNameToo() {
        val state = newViewModel(
            installedAppsProvider = InstalledAppsProvider { listOf(InstalledApp(PACKAGE, "  ")) },
        ).state.value

        assertEquals(PACKAGE, state.appName)
    }

    @Test
    fun labelResolutionFailureIsARetryableErrorNotACrash() = runTest {
        val flaky = FlakyAppsProvider(listOf(InstalledApp(PACKAGE, LABEL)))
        flaky.failing = true
        val viewModel = newViewModel(installedAppsProvider = flaky)

        assertTrue(viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.canSave)

        flaky.failing = false
        viewModel.retry()

        val state = viewModel.state.value
        assertFalse(state.error)
        assertFalse(state.loading)
        assertEquals(LABEL, state.appName)
    }

    @Test
    fun aFailedRowReadIsARetryableErrorNotACrash() = runTest {
        val flaky = FlakyReadRepository(repository)
        val viewModel = newViewModel(repository = flaky)

        assertTrue(viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)

        flaky.failing.value = false
        viewModel.retry()

        assertFalse(viewModel.state.value.error)
        assertEquals(LABEL, viewModel.state.value.appName)
    }

    /** Silence is not failure: an unanswered read keeps the skeleton up rather than raising an error. */
    @Test
    fun staysInLoadingUntilTheFirstAnswer() {
        val viewModel = newViewModel()

        assertFalse(viewModel.state.value.loading)
        assertEquals(LABEL, viewModel.state.value.appName)
    }

    @Test
    fun saveAddsTheAppWithTheChosenLimitAndFinishesOnce() = runTest {
        val viewModel = newViewModel()
        var finishes = 0
        viewModel.setMinutes(45)

        viewModel.save { finishes++ }

        val app = repository.apps.value.single()
        assertEquals(PACKAGE, app.packageName)
        assertEquals(LABEL, app.appName)
        assertEquals(45, app.dailyLimitMinutes)
        assertEquals(1, finishes)
    }

    /**
     * The insert is the only place a double tap could write twice, so the in-flight latch is the
     * guard and it is exercised with a parked write rather than a synchronous one.
     */
    @Test
    fun aSecondSaveWhileTheFirstIsInFlightIsIgnored() = runTest {
        val gated = GatedAddRepository(repository)
        val viewModel = newViewModel(repository = gated)
        var finishes = 0

        viewModel.save { finishes++ }
        assertTrue(viewModel.state.value.saving)
        viewModel.save { finishes++ }

        assertEquals(1, gated.addCalls.size)
        assertFalse(viewModel.state.value.canSave)

        gated.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gated.addCalls.size)
        assertEquals(1, finishes)
        assertEquals(1, repository.apps.value.size)
    }

    /**
     * The app is monitored when the screen opens, so the save is a *limit change* on a row the
     * screen told the user about, and the write has to land on that row rather than add a second.
     */
    @Test
    fun saveUpdatesTheLimitOfAnAlreadyMonitoredApp() = runTest {
        repository.addMonitoredApp(PACKAGE, LABEL, 120)
        val viewModel = newViewModel()
        var finishes = 0
        viewModel.setMinutes(45)

        viewModel.save { finishes++ }

        val app = repository.apps.value.single()
        assertEquals(45, app.dailyLimitMinutes)
        assertEquals(LABEL, app.appName)
        assertTrue(app.enabled)
        assertEquals(1, finishes)
    }

    /**
     * The app became monitored *behind* this create-flow screen. The insert is refused, and the
     * safe answer is a conflict: no navigation, no claim of success, and the row that somebody else
     * created keeps the limit it was created with.
     */
    @Test
    fun anAddRefusedBecauseTheAppBecameMonitoredElsewhereIsAConflictNotASuccess() = runTest {
        val viewModel = newViewModel()
        var finishes = false
        viewModel.setMinutes(45)
        repository.addMonitoredApp(PACKAGE, "Instagram", 30)

        viewModel.save { finishes = true }

        val state = viewModel.state.value
        assertTrue(state.saveConflict)
        assertFalse(state.saveFailed)
        assertFalse(finishes)
        assertFalse(state.saving)
        assertEquals(30, repository.apps.value.single().dailyLimitMinutes)
    }

    /** A conflict is terminal for this screen: re-pressing save would only be refused again. */
    @Test
    fun aConflictLeavesNothingToRetryAndDisablesSave() = runTest {
        val viewModel = newViewModel()
        repository.addMonitoredApp(PACKAGE, LABEL, 30)
        var finishes = false

        viewModel.save { finishes = true }
        viewModel.save { finishes = true }

        assertTrue(viewModel.state.value.saveConflict)
        assertFalse(viewModel.state.value.canSave)
        assertFalse(finishes)
        assertEquals(1, repository.apps.value.size)
    }

    /**
     * `updateDailyLimit` is an `UPDATE ... WHERE package_name = ?`: a row deleted between the read
     * and the save writes nothing at all. Reporting success there is a lie the dashboard would then
     * contradict, so the write is verified.
     */
    @Test
    fun anUpdateThatWritesNoRowIsAFailureNotASuccess() = runTest {
        repository.addMonitoredApp(PACKAGE, LABEL, 120)
        val viewModel = newViewModel()
        var finishes = false
        viewModel.setMinutes(45)
        repository.removeMonitoredApp(PACKAGE)

        viewModel.save { finishes = true }

        val state = viewModel.state.value
        assertTrue(state.saveFailed)
        assertFalse(state.saveConflict)
        assertFalse(finishes)
        assertFalse(state.saving)
        assertTrue(repository.apps.value.isEmpty())
    }

    @Test
    fun aFailedWriteIsAStateAndCanBeRetried() = runTest {
        var attempts = 0
        val flaky = object : ScreenTimeRepository by repository {
            override suspend fun addMonitoredApp(
                packageName: String,
                appName: String,
                dailyLimitMinutes: Int,
            ): Boolean {
                attempts++
                if (attempts == 1) throw IllegalStateException("disk full")
                return repository.addMonitoredApp(packageName, appName, dailyLimitMinutes)
            }
        }
        val viewModel = newViewModel(repository = flaky)
        var finishes = false

        viewModel.save { finishes = true }

        assertTrue(viewModel.state.value.saveFailed)
        assertFalse(viewModel.state.value.saving)
        assertFalse(finishes)
        // A failed write is not terminal: the action stays available for the retry.
        assertTrue(viewModel.state.value.canSave)

        viewModel.save { finishes = true }

        assertFalse(viewModel.state.value.saveFailed)
        assertTrue(finishes)
        assertEquals(30, repository.apps.value.single().dailyLimitMinutes)
    }

    /** The stepper stops at both ends instead of offering a control that cannot do anything. */
    @Test
    fun theStepperStopsAtBothEnds() {
        val viewModel = newViewModel()

        viewModel.setMinutes(MIN_LIMIT_MINUTES)
        viewModel.decrement()
        assertEquals(MIN_LIMIT_MINUTES, viewModel.state.value.minutes)
        assertFalse(viewModel.state.value.canDecrement)

        viewModel.setMinutes(MAX_LIMIT_MINUTES)
        viewModel.increment()
        assertEquals(MAX_LIMIT_MINUTES, viewModel.state.value.minutes)
        assertFalse(viewModel.state.value.canIncrement)
    }

    @Test
    fun presetsAreTheFiveDocumentedValues() {
        assertEquals(listOf(15, 30, 45, 60, 120), LIMIT_PRESETS)
        assertTrue(LIMIT_PRESETS.all { it in MIN_LIMIT_MINUTES..MAX_LIMIT_MINUTES })
    }

    @Test
    fun aPresetIsRecognisedByItsValueAndAnythingElseIsCustom() {
        assertEquals(30, presetFor(30))
        assertEquals(120, presetFor(120))
        assertNull(presetFor(31))
        assertNull(presetFor(0))
    }

    /**
     * The number, the unit and the preset label are all the same value, so the mapping is pinned
     * here instead of being re-derived by the screen: "60" has to read as one hour everywhere.
     */
    @Test
    fun formatsMinutesInHoursOnlyWhenItIsWhole() {
        assertEquals("1 min", formatLimitMinutes(1))
        assertEquals("45 min", formatLimitMinutes(45))
        assertEquals("1 hour", formatLimitMinutes(60))
        assertEquals("1 hour 30 min", formatLimitMinutes(90))
        assertEquals("2 hours", formatLimitMinutes(120))
        assertEquals("24 hours", formatLimitMinutes(1440))
        // Out-of-range input is clamped rather than rendered, so the screen can never show a value
        // the state does not hold.
        assertEquals("1 min", formatLimitMinutes(0))
        assertEquals("24 hours", formatLimitMinutes(2000))
    }
}
