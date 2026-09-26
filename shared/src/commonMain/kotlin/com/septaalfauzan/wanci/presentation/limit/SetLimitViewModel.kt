package com.septaalfauzan.wanci.presentation.limit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.septaalfauzan.wanci.domain.InstalledAppsProvider
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Smallest limit a user can pick, and the largest.
 *
 * 1 minute is the floor because a limit of 0 would mean "warn immediately", which the limit checker
 * already reaches by *usage*, not by a zero budget ([com.septaalfauzan.wanci.monitoring.LimitChecker]
 * compares `usage >= limit`); a stored 0 is therefore indistinguishable from 1 and would make the
 * stepper's floor a lie. 24 hours is the ceiling because a daily budget larger than a day is not a
 * budget.
 */
const val MIN_LIMIT_MINUTES = 1
const val MAX_LIMIT_MINUTES = 24 * 60

/** What a new app starts on. Half an hour: long enough to be useful, short enough to notice. */
const val DEFAULT_LIMIT_MINUTES = 30

/**
 * The shortcut values the screen offers, in minutes. They are a convenience, never a constraint:
 * any value in `1..1440` is reachable through the stepper, and the editor says "Custom limit" for
 * anything that is not one of these rather than pretending a choice was made from a list.
 */
val LIMIT_PRESETS = listOf(15, 30, 45, 60, 120)

/**
 * The set-limit screen's state.
 *
 * Exactly one of the four non-content states is presented, in this order: [missingArgument] (the
 * route arrived without a package — navigation corruption, and nothing to retry), [loading] (the
 * label and the current row have not answered yet), [error] (that read failed — retryable), and the
 * editor. The editor then carries the write outcomes as two separate, non-overlapping failures:
 * [saveConflict] (the insert was refused because somebody else monitored the app while this screen
 * was open) and [saveFailed] (the write threw, or the row it meant to update was gone).
 *
 * [appName] is what the screen shows, [packageName] what it persists. They are different strings:
 * the label is a display value that the platform can change or refuse to answer, and the fallback
 * for an app the launcher does not list is the package id itself.
 *
 * [alreadyMonitored] is a fact about the *open*, not about the save: the row existed when the
 * screen resolved, so saving changes that row's limit instead of inserting a new one. The screen
 * states this in words before the user presses Save — see [SetLimitViewModel.save].
 */
data class SetLimitUiState(
    val packageName: String? = null,
    val appName: String = "",
    val minutes: Int = DEFAULT_LIMIT_MINUTES,
    val loading: Boolean = true,
    val error: Boolean = false,
    val missingArgument: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val saveConflict: Boolean = false,
    val alreadyMonitored: Boolean = false,
) {
    /**
     * Whether the primary action may write.
     *
     * Derived rather than stored, so the button and the view model can never disagree about it: an
     * unresolved screen has nothing to save, and a conflict is terminal here (a second insert would
     * be refused in exactly the same way).
     */
    val canSave: Boolean
        get() = !loading && !error && !missingArgument && !saveConflict && !saving && packageName != null

    /** The stepper is disabled at an end rather than left enabled and inert. */
    val canDecrement: Boolean
        get() = minutes > MIN_LIMIT_MINUTES

    val canIncrement: Boolean
        get() = minutes < MAX_LIMIT_MINUTES
}

/**
 * Clamps a requested limit into the range the state can hold.
 *
 * The editor, the prefill and the formatter all go through this one function, so "1440" means the
 * same thing as a stored value, a rendered label and a stepper ceiling.
 */
internal fun clampLimitMinutes(value: Int): Int = value.coerceIn(MIN_LIMIT_MINUTES, MAX_LIMIT_MINUTES)

/** What a save attempt did. Private to the screen: only the two state flags cross the boundary. */
private enum class SaveOutcome { WRITTEN, CONFLICT, FAILED }

/**
 * The preset [value] matches, or `null` when it is a custom value.
 *
 * A screen that only tinted the selected preset would carry the choice by colour alone; the editor
 * renders this as text instead.
 */
