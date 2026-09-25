package com.septaalfauzan.wanci.domain

data class PermissionStatus(
    val usageAccess: Boolean,
    val overlay: Boolean,
    val accessibility: Boolean,
) {
    val allGranted: Boolean get() = usageAccess && overlay && accessibility
}

fun interface PermissionStatusProvider {
    fun status(): PermissionStatus
}

interface PermissionNavigator {
    fun openUsageAccess()
    fun openOverlay()
    fun openAccessibility()
}
