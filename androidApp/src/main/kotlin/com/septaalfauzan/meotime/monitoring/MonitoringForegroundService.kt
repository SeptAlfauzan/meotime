package com.septaalfauzan.meotime.monitoring

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.septaalfauzan.meotime.AppContainer
import com.septaalfauzan.meotime.MeotimeApplication
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * One poll window's answer, as a closed set rather than an `Any?` sentinel.
 *
 * The poll loop has to tell three things apart — a package, "the foreground app went away", and
 * "this window said nothing" — and the third only means something to the loop, not to the core.
 * A sealed type says which is which at every use site, so the `null`-means-one-of-two and the
 * `as String?` that went with encoding this as `Any?` plus a private identity object are both gone,
 * and a new case cannot be added without the compiler pointing at the loop.
 */
private sealed interface PollWindow {
    /** The window resolved to a package, or to `null` meaning the foreground app went away. */
    data class Foreground(val packageName: String?) : PollWindow

    /**
     * "This window said nothing about the foreground" — not a package, and not the `null` that
     * means "the foreground app went away". An object so it is compared by identity and can never
     * collide with a real package name.
     */
    data object NoSignal : PollWindow
}

/**
 * Hosts the poll loop that makes Meotime work with the app closed.
 *
 * The trade this makes is explicit: Android's price for an always-alive process is a permanent
 * notification. A screen-time limiter that only ran while Meotime was open would enforce nothing, and
 * a periodic worker would add hours of latency to a limit measured in minutes.
 *
 * **Why the event timestamp is ignored.** Polling delivers events one to five seconds late, and
 * every switch would under-count by that much. Using each event's own timestamp would mean a second
 * time source — wall clock against the core's monotonic uptime — which the core was deliberately
 * built to have exactly one of. A second per switch is noise against a minute-scale limit.
 *
 * Every change funnels through [enqueueForegroundChange] and takes the core mutex, so the core's
 * single session is only ever advanced by one coroutine at a time, exactly as it was when an
 * accessibility service fed it. The mutex alone is not sufficient — serialising a *stale* change
 * behind a fresh one still applies it in the wrong order — so the poll also tags each change with
 * the run that observed it and the change re-validates that tag under the lock. See
 * [enqueueForegroundChange] for the interleaving.
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
                    // Enqueued before `stopPolling` and deliberately untagged, so that the bump
                    // this same handler is about to make cannot retroactively void its own close.
                    // The ordering argument is in [enqueueForegroundChange].
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
        container = (application as? MeotimeApplication)?.container
        // Read once at create time rather than defaulting to on: a service started by
        // BOOT_COMPLETED routinely runs while the screen is still dark, and polling then would
        // keep the wake lock alive with nothing to detect. The broadcast only fires on a
        // transition, so a later screen-on is still what starts the loop.
        screenOn = isScreenOn(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appContainer = container
        if (appContainer == null) {
            // Declining the start is not enough on its own. `startForegroundService` bought this
            // service a few seconds' grace in which to call `startForeground`, and returning from
            // `onStartCommand` without having done so is
            // ForegroundServiceDidNotStartInTimeException — which kills the process rather than
            // just refusing the start, taking every other running piece of Meotime with it.
            // `stopSelf` withdraws the pending start inside that window, so the requirement is
            // dropped instead of failed. There is no notification to post in this branch — the
            // notifier lives on the container that is gone — so stopping is the only move left.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Seed the notification only the first time this process publishes one. `startMonitoring`
        // is called from onboarding, from the boot receiver and from `MainActivity.onCreate`, so
        // this runs repeatedly *while a session is open*; `NotificationController.start()`
        // republishes `Idle` and cancels the minute rollover, and because the poll's
        // `next == lastForeground` dedupe then swallows the next real change, the shade would sit
        // on "Monitoring active" for a live session that the core is still counting.
        //
        // The guard is the notifier's own published flag rather than a field on the service,
        // because that is what makes the check honest across a process death: the notifier is
        // rebuilt with the container, so after a restart the flag reads false and this seeds
        // `Idle` — which is the truthful state, since a process death takes the unpersisted
        // session with it and there is nothing truer to re-publish.
        if (!appContainer.notifier.published) appContainer.notificationController.start()
        val notification = appContainer.notifier.latest()
        // Two overloads, gated on the SDK level — gating only the *type constant* would not help.
        // The three-argument startForeground(id, notification, type) is API 29 (checked against the
        // SDK's api-versions.xml) and minSdk is 28, so calling it unconditionally is a
        // NoSuchMethodError thrown from inside `onStartCommand` on Android 9: the service never
        // comes up at all. And the type argument cannot be passed separately, because the overload
        // that carries it is the one that does not exist yet — the platform had no foreground
        // service types before 29, so below that there is nothing to send and the two-argument form
        // is the only call that can be made. `assembleDebug` does not run lint, so an unguarded call
        // here compiles green.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                AndroidNotifier.NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } else {
            startForeground(AndroidNotifier.NOTIFICATION_ID, notification)
        }
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
                val window = try {
                    queryForegroundPackage(watermarkMillis, now)
                } catch (_: SecurityException) {
                    // Usage access was revoked from Settings while Meotime ran. The poll loop cannot
                    // recover on its own — there is nothing to poll with — and retrying would spin.
                    // The notification says what happened; onboarding is the way back.
                    appContainer.notificationController.onUsageAccessRevoked()
                    // Cleared on the way out because the field is the loop's liveness flag:
                    // `startPolling` returns early on a non-null `pollJob`, so a finished Job left
                    // behind here would read as a running one and make polling permanently dead
                    // until an unrelated screen-off happened to null it. Nothing here can recover
                    // the loop, but a later screen-on must at least be able to try. If a screen-on
                    // races this assignment its own job is what `pollJob` ends up naming again and
                    // this one is already finishing; the only window that leaves a live job
                    // untracked is closed by the next `stopPolling`, whose generation bump the
                    // loop's own guard turns into an exit.
                    pollJob = null
                    return@launch
                }
                // The screen may have gone dark, or the service may have been destroyed, while the
                // query was in flight. Its result describes a world that no longer exists: acting
                // on it would enqueue an open for a foreground that has already been closed. This
                // check keeps that work out of the queue; it cannot by itself keep it out of the
                // core, because the gap between here and the enqueued body is not covered — that
                // is what `myGeneration` on the enqueue is for.
                if (myGeneration != generation.get()) return@launch
                watermarkMillis = now
                // `continue`, never `return@launch`, for both "nothing to do this tick" branches.
                // `return@launch` leaves the whole coroutine, so the loop died on its first quiet
                // window — and a quiet window is the normal state of a phone sitting on the home
                // screen, so the loop died within about a second of starting and then never ran
                // again, because `pollJob` still held a finished Job and `startPolling` returns
                // early on a non-null one. The same trap sat on the dedupe below: an in-app
                // navigation emits pause+resume for one package, that reduces to the package
                // already in `lastForeground`, and the old code ended the loop for it too. Only the
                // generation check above is a genuine exit, and that one is `return@launch` because
                // a bumped generation means this loop is obsolete, not that this tick is a no-op.
                when (window) {
                    PollWindow.NoSignal -> continue
                    is PollWindow.Foreground -> {
                        val next = window.packageName
                        if (next == lastForeground) continue
                        lastForeground = next
                        enqueueForegroundChange(next, myGeneration)
                    }
                }
            }
        }
    }

    private fun stopPolling() {
        generation.incrementAndGet()
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * The package in the foreground at the end of the window, or a window that said nothing.
     *
     * [PollWindow] rather than a nullable string because `parseForegroundPackage` collapses two
     * different answers into `null`, and only the signal check can tell them apart. Without it,
     * every quiet second would close the session the user is in and the running total would
     * restart from zero — the same bug in a different disguise.
     */
    private fun queryForegroundPackage(from: Long, to: Long): PollWindow {
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
        if (!hasForegroundSignal(events)) return PollWindow.NoSignal
        return PollWindow.Foreground(parseForegroundPackage(events))
    }

    /**
     * Hands one foreground change to the core, serialised on the container's core mutex.
     *
     * [expectedGeneration] is the poll run the change was observed in, and `null` means
     * "unconditional". It is re-checked *inside* the lock, immediately before the core is touched,
     * because the generation check in the poll loop is not enough on its own and the gap it leaves
     * is a silent mis-accounting bug — a session that bills the user for a dark screen:
     *
     *  1. The poll wakes, queries, and sees `com.foo`. It checks `myGeneration == generation` —
     *     true — records `lastForeground`, and enqueues this change.
     *  2. The enqueued body has not run yet: it is queued behind whoever holds the mutex, which
     *     at that moment is typically a session close waiting on a database write.
     *  3. `SCREEN_OFF` lands. It enqueues the close and bumps the generation, cancelling the job.
     *  4. The close takes the lock and closes `com.foo`. So far correct.
     *  5. The change from step 1 finally takes the lock and *opens* `com.foo` — after the close,
     *     with the screen dark, and with polling already stopped, so nothing will ever close it.
     *     It then accrues against `uptimeMillis` for as long as the screen stays off. The next
     *     screen-on does not rescue it: `startPolling` resets `lastForeground` to `null`, so the
     *     re-detected `com.foo` reaches the core's `packageName == currentSession` early-return
     *     and the session is never closed at all.
     *
     * Re-checking under the lock collapses step 5. Between reading [generation] and calling into
     * the core there is no suspension point, so the change either observes the pre-stop generation
     * and is applied strictly before the close that follows it, or observes the post-stop
     * generation and is dropped. Both orderings end with the session closed and nothing accruing
     * in the dark.
     *
     * The screen-off close is deliberately left untagged. It is enqueued in the same `onReceive`
     * that calls `stopPolling`, so every generation it could be given is already stale by the time
     * its body runs: tagging it with the generation from before the bump drops it, and tagging it
     * with the one after drops it too as soon as a `SCREEN_ON` follows. Untagged, it is the newest
     * fact there is about the screen — and the mutex hands the lock out in order, so it is taken
     * ahead of any change a later poll run could enqueue behind it.
     */
    private fun enqueueForegroundChange(packageName: String?, expectedGeneration: Long? = null) {
        val appContainer = container ?: return
        appContainer.scope.launch {
            appContainer.coreMutex.withLock {
                if (expectedGeneration != null && expectedGeneration != generation.get()) {
                    return@withLock
                }
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
        /**
         * Whether the screen is currently on, asked of [PowerManager] rather than read off the
         * sticky [Intent.ACTION_SCREEN_ON] broadcast.
         *
         * The sticky read looks equivalent and is not. `ACTION_SCREEN_ON` is only *sent* on a
         * screen-on transition, and it is not a sticky broadcast the platform guarantees to keep:
         * on modern Android — verified on API 36, where it returned null with the screen lit —
         * `registerReceiver(null, ACTION_SCREEN_ON)` yields null unless a transition happened to
         * occur recently. This is read in `onCreate`, and every ordinary way into this service
         * starts it with the screen already on: the user opening Meotime, a force-stop recovery,
         * `MainActivity.onCreate`, a reboot that lands on a charging screen. So the sticky read
         * reported "off" in precisely the cases that matter, `onStartCommand`'s `if (screenOn)`
         * gate skipped `startPolling()`, and the service ran as a live foreground notification
         * that never detected a single app — a silent, total failure of the only feature it has,
         * with no crash and nothing in the shade to say so.
         *
         * [PowerManager.isInteractive] is the supported query and is true exactly when the screen
         * is on. Transitions still arrive on the runtime receiver; only this initial read needed
         * the platform.
         */
        private fun isScreenOn(context: Context): Boolean =
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }
}
