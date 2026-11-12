package com.septaalfauzan.meotime.monitoring

/**
 * The one definition of "over the limit", shared by the monitoring core and the dashboard so
 * the two cannot disagree about whether an app has been blocked.
 *
 * Seconds on both sides, and `>=` rather than `>`: a limit of 30 minutes is reached *at* 1800
 * seconds, not one tick past it.
 */
object LimitChecker {
    fun exceeds(usageSeconds: Long, limitSeconds: Long): Boolean = usageSeconds >= limitSeconds
}
