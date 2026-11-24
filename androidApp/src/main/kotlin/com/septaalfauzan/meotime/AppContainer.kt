package com.septaalfauzan.meotime

import android.content.Context
import android.os.SystemClock
import com.septaalfauzan.meotime.apps.AppIconProviderAndroid
import com.septaalfauzan.meotime.apps.AppLabelResolver
import com.septaalfauzan.meotime.apps.InstalledAppsProviderAndroid
import com.septaalfauzan.meotime.data.local.createTodayKey
import com.septaalfauzan.meotime.data.local.createMeotimeDatabase
import com.septaalfauzan.meotime.data.repository.ScreenTimeRepositoryImpl
import com.septaalfauzan.meotime.domain.NotificationPermissionRequest
import com.septaalfauzan.meotime.domain.PermissionStatusProvider
import com.septaalfauzan.meotime.domain.useCase.MeotimeUseCases
import com.septaalfauzan.meotime.monitoring.AndroidNotifier
import com.septaalfauzan.meotime.monitoring.MonitoringCore
import com.septaalfauzan.meotime.monitoring.SystemWallClock
import com.septaalfauzan.meotime.monitoring.MonitoringForegroundService
import com.septaalfauzan.meotime.monitoring.MonotonicClock
import com.septaalfauzan.meotime.monitoring.NotificationController
import com.septaalfauzan.meotime.monitoring.OverlayManager
import com.septaalfauzan.meotime.monitoring.TrackerRenderer
import com.septaalfauzan.meotime.monitoring.UsageTrackerController
import com.septaalfauzan.meotime.permission.PermissionActions
import com.septaalfauzan.meotime.permission.PermissionChecks
import com.septaalfauzan.meotime.presentation.DeepLinkRequest
import com.septaalfauzan.meotime.presentation.MeotimeAppDependencies
import com.septaalfauzan.meotime.presentation.overlay.SleepingCatOverlayAnimation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Implemented by the Activity that owns the `POST_NOTIFICATIONS` launcher.
 *
 * An interface rather than a `NotificationPermissionRequest` lambda stored on the container so the
 * container never holds an Activity reference. `MainActivity` registers itself on create and
 * unregisters on destroy, and [AppContainer.toDependencies] reads the registration at the moment it
 * is called — so a request made while no Activity is alive is a no-op instead of a leak.
 */
interface NotificationPermissionHost {
    fun requestNotificationPermission(onResult: () -> Unit)
}

