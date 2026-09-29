package com.septaalfauzan.wanci.monitoring

import com.septaalfauzan.wanci.monitoring.MonotonicClock
import com.septaalfauzan.wanci.presentation.format.formatShortDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the persistent notification is currently saying.
 *
 * A closed set of three, so a caller cannot put the notification into a state nobody thought about.
 * [Session] carries the whole snapshot because the content is derived from the session's own start
 * timestamp, never from a counter this class keeps.
 */
sealed interface NotificationState {
    /** No monitored app is in the foreground. Monitoring itself is running. */
    data object Idle : NotificationState

    /** A monitored app is in the foreground and accruing. */
    data class Session(val snapshot: SessionSnapshot) : NotificationState

    /** Usage access was revoked mid-run. The poll loop is stopped; onboarding is the way back. */
    data object UsageAccessNeeded : NotificationState
}

/** Where the rendered text goes. The Android implementation posts it; a test records it. */
fun interface Notifier {
    fun update(title: String, text: String)
}

/**
 * Mirrors [MonitoringCore]'s session into a notification, on its own slow cadence.
 *
 * The cadence is the point. The core ticks every second; posting every tick would be 60
 * notifications a minute, which the shade would treat as noise. This republishes only when the
 * *state* changes — a session opens, a session closes, a limit is crossed — and then once a
 * minute while a session is open, purely so the elapsed figure in the shade does not go stale.
 *
 * The elapsed figure is read from the snapshot's own start timestamp, so it agrees with what the
 * core enforces, and this class never writes to the repository.
 */
class NotificationController(
    private val notifier: Notifier,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    private val rolloverIntervalMillis: Long = 60_000L,
) {

    var state: NotificationState = NotificationState.Idle
        private set

    private var rolloverJob: Job? = null

    /** Called by the service before `startForeground`, so the very first frame is not blank. */
    fun start() {
        rolloverJob?.cancel()
        rolloverJob = null
        publish(NotificationState.Idle)
    }

    fun onSessionChanged(snapshot: SessionSnapshot?) {
        if (snapshot == null) {
            stopRollover()
            publish(NotificationState.Idle)
            return
        }
        publish(NotificationState.Session(snapshot))
        startRollover()
    }

    /**
     * The core decided the limit is reached. Republishing immediately rather than waiting out the
     * rollover means the shade shows the crossing as it happens, which is the one moment the user
     * is looking at it.
     */
    fun onLimitCrossed() {
        if (state is NotificationState.Session) publish(state)
    }

    fun onUsageAccessRevoked() {
        stopRollover()
        publish(NotificationState.UsageAccessNeeded)
    }

    private fun startRollover() {
        if (rolloverJob != null) return
        rolloverJob = scope.launch {
            while (isActive) {
                delay(rolloverIntervalMillis)
                if (state !is NotificationState.Session) return@launch
                publish(state)
            }
        }
    }

    private fun stopRollover() {
        rolloverJob?.cancel()
        rolloverJob = null
    }

    private fun publish(next: NotificationState) {
        state = next
        notifier.update(next.title(), next.text())
    }

    private fun NotificationState.title(): String = when (this) {
        NotificationState.Idle -> "Monitoring active"
        NotificationState.UsageAccessNeeded -> "Usage Access needed"
        is NotificationState.Session -> snapshot.appName
    }

    private fun NotificationState.text(): String = when (this) {
        NotificationState.Idle -> "Monitoring active"
        NotificationState.UsageAccessNeeded -> "Tap to grant it again."
        is NotificationState.Session -> buildString {
            // Mid-dot, not a hyphen or a colon: a hyphen is ambiguous against the negative-looking
            // difference between usage and limit, a colon wraps on narrow shade rows.
            append(snapshot.appName)
            append(" \u00B7 ")
            append(formatShortDuration(snapshot.liveSeconds(clock.uptimeMillis())))
            append(" / ")
            append(formatShortDuration(snapshot.limitSeconds))
        }
    }
}
