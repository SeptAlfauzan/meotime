package com.septaalfauzan.meotime.monitoring

import com.septaalfauzan.meotime.data.local.TodayKey
import com.septaalfauzan.meotime.data.repository.ScreenTimeRepository
import com.septaalfauzan.meotime.domain.limit.effectiveLimitMinutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * Decides when a restricted app has had enough. One app is in the foreground at a time, so this
 * holds exactly one **session** at a time: a contiguous foreground run, opened when a monitored,
 * enabled, non-Meotime package takes the foreground and closed when any other package does, or when
 * the screen turns off (which arrives as a null package). Repeated events for the same package —
 * an activity change, an in-app dialog, a permission prompt — are not switches and do not restart it.
 *
 * Elapsed time is never accumulated by counting. The session remembers when it started and every
 * reading of "how long has this been" is [Session.liveSeconds] against [clock], so a tick cannot
 * drift from the clock because it never holds a counter to drift. That is also what makes the
 * whole thing testable: hand [clock] a value the test controls and time stops being a wall-clock
 * cost.
 *
 * Usage is written exactly once, when the session closes. Nothing is written during a session, so
 * an open session is unpersisted and a process death loses it. That is a deliberate trade, and
 * `UsageStatsManager` is read again since the 2026-09-28 migration — for foreground detection only.
 * It is not a second account of usage: the core still receives only `onForegroundChange` and still
 * writes once, at close.
 *
 * Five things are true here that the code does not spell out:
 *
 *  - [clock] must exclude deep sleep, or a session spanning a doze bills the user for sleep.
 *    See [MonotonicClock].
 *  - The ticker stopping does not stop accrual. It exits when it fires on the crossing, but the
 *    session stays open with its original start time, so time spent on a blocked app after the
 *    overlay is dismissed is still recorded on close. Accrual lives in the start timestamp, not
 *    in the ticker.
 *  - An event for the package already in the foreground is dropped before anything else happens.
 *    Closing and reopening there would write a partial session and restart the clock, turning one
 *    run into two writes and losing the sub-second remainder of the first.
 *  - The previous session is closed in the same call that opens the next one, and it is closed
 *    *first*. They are different packages, and the close writes to the database the open reads.
 *  - [Session.overlayShown] is per session, not per day. An app already over its limit is
 *    announced on every entry, every time. A per-day suppression set would let a user who hit
 *    the limit once walk past it for the rest of the day.
 *
 * A day with no limit still gets a session: it runs until the app leaves or the screen turns off,
 * and usage is recorded on close. The one exception is midnight rollover: a tick that finds the
 * session's cached start date behind [todayKey]'s current date closes the old session and opens
 * a fresh one for the same package under the new day's limit, so a rule never leaks across days.
 */
