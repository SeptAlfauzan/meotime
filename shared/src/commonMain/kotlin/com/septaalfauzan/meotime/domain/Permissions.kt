package com.septaalfauzan.meotime.domain

data class PermissionStatus(
    val usageAccess: Boolean,
    val overlay: Boolean,
    val notification: Boolean,
) {
    val allGranted: Boolean get() = usageAccess && overlay && notification
}

fun interface PermissionStatusProvider {
    fun status(): PermissionStatus
}

interface PermissionNavigator {
    fun openUsageAccess()
    fun openOverlay()

    /**
     * Opens this app's own notification settings page.
     *
     * A separate method from [openUsageAccess] and [openOverlay] because `POST_NOTIFICATIONS` has a
     * failure mode those two do not: after two denials the system stops showing the request dialog
     * altogether, so re-requesting is a request it silently ignores. Onboarding then shows a
     * "Grant" button that can never work and an `allGranted` that can never become true — a dead
     * end with no exit except the user finding Settings by themselves. This is the only route
     * that still works once the dialog is gone.
     */
    fun openNotificationSettings()
}

/**
 * Asks the platform for `POST_NOTIFICATIONS`, reporting the answer through [onResult].
 *
 * A fun-interface taking a callback rather than a bare `request()` because the grant is a
 * runtime dialog on API 33+: the answer arrives asynchronously, and a view model holding only a
 * one-shot lambda would never learn it. Shared code cannot hold an `ActivityResultLauncher`, so
 * the host supplies the implementation and common code only ever sees this shape.
 */
fun interface NotificationPermissionRequest {
    fun request(onResult: () -> Unit)
}
