package com.septaalfauzan.meotime.presentation.limit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.septaalfauzan.meotime.domain.InstalledAppsProvider
import com.septaalfauzan.meotime.domain.limit.DEFAULT_LIMIT_MINUTES
import com.septaalfauzan.meotime.domain.limit.clampLimitMinutes
import com.septaalfauzan.meotime.domain.model.WeeklyLimits
import com.septaalfauzan.meotime.domain.useCase.MeotimeUseCases
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek

/**
 * The quick-set chips the week editor offers, in display order. They are conveniences, never a
 * constraint: every value a chip writes is still editable through the pickers, and a Save writes
 * whatever is on screen.
 */
enum class QuickSet { DAILY, WEEKDAYS, WEEKENDS, CLEAR }

/**
 * Which picker the week editor is showing, if any.
 *
 * [Day] opens the picker for one weekday (the time button on that row); [AllDays] is the picker
 * the `Daily` chip opens, where one duration is written to every day.
 */
sealed interface PickerTarget {
    data class Day(val day: DayOfWeek) : PickerTarget

    data object AllDays : PickerTarget
}

/**
 * The week editor's state.
 *
 * Exactly one of the four non-content states is presented, in the same order as
 * [SetLimitUiState]: [missingArgument] (route arrived without a package), [loading] (label and
 * current schedule not answered yet), [error] (that read failed — retryable), and the editor. The
 * editor then carries the write outcomes as two separate, non-overlapping failures:
 * [saveConflict] (the insert was refused because somebody else monitored the app while this
 * screen was open) and [saveFailed] (the write threw, or the row it meant to update was gone or
 * read back differently).
 *
 * [appName] is what the screen shows, [packageName] what it persists — see [SetLimitUiState] for
 * why they differ. [minutes] holds the *remembered* per-day values: the set keeps a value for
 * days that are currently disabled so a later re-enable restores it, and [draft] is the
 * persisted [WeeklyLimits] with disabled days nulled.
 *
 * [alreadyMonitored] is a fact about the *open*: the row existed when the screen resolved, so
 * saving changes that row's weekly schedule instead of inserting a new one.
 */
data class WeeklyLimitUiState(
    val packageName: String? = null,
    val appName: String = "",
    /** The remembered per-day values, for every day whether or not it is currently limited. */
    val minutes: WeeklyLimits = WeeklyLimits().withAll(DEFAULT_LIMIT_MINUTES),
    /** The set of days the editor currently limits. */
    val enabled: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val picker: PickerTarget? = null,
    val pickerMinutes: Int = DEFAULT_LIMIT_MINUTES,
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
     * Derived rather than stored, so the button and the view model can never disagree about it:
     * an unresolved screen has nothing to save, and a conflict is terminal here (a second insert
     * would be refused in exactly the same way).
     */
    val canSave: Boolean
        get() = !loading && !error && !missingArgument && !saveConflict && !saving && packageName != null

    /**
     * The schedule the screen is editing: a limited day carries its remembered value, or the
     * default when it has none; a disabled day is `null` — "no limit" is a stored state, and a
     * day that was never given a value has nothing to restore.
     */
    val draft: WeeklyLimits
        get() = WeeklyLimits(
            monday = dayValue(DayOfWeek.MONDAY),
            tuesday = dayValue(DayOfWeek.TUESDAY),
            wednesday = dayValue(DayOfWeek.WEDNESDAY),
            thursday = dayValue(DayOfWeek.THURSDAY),
            friday = dayValue(DayOfWeek.FRIDAY),
            saturday = dayValue(DayOfWeek.SATURDAY),
            sunday = dayValue(DayOfWeek.SUNDAY),
        )

    private fun dayValue(day: DayOfWeek): Int? =
        if (day in enabled) minutes.forDay(day) ?: DEFAULT_LIMIT_MINUTES else null

    /** How many days the editor currently limits — the footer's "N days limited". */
    val limitedDays: Int
        get() = draft.limitedDays()

    /** The weekly total of the editor's draft, in minutes — the footer's "weekly total X". */
    val weeklyTotalMinutes: Long
        get() = draft.weeklyTotalMinutes()
}

/**
 * What a save attempt did. Private to the screen: only the state flags cross the boundary.
 *
 * [FAILED_ROW_GONE] is [SetLimitViewModel]'s distinction worth keeping here: the update matched
 * no row, so the fact the app is monitored is dropped and the next Save is the insert the user
 * came here to do — repeating an `UPDATE` against a missing row can never succeed.
 */
private enum class WeeklySaveOutcome {
    WRITTEN,
    CONFLICT,
    FAILED,
    FAILED_ROW_GONE,
}

