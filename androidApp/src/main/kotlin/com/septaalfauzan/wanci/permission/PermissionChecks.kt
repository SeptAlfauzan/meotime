package com.septaalfauzan.wanci.permission

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.septaalfauzan.wanci.domain.PermissionStatus

object PermissionChecks {
    fun status(context: Context): PermissionStatus = PermissionStatus(
        usageAccess = hasUsageAccess(context),
        overlay = Settings.canDrawOverlays(context),
        accessibility = hasAccessibility(context),
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

    private fun hasAccessibility(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }
}
