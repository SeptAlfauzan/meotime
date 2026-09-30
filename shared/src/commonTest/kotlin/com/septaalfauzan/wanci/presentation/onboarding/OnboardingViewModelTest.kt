package com.septaalfauzan.wanci.presentation.onboarding

import com.septaalfauzan.wanci.FakeRepository
import com.septaalfauzan.wanci.FakeTodayKey
import com.septaalfauzan.wanci.domain.NotificationPermissionRequest
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
import kotlin.test.assertNull
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

    override fun openNotificationSettings() {
        opened += "notificationSettings"
    }
}

private class FakeNotificationPermissionRequest : NotificationPermissionRequest {
    var requests = 0

    /** When true the fake answers immediately, the way a granted permission would. */
    var grantImmediately: Boolean = false

    override fun request(onResult: () -> Unit) {
        requests++
        if (grantImmediately) onResult()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val repository = FakeRepository()
    private val permissions = FakePermissionStatusProvider()
    private val navigator = RecordingPermissionNavigator()
    private val notificationRequest = FakeNotificationPermissionRequest()
    private val started = mutableListOf<String>()
    private lateinit var viewModel: OnboardingViewModel

    private fun newViewModel(): OnboardingViewModel = OnboardingViewModel(
        useCases = WanciUseCases(repository, FakeTodayKey("2026-09-25")),
        permissionStatusProvider = permissions,
        permissionNavigator = navigator,
        notificationPermissionRequest = notificationRequest,
        startMonitoring = { started += "start" },
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
        permissions.current = PermissionStatus(usageAccess = true, overlay = true, notification = true)

        viewModel.refresh()

        assertEquals(1, permissions.calls)
        assertTrue(viewModel.state.value.permissionStatus.allGranted)
    }

    @Test
    fun partialPermissionsDoNotCompleteOnboarding() {
        permissions.current = PermissionStatus(usageAccess = true, overlay = false, notification = true)

        viewModel.refresh()

        val status = viewModel.state.value.permissionStatus
        assertTrue(status.usageAccess)
        assertFalse(status.overlay)
        assertTrue(status.notification)
        assertFalse(status.allGranted)
    }

    @Test
    fun completeOnboardingPersistsFlagNotifiesAndStartsMonitoring() {
        var done = false

        viewModel.completeOnboarding { done = true }

        assertEquals("true", repository.syncValues["onboardingComplete"])
        assertFalse(viewModel.state.value.saving, "the latch is lowered on the way out, not left raised")
        assertFalse(viewModel.state.value.saveFailed)
        assertTrue(done)
        assertEquals(listOf("start"), started, "monitoring must be live the moment onboarding ends")
    }

    /**
     * A write that throws must not be treated as an onboarding the user already finished.
     *
     * The flag is the only durable record of this screen having been completed, so starting
     * monitoring and navigating away on a failed write would leave an app that blocks every app it
     * limits with nothing to show for it, and no way back to this screen to retry.
     */
    @Test
    fun aFailedOnboardingWriteReportsAndLeavesTheUserOnTheScreen() {
        repository.failOnPutSync = true
        var done = false

        viewModel.completeOnboarding { done = true }

        val state = viewModel.state.value
        assertTrue(state.saveFailed, "the failure has to reach the screen, or the retry looks like a bug")
        assertFalse(state.saving, "a latch left raised is a button nobody can press again")
        assertFalse(done, "navigating away strands the user on a screen with no flag and no way back")
        assertEquals(emptyList<String>(), started, "monitoring must not start against a flag that was never written")
        assertNull(repository.syncValues["onboardingComplete"])
    }

    /**
     * The failure is recoverable rather than terminal, and the flag that reported it is retired by
     * the press that replaces it rather than lingering over a save that worked.
     */
    @Test
    fun retryingAfterAFailedOnboardingWriteFinishesAndClearsTheFailure() {
        repository.failOnPutSync = true
        var done = false
        viewModel.completeOnboarding { done = true }
        assertTrue(viewModel.state.value.saveFailed)

        repository.failOnPutSync = false
        viewModel.completeOnboarding { done = true }

        assertEquals("true", repository.syncValues["onboardingComplete"])
        assertFalse(viewModel.state.value.saveFailed)
        assertFalse(viewModel.state.value.saving)
        assertTrue(done)
        assertEquals(listOf("start"), started)
    }

    @Test
    fun requestMethodsDelegateToNavigatorAndRequest() {
        viewModel.requestUsageAccess()
        viewModel.requestOverlay()
        viewModel.requestNotification()

        assertEquals(listOf("usage", "overlay"), navigator.opened)
        assertEquals(1, notificationRequest.requests)
    }

    @Test
    fun aNotificationAnswerRefreshesThePermissionSnapshot() {
        permissions.current = PermissionStatus(usageAccess = true, overlay = true, notification = true)
        notificationRequest.grantImmediately = true

        viewModel.requestNotification()

        assertEquals(1, permissions.calls, "the grant is only visible after a re-read")
        assertTrue(viewModel.state.value.permissionStatus.notification)
    }
}
