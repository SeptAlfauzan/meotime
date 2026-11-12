package com.septaalfauzan.meotime.presentation.appsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.septaalfauzan.meotime.domain.InstalledAppCheck
import com.septaalfauzan.meotime.domain.model.MonitoredApp
import com.septaalfauzan.meotime.domain.useCase.MeotimeUseCases
import com.septaalfauzan.meotime.presentation.dashboard.DashboardAppRow
import com.septaalfauzan.meotime.presentation.dashboard.dashboardRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The app-settings screen's state.
 *
 * Exactly one non-content state is presented, in this order: [missingArgument] (the route arrived
 * without a package — navigation corruption, and nothing to retry), [loading] (the row, today's usage
 * and the installed probe have not answered yet), [error] (that read failed — retryable),
 * [notMonitored] (the read succeeded and there is no row, which is a real answer rather than a
 * failure) and the settings themselves. An empty [app] on its own is therefore never "not read
 * yet", which is what lets the screen say *why* it has nothing to show instead of rendering a blank
 * page.
 *
 * The two writes are reported as four separate, non-overlapping failures. [updateFailed] is the
 * pause/resume write — an `UPDATE` that cannot be proven to have landed, so the switch is not moved
 * and the row is not re-read as if it had. [removeFailed] is the delete, and it is only raised when
 * the row is *still there* afterwards; a delete that had nothing left to remove is the state the
 * user asked for, so it finishes.
 *
 * The brief's flat `app: MonitoredApp? / usage / limit` triple is kept, with two deliberate
 * changes: the limit is [derived] rather than stored, so it cannot disagree with the row it came
 * from, and the row carries `installed` because the repository never populates it (it has no
 * platform access). [displayName] is what the screen prints, and [row] is the same limit arithmetic
 * the dashboard list uses, from the same function.
 */
data class AppSettingsUiState(
    val app: MonitoredApp? = null,
    val usageSeconds: Long = 0,
    val loading: Boolean = true,
    val error: Boolean = false,
    val missingArgument: Boolean = false,
    val notMonitored: Boolean = false,
    val updatingEnabled: Boolean = false,
    val updateFailed: Boolean = false,
    /**
     * The state the last pause/resume asked for, kept only to name it in the failure notice.
     *
     * The row's own [AppSettingsUiState.paused] cannot say it: a write that did not land leaves the
     * row exactly as it was, so a failed *pause* would be reported as a failed resume. Cleared by
     * any read that answers, because a read is the truth the notice is making a claim about.
     */
    val requestedEnabled: Boolean? = null,
    val removeRequested: Boolean = false,
    val removing: Boolean = false,
    val removeFailed: Boolean = false,
) {
    /**
     * The daily limit, taken from the row rather than held separately.
     *
     * The brief's version stored it and overwrote it on every write, which is two values that can
     * disagree; here there is one, and the screen cannot show a limit the row does not have.
     */
    val limitMinutes: Int get() = app?.dailyLimitMinutes ?: 0

    /**
     * What the screen titles itself with.
     *
     * The row's own label, with the package id as the fallback. A blank stored label would render a
     * settings page with no title at all, and the id is a worse label but a truthful one — it is the
     * value the row is keyed on. The label deliberately comes from the *row* rather than from a
     * launcher lookup: this screen is about the monitored row, and the string in the row is the one
     * [com.septaalfauzan.meotime.monitoring.MonitoringCore] puts in the overlay, so a deep link from
     * that overlay lands here naming the app exactly the way the overlay did.
     */
    val displayName: String
        get() = app?.appName?.takeIf { it.isNotBlank() } ?: app?.packageName.orEmpty()

    /**
     * Whether to tell the user the app is gone from the device.
     *
     * Text, not a tint: an uninstalled app keeps its row (the user may reinstall it and get the
     * limit back), so this is a notice about the device, not an error about the configuration.
     */
    val showsUninstallNotice: Boolean get() = app != null && !app.installed

    /** Paused is a separate axis from the limit state, exactly as on the dashboard row. */
    val paused: Boolean get() = app?.enabled == false

    /**
     * The same row the dashboard would draw for this app.
     *
     * [getMonitoredApp] does not join today's usage the way the observed read does, so the usage
     * this screen resolved separately is folded in before the arithmetic runs. Sharing the function
     * is what keeps "Monitoring paused" and the remaining-minutes wording identical on the two
     * screens instead of two copies of one rule.
     */
    val row: DashboardAppRow?
        get() = app?.let { dashboardRow(it.copy(todayUsageSeconds = usageSeconds)) }

    /**
     * Whether the settings body may be written at all.
     *
     * Derived, so the view model and the buttons cannot disagree: an unresolved screen has no row
     * to change, and a write already in flight is not a second write.
     */
    val canToggleMonitoring: Boolean
        get() = app != null && !loading && !error && !notMonitored && !missingArgument && !updatingEnabled

    val canRequestRemove: Boolean
        get() = canToggleMonitoring && !removeRequested

    /** The destructive action is only offered after the confirmation step has been opened. */
    val canConfirmRemove: Boolean
        get() = canToggleMonitoring && removeRequested && !removing
}

