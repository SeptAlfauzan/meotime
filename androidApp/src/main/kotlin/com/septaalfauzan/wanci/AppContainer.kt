package com.septaalfauzan.wanci

import android.content.Context
import com.septaalfauzan.wanci.apps.AppLabelResolver
import com.septaalfauzan.wanci.apps.InstalledAppsProviderAndroid
import com.septaalfauzan.wanci.data.local.createTodayKey
import com.septaalfauzan.wanci.data.local.createWanciDatabase
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepositoryImpl
import com.septaalfauzan.wanci.domain.PermissionStatusProvider
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import com.septaalfauzan.wanci.monitoring.MonitoringCore
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
    val core = MonitoringCore(repository, todayKey, context.packageName)
    internal val coreMutex = Mutex()
    val overlayManager = OverlayManager(context, core, scope, coreMutex)

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
