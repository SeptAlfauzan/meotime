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
 *
 * [monitoredPackages] is the exclusion set itself, published rather than kept private, so that
 * [canConfirm] can be *derived* instead of recomputed at each write site: a second copy of the flag
 * would be a second thing to forget to update. [hasPlatformApps] is the fact the zero state needs —
 * "no app on this device can be monitored" and "every one of them is monitored already" are both an
 * empty [apps], and only the platform list tells them apart.
 */
data class AddAppUiState(
    val apps: List<InstalledApp> = emptyList(),
    val selected: String? = null,
    val query: String = "",
    val loading: Boolean = true,
    val error: Boolean = false,
    val monitoredPackages: Set<String> = emptySet(),
    val hasPlatformApps: Boolean = true,
) {
    /**
     * Whether the primary action may hand the selection to the limit step.
     *
     * A selection stops being confirmable the moment its package is monitored, wherever that
     * happened: the row has left [apps] and the repository's insert would be rejected, so an
     * enabled action would promise a step that silently does nothing.
     *
     * [query] is deliberately **not** part of this. A selection the search filter happens to hide is
     * still a valid choice — a filtered-out row must stay confirmable, or clearing the search
     * would be the only way to finish, which is exactly the trap.
     */
    val canConfirm: Boolean
        get() = selected != null && selected !in monitoredPackages
}

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
 * **Failures are states.** A failed read replaces the state with [AddAppUiState.error] and cancels
 * the collection (which would otherwise hold the last good answer forever); [retry] re-subscribes.
 * The replacement is a full reset, the same shape as the dashboard's, rather than a retained list
 * under an error flag: the screen presents the error *instead of* the list, so keeping the list
 * would publish state nothing renders and that reads as "these rows are current" to every reader
 * that is not the screen. A read that simply has not answered yet is [AddAppUiState.loading], not
 * an error.
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

    /**
     * Whether the platform offered anything at all. A fact about the provider, not about the
     * observed read, so it is computed once and published on every state write — the zero state
     * needs it to tell "nothing left to add" from "this device offers nothing", and both look like
     * an empty list otherwise.
     */
    private val hasPlatformApps: Boolean = allInstalled.isNotEmpty()

    private val _state = MutableStateFlow(AddAppUiState())
    val state: StateFlow<AddAppUiState> = _state.asStateFlow()

    private var observeJob: Job? = null

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
        _state.update { current ->
            current.copy(query = query, apps = visibleApps(query, current.monitoredPackages))
        }
    }

    /**
     * Continues to the limit step for the selected package, doing nothing when there is nothing
     * confirmable to hand over.
     *
     * The disabled primary action is the visible guard ([AddAppUiState.canConfirm]); this is the same
     * guard for a tap that arrives from somewhere else, so the navigation can never be handed a
     * null package or one the repository is about to reject as a duplicate.
     */
    fun confirm(onNavigateToLimit: (String) -> Unit) {
        val selected = _state.value.selected ?: return
        if (!_state.value.canConfirm) return
        onNavigateToLimit(selected)
    }

    private fun observe() {
        observeJob?.cancel()
        _state.update { it.copy(loading = it.apps.isEmpty(), error = false) }
        observeJob = viewModelScope.launch {
            useCases.observeMonitoredApps()
                .catch {
                    _state.value = AddAppUiState(
                        loading = false,
                        error = true,
                        hasPlatformApps = hasPlatformApps,
                    )
                }
                .collect { monitored ->
                    val packages = monitored.mapTo(mutableSetOf()) { it.packageName }
                    _state.update { current ->
                        current.copy(
                            apps = visibleApps(current.query, packages),
                            monitoredPackages = packages,
                            loading = false,
                            hasPlatformApps = hasPlatformApps,
                        )
                    }
                }
        }
    }

    /**
     * The monitorable set, derived from the platform list, [monitored] and [query]: a re-derivation
     * on every emission rather than a one-off list, so a package that started being monitored
     * elsewhere cannot be offered here.
     */
    private fun visibleApps(query: String, monitored: Set<String>): List<InstalledApp> {
        val needle = query.trim().lowercase()
        return allInstalled.filter { app ->
            app.packageName !in monitored && app.appName.lowercase().contains(needle)
        }
    }
}
