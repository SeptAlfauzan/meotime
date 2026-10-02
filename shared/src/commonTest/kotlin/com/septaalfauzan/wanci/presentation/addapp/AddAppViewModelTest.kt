@file:OptIn(ExperimentalCoroutinesApi::class)

package com.septaalfauzan.wanci.presentation.addapp

import com.septaalfauzan.wanci.FakeRepository
import com.septaalfauzan.wanci.FakeTodayKey
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepository
import com.septaalfauzan.wanci.domain.AppIconProvider
import com.septaalfauzan.wanci.domain.InstalledApp
import com.septaalfauzan.wanci.domain.InstalledAppsProvider
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A repository that never answers. Delegation keeps the fake down to the one method under test,
 * so "nothing observed yet" is expressed as silence rather than as a second implementation.
 */
private class SilentRepository(private val delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    override fun observeMonitoredApps(): Flow<List<MonitoredApp>> = emptyFlow()
}

/** Fails the observed-apps flow while [failing] is true; the screen must survive and retry. */
private class FlakyRepository(private val delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    val failing = MutableStateFlow(true)

    override fun observeMonitoredApps(): Flow<List<MonitoredApp>> = failing.flatMapLatest { isFailing ->
        if (isFailing) flow { throw IllegalStateException("database unavailable") }
        else delegate.observeMonitoredApps()
    }
}

/** Silent until [opened] flips, then answers with the delegate's list: a read that arrives late. */
private class GatedRepository(private val delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    val opened = MutableStateFlow(false)

    override fun observeMonitoredApps(): Flow<List<MonitoredApp>> = opened.flatMapLatest { isOpen ->
        if (isOpen) delegate.observeMonitoredApps() else emptyFlow()
    }
}

class AddAppViewModelTest {

    private val repository = FakeRepository()
    private val today = FakeTodayKey("2026-09-25")

    private val installed = InstalledAppsProvider {
        listOf(
            InstalledApp("com.instagram.android", "Instagram"),
            InstalledApp("com.google.android.youtube", "YouTube"),
            InstalledApp("com.example.tiktok", "TikTok"),
        )
    }

