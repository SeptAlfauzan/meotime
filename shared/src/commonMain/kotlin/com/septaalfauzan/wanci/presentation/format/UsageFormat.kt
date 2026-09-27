package com.septaalfauzan.wanci.presentation.format

/**
 * A recorded duration in the words a person would use.
 *
 * Lives in its own file rather than beside the settings view model because it is a property of
 * *usage*, not of one screen: the dashboard row and the settings page both print it, and the
 * overlay — which runs over another app with no view model in sight — is what finally
 * needs a number, once that screen is converted.
 *
 * Deliberately *not* `com.septaalfauzan.wanci.presentation.limit.formatLimitMinutes`, which
 * clamps into `1..1440` because it formats a limit and 0 minutes is not a limit a user can
 * store. A usage reading has the opposite shape: 0 is the common case, and a day can overrun its
 * limit by more than 24 hours. So no clamp, and a negative reading is floored at zero rather
 * than rendered as one.
 *
 * Seconds appear only below a minute. Above that it would be noise on a dashboard row, and
 * minute granularity is what the rest of the screen already speaks in.
 */
fun formatUsage(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    if (total < 60L) return "$total sec"
    val minutes = total / 60
    val hours = minutes / 60
    val restMinutes = minutes % 60
    val hourLabel = if (hours == 1L) "hour" else "hours"
    return when {
        hours == 0L -> "$minutes min"
        restMinutes == 0L -> "$hours $hourLabel"
        else -> "$hours $hourLabel $restMinutes min"
    }
}
