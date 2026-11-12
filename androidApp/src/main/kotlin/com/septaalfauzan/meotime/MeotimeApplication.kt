package com.septaalfauzan.meotime

import android.app.Application
import com.septaalfauzan.meotime.data.local.AndroidAppContextHolder
import java.time.LocalDate
import kotlinx.coroutines.launch

class MeotimeApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        AndroidAppContextHolder.context = applicationContext
        container = AppContainer(this)
        container.scope.launch {
            // Derived from the injected clock rather than a second LocalDate.now(), so the prune
            // cannot disagree with which day the rest of the app thinks it is.
            val cutoff = LocalDate.parse(container.todayKey.todayIso()).minusDays(7).toString()
            container.repository.deleteUsageBefore(cutoff)
        }
    }
}
