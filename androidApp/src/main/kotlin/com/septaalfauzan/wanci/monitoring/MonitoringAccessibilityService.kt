package com.septaalfauzan.wanci.monitoring

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.septaalfauzan.wanci.AppContainer
import com.septaalfauzan.wanci.WanciApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class MonitoringAccessibilityService : AccessibilityService() {

    private val ready = AtomicBoolean(false)
    private var container: AppContainer? = null
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            enqueueForegroundChange(null, SystemClock.uptimeMillis())
        }
    }

    companion object {
        private const val RECONCILE_INTERVAL_MILLIS = 15 * 60_000L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        container = (application as? WanciApplication)?.container
        if (container == null) return
        val appContainer = container!!
        registerScreenReceiver()
        ready.set(true)
        appContainer.scope.launch {
            appContainer.usageStatsReader.reconcile(
                repository = appContainer.repository,
                todayKey = { appContainer.todayKey.todayIso() },
            )
        }
        startReconcileLoop()
    }

    private fun startReconcileLoop() {
        val container = (application as WanciApplication).container
        container.scope.launch {
            while (true) {
                delay(RECONCILE_INTERVAL_MILLIS)
                container.usageStatsReader.reconcile(
                    repository = container.repository,
                    todayKey = { container.todayKey.todayIso() },
                )
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ready.get() || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        enqueueForegroundChange(event.packageName?.toString(), event.eventTime)
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        ready.set(false)
        unregisterScreenReceiver()
        container?.overlayManager?.dismiss()
        enqueueForegroundChange(null, SystemClock.uptimeMillis())
        container = null
        super.onDestroy()
    }

    private fun enqueueForegroundChange(packageName: String?, eventTimeMillis: Long) {
        val appContainer = container ?: return
        appContainer.scope.launch {
            val decision = appContainer.coreMutex.withLock {
                appContainer.core.onForegroundChange(packageName, eventTimeMillis)
            }
            if (decision != null && ready.get()) {
                appContainer.overlayManager.show(decision)
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
