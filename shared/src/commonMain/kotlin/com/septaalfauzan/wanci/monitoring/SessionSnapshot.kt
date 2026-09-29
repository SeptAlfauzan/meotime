package com.septaalfauzan.wanci.monitoring

/**
 * A read-only picture of an open session, published by [MonitoringCore] so a consumer can show
 * what is happening without asking the core anything.
 *
 * [liveSeconds] is deliberately the same arithmetic the core uses internally, so a consumer that
 * renders a running total and the core that enforces the limit can never disagree by a second.
 * Nothing here writes to the repository: a snapshot is a reading, not an update.
 */
data class SessionSnapshot(
    val packageName: String,
    val appName: String,
    val startedAtMillis: Long,
    val storedSeconds: Long,
    val limitSeconds: Long,
) {
    fun liveSeconds(nowMillis: Long): Long = storedSeconds + (nowMillis - startedAtMillis) / 1000
}