fun presetFor(value: Int): Int? = LIMIT_PRESETS.firstOrNull { it == clampLimitMinutes(value) }

/**
 * A limit in the words a person would use: hours only when there are whole hours, and never a
 * fraction, so the stepper's per-minute feedback has something to show at every value.
 *
 * [minutes] is clamped rather than trusted, so this can never render a number the state does not
 * hold.
 */
fun formatLimitMinutes(minutes: Int): String {
    val total = clampLimitMinutes(minutes)
    val hours = total / 60
    val rest = total % 60
    val hourLabel = if (hours == 1) "hour" else "hours"
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours $hourLabel"
        else -> "$hours $hourLabel $rest min"
    }
}

/**
 * The set-limit screen's state machine: resolve who this screen is about, then persist an app and
 * its daily limit — the write the add-app step deliberately left to this destination.
 *
 * **Resolving happens here, not at the call site.** The nav host could enumerate the launcher from
 * the route body, but then a provider that throws would crash while the destination is being
 * composed, there would be no read to show a skeleton for, and a failure could not be retried. So
 * the package comes from the route and everything else — the label and whether a row already exists
 * — is read here, where it can be a state. A package the platform does not list is not a failure:
 * it falls back to the package id, because the row still has to be persistable. A provider or
 * database that *throws* is a failure, and [retry] re-runs the same read.
 *
 * **An existing row is prefilled, not defaulted.** When the app is already monitored the editor
 * starts on that row's limit, clamped, because this screen is about to write a limit and starting
 * at [DEFAULT_LIMIT_MINUTES] would turn a Save the user did not think about into a change of
 * whatever they had set.
 *
 * **The save writes in two shapes, and says which one it is.** If the row was absent on open, the
 * save inserts; if it was present, the save updates that row's limit — the user was told the app
 * was already monitored before they pressed Save, so the write is an informed one. The insert is
 * the only path that can be *refused* (the repository is an `INSERT OR IGNORE`): when that happens
 * the app became monitored behind this screen, which is a different situation from the row that was
 * already there — its limit belongs to whichever flow created it — so the save reports
 * [SetLimitUiState.saveConflict], writes nothing and does not navigate. Claiming success there, or
 * quietly overwriting the other flow's limit, would both be the user being told something untrue.
 *
 * **The update path verifies itself.** `updateDailyLimit` is an `UPDATE ... WHERE package_name = ?`,
 * so a row deleted between the read and the save writes nothing and still returns normally. The
 * write is therefore re-read before it is reported as done, because a "saved" the dashboard then
 * contradicts is the exact failure this screen exists to prevent.
 *
 * **One write in flight at a time.** [save] is a no-op while a save is running, which is the guard
 * against a double tap inserting the row twice; the latch stays set on success because success ends
 * the screen.
 */
