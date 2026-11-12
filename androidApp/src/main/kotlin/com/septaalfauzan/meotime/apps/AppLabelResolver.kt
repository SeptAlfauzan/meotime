package com.septaalfauzan.meotime.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.septaalfauzan.meotime.domain.InstalledAppCheck

/**
 * Resolves one package at a time. Kept apart from [InstalledAppsProviderAndroid] because the
 * dashboard only needs "does this row still point at something real?", and enumerating every
 * launcher activity to answer that would be wasted work on a cold start.
 */
class AppLabelResolver(private val context: Context) {

    /**
     * Whether the package still points at something on the device. The only question the
     * presentation layer asks of this class: the dashboard resolves it per row, and an app's own
     * settings screen resolves it for that one app, so a monitored row survives an uninstall.
     *
     * The single-package *label* lookup this class used to carry is gone. The app-settings screen
     * titles itself with the label stored on the monitored row, which is the same string
     * `MonitoringCore` puts in the overlay — so a deep link from that overlay names the app exactly
     * the way the overlay did, and the settings screen and the dashboard can never disagree about
     * what an app is called. Reading a live label here instead would give the settings page a third
     * spelling without correcting the row, and a lookup cannot help an uninstalled app in any case:
     * that is precisely the case where the platform refuses to answer and only the stored label is
     * left.
     */
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
