package com.septaalfauzan.wanci.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.septaalfauzan.wanci.domain.InstalledApp
import com.septaalfauzan.wanci.domain.InstalledAppsProvider

/**
 * Launcher-visible apps, which is the closest thing Android offers to "apps the user can limit":
 * the accessibility service can only ever see an app that was started somehow, and a
 * non-launchable app is not something a user picks on the add-app screen.
 *
 * Wanci itself is filtered out, and one entry is kept per package so a device with several
 * launcher activities for the same app (some launchers) does not produce duplicates.
 */
class InstalledAppsProviderAndroid(private val context: Context) : InstalledAppsProvider {

    override fun getInstalledApps(): List<InstalledApp> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return launchableActivities(packageManager, launcherIntent)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName
                if (packageName == context.packageName) return@mapNotNull null
                val appName = activityInfo.loadLabel(packageManager).toString()
                InstalledApp(packageName = packageName, appName = appName)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName }
    }

    private fun launchableActivities(
        packageManager: PackageManager,
        intent: Intent,
    ): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
}
