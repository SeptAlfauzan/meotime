@file:OptIn(ExperimentalCoroutinesApi::class)

package com.septaalfauzan.wanci.presentation.dashboard

import com.septaalfauzan.wanci.FakeRepository
import com.septaalfauzan.wanci.FakeTodayKey
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepository
import com.septaalfauzan.wanci.domain.InstalledAppCheck
import com.septaalfauzan.wanci.domain.model.MonitoredApp
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A repository that never answers. Delegation keeps the fake down to the one method under test,
 * so the "nothing observed yet" case does not need a second full implementation.
 */
private class SilentRepository(private val delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    override fun observeMonitoredApps(): Flow<List<MonitoredApp>> = emptyFlow()
}

/** Fails the observed-apps flow while [failing] is true; the dashboard must survive and retry. */
private class FlakyRepository(private val delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    val failing = MutableStateFlow(true)

    override fun observeMonitoredApps(): Flow<List<MonitoredApp>> = failing.flatMapLatest { isFailing ->
        if (isFailing) flow { throw IllegalStateException("database unavailable") }
        else delegate.observeMonitoredApps()
    }
}

class DashboardViewModelTest {

    private val repository = FakeRepository()
    private val today = FakeTodayKey("2026-09-25")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        repository: ScreenTimeRepository = this.repository,
        installed: (String) -> Boolean = { true },
    ) = DashboardViewModel(
        useCases = WanciUseCases(repository, today),
        installedAppCheck = InstalledAppCheck { installed(it) },
    )

    @Test
    fun exposesTodayUsageAndUninstalledFlag() = runTest {
        repository.addMonitoredApp("com.instagram.android", "Instagram", 30)
        repository.recordUsageSeconds("com.instagram.android", 42, "2026-09-25")
        repository.addMonitoredApp("com.example.gone", "Gone App", 60)

        val state = newViewModel { it != "com.example.gone" }.state.value

        assertEquals(2, state.apps.size)
        assertEquals(42L, state.apps.first().todayUsageSeconds)
        assertTrue(state.apps.first().installed)
        assertFalse(state.apps.last().installed)
    }

    @Test
    fun staysInLoadingUntilTheFirstEmission() {
        val state = newViewModel(SilentRepository(repository)).state.value

        assertTrue(state.loading)
        assertTrue(state.apps.isEmpty())
        assertFalse(state.error)
    }

    @Test
    fun usageUpdatesLiveWhileTheServiceRecordsIt() = runTest {
        repository.addMonitoredApp("com.instagram.android", "Instagram", 30)
        val viewModel = newViewModel()
        assertEquals(0L, viewModel.state.value.apps.first().todayUsageSeconds)

        repository.recordUsageSeconds("com.instagram.android", 7, "2026-09-25")

        assertEquals(7L, viewModel.state.value.apps.first().todayUsageSeconds)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun midnightRolloverResetsUsageToZero() = runTest {
        repository.addMonitoredApp("com.instagram.android", "Instagram", 30)
        repository.recordUsageSeconds("com.instagram.android", 42, "2026-09-25")
        val viewModel = newViewModel()
        assertEquals(42L, viewModel.state.value.apps.first().todayUsageSeconds)

        // Stands in for the real repository re-querying `daily_usage` for the new date when
        // `TodayKey.dateFlow()` emits past midnight (covered by ScreenTimeRepositoryImplTest).
        repository.rollOverToNewDay()

        assertEquals(0L, viewModel.state.value.apps.first().todayUsageSeconds)
    }

    @Test
    fun reportsUpstreamFailureAndRecoversOnRefresh() = runTest {
        repository.addMonitoredApp("com.instagram.android", "Instagram", 30)
        val flaky = FlakyRepository(repository)
        val viewModel = newViewModel(flaky)

        assertTrue(viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)

        flaky.failing.value = false
        viewModel.refresh()

        val state = viewModel.state.value
        assertFalse(state.error)
        assertFalse(state.loading)
        assertEquals(1, state.apps.size)
    }

    @Test
    fun usageRowReportsRemainingTimeAndFullProgressBelowTheLimit() = runTest {
        val row = dashboardRow(app(usageMinutes = 20, limitMinutes = 60))

        assertFalse(row.limitReached)
        assertEquals(40L, row.remainingMinutes)
        assertEquals(1f / 3f, row.progress, absoluteTolerance = 0.0001f)
    }

    @Test
    fun usageRowFlagsTheLimitReachedExactlyAtTheLimit() = runTest {
        val row = dashboardRow(app(usageMinutes = 60, limitMinutes = 60))

        assertTrue(row.limitReached)
        assertEquals(0L, row.remainingMinutes)
        assertEquals(1f, row.progress)
    }

    @Test
    fun usageRowClampsProgressAndRemainingWhenOverTheLimit() = runTest {
        val row = dashboardRow(app(usageMinutes = 95, limitMinutes = 60))

        assertTrue(row.limitReached)
        assertEquals(0L, row.remainingMinutes)
        assertEquals(1f, row.progress)
    }

    @Test
    fun usageRowSurvivesANonPositiveLimit() = runTest {
        val row = dashboardRow(app(usageMinutes = 5, limitMinutes = 0))

        assertTrue(row.limitReached)
        assertEquals(0L, row.remainingMinutes)
        assertEquals(1f, row.progress)
    }

    @Test
    fun pausedRowReportsNoBudgetBecauseNothingIsMeasuringIt() = runTest {
        val row = dashboardRow(app(usageMinutes = 0, limitMinutes = 60, enabled = false))

        assertTrue(row.paused)
        assertEquals(0f, row.progress)
        assertEquals(0L, row.remainingMinutes)
        assertFalse(row.limitReached)
    }

    @Test
    fun pausedRowKeepsALimitItHadAlreadyReached() = runTest {
        val row = dashboardRow(app(usageMinutes = 95, limitMinutes = 60, enabled = false))

        assertTrue(row.paused)
        assertTrue(row.limitReached)
        assertEquals(0f, row.progress)
    }

    /**
     * The wording is one function for the two screens that report the same row — the dashboard list
     * and the app's own settings page — so every branch is pinned here. A further copy of this
     * mapping in a screen is how the list and the settings page start describing the same app
     * differently.
     */
    @Test
    fun statusLabelNamesEveryStateARowCanBeIn() = runTest {
        assertEquals("Limit reached", appStatusLabel(dashboardRow(app(usageMinutes = 60, limitMinutes = 60))))
        assertEquals(
            "Monitoring paused",
            appStatusLabel(dashboardRow(app(usageMinutes = 10, limitMinutes = 60, enabled = false))),
        )
        assertEquals("1 minute remaining", appStatusLabel(dashboardRow(app(usageMinutes = 59, limitMinutes = 60))))
        assertEquals("5 minutes remaining", appStatusLabel(dashboardRow(app(usageMinutes = 55, limitMinutes = 60))))
        // A limit that cannot be measured reports reached, and a paused app that had already reached
        // its limit keeps saying so: both are real readings, not stale ones.
        assertEquals("Limit reached", appStatusLabel(dashboardRow(app(usageMinutes = 0, limitMinutes = 0))))
        assertEquals(
            "Limit reached",
            appStatusLabel(dashboardRow(app(usageMinutes = 95, limitMinutes = 60, enabled = false))),
        )
        // The sub-minute states, which need seconds to express. A paused app's remaining time is
        // zero by construction, so the zero branch has to sit below `paused` or every paused row
        // would start claiming to have under a minute left of a budget nothing is spending.
        assertEquals(
            "Less than a minute remaining",
            appStatusLabel(dashboardRow(appAtSeconds(usageSeconds = 1_770L, limitMinutes = 30))),
        )
        assertEquals(
            "Monitoring paused",
            appStatusLabel(dashboardRow(appAtSeconds(usageSeconds = 1_770L, limitMinutes = 30, enabled = false))),
        )
    }

    @Test
    fun refreshKeepsAPopulatedListOnScreenWhileItReReads() = runTest {
        repository.addMonitoredApp("com.instagram.android", "Instagram", 30)
        repository.recordUsageSeconds("com.instagram.android", 42, "2026-09-25")
        val viewModel = newViewModel()

        viewModel.refresh()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertFalse(state.error)
        assertEquals(1, state.apps.size)
        assertEquals(42L, state.apps.first().todayUsageSeconds)
    }

    @Test
    fun refreshRaisesLoadingOnlyWhenThereIsNothingToShow() = runTest {
        val viewModel = newViewModel(SilentRepository(repository))

        viewModel.refresh()

        assertTrue(viewModel.state.value.loading)
    }

    @Test
    fun dashboardRowComparesSecondsAgainstAMinuteLimitWithoutDividingBySixty() {
        // Deliberately not routed through the `app(...)` helper, which takes minutes. The other
        // ten dashboardRow cases use whole-minute fixtures and would pass even with the
        // conversion missing; this one is the only thing standing between a silent 60x error and
        // a shipped row that reads "limit reached" for an app at 29m30s.
        val row = dashboardRow(
            MonitoredApp(
                id = 1,
                packageName = "com.instagram.android",
                appName = "Instagram",
                dailyLimitMinutes = 30,
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
                todayUsageSeconds = 1_770L,
            ),
        )

        assertFalse(row.limitReached, "29m30s against a 30m limit is not over the limit")
        assertEquals(0L, row.remainingMinutes, "the half-minute left rounds down to no minutes")
        assertTrue(row.progress < 1f, "the bar must not be full at 29m30s")
    }

    private fun app(usageMinutes: Long, limitMinutes: Int, enabled: Boolean = true) = MonitoredApp(
        id = 1,
        packageName = "com.instagram.android",
        appName = "Instagram",
        dailyLimitMinutes = limitMinutes,
        enabled = enabled,
        createdAt = 0,
        updatedAt = 0,
        // The helper speaks minutes because that is how these fixtures read; the model speaks
        // seconds because that is what is stored. Converting here keeps sixteen call sites
        // unchanged and honest at the same time.
        todayUsageSeconds = usageMinutes * 60,
    )

    /**
     * [app] speaks minutes and so cannot express a sub-minute remainder — the one row state that
     * second-granularity storage makes reachable, and the only place the minute-denominated
     * `remainingMinutes` can read as zero without the limit having been reached.
     */
    private fun appAtSeconds(usageSeconds: Long, limitMinutes: Int, enabled: Boolean = true) = MonitoredApp(
        id = 1,
        packageName = "com.instagram.android",
        appName = "Instagram",
        dailyLimitMinutes = limitMinutes,
        enabled = enabled,
        createdAt = 0,
        updatedAt = 0,
        todayUsageSeconds = usageSeconds,
    )
}
