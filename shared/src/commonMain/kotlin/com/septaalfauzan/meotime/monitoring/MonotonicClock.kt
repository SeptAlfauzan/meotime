package com.septaalfauzan.meotime.monitoring

/**
 * The only clock session arithmetic is allowed to read.
 *
 * A parameter rather than a direct `SystemClock` call for two reasons. On Android it binds to
 * `SystemClock.uptimeMillis()` rather than `elapsedRealtime()`: uptime does not advance while the
 * device is asleep, so a session spanning a doze records awake time only, and the ticker's own
 * `delay` — which *is* driven by `elapsedRealtime` — stalling under doze is harmless because the
 * reading it produces on wake comes from here, which never moved. In tests it binds to
 * `TestScope.currentTime`, which is what makes every timing assertion in `MonitoringCoreTest`
 * virtual and instant.
 *
 * It is also monotonic, so a user changing the wall clock or the timezone mid-session cannot
 * corrupt a running session. The one wall-clock-derived value in the monitoring path is
 * `TodayKey.todayIso()`, and it is only read at the moment of writing.
 */
fun interface MonotonicClock {
    fun uptimeMillis(): Long
}
