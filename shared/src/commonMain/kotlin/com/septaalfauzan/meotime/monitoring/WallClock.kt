package com.septaalfauzan.meotime.monitoring

import kotlin.time.Clock

/**
 * Wall-clock epoch time, separate from [MonotonicClock].
 *
 * The monotonic clock decides how many seconds a session lasted; this one decides which local
 * hours those seconds belong to. Keeping the two apart is what lets a doze-stalled ticker stay
 * honest without letting a device clock change alter the duration.
 */
fun interface WallClock {
    fun nowEpochMillis(): Long
}

val SystemWallClock = WallClock { Clock.System.now().toEpochMilliseconds() }
