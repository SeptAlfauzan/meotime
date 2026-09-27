package com.septaalfauzan.wanci.monitoring

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import com.septaalfauzan.wanci.AppContainer
import com.septaalfauzan.wanci.WanciApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * The only source of truth for screen time. There is no `UsageStatsManager` fallback: the
 * service's window-state events plus the screen-off broadcast are the complete account of what
 * the user did, and a second source would only be a second thing to drift.
 *
 * Every event funnels through [enqueueForegroundChange] and takes the core mutex, so the core's
 * single session is only ever advanced by one coroutine at a time.
 */
class MonitoringAccessibilityService : AccessibilityService() {

    private val ready = AtomicBoolean(false)
    private var container: AppContainer? = null
    private var screenReceiverRegistered = false

    /**
     * Screen-off is not a window-state event, so without this the session of whatever was in the
     * foreground would stay open — and keep accruing — for as long as the screen was dark.
     * A null package is how the core is told to close it.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            enqueueForegroundChange(null)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val appContainer = (application as? WanciApplication)?.container ?: return
        container = appContainer
        registerScreenReceiver()
        ready.set(true)
        appContainer.core.attach()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ready.get() || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // The event's own timestamp is deliberately not passed on. The core reads its injected
        // clock when it processes the change, so there is no second time source to keep in step.
        enqueueForegroundChange(event.packageName?.toString())
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        ready.set(false)
        unregisterScreenReceiver()
        // Order matters, and both calls are synchronous. detach() stops the ticker before it
        // returns, so no tick can fire between here and the view being removed. Closing the
        // session instead would queue a coroutine and leave that race open.
        container?.core?.detach()
        container?.overlayManager?.dismiss()
        container = null
        super.onDestroy()
    }

    private fun enqueueForegroundChange(packageName: String?) {
        val appContainer = container ?: return
        appContainer.scope.launch {
            appContainer.coreMutex.withLock {
                appContainer.core.onForegroundChange(packageName)
            }
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        @Suppress("DEPRECATION")
        registerReceiver(screenReceiver, filter)
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        unregisterReceiver(screenReceiver)
        screenReceiverRegistered = false
    }
}
