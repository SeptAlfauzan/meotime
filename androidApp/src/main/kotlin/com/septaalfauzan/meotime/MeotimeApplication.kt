package com.septaalfauzan.meotime

import android.app.Application
import com.septaalfauzan.meotime.data.local.AndroidAppContextHolder

class MeotimeApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        AndroidAppContextHolder.context = applicationContext
        container = AppContainer(this)
    }
}