/**
 * What a write attempt actually did, as read back from the row.
 *
 * [THREW] and [GONE] are indistinguishable from the re-read alone — a deleted row and a write that
 * blew up both leave no row to look at — so the outcome is captured at the point of failure and kept
 * apart from the answer. They mean opposite things: [THREW] left the row exactly as it was, while
 * [GONE] means the app is not monitored at all. Conflating them would either report a removal that
 * never happened or send a user whose app is still monitored to a screen claiming it is not.
 */
private enum class WriteOutcome { THREW, GONE, READ }

/**
 * Performs [writeAndReadBack] and reports what it did.
 *
 * The pair is `(outcome, row)`: the row is non-null only for [WriteOutcome.READ]. Both writes on this
 * screen are `UPDATE`/`DELETE`s keyed on the package name, and Room answers "matched no row" exactly
 * the way it answers "changed one row" — the re-read is the only way to tell, and [THREW] is recorded
 * here because the re-read cannot.
 */
private suspend fun write(
    writeAndReadBack: suspend () -> MonitoredApp?,
): Pair<WriteOutcome, MonitoredApp?> = try {
    val app = writeAndReadBack()
    if (app == null) WriteOutcome.GONE to null else WriteOutcome.READ to app
} catch (cancellation: CancellationException) {
    // Rethrown before the generic catch below, so cancelling the scope can never be reported as a
    // failed write — which would show a notice about a write that may well have landed.
    throw cancellation
} catch (_: Exception) {
    WriteOutcome.THREW to null
}

/**
 * The app-settings screen's state machine: resolve the app this screen is about, then pause, resume
 * and remove it.
 *
 * **What resolves, and where.** The package comes from the route and everything else is read here,
 * for the reason the set-limit screen reads its own label here: a provider that throws while the
 * destination composes cannot become a retryable state, and there would be nothing to show a
 * skeleton for. A missing or blank package short-circuits to the terminal [AppSettingsUiState]
 * `missingArgument` state without touching a seam, because there is no app to look up and a retry
 * would only produce the same answer.
 *
 * **The installed probe is one package, once per resolve.** [MonitoredApp.installed] is a read-time
 * fact about the device and the repository cannot populate it — it has no platform access — so the
 * probe is what answers it (ledger (f)). The dashboard has to memoise its probe against the package
 * set, because it re-derives on every usage emission; this screen resolves once per resume and is not
 * re-driven by usage, so one probe per resolve is the entire cost. It is deliberately *not* re-run
 * on a write: pausing does not change whether an app is installed, and re-probing there would make
 * a PackageManager round trip per tap. The staleness that buys is bounded by [refresh], which the
 * screen calls on every resume.
 *
 * **Both writes are verified, because both can silently do nothing.** `setMonitoringEnabled` and
 * `removeMonitoredApp` are an `UPDATE` and a `DELETE` keyed on the package name: Room reports "matched
 * no row" exactly the way it reports "changed one row", so a repository that ignores the input, or a
 * row that was deleted behind this screen, returns normally and writes nothing. Every write is
 * therefore re-read before it is reported as done, and the answers are kept apart because they mean
 * different things to the user:
 *
 * | After the write | Reported as | Why |
 * |---|---|---|
 * | row present and says what was asked | success | the write landed |
 * | row **absent** (toggle) | [AppSettingsUiState.notMonitored] | the app stopped being monitored behind this screen; there is nothing left to pause |
 * | row absent (remove) | success, via `onDone` | "not monitored" is the state the user asked for |
 * | row present and still disagrees (toggle) | [AppSettingsUiState.updateFailed] | nothing was written, so the switch must not move |
 * | row **present** (remove) | [AppSettingsUiState.removeFailed] | the delete did not happen, so leaving would drop the user on a dashboard that still lists the app |
 * | the write threw | the matching `*Failed` flag | the row is untouched and the action stays available |
 *
 * A throwing write is deliberately *not* treated as a vanished row: the row is still there, so the
 * retry is the same write again. Conflating the two would either report a removal that did not happen
 * or send a user whose app is still monitored to a screen saying it is not.
 *
 * **Removal is confirmed in the state, not only in the screen.** [remove] is a no-op unless
 * [requestRemove] opened the confirmation first, so a view model cannot be talked into an
 * irreversible delete by a single call, and [cancelRemove] leaves the row exactly as it was.
 *
 * **One write at a time, and the success latch stays raised.** [setMonitoringEnabled] and [remove]
 * are no-ops while a write of their own kind is in flight (the double-tap guard), and neither lowers
 * its flag on success — success ends the screen, and clearing the flag would open the window between
 * the write and the navigation for a second one.
 */
