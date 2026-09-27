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
 * A monitored app's limit arithmetic, derived once per emission so the screen never recomputes it
 * and never disagrees with the test suite about it.
 *
 * [progress] is clamped to `0f..1f` and [remainingMinutes] to `0`, so a row can be rendered from
 * these values alone. `limitReached` is the same comparison the overlay uses
 * ([com.septaalfauzan.wanci.monitoring.LimitChecker]): `usage >= limit`.
 *
 * [paused] is a separate axis, not a fourth kind of limit state: an app the user switched off is
 * not measured at all ([com.septaalfauzan.wanci.monitoring.MonitoringCore] returns before
 * recording), so its `todayUsageSeconds` is a frozen reading rather than a live one. A paused row
 * therefore reports an empty bar and no remaining minutes — drawing a live-looking budget for a
 * number nothing is updating is the bug, not the label. `limitReached` keeps its plain meaning
 * (`usage >= limit`) so a row that had already reached its limit when it was paused is not
 * laundered into looking untouched.
 */
data class DashboardAppRow(
    val limitReached: Boolean,
    val remainingMinutes: Long,
    val progress: Float,
    val paused: Boolean,
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
    // Converted once, here, because the limit is stored in minutes and the usage is read in
    // seconds. Doing the arithmetic across the two units would leave the ratio, the comparison
    // and the subtraction each wrong by a factor of 60 — with a row that still renders, so the
    // bug would ship looking like a design choice.
    val limitSeconds = app.dailyLimitMinutes * 60L
    val usage = app.todayUsageSeconds
    val measurable = limitSeconds > 0
    val paused = !app.enabled
    val progress = when {
        paused -> 0f
        measurable -> (usage.toFloat() / limitSeconds.toFloat()).coerceIn(0f, 1f)
        else -> 1f
    }
    val remainingSeconds = if (measurable && !paused) (limitSeconds - usage).coerceAtLeast(0L) else 0L
    return DashboardAppRow(
        limitReached = !measurable || usage >= limitSeconds,
        remainingMinutes = remainingSeconds / 60,
        progress = progress,
        paused = paused,
    )
}

/**
 * The words a row's limit state is carried by.
 *
 * Extracted from the dashboard row so the two screens that report the same fact cannot drift apart:
 * a settings screen with its own copy of this mapping is how a list starts saying "3 minutes left"
 * about an app the app's own page calls "Monitoring paused". Both screens derive it from the same
 * [dashboardRow], so the wording is one function, and a test pins all five branches.
 *
 * "Limit reached" is checked first, and stays the answer for a paused app that had already reached
 * its limit when it was paused: that is a real reading, and the usage beside it is still true.
 *
 * [DashboardAppRow.remainingMinutes] is minute-denominated, so second-granularity storage makes a
 * sub-minute remainder floor to zero — a state that was unreachable while usage was whole minutes,
 * because zero then implied the limit had been reached and the first branch answered instead.
 */
internal fun appStatusLabel(row: DashboardAppRow): String = when {
    row.limitReached -> "Limit reached"
    row.paused -> "Monitoring paused"
    // Under the limit with less than a minute left. "0 minutes remaining" is not a thing anyone
    // has ever said, and rounding the row up would claim a minute the user does not have.
    row.remainingMinutes == 0L -> "Less than a minute remaining"
    row.remainingMinutes == 1L -> "1 minute remaining"
    else -> "${row.remainingMinutes} minutes remaining"
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
 *
 * **The installed probe is keyed on the package set.** Resolving `installed` means a
 * `PackageManager` round trip per row, and a usage emission arrives every recorded minute, so
 * probing per emission is a per-minute cost for a fact that only changes when apps come and go.
 * The lookup is therefore cached against the exact set of packages and re-run only when that set
 * changes; [refresh] drops the cache, so a manual refresh re-probes and a reinstall is picked up
 * by the next refresh or the next list change rather than never.
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
     *
     * A list that is already on screen stays on screen: [DashboardUiState.loading] is only raised
     * when there is nothing to show, so a refresh over good data does not flash the skeleton, and
     * the re-subscribed rows are already correct while the fresh query runs. The error flag is
     * always cleared, which is what makes the retry from the error state work.
     */
    fun refresh() {
        val current = _state.value
        _state.value = current.copy(
            loading = current.apps.isEmpty(),
            error = false,
        )
        observe()
    }

    private fun observe() {
        observeJob?.cancel()
        var probedPackages: Set<String> = emptySet()
        var installedByPackage: Map<String, Boolean> = emptyMap()
        observeJob = viewModelScope.launch {
            useCases.observeMonitoredApps()
                .map { apps ->
                    val packages = apps.mapTo(mutableSetOf()) { it.packageName }
                    if (packages != probedPackages) {
                        probedPackages = packages
                        installedByPackage = packages.associateWith { installedAppCheck.isInstalled(it) }
                    }
                    apps.map { app ->
                        app.copy(installed = installedByPackage[app.packageName] ?: app.installed)
                    }
                }
                .catch { _state.value = DashboardUiState(loading = false, error = true) }
                .collect { apps -> _state.value = DashboardUiState(apps = apps, loading = false) }
        }
    }
}
