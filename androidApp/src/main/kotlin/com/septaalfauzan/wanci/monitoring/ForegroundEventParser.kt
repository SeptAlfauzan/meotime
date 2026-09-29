package com.septaalfauzan.wanci.monitoring

import android.app.usage.UsageEvents

/**
 * One `UsageEvents.Event`, reduced to the two fields the parser reads. A local type rather than
 * the platform class so the parser can be host-tested without a `UsageStatsManager` or Robolectric
 * — the monitoring service does the reduction in one place, when it is built.
 */
internal data class RawUsageEvent(
    val eventType: Int,
    val packageName: String?,
)

/**
 * The package in the foreground at the **end** of the window, or `null` if the window ends with
 * the foreground app going away.
 *
 * Only the last relevant event is consulted, which is what makes a pause-and-resume of the same
 * app — a dialog, an in-app activity, a permission prompt — resolve to that app instead of to
 * "nobody". Reading every event in order would drop the session on each of those.
 *
 * `ACTIVITY_PAUSED` counts as background, which is the only way a leave-home is visible: the
 * launcher emits no `ACTIVITY_RESUMED`, so without the pause the previous app would look like it
 * was still in the foreground until the user came back.
 *
 * `ACTIVITY_RESUMED` and `MOVE_TO_FOREGROUND` are two names for the same constant (1), and
 * `ACTIVITY_PAUSED` and `MOVE_TO_BACKGROUND` likewise (2) — `api-versions.xml` dates the former
 * pair to API 29 and the latter to API 1. Only one of each pair appears in the `when` above, so
 * both branches are listed for the reader, not because two types are handled. Neither is ever
 * resolved at runtime: both are `static final int` with a ConstantValue, so they inline to
 * literals at compile time.
 *
 * `SCREEN_NON_INTERACTIVE` (16) collides with nothing — `MOVE_TO_FOREGROUND` is 1 — so it reaches
 * the `else` branch and is ignored. That is what a screen-off event should do: it carries no
 * package, and reading it as either a foreground or a background signal would attribute the
 * user's last app with usage, or wipe a live session, at the exact moment the screen went off.
 * The poll loop is gated on the screen being on, and the `SCREEN_OFF` broadcast closes the session
 * through a channel that cannot be ambiguous.
 */
internal fun parseForegroundPackage(events: List<RawUsageEvent>): String? {
    var foreground: String? = null
    for (event in events) {
        when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND,
            UsageEvents.Event.ACTIVITY_RESUMED,
            -> foreground = event.packageName

            UsageEvents.Event.MOVE_TO_BACKGROUND,
            UsageEvents.Event.ACTIVITY_PAUSED,
            -> foreground = null

            else -> Unit
        }
    }
    return foreground
}

/**
 * Whether the window said anything about the foreground at all.
 *
 * [parseForegroundPackage] returns `null` for two different situations — "the app in the
 * foreground went away" and "nothing relevant happened" — and the poll loop has to tell them
 * apart. Reading the first as the second would close the user's session on every quiet second, and
 * the accrual would restart from zero each time.
 *
 * Only the four activity types count. `SCREEN_NON_INTERACTIVE` (16) does not: it says the screen
 * went off, not that an app came or went, and a screen-off on its own must not be read as "the
 * foreground app left". Screen-off is handled by a broadcast instead, which is unambiguous.
 */
internal fun hasForegroundSignal(events: List<RawUsageEvent>): Boolean =
    events.any { event ->
        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
            event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
            event.eventType == UsageEvents.Event.ACTIVITY_PAUSED
    }
