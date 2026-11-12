package com.septaalfauzan.meotime.presentation.dashboard

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.septaalfauzan.meotime.domain.AppIconProvider
import com.septaalfauzan.meotime.domain.InstalledAppCheck
import com.septaalfauzan.meotime.domain.model.MonitoredApp
import com.septaalfauzan.meotime.domain.useCase.MeotimeUseCases
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
    val appIcons: Map<String, ImageBitmap> = emptyMap(),
    val loading: Boolean = true,
    val error: Boolean = false,
)

/**
 * A monitored app's limit arithmetic, derived once per emission so the screen never recomputes it
 * and never disagrees with the test suite about it.
 *
 * [progress] is clamped to `0f..1f` and [remainingMinutes] to `0`, so a row can be rendered from
 * these values alone. `limitReached` is the same comparison the overlay uses
 * ([com.septaalfauzan.meotime.monitoring.LimitChecker]): `usage >= limit`.
 *
 * [paused] is a separate axis, not a fourth kind of limit state: an app the user switched off is
 * not measured at all ([com.septaalfauzan.meotime.monitoring.MonitoringCore] returns before
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
 * **Live usage, no polling.** The single source is [MeotimeUseCases.observeMonitoredApps], whose
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
    private val useCases: MeotimeUseCases,
    private val appIconProvider: AppIconProvider,
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
        var iconProbedPackages: Set<String> = emptySet()
        var iconByPackage: Map<String, ImageBitmap> = emptyMap()
        observeJob = viewModelScope.launch {
            useCases.observeMonitoredApps()
                .map { apps ->
                    val packages = apps.mapTo(mutableSetOf()) { it.packageName }
                    if (packages != probedPackages) {
                        probedPackages = packages
                        installedByPackage = packages.associateWith { installedAppCheck.isInstalled(it) }
                    }
                    // Same cache discipline as the installed probe above, and for the same reason: an icon is a
                    // `PackageManager` round trip plus a rasterise, and a usage emission arrives every recorded minute.
                    // Re-decoding per emission would make the dashboard's icon bill scale with how much the user uses
                    // their phone. Re-read only when the package set itself changes, which also means an app added or
                    // removed elsewhere is the thing that costs a d
                    // ecode.
                    if (packages != iconProbedPackages) {
                        iconProbedPackages = packages
                        iconByPackage = loadIcons(packages)
                    }
                    apps.map { app ->
                        app.copy(installed = installedByPackage[app.packageName] ?: app.installed)
                    }
                }
                // `update`, not a fresh state: a usage emission arrives every recorded minute, and rebuilding the state
                // from scratch here would clear `appIcons` on every one of them — a map that took a `PackageManager`
                // round trip per row to fill, dropped by the arrival of an unrelated number.
                .catch { _state.update { it.copy(loading = false, error = true) } }
                .collect { apps ->
                    _state.update { it.copy(apps = apps, loading = false, appIcons = iconByPackage) }
                }
        }
    }

    /**
     * Decodes one icon per package. A package with no loadable icon is simply absent from the result rather than
     * mapped to null: the screen treats a missing key and a null value identically, and a map that cannot hold null
     * cannot be read as "this row failed to load" when it only means "this row has no icon".
     *
     * Failure is isolated per package because this runs inside the observed-apps stream: one throwable drawable
     * would otherwise abort the whole list and leave the dashboard empty behind an error, which lets a cosmetic
     * failure take out the entire feature. Cancellation is rethrown so this cannot swallow a [refresh].
     */
    private suspend fun loadIcons(packages: Set<String>): Map<String, ImageBitmap> =
        packages.mapNotNull { packageName ->
            try {
                appIconProvider.getIcon(packageName)?.let { packageName to it }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }.toMap()
}