private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)
private val WEEKENDS = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/**
 * The week editor's state machine: resolve who this screen is about, then persist the app's
 * weekly schedule.
 *
 * **Resolving happens here, not at the call site** — same rule as [SetLimitViewModel]: the
 * package comes from the route and everything else is read inside the view model, where a
 * provider or database that throws becomes a retryable state rather than a crash while the
 * destination is being composed.
 *
 * **The draft is the source of truth, and the remembered values survive it.** [WeeklyLimitUiState.minutes]
 * holds every day's value; `draft` nulls the disabled days. That is what makes `Clear` reversible:
 * it only empties the schedule, and a later re-enable reads the value back out of [minutes].
 *
 * **The save writes in two shapes, and says which one it is.** A row that existed on open is
 * updated with [useCases.updateWeeklyLimits]; a row that did not is inserted with
 * [useCases.addMonitoredAppWithWeeklyLimits], the insert being the only path that can be
 * *refused* — the repository is an `INSERT OR IGNORE`, so the app became monitored behind this
 * screen and the save reports [WeeklyLimitUiState.saveConflict], writes nothing and does not
 * navigate. An update is re-read after the write and the read must match the draft, because a
 * "saved" the dashboard then contradicts is the exact failure this screen exists to prevent.
 *
 * **One write in flight at a time.** [save] is a no-op while a save is running, which is the guard
 * against a double tap inserting the row twice; the latch stays set on success because success
 * ends the screen.
 */
