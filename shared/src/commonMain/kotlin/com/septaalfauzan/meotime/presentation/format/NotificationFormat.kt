package com.septaalfauzan.meotime.presentation.format

/**
 * The running-total text the persistent notification shows while an app is in the foreground:
 * `"<app> · <usage> / <limit>"`. A day with no limit drops the slash segment — `"<app> ·
 * <usage>"` — because there is no limit to compare against.
 *
 * [formatShortDuration] builds both durations, so every duration a person reads as a unit in
 * this text agrees with the rest of the app's notation ("2h", "30m").
 */
fun formatSessionNotificationText(
    appName: String,
    liveSeconds: Long,
    limitSeconds: Long?,
): String = buildString {
    // Mid-dot, not a hyphen or a colon: a hyphen is ambiguous against the negative-looking
    // difference between usage and limit, a colon wraps on narrow shade rows.
    append(appName)
    append(" \u00B7 ")
    append(formatShortDuration(liveSeconds))
    limitSeconds?.let {
        append(" / ")
        append(formatShortDuration(it))
    }
}
