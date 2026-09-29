package com.septaalfauzan.wanci.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.septaalfauzan.wanci.domain.NotificationPermissionRequest
import com.septaalfauzan.wanci.domain.PermissionNavigator
import com.septaalfauzan.wanci.domain.PermissionStatus
import com.septaalfauzan.wanci.domain.PermissionStatusProvider
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Onboarding state machine:
 *
 * - [onboardingComplete] mirrors the persisted `onboardingComplete` sync value and is observed from
 *   the repository, so it survives process death and decides whether onboarding is shown at all.
 * - [permissionStatus] is a snapshot: the platform cannot push changes while the app is backgrounded
 *   on a Settings page, so it only advances on [OnboardingViewModel.refresh].
 * - [saving] is true for the duration of the persist in [OnboardingViewModel.completeOnboarding]
 *   and is lowered on every way out of it. A write that threw used to leave it raised for good,
 *   which is a button nobody can press again and an error nobody is shown — the only way out of
 *   that state was reinstalling.
 * - [saveFailed] is what makes the retry a *visible* one, and it is cleared as the next attempt
 *   starts rather than on its own, so a failure cannot outlive the press that replaced it.
 */
data class OnboardingUiState(
    val onboardingComplete: Boolean = false,
    val permissionStatus: PermissionStatus = PermissionStatus(false, false, false),
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
)

class OnboardingViewModel(
    private val useCases: WanciUseCases,
    private val permissionStatusProvider: PermissionStatusProvider,
    private val permissionNavigator: PermissionNavigator,
    private val notificationPermissionRequest: NotificationPermissionRequest,
    private val startMonitoring: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            useCases.observeOnboardingComplete().collect { complete ->
                _state.value = _state.value.copy(onboardingComplete = complete)
            }
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(permissionStatus = permissionStatusProvider.status())
    }

    fun requestUsageAccess() = permissionNavigator.openUsageAccess()

    fun requestOverlay() = permissionNavigator.openOverlay()

    fun requestNotification() = notificationPermissionRequest.request { refresh() }

    fun completeOnboarding(onDone: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(saving = true, saveFailed = false) }
            val persisted = try {
                useCases.putSyncValue("onboardingComplete", "true")
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The flag is the only record that onboarding finished, so leaving this screen
                // without it means the next launch shows onboarding again and nothing is ever
                // monitored. A failed write therefore cannot be papered over by navigating: the
                // user stays, is told, and presses again — and the retry is safe because the write
                // is an idempotent upsert of one key.
                _state.update { it.copy(saveFailed = true) }
                false
            } finally {
                // Lowered on every path, success included. Unlike the set-limit editor there is
                // nothing to latch here: the persist, `startMonitoring()` and `onDone()` run with
                // no suspension point between them, so a second tap cannot interleave, and a flag
                // left raised for the life of the screen is the stuck-button state this removes.
                _state.update { it.copy(saving = false) }
            }
            if (!persisted) return@launch
            // Started here, not from the dashboard: onboarding is the one moment the three
            // permissions are known to be granted, and the service has to be alive before the user
            // leaves the flow. The persisted flag above is what makes it idempotent for the
            // `MainActivity` start path.
            startMonitoring()
            onDone()
        }
    }
}
