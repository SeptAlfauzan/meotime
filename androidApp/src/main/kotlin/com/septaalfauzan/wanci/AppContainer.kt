package com.septaalfauzan.wanci

import android.content.Context
import android.os.SystemClock
import com.septaalfauzan.wanci.apps.AppLabelResolver
import com.septaalfauzan.wanci.apps.InstalledAppsProviderAndroid
import com.septaalfauzan.wanci.data.local.createTodayKey
import com.septaalfauzan.wanci.data.local.createWanciDatabase
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepositoryImpl
import com.septaalfauzan.wanci.domain.NotificationPermissionRequest
import com.septaalfauzan.wanci.domain.PermissionStatusProvider
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import com.septaalfauzan.wanci.monitoring.AndroidNotifier
import com.septaalfauzan.wanci.monitoring.MonitoringCore
import com.septaalfauzan.wanci.monitoring.MonitoringForegroundService
import com.septaalfauzan.wanci.monitoring.MonotonicClock
import com.septaalfauzan.wanci.monitoring.NotificationController
import com.septaalfauzan.wanci.monitoring.OverlayManager
import com.septaalfauzan.wanci.permission.PermissionActions
import com.septaalfauzan.wanci.permission.PermissionChecks
import com.septaalfauzan.wanci.presentation.DeepLinkRequest
import com.septaalfauzan.wanci.presentation.WanciAppDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val database = createWanciDatabase()
    val repository = ScreenTimeRepositoryImpl(
        monitoredAppDao = database.monitoredAppDao(),
        dailyUsageDao = database.dailyUsageDao(),
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

    // The `@Suppress("UnnecessaryLateinit")` these three used to carry was inert: the diagnostic is
    // reported *at* the declaration and survives a suppression placed there — verified by putting
    // the annotation back and getting the same three warnings. Kept as plain `lateinit`, because
    // they genuinely cannot be constructor parameters: `core` below is built in this class's
    // initialiser block and reads all three from its lambdas, so it must exist before the
    // constructor body runs. The warning is a false positive that costs three lines of build log.
    internal lateinit var overlayManager: OverlayManager
        private set

    internal lateinit var notifier: AndroidNotifier
        private set

    internal lateinit var notificationController: NotificationController
        private set

    /**
     * Set by `MainActivity` for as long as an Activity is alive. Read only inside
     * [toDependencies], so a request made with no Activity alive drops the callback instead of
     * crashing — which is the correct outcome, because there is no visible surface to answer on.
     */
    @Volatile
    internal var notificationPermissionHost: NotificationPermissionHost? = null

    val coreMutex = Mutex()

    val core = MonitoringCore(
        repository = repository,
        todayKey = todayKey,
        selfPackageName = context.packageName,
        clock = clock,
        scope = scope,
        // No extra launch hop: OverlayManager.show posts to the main thread itself, so wrapping
        // this in scope.launch would only add a queue between the decision and the screen. The
        // lambdas read the lateinit properties when they run, which is after init.
        onOverlayDecision = { decision ->
            overlayManager.show(decision)
            // The core already emits the crossing, so the notification hears about it here rather
            // than through a second callback for the same event — one signal, one place.
            notificationController.onLimitCrossed()
        },
        onSessionChanged = { snapshot -> notificationController.onSessionChanged(snapshot) },
    )

    init {
        overlayManager = OverlayManager(context)
        notifier = AndroidNotifier(context)
        notificationController = NotificationController(
            notifier = notifier,
            clock = clock,
            scope = scope,
        )
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
    fun toDependencies(deepLink: DeepLinkRequest?): WanciAppDependencies =
        WanciAppDependencies(
            useCases = WanciUseCases(repository, todayKey),
            todayKey = todayKey,
            permissionStatusProvider = PermissionStatusProvider { PermissionChecks.status(context) },
            permissionNavigator = PermissionActions(context),
            installedAppsProvider = InstalledAppsProviderAndroid(context),
            installedAppCheck = AppLabelResolver(context).asInstalledAppCheck(),
            // The launcher lives in the Activity, so the shared graph only ever sees the request
            // shape. Null host is a dropped request, not a crash: there is no visible surface to
            // show the system dialog on.
            notificationPermissionRequest = NotificationPermissionRequest { onResult ->
                notificationPermissionHost?.requestNotificationPermission(onResult)
            },
            startMonitoring = { startMonitoring() },
            deepLink = deepLink,
        )
}
