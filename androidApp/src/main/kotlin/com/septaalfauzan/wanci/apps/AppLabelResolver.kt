package com.septaalfauzan.wanci.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.septaalfauzan.wanci.domain.InstalledAppCheck

/**
 * Resolves one package at a time. Kept apart from [InstalledAppsProviderAndroid] because the
 * dashboard only needs "does this row still point at something real?", and enumerating every
 * launcher activity to answer that would be wasted work on a cold start.
 */
class AppLabelResolver(private val context: Context) {

    fun resolveAppName(packageName: String, fallback: String): String =
        applicationInfoOrNull(packageName)
            ?.let { context.packageManager.getApplicationLabel(it).toString() }
            ?: fallback

    fun isInstalled(packageName: String): Boolean = applicationInfoOrNull(packageName) != null

    /** Adapts this resolver to the `fun interface` the presentation layer depends on. */
    fun asInstalledAppCheck(): InstalledAppCheck = InstalledAppCheck { packageName ->
        isInstalled(packageName)
    }

    /**
     * An uninstalled app is a normal state here, not an error: a monitored app can disappear any
     * time and the dashboard has to keep rendering its row. `NameNotFoundException` is therefore
     * the answer for "gone", and nothing else is swallowed.
     */
    private fun applicationInfoOrNull(packageName: String): ApplicationInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
