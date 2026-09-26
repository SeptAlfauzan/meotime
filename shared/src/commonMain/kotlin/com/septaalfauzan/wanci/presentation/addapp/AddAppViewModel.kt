package com.septaalfauzan.wanci.presentation.addapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.septaalfauzan.wanci.domain.InstalledApp
import com.septaalfauzan.wanci.domain.InstalledAppsProvider
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The add-app screen's state.
 *
 * [apps] is the *monitorable* list: installed apps the user can still start monitoring, already
 * filtered by the search [query] and by the packages that are monitored elsewhere. It is never the
 * raw platform list, because offering a row for an app that is already monitored makes the screen
 * promise something the repository will refuse.
 *
 * [loading] and [error] are the same two-state contract the dashboard publishes, for the same
 * reason: an unanswered read and an empty list look identical on a blank screen, so the three are
 * kept distinguishable. [loading] starts true because the exclusion set arrives from the database
 * after the platform list is already in hand.
 *
 * [selected] is a package name, not an index, because the list is re-derived on every monitored-set
 * emission and an index would silently point at a different app.
 */
data class AddAppUiState(
    val apps: List<InstalledApp> = emptyList(),
    val selected: String? = null,
    val query: String = "",
    val loading: Boolean = true,
    val error: Boolean = false,
)

/**
 * The add-app screen's state machine: platform apps minus the ones already monitored, filtered by a
 * search query, with a single selection.
 *
 * **The monitorable set is a subtraction, not a whitelist.** "Can this app be monitored" is
 * answered by the platform list and the repository, not by a hard-coded catalogue, so an app
 * installed after this release is offered without a code change. Two exclusions apply: packages
 * already in the monitored set (offering them would produce a row whose insert the repository
 * rejects) and Wanci itself. The second is a *provider* contract rather than a rule here —
 * `InstalledAppsProviderAndroid` drops the host package while enumerating launcher activities —
 * because common code has no way to know the app's own package id; the platform is the only place
 * that can answer it.
 *
 * **The monitored set is observed, not sampled once.** The subscription stays open and every
 * emission re-derives the visible list, so an app monitored from anywhere else disappears from this
 * screen instead of being offered twice, and a `popUpTo` back-navigation re-uses a live answer
 * rather than a stale one. It is collected rather than `first()`-ed on purpose: `first()` on a flow
 * that has not answered yet throws `NoSuchElementException` inside the view model scope, which is a
 * crash, and a repository that answers later would leave the screen stuck.
 *
 * **Failures are states.** A failed read publishes [AddAppUiState.error] and cancels the collection
 * (which would otherwise hold the last good answer forever); [retry] re-subscribes. A read that
 * simply has not answered yet is [AddAppUiState.loading], not an error.
 *
 * **This step does not persist.** [confirm] hands the selection to the limit step, which owns the
 * insert: writing here would create the row with a default limit that the user has not chosen yet,
 * and the limit step's own insert would then be rejected as a duplicate.
 */
class AddAppViewModel(
    installedAppsProvider: InstalledAppsProvider,
    private val useCases: WanciUseCases,
) : ViewModel() {

    private val allInstalled: List<InstalledApp> = installedAppsProvider.getInstalledApps()

    private val _state = MutableStateFlow(AddAppUiState())
    val state: StateFlow<AddAppUiState> = _state.asStateFlow()

    private var observeJob: Job? = null
    private var monitoredPackages: Set<String> = emptySet()

    init {
        observe()
    }

    /** Re-reads the monitored set: the retry behind the error state, and a manual refresh. */
    fun retry() {
        observe()
    }

    fun select(packageName: String) {
        _state.update { it.copy(selected = packageName) }
    }

    fun updateQuery(query: String) {
        _state.update { current -> current.copy(query = query, apps = visibleApps(query)) }
    }

    /**
     * Continues to the limit step for the selected package, doing nothing when nothing is selected.
     * The disabled primary action is the visible guard; this is the same guard for a tap that
     * arrives from somewhere else, so the navigation can never be handed a null package.
     */
    fun confirm(onNavigateToLimit: (String) -> Unit) {
        val selected = _state.value.selected ?: return
        onNavigateToLimit(selected)
    }

    private fun observe() {
        observeJob?.cancel()
        _state.update { it.copy(loading = it.apps.isEmpty(), error = false) }
        observeJob = viewModelScope.launch {
            useCases.observeMonitoredApps()
                .catch { _state.update { current -> current.copy(loading = false, error = true) } }
                .collect { monitored ->
                    monitoredPackages = monitored.mapTo(mutableSetOf()) { it.packageName }
                    _state.update { current ->
                        current.copy(apps = visibleApps(current.query), loading = false)
                    }
                }
        }
    }

    /**
     * The monitorable set, derived from the platform list, the monitored packages and [query]: a
     * re-derivation on every emission rather than a one-off list, so a package that started being
     * monitored elsewhere cannot be offered here.
     */
    private fun visibleApps(query: String): List<InstalledApp> {
        val needle = query.trim().lowercase()
        return allInstalled.filter { app ->
            app.packageName !in monitoredPackages && app.appName.lowercase().contains(needle)
        }
    }
}
