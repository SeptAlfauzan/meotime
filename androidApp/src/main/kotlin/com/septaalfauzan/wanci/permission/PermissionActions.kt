package com.septaalfauzan.wanci.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.septaalfauzan.wanci.domain.PermissionNavigator

class PermissionActions(private val context: Context) : PermissionNavigator {
    override fun openUsageAccess() {
        try {
            start(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
        }
    }

    override fun openOverlay() {
        val uri = Uri.parse("package:${context.packageName}")
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
        if (intent.resolveActivity(context.packageManager) != null) {
            start(intent)
        }
    }

    override fun openAccessibility() {
        try {
            start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
        }
    }

    private fun start(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