    private val iconProvider = AppIconProvider { null }

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
        installedAppsProvider: InstalledAppsProvider = installed,
    ) = AddAppViewModel(
        installedAppsProvider = installedAppsProvider,
        appIconProvider = iconProvider,
        useCases = WanciUseCases(repository, today),
    )

    @Test
    fun listsEveryInstalledAppThePlatformProviderOffers() {
        val state = newViewModel().state.value

        assertFalse(state.loading)
        assertFalse(state.error)
        assertEquals(
            listOf("com.instagram.android", "com.google.android.youtube", "com.example.tiktok"),
            state.apps.map { it.packageName },
        )
    }

    /**
     * The provider owns the ordering (Android sorts by label); filtering re-derives the list and
     * must not reshuffle it.
     */
    @Test
    fun filteringKeepsTheProviderOrdering() {
        val viewModel = newViewModel()

        viewModel.updateQuery("o")

        assertEquals(
            listOf("com.google.android.youtube", "com.example.tiktok"),
            viewModel.state.value.apps.map { it.packageName },
        )
    }

    @Test
    fun searchMatchesTheLabelCaseInsensitivelyAndIgnoresSurroundingSpace() {
        val viewModel = newViewModel()

        viewModel.updateQuery("  INSTAGRAM  ")

        val state = viewModel.state.value
        assertEquals(1, state.apps.size)
        assertEquals("Instagram", state.apps.first().appName)
        assertEquals("  INSTAGRAM  ", state.query)
    }

    @Test
    fun clearingTheQueryBringsTheWholeListBack() {
        val viewModel = newViewModel()
        viewModel.updateQuery("instagram")

        viewModel.updateQuery("")

        assertEquals(3, viewModel.state.value.apps.size)
    }

    @Test
    fun aQueryThatMatchesNothingIsAnEmptyListNotAFailure() {
        val viewModel = newViewModel()

        viewModel.updateQuery("zzzz")

        val state = viewModel.state.value
        assertTrue(state.apps.isEmpty())
        assertFalse(state.loading)
        assertFalse(state.error)
    }

    @Test
    fun selectionIsSingleAndReplaceable() {
        val viewModel = newViewModel()
        assertNull(viewModel.state.value.selected)

        viewModel.select("com.instagram.android")
        assertEquals("com.instagram.android", viewModel.state.value.selected)

        viewModel.select("com.example.tiktok")
        assertEquals("com.example.tiktok", viewModel.state.value.selected)
    }

    @Test
    fun alreadyMonitoredAppsAreHidden() = runTest {
        repository.addMonitoredApp("com.instagram.android", "Instagram", 30)

        val packages = newViewModel().state.value.apps.map { it.packageName }

        assertNull(packages.firstOrNull { it == "com.instagram.android" })
        assertTrue(packages.contains("com.google.android.youtube"))
    }

    /**
     * An app monitored from anywhere else stops being offered while it is still the selection, and
     * the selection stops being confirmable with it: the row is gone and the repository's insert
     * would be rejected, so an enabled action would promise a step that silently does nothing.
     */
    @Test
    fun monitoringAnAppElsewhereRemovesItFromTheListAndDisablesConfirm() = runTest {
        val viewModel = newViewModel()
        viewModel.select("com.google.android.youtube")
        assertTrue(viewModel.state.value.canConfirm)

        repository.addMonitoredApp("com.google.android.youtube", "YouTube", 30)

        val state = viewModel.state.value
        assertEquals(listOf("com.instagram.android", "com.example.tiktok"), state.apps.map { it.packageName })
        assertEquals("com.google.android.youtube", state.selected)
        assertFalse(state.canConfirm)
    }

    /**
     * The search filter is the user's own doing, not a change in what is offerable, so it must not
     * disable the action: a selection the query hides stays confirmable, or clearing the search
     * would be the only way to finish.
     */
    @Test
    fun filteringTheSelectedAppOutKeepsConfirmAvailable() = runTest {
        val viewModel = newViewModel()
        viewModel.select("com.example.tiktok")

        viewModel.updateQuery("instagram")

        val state = viewModel.state.value
        assertEquals(listOf("com.instagram.android"), state.apps.map { it.packageName })
        assertEquals("com.example.tiktok", state.selected)
        assertTrue(state.canConfirm)
    }

    /** A selection the repository would reject as a duplicate is never handed to the limit step. */
    @Test
    fun confirmGoesNowhereForASelectionThatBecameMonitoredElsewhere() = runTest {
        val viewModel = newViewModel()
        viewModel.select("com.example.tiktok")
        repository.addMonitoredApp("com.example.tiktok", "TikTok", 30)
        var navigated = false

        viewModel.confirm { navigated = true }

        assertFalse(navigated)
    }

    /** Every eligible app is gone: a real zero state, not a failed read and not a blank screen. */
    @Test
    fun everyInstalledAppMonitoredIsAZeroState() = runTest {
        repository.addMonitoredApp("com.instagram.android", "Instagram", 30)
        repository.addMonitoredApp("com.google.android.youtube", "YouTube", 30)
        repository.addMonitoredApp("com.example.tiktok", "TikTok", 30)

        val state = newViewModel().state.value

        assertTrue(state.apps.isEmpty())
        assertFalse(state.loading)
        assertFalse(state.error)
        // Distinguishes "nothing left to add" from "this device offers nothing": both are an empty
        // list, and the empty state must not blame the user's monitoring for the second one.
        assertTrue(state.hasPlatformApps)
    }

    @Test
    fun noInstalledAppsIsAlsoAZeroState() {
        val state = newViewModel(installedAppsProvider = InstalledAppsProvider { emptyList() }).state.value

        assertTrue(state.apps.isEmpty())
        assertFalse(state.loading)
        assertFalse(state.error)
        assertFalse(state.hasPlatformApps)
    }

    /** Silence is not failure: an unanswered read must keep the skeleton up, not raise an error. */
    @Test
    fun staysInLoadingUntilTheFirstEmission() {
        val state = newViewModel(SilentRepository(repository)).state.value

        assertTrue(state.loading)
        assertTrue(state.apps.isEmpty())
        assertFalse(state.error)
    }

    @Test
    fun reportsUpstreamFailureAndRecoversOnRetry() = runTest {
        repository.addMonitoredApp("com.instagram.android", "Instagram", 30)
        val flaky = FlakyRepository(repository)
        val viewModel = newViewModel(flaky)

        assertTrue(viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)

        flaky.failing.value = false
        viewModel.retry()

        val state = viewModel.state.value
        assertFalse(state.error)
        assertFalse(state.loading)
        assertEquals(listOf("com.google.android.youtube", "com.example.tiktok"), state.apps.map { it.packageName })
    }

    /**
     * The error state replaces the data rather than sitting on top of it, as the dashboard's does:
     * the screen renders the error *instead of* the list, so a retained list is state nothing draws
     * and every other reader of this state would take it for the current answer.
     */
    @Test
    fun aFailureAfterAGoodAnswerDropsTheListInsteadOfKeepingItUnderTheError() {
        val flaky = FlakyRepository(repository)
        flaky.failing.value = false
        val viewModel = newViewModel(flaky)
        viewModel.select("com.example.tiktok")
        assertEquals(3, viewModel.state.value.apps.size)

        flaky.failing.value = true

        val state = viewModel.state.value
        assertTrue(state.error)
        assertFalse(state.loading)
        assertTrue(state.apps.isEmpty())
        assertNull(state.selected)
        assertFalse(state.canConfirm)
    }

    /**
     * A read that answers late is not an error and must not need a retry to be seen.
     */
    @Test
    fun aReadThatAnswersLateFillsTheListWithoutARetry() = runTest {
        val gated = GatedRepository(repository)
        val viewModel = newViewModel(gated)
        assertTrue(viewModel.state.value.loading)

        gated.opened.value = true

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertFalse(state.error)
        assertEquals(3, state.apps.size)
    }

    @Test
    fun retryKeepsAPopulatedListOnScreenWhileItRereads() {
        val viewModel = newViewModel()
        viewModel.select("com.example.tiktok")

        viewModel.retry()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertFalse(state.error)
        assertEquals(3, state.apps.size)
        assertEquals("com.example.tiktok", state.selected)
    }

    @Test
    fun confirmHandsTheSelectedPackageToTheLimitStep() {
        val viewModel = newViewModel()
        var navigatedTo: String? = null
        viewModel.select("com.example.tiktok")

        viewModel.confirm { navigatedTo = it }

        assertEquals("com.example.tiktok", navigatedTo)
    }

    @Test
    fun confirmWithoutASelectionGoesNowhere() {
        val viewModel = newViewModel()
        var navigated = false

        viewModel.confirm { navigated = true }

        assertFalse(navigated)
    }

    /**
     * The add-app step does not persist: the limit step owns the insert, so writing here would
     * create the row twice and lock the default limit in ahead of the user's choice.
     */
    @Test
    fun confirmDoesNotPersistAnything() = runTest {
        val viewModel = newViewModel()
        viewModel.select("com.example.tiktok")

        viewModel.confirm { }

        assertTrue(repository.apps.value.isEmpty())
    }

    /**
     * A second tap on an already-confirmed selection still hands the same package over: the
     * destination is idempotent, so the guard lives there rather than in a latched VM flag.
     */
    @Test
    fun confirmIsRepeatableForTheSameSelection() {
        val viewModel = newViewModel()
        val navigated = mutableListOf<String>()
        viewModel.select("com.example.tiktok")

        viewModel.confirm { navigated += it }
        viewModel.confirm { navigated += it }

        assertEquals(listOf("com.example.tiktok", "com.example.tiktok"), navigated)
    }
}