class AppContainer(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val todayKey = createTodayKey()
    val database = createMeotimeDatabase()
    val repository = ScreenTimeRepositoryImpl(
        monitoredAppDao = database.monitoredAppDao(),
        dailyUsageDao = database.dailyUsageDao(),
        hourlyUsageDao = database.hourlyUsageDao(),
        syncStateDao = database.syncStateDao(),
        todayKey = todayKey,
    )
    /**
     * The one clock every piece of session arithmetic reads, hoisted so the core and the
     * notification controller cannot end up on two different notions of "now". Uptime, not
     * elapsedRealtime: a session must not bill the user for time the device spent asleep. See
     * MonotonicClock for the rest of the reasoning.
     */
    val clock = MonotonicClock { SystemClock.uptimeMillis() }

    /** Shared by the settings list and the tracker window, so the tracker paints a real icon. */
    private val appIconProvider = AppIconProviderAndroid(context)

    // The `@Suppress("UnnecessaryLateinit")` these four used to carry was inert: the diagnostic is
    // reported *at* the declaration and survives a suppression placed there — verified by putting
    // the annotation back and getting the same warnings. Kept as plain `lateinit`, because they
    // genuinely cannot be constructor parameters: `core` below is built in this class's initialiser
    // block and reads all four from its lambdas, so they must exist before the constructor body
    // runs. The warning is a false positive that costs build-log lines.
    internal lateinit var overlayManager: OverlayManager
        private set

    internal lateinit var notifier: AndroidNotifier
        private set

    internal lateinit var notificationController: NotificationController
        private set

    internal lateinit var usageTrackerController: UsageTrackerController
        private set

    /**
     * Set by `MainActivity` for as long as an Activity is alive. Read only inside
     * [toDependencies], so a request made with no Activity alive drops the callback instead of
     * crashing — which is the correct outcome, because there is no visible surface to answer on.
     */
    @Volatile
    internal var notificationPermissionHost: NotificationPermissionHost? = null

    /**
     * Set by service teardown before the controller hide is posted. A foreground change already
     * suspended in the repository can still resume after `core.detach()`; its tracker decision is
     * dropped here instead of queuing an open behind the hide. `resumeTracking` re-opens the gate
     * when the service starts again in the same process.
     */
    @Volatile
    private var trackerStopped = false

    val coreMutex = Mutex()

    val core = MonitoringCore(
        repository = repository,
        todayKey = todayKey,
        selfPackageName = context.packageName,
        clock = clock,
        wallClock = SystemWallClock,
        scope = scope,
        // No extra launch hop: OverlayManager.show posts to the main thread itself, so wrapping
        // this in scope.launch would only add a queue between the decision and the screen. The
        // lambdas read the lateinit properties when they run, which is after init.
        onOverlayDecision = { decision ->
            // Stop the pill before the blocker takes the screen: onLimitCrossed() posts hide to
            // the main Handler before show() posts its own hide and blocker, so FIFO keeps the
            // controller's null frame ahead of the blocker. show() hides it too, but the controller
            // must also drop its ticker and pending collapse so no frame can follow.
            usageTrackerController.onLimitCrossed()
            overlayManager.show(decision)
            // The core already emits the crossing, so the notification hears about it here rather
            // than through a second callback for the same event — one signal, one place.
            notificationController.onLimitCrossed()
        },
        onSessionChanged = { snapshot ->
            if (snapshot == null) usageTrackerController.onSessionClosed()
            notificationController.onSessionChanged(snapshot)
        },
        onUsageTrackerDecision = { if (!trackerStopped) usageTrackerController.onSessionOpened(it) },
    )

    init {
        overlayManager = OverlayManager(context, appIconProvider, scope)
        usageTrackerController = UsageTrackerController(
            renderer = TrackerRenderer { overlayManager.renderTracker(it) },
            clock = clock,
            scope = scope,
            // Plain Main always posts, even from main: hide cannot run inline ahead of an open
            // already queued by a core IO callback. Queue order then decides every render post.
            eventDispatcher = Dispatchers.Main,
            // Re-read on main when the open actually runs: an IO-side check can be stale after
            // teardown posts its hide.
            isEnabled = { !trackerStopped },
        )
        overlayManager.onTrackerTap = { usageTrackerController.onPillTapped() }
        notifier = AndroidNotifier(context)
        notificationController = NotificationController(
            notifier = notifier,
            clock = clock,
            scope = scope,
        )
        scope.launch { SleepingCatOverlayAnimation.preload() }
    }

    /** Teardown gate: drop any tracker decision still in flight, then post the hide. */
    internal fun stopTracking() {
        trackerStopped = true
        usageTrackerController.onSessionClosed()
    }

    /** A restarted service in this process must be able to show the pill again. */
    internal fun resumeTracking() {
        trackerStopped = false
    }

    /**
     * Starts (or re-starts) the monitoring foreground service. Idempotent by construction:
     * `onStartCommand` re-registers receivers — guarded by a flag — and `startPolling` returns
     * early when a poll job is already running, so calling this from onboarding, from a boot
     * broadcast and from `MainActivity.onCreate` cannot double-poll.
     */
    fun startMonitoring() = MonitoringForegroundService.start(context)

    /**
     * The whole presentation-layer graph, assembled per call. Cheap by construction: every member
     * here is either a shared singleton from this container or a stateless wrapper over the
     * application [Context], so calling this from a composable on every deep-link intent cannot
     * leak an Activity or duplicate the database.
     *
     * [deepLink] is what `MainActivity` read off the launch intent; the navigation host turns it
     * into the `appsettings/{packageName}` destination.
     */
    fun toDependencies(deepLink: DeepLinkRequest?): MeotimeAppDependencies =
        MeotimeAppDependencies(
            useCases = MeotimeUseCases(repository, todayKey),
            todayKey = todayKey,
            permissionStatusProvider = PermissionStatusProvider { PermissionChecks.status(context) },
            permissionNavigator = PermissionActions(context),
            installedAppsProvider = InstalledAppsProviderAndroid(context),
            installedAppCheck = AppLabelResolver(context).asInstalledAppCheck(),
            appIconProvider = appIconProvider,
            // The launcher lives in the Activity, so the shared graph only ever sees the request
            // shape. Null host is a dropped request, not a crash: there is no visible surface to
            // show the system dialog on.
            notificationPermissionRequest = NotificationPermissionRequest { onResult ->
                notificationPermissionHost?.requestNotificationPermission(onResult)
            },
            startMonitoring = { startMonitoring() },
            deepLink = deepLink,
            weeklyLimitsEnabled = BuildConfig.FLAVOR == "pro",
        )
}
