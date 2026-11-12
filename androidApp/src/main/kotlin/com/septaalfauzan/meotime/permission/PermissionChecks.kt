package com.septaalfauzan.meotime.permission

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import com.septaalfauzan.meotime.domain.PermissionStatus

object PermissionChecks {
    fun status(context: Context): PermissionStatus = PermissionStatus(
        usageAccess = hasUsageAccess(context),
        overlay = Settings.canDrawOverlays(context),
        notification = hasNotificationPermission(context),
    )

    private fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Whether notifications are allowed, which is the question the onboarding row actually asks.
     * `areNotificationsEnabled` is the answer the system gives when the permission is granted *or*
     * when the user turned notifications off in Settings afterwards — and a user who did that has
     * the same problem as a user who never granted: no notification, no visible enforcement.
     */
    private fun hasNotificationPermission(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled()
    }
}