class AppSettingsViewModel(
    private val requestedPackageName: String?,
    private val useCases: MeotimeUseCases,
    private val installedAppCheck: InstalledAppCheck,
) : ViewModel() {

    private val _state = MutableStateFlow(AppSettingsUiState())
    val state: StateFlow<AppSettingsUiState> = _state.asStateFlow()

    private var resolveJob: Job? = null

    init {
        resolve()
    }

    /**
     * Re-reads the row, today's usage and the installed probe.
     *
     * The screen calls this on every resume, which is what keeps the limit honest after the user
     * backs out of the limit editor, and what re-probes an app that was reinstalled while this
     * screen sat in the back stack. A read that fails is reported *without* dropping a row that is
     * already on screen: there is still a correct answer for the app being viewed, and blanking it
     * would turn a refresh into a flash of skeleton.
     */
    fun refresh() {
        val packageName = requestedPackageName?.takeIf { it.isNotBlank() }
        if (packageName == null) {
            resolve()
            return
        }
        // The skeleton is only for a screen with nothing to show: re-reading over a row, or over a
        // not-monitored answer, must not replace what the user is looking at with a flash.
        _state.update { it.copy(loading = it.app == null && !it.notMonitored, error = false) }
        read(packageName)
    }

    /** The retry behind the error state. Identical to [refresh] — the same read, deliberately. */
    fun retry() = refresh()

    /**
     * Pauses or resumes monitoring for this app.
     *
     * Nothing is written while the state cannot be changed or a write is already in flight. The
     * requested state is *not* applied optimistically: the switch only moves once the row has been
     * re-read saying so, so it can never show "paused" over an app that is still being counted.
     */
    fun setMonitoringEnabled(enabled: Boolean) {
        val snapshot = _state.value
        val packageName = snapshot.app?.packageName ?: requestedPackageName?.takeIf { it.isNotBlank() }
        if (!snapshot.canToggleMonitoring || packageName == null) return
        _state.update { it.copy(updatingEnabled = true, updateFailed = false, requestedEnabled = enabled) }
        viewModelScope.launch {
            val (outcome, row) = write {
                useCases.setMonitoringEnabled(packageName, enabled)
                useCases.getMonitoredApp(packageName)
            }
            when (outcome) {
                WriteOutcome.THREW ->
                    // The row is untouched — the write never reached it — so the switch stays where
                    // it was and the same call is the retry.
                    _state.update { it.copy(updatingEnabled = false, updateFailed = true) }

                WriteOutcome.GONE ->
                    // The app stopped being monitored behind this screen. There is nothing left to
                    // pause, and moving the switch would be a claim about a row that is not there.
                    _state.value = AppSettingsUiState(loading = false, notMonitored = true)

                WriteOutcome.READ -> {
                    val current = _state.value
                    val verified = row
                    when {
                        // A resume's re-read found the row gone while this write was in flight; that
                        // answer is the newer one, so it stands and this write says nothing.
                        current.app == null -> Unit
                        verified == null || verified.enabled != enabled ->
                            _state.update { it.copy(updatingEnabled = false, updateFailed = true) }

                        else ->
                            // The installed flag is carried over rather than re-probed: a pause cannot
                            // change whether an app is installed, and the probe is one PackageManager
                            // call per resolve, not per write.
                            _state.update {
                                it.copy(
                                    app = verified.copy(installed = current.app.installed),
                                    updatingEnabled = false,
                                    updateFailed = false,
                                    requestedEnabled = null,
                                )
                            }
                    }
                }
            }
        }
    }

    /** Opens the confirmation for the destructive action. No write happens here. */
    fun requestRemove() {
        if (!_state.value.canRequestRemove) return
        _state.update { it.copy(removeRequested = true, removeFailed = false) }
    }

    /** Closes the confirmation, leaving the row exactly as it was. */
    fun cancelRemove() {
        if (_state.value.removing) return
        _state.update { it.copy(removeRequested = false, removeFailed = false) }
    }

    /**
     * Deletes the row and hands control to [onDone], which the screen turns into navigation.
     *
     * Only reachable once [requestRemove] has opened the confirmation, and only once: the latch
     * stays raised on success because success ends the screen. On failure the row stays, the
     * confirmation stays open with an explanation, and the same call is the retry.
     */
    fun remove(onDone: () -> Unit) {
        val snapshot = _state.value
        val packageName = snapshot.app?.packageName
        if (!snapshot.canConfirmRemove || packageName == null) return
        _state.update { it.copy(removing = true, removeFailed = false) }
        viewModelScope.launch {
            val (outcome, _) = write {
                useCases.removeMonitoredApp(packageName)
                useCases.getMonitoredApp(packageName)
            }
            when (outcome) {
                // Either the delete worked, or the row was already gone. Both leave the app
                // unmonitored, which is what the user asked for, so the screen leaves with it.
                WriteOutcome.GONE -> onDone()
                // A write that threw left the row in place, and a delete the repository did not
                // perform left it in place too. Both are a row the dashboard still lists, so
                // navigating away now would be the dead end this screen exists to prevent.
                WriteOutcome.THREW, WriteOutcome.READ ->
                    _state.update { it.copy(removing = false, removeFailed = true) }
            }
        }
    }

    /**
     * Reads the row, today's usage and the installed probe, then publishes a state that can be
     * written.
     */
    private fun resolve() {
        resolveJob?.cancel()
        val packageName = requestedPackageName?.takeIf { it.isNotBlank() }
        if (packageName == null) {
            _state.value = AppSettingsUiState(loading = false, missingArgument = true)
            return
        }
        // The error flag goes down as the read starts, so the screen leaves the error branch and
        // shows the skeleton for the re-read rather than sitting on a button that looks inert.
        _state.update { it.copy(loading = true, error = false) }
        read(packageName)
    }

    private fun read(packageName: String) {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            val resolved = try {
                val app = useCases.getMonitoredApp(packageName)
                Triple(
                    app,
                    useCases.getTodayUsageSeconds(packageName),
                    // Probed only for a row that exists: there is no device question to ask about an
                    // app Meotime is not watching, and the answer would be thrown away.
                    app?.let { installedAppCheck.isInstalled(it.packageName) },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // A database *or* a PackageManager that throws lands here. Both are reads about a
                // row that may well be fine, so both are the same retryable state.
                null
            }
            if (resolved == null) {
                _state.update { it.copy(loading = false, error = true) }
                return@launch
            }
            val (app, usage, installed) = resolved
            _state.update { current ->
                if (app == null) {
                    // A read that succeeded and found nothing. Distinct from [error]: there is
                    // nothing to retry, because the answer is not going to change on its own.
                    current.copy(
                        app = null,
                        usageSeconds = 0,
                        loading = false,
                        error = false,
                        notMonitored = true,
                    )
                } else {
                    current.copy(
                        app = app.copy(installed = installed ?: app.installed),
                        usageSeconds = usage,
                        loading = false,
                        error = false,
                        notMonitored = false,
                        // A read that answered is the truth any pending failure notice is about, so
                        // a stale one is dropped rather than left to describe a row that has moved.
                        updateFailed = false,
                        requestedEnabled = null,
                        removeFailed = false,
                    )
                }
            }
        }
    }
}