class MonitoringCore(
    private val repository: ScreenTimeRepository,
    private val todayKey: TodayKey,
    private val selfPackageName: String,
    private val clock: MonotonicClock,
    private val wallClock: WallClock = SystemWallClock,
    private val scope: CoroutineScope,
    private val onOverlayDecision: (OverlayDecision) -> Unit,
    /**
     * Published whenever the core's session changes: a non-null snapshot when a session opens, and
     * `null` when one closes. Defaulted to `null` so every existing construction site — and the
     * whole existing test suite — keeps compiling unchanged.
     *
     * Not fired by [detach]: the service is going away and its notification with it, so a close
     * there would be reported to a listener that is already being torn down.
     */
    private val onSessionChanged: ((SessionSnapshot?) -> Unit)? = null,
    private val tickIntervalMillis: Long = 1_000L,
) {
    data class OverlayDecision(
        val packageName: String,
        val appName: String,
        val usageSeconds: Long,
        val limitSeconds: Long,
    )

    private class Session(
        val packageName: String,
        val appName: String,
        val startedAtMillis: Long,
        val startedAtEpochMillis: Long,
        val storedSeconds: Long,
        val startedAtDate: LocalDate,
        /** The effective per-day limit, or `null` on a no-limit day. */
        val limitSeconds: Long?,
        var overlayShown: Boolean,
    ) {
        fun liveSeconds(nowMillis: Long): Long = storedSeconds + (nowMillis - startedAtMillis) / 1000

        fun toDecision(liveSeconds: Long, limitSeconds: Long) =
            OverlayDecision(packageName, appName, liveSeconds, limitSeconds)

        fun toSnapshot() =
            SessionSnapshot(packageName, appName, startedAtMillis, storedSeconds, limitSeconds)
    }

    private var currentSession: Session? = null
    private var tickJob: Job? = null
    private var detached = true

    /** Exposed for tests: a ticking session is the only reason this core holds a coroutine. */
    internal val activeTick: Job? get() = tickJob

    /**
     * Begins accepting foreground changes. The core is a process-wide singleton and Android
     * restarts the foreground service in the same process, so [detach] has to be reversible
     * rather than terminal. Called from `onStartCommand`.
     */
    fun attach() {
        detached = false
    }

    /**
     * Stops everything immediately and synchronously, for `Service.onDestroy`,
     * which cannot wait for a coroutine.
     *
     * Cancelling the ticker *before returning* is the point: once this returns, no tick can fire,
     * so the caller can remove the overlay knowing nothing will put it straight back. Closing the
     * session instead would queue a coroutine and leave exactly the race this avoids. The open
     * session's unpersisted seconds are dropped, which is the same loss as any other process
     * death — the two paths now fail identically instead of one of them racing.
     */
    fun detach() {
        tickJob?.cancel()
        tickJob = null
        currentSession = null
        detached = true
    }

    suspend fun onForegroundChange(packageName: String?) {
        if (detached) return
        // Every foreground source — a window-state change, a usage event, an activity change, an
        // in-app dialog, a permission prompt — repeats the same package for what is not a switch,
        // so those must not reach the close path at all: closing would persist a partial session
        // and reopening would restart the clock, and the run would become two writes instead of one.
        if (packageName == currentSession?.packageName) return
        val now = clock.uptimeMillis()
        closeCurrentSession(now)
        val session = createSession(packageName, now) ?: return
        currentSession = session
        // Published before the over-limit branch in [afterSessionOpened], so the immediate-fire
        // branch there cannot swallow the open. A consumer that shows a running total has to hear
        // about an over-limit entry too.
        onSessionChanged?.invoke(session.toSnapshot())
        afterSessionOpened(session, now)
    }

    /**
     * The steps a freshly opened session takes, shared by the foreground-change path and the
     * midnight rollover in [startTicking]. On a no-limit day the session stops here — no over
     * check, no ticker — because there is nothing to enforce. Otherwise it fires immediately
     * when the session is already over, or starts the ticker.
     */
    private suspend fun afterSessionOpened(session: Session, now: Long) {
        val limitSeconds = session.limitSeconds ?: return
        val live = session.liveSeconds(now)
        if (LimitChecker.exceeds(live, limitSeconds)) {
            // Over before a second has passed. Announced now and never ticked — there is nothing
            // left to watch for. The session still closes normally, so time spent on the blocked
            // app is still recorded.
            session.overlayShown = true
            onOverlayDecision(session.toDecision(live, limitSeconds))
            return
        }
        startTicking()
    }

    private suspend fun closeCurrentSession(now: Long) {
        tickJob?.cancel()
        tickJob = null
        val session = currentSession ?: return
        currentSession = null
        // Fires even when nothing is written below: a session that closed after zero elapsed
        // seconds is still a state change, and a consumer mirroring "an app is open" has to hear
        // about it or it would show an app that is no longer in the foreground.
        onSessionChanged?.invoke(null)
        val elapsed = (now - session.startedAtMillis) / 1000
        if (elapsed <= 0L) return
        try {
            repository.recordUsageSession(
                packageName = session.packageName,
                deltaSeconds = elapsed,
                startedAtEpochMillis = session.startedAtEpochMillis,
                endedAtEpochMillis = wallClock.nowEpochMillis(),
            )
        } catch (_: Exception) {
            // The session is closed and the state is consistent, so a failed write costs the
            // seconds it carried and nothing else. Letting it escape would abort before the next
            // session is opened, so one database error would leave whatever app the user switched
            // to untracked for the rest of the foreground period.
        }
    }

    private suspend fun createSession(packageName: String?, now: Long): Session? {
        if (packageName == null || packageName == selfPackageName) return null
        val app = repository.getMonitoredApp(packageName) ?: return null
        if (!app.enabled) return null
        val date = LocalDate.parse(todayKey.todayIso())
        return Session(
            packageName = packageName,
            appName = app.appName,
            startedAtMillis = now,
            startedAtEpochMillis = wallClock.nowEpochMillis(),
            storedSeconds = repository.getTodayUsageSeconds(packageName, todayKey.todayIso()),
            startedAtDate = date,
            // The one place the limit's authoring unit becomes an arithmetic unit. A null here
            // is a no-limit day: the session still runs, but nothing ticks for it.
            limitSeconds = effectiveLimitMinutes(app, date)?.times(60L),
            overlayShown = false,
        )
    }

    private fun startTicking() {
        tickJob = scope.launch {
            while (isActive) {
                delay(tickIntervalMillis)
                val session = currentSession ?: return@launch
                if (session.overlayShown) return@launch
                val now = clock.uptimeMillis()
                if (LocalDate.parse(todayKey.todayIso()) != session.startedAtDate) {
                    // Midnight rollover: a rule must not leak across days, so the old session
                    // closes and a fresh one opens for the same package under the new day's
                    // limit. This loop IS the current tick job, so it detaches itself first —
                    // close, reopen, then either fire immediately or start ticking again — and
                    // returns so the old loop cannot continue with the superseded session.
                    tickJob = null
                    closeCurrentSession(now)
                    val reopened = createSession(session.packageName, now)
                    if (reopened != null) {
                        currentSession = reopened
                        onSessionChanged?.invoke(reopened.toSnapshot())
                        afterSessionOpened(reopened, now)
                    }
                    return@launch
                }
                val limitSeconds = session.limitSeconds ?: return@launch
                val live = session.liveSeconds(now)
                if (!LimitChecker.exceeds(live, limitSeconds)) continue
                session.overlayShown = true
                tickJob = null
                onOverlayDecision(session.toDecision(live, limitSeconds))
                return@launch
            }
        }
    }
}
