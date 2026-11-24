@file:OptIn(ExperimentalCoroutinesApi::class)

package com.septaalfauzan.meotime.presentation.limit

import com.septaalfauzan.meotime.FakeRepository
import com.septaalfauzan.meotime.FakeTodayKey
import com.septaalfauzan.meotime.data.repository.ScreenTimeRepository
import com.septaalfauzan.meotime.domain.InstalledApp
import com.septaalfauzan.meotime.domain.InstalledAppsProvider
import com.septaalfauzan.meotime.domain.limit.effectiveLimitMinutes
import com.septaalfauzan.meotime.domain.model.MonitoredApp
import com.septaalfauzan.meotime.domain.model.WeeklyLimits
import com.septaalfauzan.meotime.domain.useCase.MeotimeUseCases
import com.septaalfauzan.meotime.presentation.format.formatUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PACKAGE = "com.instagram.android"
private const val LABEL = "Instagram"

/** Fails the single-row read while [failing] is set; the screen must survive and retry. */
private class WeeklyLimitFlakyReadRepository(private val delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    val failing = MutableStateFlow(true)

    override suspend fun getMonitoredApp(packageName: String): MonitoredApp? {
        if (failing.value) throw IllegalStateException("database unavailable")
        return delegate.getMonitoredApp(packageName)
    }
}

/** Parks the insert on [gate], so an in-flight save can be observed. */
private class GatedWeeklyAddRepository(private val delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    val addCalls = mutableListOf<WeeklyLimits>()
    val gate = CompletableDeferred<Unit>()

    override suspend fun addMonitoredAppWithWeeklyLimits(
        packageName: String,
        appName: String,
        weeklyLimits: WeeklyLimits,
    ): Boolean {
        addCalls += weeklyLimits
        gate.await()
        return delegate.addMonitoredAppWithWeeklyLimits(packageName, appName, weeklyLimits)
    }
}

