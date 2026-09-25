package com.septaalfauzan.wanci

import android.content.Context
import com.septaalfauzan.wanci.data.local.createTodayKey
import com.septaalfauzan.wanci.data.local.createWanciDatabase
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepositoryImpl
import com.septaalfauzan.wanci.monitoring.MonitoringCore
import com.septaalfauzan.wanci.monitoring.OverlayManager
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
}
