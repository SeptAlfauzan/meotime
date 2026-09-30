package com.septaalfauzan.wanci.monitoring

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.septaalfauzan.wanci.AppContainer
import com.septaalfauzan.wanci.WanciApplication
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Hosts the poll loop that makes Wanci work with the app closed.
 *
 * The trade this makes is explicit: Android's price for an always-alive process is a permanent
 * notification. A screen-time limiter that only ran while Wanci was open would enforce nothing, and
 * a periodic worker would add hours of latency to a limit measured in minutes.
 *
 * **Why the event timestamp is ignored.** Polling delivers events one to five seconds late, and
 * every switch would under-count by that much. Using each event's own timestamp would mean a second
 * time source — wall clock against the core's monotonic uptime — which the core was deliberately
 * built to have exactly one of. A second per switch is noise against a minute-scale limit.
 *
 * Every change funnels through [enqueueForegroundChange] and takes the core mutex, so the core's
 * single session is only ever advanced by one coroutine at a time, exactly as it was when an
 * accessibility service fed it.
 */
class MonitoringForegroundService : Service() {

    private val generation = AtomicLong(0L)
    private var container: AppContainer? = null
    private var pollJob: Job? = null
    private var screenReceiverRegistered = false
    private var screenOn = true
    private var watermarkMillis = 0L
    private var lastForeground: String? = null

    /**
     * Screen-off is not a usage event, so without this the session of whatever was in the
     * foreground would stay open — and keep accruing — for as long as the screen was dark. A null
     * package is how the core is told to close it.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    enqueueForegroundChange(null)
                    stopPolling()
                }

                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    startPolling()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as? WanciApplication)?.container
        // Read once at create time rather than defaulting to on: a service started by
        // BOOT_COMPLETED routinely runs while the screen is still dark, and polling then would
        // keep the wake lock alive with nothing to detect. The broadcast only fires on a
        // transition, so a later screen-on is still what starts the loop.
        screenOn = isScreenOn(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appContainer = container ?: return START_NOT_STICKY
        appContainer.notificationController.start()
        startForeground(
            AndroidNotifier.NOTIFICATION_ID,
            appContainer.notifier.latest(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        appContainer.core.attach()
        registerScreenReceiver()
        if (screenOn) startPolling()
        // STICKY: the process is killed under memory pressure and the limiter must come back on its
        // own. The gap is not recovered — see the spec's Decision 7 — but a dead service is worse
        // than a few lost seconds, and the notification tells the user monitoring is running.
        return START_STICKY
    }

    override fun onDestroy() {
        stopPolling()
        unregisterScreenReceiver()
        // Order matters, and both calls are synchronous. detach() stops the ticker before it
        // returns, so no tick can fire between here and the view being removed. Closing the
        // session instead would queue a coroutine and leave that race open.
        container?.core?.detach()
        container?.overlayManager?.dismiss()
        container = null
        super.onDestroy()
    }

    private fun startPolling() {
        if (pollJob != null) return
        val appContainer = container ?: return
        // Only ever polls forward from now. Replaying older events would be safe in a way the
        // deleted reconcile was not — it would deliver ordered open/close pairs rather than
        // aggregates — but it is an accuracy upgrade with its own test surface, and a service that
        // restarts has not lost anything the user would notice at minute scale.
        watermarkMillis = System.currentTimeMillis()
        lastForeground = null
        val myGeneration = generation.incrementAndGet()
        pollJob = appContainer.scope.launch {
            while (isActive && myGeneration == generation.get()) {
                delay(POLL_INTERVAL_MILLIS)
                val now = System.currentTimeMillis()
                val packageName = try {
                    queryForegroundPackage(watermarkMillis, now)
                } catch (_: SecurityException) {
                    // Usage access was revoked from Settings while Wanci ran. The poll loop cannot
                    // recover on its own — there is nothing to poll with — and retrying would spin.
                    // The notification says what happened; onboarding is the way back.
                    appContainer.notificationController.onUsageAccessRevoked()
                    return@launch
                }
                // The screen may have gone dark, or the service may have been destroyed, while the
                // query was in flight. Its result describes a world that no longer exists: acting
                // on it would open a session the core then accrues until the next event.
                if (myGeneration != generation.get()) return@launch
                watermarkMillis = now
                if (packageName === NO_SIGNAL) return@launch
                val next = packageName as String?
                if (next == lastForeground) return@launch
                lastForeground = next
                enqueueForegroundChange(next)
            }
        }
    }

    private fun stopPolling() {
        generation.incrementAndGet()
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * The package in the foreground at the end of the window, `null` if the foreground app went
     * away, or [NO_SIGNAL] if the window contained no foreground event at all.
     */
    private fun queryForegroundPackage(from: Long, to: Long): Any? {
        val stats = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usageEvents = stats.queryEvents(from, to)
        // `UsageEvents` is a cursor, not a collection: one reusable `Event` is filled in place and
        // its two fields are copied, which is the platform's own idiom and keeps the platform type
        // out of everything downstream of here.
        val events = buildList {
            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                add(RawUsageEvent(event.eventType, event.packageName))
            }
        }
        // Two nulls, one meaning. `parseForegroundPackage` cannot distinguish "the app left" from
        // "nothing happened", so the signal check decides which of the two this window was. Without
        // it, every quiet second would close the session the user is in and the running total would
        // restart from zero — the same bug in a different disguise.
        if (!hasForegroundSignal(events)) return NO_SIGNAL
        return parseForegroundPackage(events)
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
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        unregisterReceiver(screenReceiver)
        screenReceiverRegistered = false
    }

    companion object {
        private const val POLL_INTERVAL_MILLIS = 1_000L

        /**
         * "This window said nothing about the foreground" — not a package, and not `null`, which
         * means "the foreground app went away". A private object so it can be compared by identity
         * and can never collide with a real package name.
         */
        private val NO_SIGNAL = object {}

        fun start(context: Context) {
            // minSdk is 28, so the branch is only here to keep the call honest if the floor moves.
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(
                    Intent(context, MonitoringForegroundService::class.java),
                )
            } else {
                context.startService(Intent(context, MonitoringForegroundService::class.java))
            }
        }

        /**
         * Whether the screen is on right now, read from the sticky broadcast rather than assumed.
         * A null-returning receiver is a query, not a subscription: nothing is registered.
         */
        @Suppress("DEPRECATION")
        private fun isScreenOn(context: Context): Boolean =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_SCREEN_ON)) != null
    }
}