class WeeklyLimitViewModelTest {

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
    ) = WeeklyLimitViewModel(
        requestedPackageName = packageName,
        installedAppsProvider = installedAppsProvider,
        useCases = MeotimeUseCases(repository, today),
    )

    /** Cold start: nothing is monitored, so the editor is on by default. */
    @Test
    fun coldStartEnablesAllSevenDaysAtThirty() = runTest {
        val state = newViewModel().state.value

        assertEquals(DayOfWeek.entries.toSet(), state.enabled)
        DayOfWeek.entries.forEach { assertEquals(30, state.minutes.forDay(it), it.name) }
        assertTrue(state.canSave)
    }

    /**
     * An existing weekly row prefills the draft, and a day the row does not limit keeps a null:
     * the schedule is read back, not defaulted over.
     */
    @Test
    fun existingWeeklyRowPrefillsTheDraftAndAnotherDayKeepsItsValue() = runTest {
        repository.addMonitoredAppWithWeeklyLimitsBlocking(
            PACKAGE, LABEL, WeeklyLimits(monday = 45, wednesday = 60),
        )

        val state = newViewModel().state.value

        assertEquals(WeeklyLimits(monday = 45, wednesday = 60), state.draft)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), state.enabled)
        assertTrue(state.alreadyMonitored)
    }

    /**
     * A pre-Pro row (marker false) still has to become a weekly schedule: its daily value is
     * defensively widened to "every day on", so the editor shows and saves a real weekly schedule.
     */
    @Test
    fun legacyRowIsConvertedToAllSevenEnabledAtTheDailyValue() = runTest {
        repository.addMonitoredApp(PACKAGE, LABEL, 45)

        val state = newViewModel().state.value

        assertEquals(DayOfWeek.entries.toSet(), state.enabled)
        assertEquals(WeeklyLimits().withAll(45), state.draft)
        assertTrue(state.alreadyMonitored)
    }

    /** The `Weekdays` chip turns the four weekdays on and leaves the weekend values as they were. */
    @Test
    fun weekdaysChipEnablesMonToFriWithoutTouchingWeekends() {
        val viewModel = newViewModel()

        viewModel.applyQuickSet(QuickSet.WEEKDAYS)

        val state = viewModel.state.value
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            state.enabled,
        )
        // The weekend values are remembered and untouched: disabled in the draft, still held in minutes.
        assertNull(state.draft.saturday)
        assertNull(state.draft.sunday)
        assertEquals(30, state.minutes.saturday)
        assertEquals(30, state.minutes.sunday)
    }

    /** The `Weekends` chip is the mirror image of `Weekdays`. */
    @Test
    fun weekendsChipEnablesSatAndSunWithoutTouchingWeekdays() {
        val viewModel = newViewModel()

        viewModel.applyQuickSet(QuickSet.WEEKENDS)

        val state = viewModel.state.value
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), state.enabled)
        assertNull(state.draft.monday)
        assertEquals(30, state.minutes.monday)
    }

    /**
     * `Clear` turns every day off but keeps the remembered values: the user asked for no limits
     * this week, not for the values they may want back later.
     */
    @Test
    fun clearKeepsRememberedMinutes() {
        val viewModel = newViewModel()
        viewModel.openDayPicker(DayOfWeek.MONDAY)
        viewModel.setPickerMinutes(45)
        viewModel.confirmPicker()

        viewModel.applyQuickSet(QuickSet.CLEAR)

        val state = viewModel.state.value
        assertTrue(state.enabled.isEmpty())
        assertNull(state.draft.monday)
        assertEquals(45, state.minutes.monday)
    }

    /**
     * Review Focus #2: after `Clear`, re-enabling a day restores the value it had, not a default.
     */
    @Test
    fun clearThenReEnableRestoresRememberedMinutes() {
        val viewModel = newViewModel()
        viewModel.openDayPicker(DayOfWeek.MONDAY)
        viewModel.setPickerMinutes(45)
        viewModel.confirmPicker()
        viewModel.applyQuickSet(QuickSet.CLEAR)

        viewModel.setDayEnabled(DayOfWeek.MONDAY, true)

        assertEquals(45, viewModel.state.value.draft.monday)
        assertTrue(DayOfWeek.MONDAY in viewModel.state.value.enabled)
    }

    /** The Switch on a row is the day's enabled bit: off drops it, on restores the remembered value. */
    @Test
    fun setDayEnabledTogglesTheSchedule() {
        val viewModel = newViewModel()

        viewModel.setDayEnabled(DayOfWeek.MONDAY, false)
        assertNull(viewModel.state.value.draft.monday)

        viewModel.setDayEnabled(DayOfWeek.MONDAY, true)
        assertEquals(30, viewModel.state.value.draft.monday)
    }

    /**
     * Day picker flow: Save on the picker writes only that day, and closing the picker without
     * saving writes nothing.
     */
    @Test
    fun dayPickerSaveWritesOnlyThatDayAndBackCancels() {
        val viewModel = newViewModel()

        viewModel.openDayPicker(DayOfWeek.WEDNESDAY)
        assertEquals(PickerTarget.Day(DayOfWeek.WEDNESDAY), viewModel.state.value.picker)
        viewModel.setPickerMinutes(90)
        viewModel.confirmPicker()

        val afterSave = viewModel.state.value
        assertEquals(90, afterSave.draft.wednesday)
        assertEquals(30, afterSave.draft.monday)
        assertNull(afterSave.picker)

        viewModel.openDayPicker(DayOfWeek.FRIDAY)
        viewModel.setPickerMinutes(120)
        viewModel.closePicker()

        assertEquals(30, viewModel.state.value.draft.friday)
        assertTrue(repository.apps.value.isEmpty())
    }

    /** All-days picker: one value on Save lands on all seven days. */
    @Test
    fun allDaysPickerWritesOneValueToSevenDays() {
        val viewModel = newViewModel()

        viewModel.openAllDaysPicker()
        assertEquals(PickerTarget.AllDays, viewModel.state.value.picker)
        viewModel.setPickerMinutes(90)
        viewModel.confirmPicker()

        val state = viewModel.state.value
        assertEquals(WeeklyLimits().withAll(90), state.draft)
        assertEquals(DayOfWeek.entries.toSet(), state.enabled)
    }

    /**
     * Final review #3: a wheel combination can compute beyond the stored ceiling (from a 24:00
     * position, tapping a minute yields 1499). The single writer clamps before the value is shown
     * or persisted, at both ends of the range.
     */
    @Test
    fun setPickerMinutesClampsTheWheelPositionToTheStoredRange() {
        val viewModel = newViewModel()
        viewModel.openDayPicker(DayOfWeek.MONDAY)

        viewModel.setPickerMinutes(1499)
        assertEquals(1440, viewModel.state.value.pickerMinutes)

        viewModel.setPickerMinutes(-5)
        assertEquals(0, viewModel.state.value.pickerMinutes)
    }

    /**
     * Review Focus #4: the ghost remove on the all-days picker leaves a real all-null schedule
     * with the weekly marker on — "no limit on any day" is a schedule, not the absence of one.
     */
    @Test
    fun allDaysRemoveWritesAllNullWithWeeklyMarkerTrue() = runTest {
        repository.addMonitoredAppWithWeeklyLimitsBlocking(PACKAGE, LABEL, WeeklyLimits())
        val viewModel = newViewModel()

        viewModel.openAllDaysPicker()
        viewModel.removeFromPicker()
        viewModel.save { }

        val app = repository.apps.value.single()
        assertTrue(app.usesWeeklyLimits)
        assertEquals(WeeklyLimits(), app.weeklyLimits)
        for (day in 16..22) {
            assertNull(effectiveLimitMinutes(app, LocalDate(2026, 11, day)))
        }
    }

    /**
     * The `Daily` chip opens the one all-days picker and does not touch the schedule by itself:
     * the duration is chosen in the picker and written by its Save.
     */
    @Test
    fun dailyChipOpensTheAllDaysPicker() {
        val viewModel = newViewModel()
        val enabledBefore = viewModel.state.value.enabled

        viewModel.applyQuickSet(QuickSet.DAILY)

        val state = viewModel.state.value
        assertEquals(PickerTarget.AllDays, state.picker)
        assertEquals(enabledBefore, state.enabled)
    }

    /**
     * Review Focus #3: the footer reads the weekly total through `formatUsage` with the opt-in
     * day-aware rendering, so a total over a day reads as days, not as a clamped "24 hours".
     */
    @Test
    fun footerUsesFormatUsageForTotalsOverADay() = runTest {
        repository.addMonitoredAppWithWeeklyLimitsBlocking(PACKAGE, LABEL, WeeklyLimits().withAll(240))
        val viewModel = newViewModel()

        assertEquals(
            "1 day 4 hours",
            formatUsage(viewModel.state.value.weeklyTotalMinutes * 60L, includeDays = true),
        )
    }

    /**
     * The insert is the only place a double tap could write twice, so the in-flight latch is the
     * guard and it is exercised with a parked write rather than a synchronous one.
     */
    @Test
    fun aSecondSaveWhileTheFirstIsInFlightIsIgnored() = runTest {
        val gated = GatedWeeklyAddRepository(repository)
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
     * The in-flight latch is not lowered on success: success ends the screen, but navigation is
     * not instantaneous, and lowering it would reopen the write window.
     */
    @Test
    fun theSuccessLatchStaysRaisedAndRefusesASecondWrite() = runTest {
        var attempts = 0
        val counting = object : ScreenTimeRepository by repository {
            override suspend fun addMonitoredAppWithWeeklyLimits(
                packageName: String,
                appName: String,
                weeklyLimits: WeeklyLimits,
            ): Boolean {
                attempts++
                return repository.addMonitoredAppWithWeeklyLimits(packageName, appName, weeklyLimits)
            }
        }
        val viewModel = newViewModel(repository = counting)
        var finishes = 0

        viewModel.save { finishes++ }

        val afterSuccess = viewModel.state.value
        assertFalse(afterSuccess.saveFailed)
        assertTrue(afterSuccess.saving)
        assertFalse(afterSuccess.canSave)

        viewModel.save { finishes++ }

        assertEquals(1, attempts)
        assertEquals(1, finishes)
        assertEquals(1, repository.apps.value.size)
    }

    @Test
    fun saveInsertsTheWeeklyRowWithTheDraftAndFinishesOnce() = runTest {
        val viewModel = newViewModel()
        var finishes = 0

        viewModel.save { finishes++ }

        val app = repository.apps.value.single()
        assertEquals(PACKAGE, app.packageName)
        assertEquals(LABEL, app.appName)
        assertTrue(app.usesWeeklyLimits)
        assertEquals(viewModel.state.value.draft, app.weeklyLimits)
        assertEquals(1, finishes)
    }

    /**
     * The app became monitored *behind* this screen: the insert is refused, and the safe answer
     * is a conflict — no navigation, no claim of success, the row keeps its values.
     */
    @Test
    fun insertConflictAndUpdateFailureAreReported() = runTest {
        val conflictVm = newViewModel()
        repository.addMonitoredApp(PACKAGE, "Instagram", 30)
        var conflictFinished = false

        conflictVm.save { conflictFinished = true }

        val conflictState = conflictVm.state.value
        assertTrue(conflictState.saveConflict)
        assertFalse(conflictState.saveFailed)
        assertFalse(conflictFinished)
        assertFalse(conflictState.saving)
        assertEquals(30, repository.apps.value.single().dailyLimitMinutes)

        // The row the other flow created is monitored, so the editor opens in update shape.
        repository.apps.value = emptyList()
        repository.addMonitoredAppWithWeeklyLimitsBlocking(PACKAGE, LABEL, WeeklyLimits())
        var tampered = false
        val tampering = object : ScreenTimeRepository by repository {
            override suspend fun updateWeeklyLimits(packageName: String, weeklyLimits: WeeklyLimits) {
                repository.updateWeeklyLimits(packageName, weeklyLimits)
                if (tampered) {
                    // A change between the write and the re-read: the save must verify and fail.
                    repository.apps.update { rows ->
                        rows.map { row ->
                            if (row.packageName == packageName) {
                                row.copy(weeklyLimits = WeeklyLimits(monday = 99))
                            } else {
                                row
                            }
                        }
                    }
                }
            }
        }
        val failingVm = newViewModel(repository = tampering)
        tampered = true
        var failureFinished = false

        failingVm.save { failureFinished = true }

        val failureState = failingVm.state.value
        assertTrue(failureState.saveFailed)
        assertFalse(failureState.saveConflict)
        assertFalse(failureFinished)
        assertFalse(failureState.saving)
        assertTrue(failureState.canSave)
    }

    @Test
    fun retryRecoversFromAThrowingRead() = runTest {
        val flaky = WeeklyLimitFlakyReadRepository(repository)
        val viewModel = newViewModel(repository = flaky)

        assertTrue(viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)

        flaky.failing.value = false
        viewModel.retry()

        val state = viewModel.state.value
        assertFalse(state.error)
        assertFalse(state.loading)
        assertEquals(LABEL, state.appName)
        assertEquals(PACKAGE, state.packageName)
    }

    /**
     * A missing argument is navigation corruption, not a failed read: there is nothing to retry,
     * so the screen must say so and refuse to save rather than invent a package.
     */
    @Test
    fun missingArgumentIsTerminal() = runTest {
        val viewModel = newViewModel(packageName = null)
        val state = viewModel.state.value
        var finished = false

        assertTrue(state.missingArgument)
        assertFalse(state.loading)
        assertFalse(state.canSave)

        viewModel.save { finished = true }

        assertFalse(finished)
        assertTrue(repository.apps.value.isEmpty())
    }
}
