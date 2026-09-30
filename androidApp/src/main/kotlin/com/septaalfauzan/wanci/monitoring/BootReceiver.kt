package com.septaalfauzan.wanci.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts monitoring after a reboot.
 *
 * A foreground service does not survive one, and without this the limiter is silently dead until
 * the user happens to open Wanci — which breaks the promise the accessibility service used to get
 * from the system for free. It does not check permissions: starting the service is idempotent and
 * it degrades to the "Usage Access needed" notification if usage access was not restored.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        MonitoringForegroundService.start(context.applicationContext)
    }
}