class WeeklyLimitViewModel(
    private val requestedPackageName: String?,
    private val installedAppsProvider: InstalledAppsProvider,
    private val useCases: MeotimeUseCases,
) : ViewModel() {

    private val _state = MutableStateFlow(WeeklyLimitUiState())
    val state: StateFlow<WeeklyLimitUiState> = _state.asStateFlow()

    private var resolveJob: Job? = null

    init {
        resolve()
    }

    /**
     * Re-runs the read behind the error state, the same way as [SetLimitViewModel.retry]: the
     * previous attempt is cancelled first, the error flag is cleared while the re-read runs, and
     * a package the platform does not list falls back to the package id rather than failing.
     */
    fun retry() {
        resolve()
    }

    /** The Switch on a row's [day]: enabled when the day is limited, disabled when it is not. */
    fun setDayEnabled(day: DayOfWeek, enabled: Boolean) {
        _state.update { current -> current.copy(enabled = if (enabled) current.enabled + day else current.enabled - day) }
    }

    /**
     * Opens the picker for [day]. The wheel starts from the value the row shows — the remembered
     * value when there is one, [DEFAULT_LIMIT_MINUTES] otherwise — so opening the picker and
     * saving it without touching the wheels writes the same value back.
     */
    fun openDayPicker(day: DayOfWeek) {
        _state.update { current -> current.copy(picker = PickerTarget.Day(day), pickerMinutes = current.dayPickerMinutes(day)) }
    }

    /**
     * Opens the all-days picker — the one the `Daily` chip opens — starting from the first
     * limited day's value, so tapping `Daily` then Save writes that duration to all seven days.
     */
    fun openAllDaysPicker() {
        _state.update { current -> current.copy(picker = PickerTarget.AllDays, pickerMinutes = current.allDaysPickerMinutes()) }
    }

    /** Writes the wheel position; wheels are capped at 0..1440 by the picker's own construction. */
    fun setPickerMinutes(value: Int) {
        _state.update { it.copy(pickerMinutes = value) }
    }

    /**
     * The picker's Save: commits the edit to the schedule and closes the picker.
     *
     * `Day` writes only that day (the remembered value is replaced, the rest of the schedule is
     * untouched); `AllDays` writes the one value to all seven days and leaves every day enabled.
     */
    fun confirmPicker() {
        val snapshot = _state.value
        when (val picker = snapshot.picker) {
            null -> {}

            is PickerTarget.Day -> _state.update { current ->
                current.copy(
                    minutes = current.minutes.withDay(picker.day, snapshot.pickerMinutes),
                    enabled = current.enabled + picker.day,
                    picker = null,
                )
            }

            PickerTarget.AllDays -> _state.update { current ->
                current.copy(
                    minutes = current.minutes.withAll(snapshot.pickerMinutes),
                    enabled = DayOfWeek.entries.toSet(),
                    picker = null,
                )
            }
        }
    }

    /**
     * The picker's ghost action: `Remove limit for [day]` turns the day off; `Remove limits for
     * all days` empties the schedule. The remembered values are kept, so a later re-enable
     * restores them — the same path `Clear` uses.
     */
    fun removeFromPicker() {
        val picker = _state.value.picker ?: return
        when (picker) {
            is PickerTarget.Day -> _state.update { it.copy(enabled = it.enabled - picker.day, picker = null) }
            PickerTarget.AllDays -> _state.update { it.copy(enabled = emptySet(), picker = null) }
        }
    }

    /** Back out of the picker without committing: nothing changes, nothing is written. */
    fun closePicker() {
        _state.update { it.copy(picker = null) }
    }

    /**
     * The quick-set chips: `Daily` opens the all-days picker (it writes nothing by itself);
     * `Weekdays`/`Weekends` enable those days; `Clear` empties the schedule and keeps the
     * remembered values so re-enabling a day restores them.
     */
    fun applyQuickSet(quickSet: QuickSet) {
        when (quickSet) {
            QuickSet.DAILY -> _state.update { current ->
                current.copy(picker = PickerTarget.AllDays, pickerMinutes = current.allDaysPickerMinutes())
            }
            QuickSet.WEEKDAYS -> _state.update { it.copy(enabled = WEEKDAYS) }
            QuickSet.WEEKENDS -> _state.update { it.copy(enabled = WEEKENDS) }
            QuickSet.CLEAR -> _state.update { it.copy(enabled = emptySet()) }
        }
    }

    /**
     * Persists the app's weekly schedule, then hands control to [onDone] — the screen navigates,
     * this class does not know where to.
     *
     * Nothing is written while the state cannot be saved, and nothing is written while a save is
     * in flight, so the only way to write twice is a repository that ignores its input.
     */
    fun save(onDone: () -> Unit) {
        val snapshot = _state.value
        val packageName = snapshot.packageName
        if (!snapshot.canSave || packageName == null) return
        _state.update { it.copy(saving = true, saveFailed = false, saveConflict = false) }
        viewModelScope.launch {
            when (val outcome = write(snapshot, packageName)) {
                WeeklySaveOutcome.WRITTEN ->
                    // The latch stays raised: the screen is leaving, and clearing it would open
                    // the window for a second write between the write and the navigation.
                    onDone()

                WeeklySaveOutcome.CONFLICT -> _state.update { it.copy(saving = false, saveConflict = true) }

                WeeklySaveOutcome.FAILED -> _state.update { it.copy(saving = false, saveFailed = true) }

                WeeklySaveOutcome.FAILED_ROW_GONE -> _state.update { it.copy(saving = false, saveFailed = true, alreadyMonitored = false) }
            }
        }
    }

    /**
     * The write itself, as an outcome the screen can act on.
     *
     * The two write shapes are kept apart because only the insert can be *refused*. The update
     * re-reads afterwards, and the read answers whether the write landed: a row that is *absent*
     * means the update matched nothing ([SaveOutcome.FAILED_ROW_GONE]); a row that is *present
     * with another schedule* means the write lost a race it cannot prove it won ([SaveOutcome.FAILED]).
     */
    private suspend fun write(snapshot: WeeklyLimitUiState, packageName: String): WeeklySaveOutcome = try {
        if (snapshot.alreadyMonitored) {
            useCases.updateWeeklyLimits(packageName, snapshot.draft)
            val after = useCases.getMonitoredApp(packageName)
            when {
                after == null -> WeeklySaveOutcome.FAILED_ROW_GONE
                after.usesWeeklyLimits != true || after.weeklyLimits != snapshot.draft -> WeeklySaveOutcome.FAILED
                else -> WeeklySaveOutcome.WRITTEN
            }
        } else {
            if (useCases.addMonitoredAppWithWeeklyLimits(packageName, snapshot.appName, snapshot.draft)) {
                WeeklySaveOutcome.WRITTEN
            } else {
                WeeklySaveOutcome.CONFLICT
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        WeeklySaveOutcome.FAILED
    }

    /**
     * Reads the label and the current schedule, then publishes a state that is ready to be
     * saved.
     *
     * A missing or blank package short-circuits to the terminal [WeeklyLimitUiState.missingArgument]
     * state without touching either seam. A new app starts enabled on all seven days at
     * [DEFAULT_LIMIT_MINUTES], so a bare Save creates a working default schedule. A weekly row
     * prefills from its schedule; a legacy row (marker false) is defensively converted to a
     * schedule of the daily value on every day.
     */
    private fun resolve() {
        resolveJob?.cancel()
        val packageName = requestedPackageName?.takeIf { it.isNotBlank() }
        if (packageName == null) {
            _state.value = WeeklyLimitUiState(loading = false, missingArgument = true)
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
                _state.value = WeeklyLimitUiState(loading = false, error = true)
            } else {
                val (label, existing) = resolved
                val current = _state.value
                val (minutes, enabled) = when {
                    existing == null -> current.minutes to current.enabled
                    existing.usesWeeklyLimits -> existing.weeklyLimits to
                        DayOfWeek.entries.filter { existing.weeklyLimits.forDay(it) != null }.toSet()
                    else -> existing.weeklyLimits.withAll(existing.dailyLimitMinutes) to DayOfWeek.entries.toSet()
                }
                _state.update { state ->
                    state.copy(
                        packageName = packageName,
                        appName = label,
                        minutes = minutes,
                        enabled = enabled,
                        alreadyMonitored = existing != null,
                        loading = false,
                        error = false,
                    )
                }
            }
        }
    }

    /** The minutes a [day] picker opens with: the day's remembered value when it has one, [DEFAULT_LIMIT_MINUTES] when it does not. */
    private fun WeeklyLimitUiState.dayPickerMinutes(day: DayOfWeek): Int =
        minutes.forDay(day)?.let(::clampLimitMinutes) ?: DEFAULT_LIMIT_MINUTES

    /**
     * The minutes the all-days picker opens with: the first limited day's value — what the user
     * last set anywhere in the week — or the default when the schedule is empty.
     */
    private fun WeeklyLimitUiState.allDaysPickerMinutes(): Int {
        for (day in DayOfWeek.entries) {
            draft.forDay(day)?.let { return it }
        }
        return DEFAULT_LIMIT_MINUTES
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
