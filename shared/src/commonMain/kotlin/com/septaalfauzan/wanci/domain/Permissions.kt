package com.septaalfauzan.wanci.domain

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
