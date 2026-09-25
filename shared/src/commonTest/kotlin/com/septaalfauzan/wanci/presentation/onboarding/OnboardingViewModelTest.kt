package com.septaalfauzan.wanci.presentation.onboarding

import com.septaalfauzan.wanci.FakeRepository
import com.septaalfauzan.wanci.FakeTodayKey
import com.septaalfauzan.wanci.domain.PermissionNavigator
import com.septaalfauzan.wanci.domain.PermissionStatus
import com.septaalfauzan.wanci.domain.PermissionStatusProvider
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakePermissionStatusProvider(
    var current: PermissionStatus = PermissionStatus(false, false, false),
) : PermissionStatusProvider {
    var calls = 0
    override fun status(): PermissionStatus {
        calls++
        return current
    }
}

private class RecordingPermissionNavigator : PermissionNavigator {
    val opened = mutableListOf<String>()

    override fun openUsageAccess() {
        opened += "usage"
    }

    override fun openOverlay() {
        opened += "overlay"
    }

    override fun openAccessibility() {
        opened += "accessibility"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val repository = FakeRepository()
    private val permissions = FakePermissionStatusProvider()
    private val navigator = RecordingPermissionNavigator()
    private lateinit var viewModel: OnboardingViewModel

    private fun newViewModel(): OnboardingViewModel = OnboardingViewModel(
        useCases = WanciUseCases(repository, FakeTodayKey("2026-09-25")),
        permissionStatusProvider = permissions,
        permissionNavigator = navigator,
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = newViewModel()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsWithNoPermissionsAndIncompleteOnboarding() {
        val state = viewModel.state.value
        assertFalse(state.onboardingComplete)
        assertFalse(state.permissionStatus.allGranted)
        assertFalse(state.saving)
    }

    @Test
    fun readsPersistedOnboardingFlagOnInit() {
        repository.syncValues["onboardingComplete"] = "true"

        assertTrue(newViewModel().state.value.onboardingComplete)
    }

    @Test
    fun refreshPublishesLatestPermissionSnapshot() {
        permissions.current = PermissionStatus(usageAccess = true, overlay = true, accessibility = true)

        viewModel.refresh()

        assertEquals(1, permissions.calls)
        assertTrue(viewModel.state.value.permissionStatus.allGranted)
    }

    @Test
    fun partialPermissionsDoNotCompleteOnboarding() {
        permissions.current = PermissionStatus(usageAccess = true, overlay = false, accessibility = true)

        viewModel.refresh()

        val status = viewModel.state.value.permissionStatus
        assertTrue(status.usageAccess)
        assertFalse(status.overlay)
        assertTrue(status.accessibility)
        assertFalse(status.allGranted)
    }

    @Test
    fun completeOnboardingPersistsFlagAndNotifies() {
        var done = false

        viewModel.completeOnboarding { done = true }

        assertEquals("true", repository.syncValues["onboardingComplete"])
        assertTrue(viewModel.state.value.saving)
        assertTrue(done)
    }

    @Test
    fun requestMethodsDelegateToNavigator() {
        viewModel.requestUsageAccess()
        viewModel.requestOverlay()
        viewModel.requestAccessibility()

        assertEquals(listOf("usage", "overlay", "accessibility"), navigator.opened)
    }
}
