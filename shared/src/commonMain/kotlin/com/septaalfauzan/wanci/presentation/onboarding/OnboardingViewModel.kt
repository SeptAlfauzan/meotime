package com.septaalfauzan.wanci.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.septaalfauzan.wanci.domain.PermissionNavigator
import com.septaalfauzan.wanci.domain.PermissionStatus
import com.septaalfauzan.wanci.domain.PermissionStatusProvider
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Onboarding state machine:
 *
 * - [onboardingComplete] mirrors the persisted `onboardingComplete` sync value and is observed from
 *   the repository, so it survives process death and decides whether onboarding is shown at all.
 * - [permissionStatus] is a snapshot: the platform cannot push changes while the app is backgrounded
 *   on a Settings page, so it only advances on [OnboardingViewModel.refresh].
 * - [saving] is true for the duration of the persist in [OnboardingViewModel.completeOnboarding].
 */
data class OnboardingUiState(
    val onboardingComplete: Boolean = false,
    val permissionStatus: PermissionStatus = PermissionStatus(false, false, false),
    val saving: Boolean = false,
)

class OnboardingViewModel(
    private val useCases: WanciUseCases,
    private val permissionStatusProvider: PermissionStatusProvider,
    private val permissionNavigator: PermissionNavigator,
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

    fun requestAccessibility() = permissionNavigator.openAccessibility()

    fun completeOnboarding(onDone: () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true)
            useCases.putSyncValue("onboardingComplete", "true")
            onDone()
        }
    }
}
