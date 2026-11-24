package com.septaalfauzan.meotime.monitoring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One renderable reading of an open session. `percent` never reaches 100: the limit crossing is
 * the core's decision, not the tracker's. */
data class TrackerFrame(
    val packageName: String,
    val appName: String,
    val usageSeconds: Long,
    val limitSeconds: Long,
    val percent: Int,
    val nearLimit: Boolean,
    val expanded: Boolean,
)

/** The platform draws whatever the controller last published; a `null` frame means hide. */
fun interface TrackerRenderer {
    fun render(frame: TrackerFrame?)
}

/**
 * Turns [SessionSnapshot] decisions from [MonitoringCore] into a running overlay. The core owns
 * enforcement and publishes an open session once; this controller owns only presentation: it
 * re-reads the snapshot's live usage every second while the session is open, expanded or collapsed,
 * and keeps the expand timer for three seconds.
 *
 * One snapshot at a time. A close or a limit crossing ends it synchronously — both jobs are
 * cancelled before returning so no delayed tick can republish a frame after the overlay is gone.
 * A no-limit day is never published by the core, so a snapshot here always has a limit.
 */
class UsageTrackerController(
    private val renderer: TrackerRenderer,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    private val tickIntervalMillis: Long = 1_000L,
    private val expandTimeoutMillis: Long = 3_000L,
) {
    private var snapshot: SessionSnapshot? = null
    private var expanded = false
    private var tickJob: Job? = null
    private var collapseJob: Job? = null

    fun onSessionOpened(snapshot: SessionSnapshot) {
        this.snapshot = snapshot
        expanded = false
        collapseJob?.cancel()
        collapseJob = null
        if (tickJob == null) startTicking()
        publish()
    }

    fun onSessionClosed() = hide()

    fun onLimitCrossed() = hide()

    fun onPillTapped() {
        expanded = !expanded
        collapseJob?.cancel()
        collapseJob = null
        if (expanded) {
            collapseJob = scope.launch {
                delay(expandTimeoutMillis)
                expanded = false
                collapseJob = null
                publish()
            }
        }
        publish()
    }

    private fun hide() {
        snapshot = null
        expanded = false
        tickJob?.cancel()
        tickJob = null
        collapseJob?.cancel()
        collapseJob = null
        renderer.render(null)
    }

    private fun startTicking() {
        tickJob = scope.launch {
            while (isActive) {
                delay(tickIntervalMillis)
                if (snapshot == null) return@launch
                publish()
            }
        }
    }

    private fun publish() {
        val current = snapshot
        if (current == null) {
            renderer.render(null)
            return
        }
        val limitSeconds = current.limitSeconds!!
        val usageSeconds = current.liveSeconds(clock.uptimeMillis())
        renderer.render(
            TrackerFrame(
                packageName = current.packageName,
                appName = current.appName,
                usageSeconds = usageSeconds,
                limitSeconds = limitSeconds,
                percent = (usageSeconds * 100 / limitSeconds).coerceIn(0, 99).toInt(),
                nearLimit = limitSeconds - usageSeconds <= limitSeconds * 0.10,
                expanded = expanded,
            ),
        )
    }
}
