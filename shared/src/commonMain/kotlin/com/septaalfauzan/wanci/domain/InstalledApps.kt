package com.septaalfauzan.wanci.domain

/**
 * An installed, launchable app as the platform reports it. [packageName] is the stable
 * identity used everywhere else (limits, usage rows, deep links); [appName] is a
 * human-readable label that can change at any time and must never be persisted as a key.
 */
data class InstalledApp(
    val packageName: String,
    val appName: String,
)

/** Platform seam: Android implements it with `PackageManager`; iOS has no equivalent yet. */
fun interface InstalledAppsProvider {
    fun getInstalledApps(): List<InstalledApp>
}

/**
 * Single-package probe, kept separate from [InstalledAppsProvider] because the dashboard
 * needs "is this still installed?" for a handful of rows and must not enumerate the whole
 * launcher list to answer it.
 */
fun interface InstalledAppCheck {
    fun isInstalled(packageName: String): Boolean
}
