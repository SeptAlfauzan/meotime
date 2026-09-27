package com.septaalfauzan.wanci

import android.content.Context
import android.os.SystemClock
import com.septaalfauzan.wanci.apps.AppLabelResolver
import com.septaalfauzan.wanci.apps.InstalledAppsProviderAndroid
import com.septaalfauzan.wanci.data.local.createTodayKey
import com.septaalfauzan.wanci.data.local.createWanciDatabase
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepositoryImpl
import com.septaalfauzan.wanci.domain.PermissionStatusProvider
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import com.septaalfauzan.wanci.monitoring.MonitoringCore
import com.septaalfauzan.wanci.monitoring.MonotonicClock
import com.septaalfauzan.wanci.monitoring.OverlayManager
import com.septaalfauzan.wanci.permission.PermissionActions
import com.septaalfauzan.wanci.permission.PermissionChecks
import com.septaalfauzan.wanci.presentation.DeepLinkRequest
import com.septaalfauzan.wanci.presentation.WanciAppDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

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
    val core = MonitoringCore(
        repository = repository,
        todayKey = todayKey,
        selfPackageName = context.packageName,
        // Uptime, not elapsedRealtime: a session must not bill the user for time the device spent
        // asleep. See MonotonicClock for the rest of the reasoning.
        clock = MonotonicClock { SystemClock.uptimeMillis() },
        scope = scope,
        // No extra launch hop: OverlayManager.show posts to the main thread itself, so wrapping
        // this in scope.launch would only add a queue between the decision and the screen.
        onOverlayDecision = { decision -> overlayManager.show(decision) },
    )
    val coreMutex = Mutex()
    @Suppress("UnnecessaryLateinit")
    internal lateinit var overlayManager: OverlayManager
        private set

    init {
        overlayManager = OverlayManager(context)
    }

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
            deepLink = deepLink,
        )
}
