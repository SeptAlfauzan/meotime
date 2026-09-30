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

    override fun openNotificationSettings() {
        // No version branch: `ACTION_APP_NOTIFICATION_SETTINGS` is API 26 (checked against the
        // SDK api-versions.xml) and minSdk is 28, so it is always present. The `package:` URI is
        // what scopes the page to Wanci instead of to a list of every app on the device, and the
        // `resolveActivity` guard is [openOverlay]'s for the same reason — a device that has
        // stripped the settings activity must not take Wanci down with an ActivityNotFoundException
        // raised from a button the user pressed.
        val uri = Uri.parse("package:${context.packageName}")
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS, uri)
        if (intent.resolveActivity(context.packageManager) != null) {
            start(intent)
        }
    }

    private fun start(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
