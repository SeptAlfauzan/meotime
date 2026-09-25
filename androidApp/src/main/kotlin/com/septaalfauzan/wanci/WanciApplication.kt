package com.septaalfauzan.wanci

import android.app.Application
import com.septaalfauzan.wanci.data.local.AndroidAppContextHolder

class WanciApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        AndroidAppContextHolder.context = applicationContext
        container = AppContainer(this)
    }
}