class SetLimitViewModel(
    private val requestedPackageName: String?,
    private val installedAppsProvider: InstalledAppsProvider,
    private val useCases: WanciUseCases,
) : ViewModel() {

    private val _state = MutableStateFlow(SetLimitUiState())
    val state: StateFlow<SetLimitUiState> = _state.asStateFlow()

    private var resolveJob: Job? = null

    init {
        resolve()
    }

    /**
     * Re-runs the read behind the error state.
     *
     * The previous attempt is cancelled first so two answers cannot race to publish, and the error
     * flag is cleared up front so the screen leaves the error branch while the retry runs.
     */
    fun retry() {
        resolve()
    }

    fun setMinutes(value: Int) {
        _state.update { it.copy(minutes = clampLimitMinutes(value)) }
    }

    fun increment() = setMinutes(_state.value.minutes + 1)

    fun decrement() = setMinutes(_state.value.minutes - 1)

    /**
     * Persists the app and its limit, then hands control to [onDone] — the screen navigates, this
     * class does not know where to.
     *
     * Nothing is written while the state cannot be saved (no package, an unresolved or failed read,
     * or a conflict that has already been reported), and nothing is written while a save is in
     * flight, so the only way to insert a row twice is a repository that ignores its input.
     */
    fun save(onDone: () -> Unit) {
        val snapshot = _state.value
        val packageName = snapshot.packageName
        if (!snapshot.canSave || packageName == null) return
        _state.update { it.copy(saving = true, saveFailed = false, saveConflict = false) }
        viewModelScope.launch {
            when (val outcome = write(snapshot, packageName)) {
                SaveOutcome.WRITTEN ->
                    // The latch stays raised: the screen is leaving, and clearing it would open the
                    // window for a second insert between the write and the navigation.
                    onDone()

                SaveOutcome.CONFLICT ->
                    _state.update { it.copy(saving = false, saveConflict = true) }

                SaveOutcome.FAILED ->
                    _state.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    /**
     * The write itself, as an outcome the screen can act on.
     *
     * The two write shapes are kept apart here because only one of them can be *refused*: the
     * insert returns `false` when the row is already there, which is a conflict, while a throw and
     * an update that matched no row are both ordinary failures the user can retry.
     */
    private suspend fun write(snapshot: SetLimitUiState, packageName: String): SaveOutcome = try {
        if (snapshot.alreadyMonitored) {
            useCases.updateDailyLimit(packageName, snapshot.minutes)
            // The UPDATE matches nothing when the row is gone, and Room reports that the same way
            // as a row it changed, so the answer is read back rather than assumed.
            val landed = useCases.getMonitoredApp(packageName)?.dailyLimitMinutes == snapshot.minutes
            if (landed) SaveOutcome.WRITTEN else SaveOutcome.FAILED
        } else {
            if (useCases.addMonitoredApp(packageName, snapshot.appName, snapshot.minutes)) {
                SaveOutcome.WRITTEN
            } else {
                SaveOutcome.CONFLICT
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        SaveOutcome.FAILED
    }

    /**
     * Reads the label and the current row, then publishes a state that is ready to be saved.
     *
     * A missing or blank package short-circuits to the terminal [SetLimitUiState.missingArgument]
     * state without touching either seam: there is no app to look up, and retrying would only
     * produce the same answer.
     */
    private fun resolve() {
        resolveJob?.cancel()
        val packageName = requestedPackageName?.takeIf { it.isNotBlank() }
        if (packageName == null) {
            _state.value = SetLimitUiState(loading = false, missingArgument = true)
            return
        }
        // The error flag goes down as the retry starts, so the screen leaves the error branch and
        // shows the skeleton for the re-read instead of sitting on a button that appears inert.
        _state.update { it.copy(loading = true, error = false) }
        resolveJob = viewModelScope.launch {
            val resolved = try {
                appLabel(packageName) to useCases.getMonitoredApp(packageName)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            if (resolved == null) {
                // Full reset, the dashboard's shape: the screen presents the error instead of the
                // editor, so anything left in the state would be state nothing renders.
                _state.value = SetLimitUiState(loading = false, error = true)
            } else {
                val (label, existing) = resolved
                _state.update { current ->
                    current.copy(
                        packageName = packageName,
                        appName = label,
                        // An existing row's limit is the starting value, repaired rather than trusted
                        // (a stored 0 or 5000 would otherwise be shown and re-saved as itself). With
                        // no row, whatever the editor already holds stands — [DEFAULT_LIMIT_MINUTES]
                        // on a first resolve, and a re-resolve does not reset a value in progress.
                        minutes = existing?.dailyLimitMinutes?.let(::clampLimitMinutes)
                            ?: clampLimitMinutes(current.minutes),
                        alreadyMonitored = existing != null,
                        loading = false,
                        error = false,
                    )
                }
            }
        }
    }

    /**
     * The display label for [packageName], falling back to the package id.
     *
     * A blank platform label is treated as no label: the id is a worse label but a truthful one,
     * and it is the value the row is keyed on.
     */
    private fun appLabel(packageName: String): String =
        installedAppsProvider.getInstalledApps()
            .firstOrNull { it.packageName == packageName }
            ?.appName
            ?.takeIf { it.isNotBlank() }
            ?: packageName
}
