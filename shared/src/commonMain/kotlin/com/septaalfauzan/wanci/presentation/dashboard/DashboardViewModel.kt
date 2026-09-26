package com.septaalfauzan.wanci.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.septaalfauzan.wanci.domain.InstalledAppCheck
import com.septaalfauzan.wanci.domain.model.MonitoredApp
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Dashboard state.
 *
 * Exactly one of the three is presented, in this order: [loading] while nothing has been observed
 * yet, [error] when the observed read failed, and [apps] (possibly empty) once it succeeded. An
 * empty [apps] is therefore a real "no monitored apps" answer, never an unobserved one, which is
 * what lets the screen show a zero state with a call to action instead of a blank list.
 */
data class DashboardUiState(
    val apps: List<MonitoredApp> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
)

/**
 * A monitored app plus the limit arithmetic the row needs, derived once per emission so the
 * screen never recomputes it and never disagrees with the test suite about it.
 *
 * [progress] is clamped to `0f..1f` and [remainingMinutes] to `0`, so a row can be rendered from
 * these values alone. `limitReached` is the same comparison the overlay uses
 * ([com.septaalfauzan.wanci.monitoring.LimitChecker]): `usage >= limit`.
 */
data class DashboardAppRow(
    val app: MonitoredApp,
    val limitReached: Boolean,
    val remainingMinutes: Long,
    val progress: Float,
)

/**
 * Derives one dashboard row from an app. Pure, so the limit arithmetic is unit-testable without a
 * composition and the screen stays a renderer.
 *
 * A non-positive limit would make `usage / limit` NaN or Infinity, and `coerceIn` returns NaN
 * unchanged, so the divisor is guarded: a row that cannot be measured reports the limit as reached
 * with a full bar rather than rendering a broken indicator.
 */
internal fun dashboardRow(app: MonitoredApp): DashboardAppRow {
    val limit = app.dailyLimitMinutes
    val usage = app.todayUsageMinutes
    val measurable = limit > 0
    val progress = if (measurable) (usage.toFloat() / limit.toFloat()).coerceIn(0f, 1f) else 1f
    return DashboardAppRow(
        app = app,
        limitReached = !measurable || usage >= limit.toLong(),
        remainingMinutes = if (measurable) (limit.toLong() - usage).coerceAtLeast(0L) else 0L,
        progress = progress,
    )
}

/**
 * The dashboard's state machine: it observes the monitored apps and publishes them with their
 * installed-state resolved.
 *
 * **Live usage, no polling.** The single source is [WanciUseCases.observeMonitoredApps], whose
 * implementation joins today's usage rows into each app and re-queries them whenever the date
 * changes. Recording usage therefore reaches the UI as a fresh emission while the app is running,
 * and midnight resets every row to 0 without this class owning a timer. The list is re-derived on
 * every emission, so nothing is cached between them.
 *
 * **Failures are states, not crashes.** A failed read publishes [DashboardUiState.error] and
 * cancels the collection, which would otherwise leave the screen stuck on the last good value
 * forever; [refresh] re-subscribes, and is also the retry the error state offers.
 */
class DashboardViewModel(
    private val useCases: WanciUseCases,
    private val installedAppCheck: InstalledAppCheck,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var observeJob: Job? = null

    init {
        observe()
    }

    /**
     * Re-reads the monitored apps: the retry behind the error state, and the manual refresh for a
     * dashboard that was left open while the platform changed underneath it. Any previous
     * subscription is cancelled first so the two cannot race to publish.
     */
    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = false)
        observe()
    }

    private fun observe() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            useCases.observeMonitoredApps()
                .map { apps -> apps.map { it.copy(installed = installedAppCheck.isInstalled(it.packageName)) } }
                .catch { _state.value = DashboardUiState(loading = false, error = true) }
                .collect { apps -> _state.value = DashboardUiState(apps = apps, loading = false) }
        }
    }
}
