package com.septaalfauzan.wanci.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.time.LocalDate

object AndroidAppContextHolder {
    lateinit var context: Context
}

actual fun createWanciDatabase(nameOverride: String?): WanciDatabase {
    val name = nameOverride ?: WanciDatabase.DB_NAME
    return Room.databaseBuilder(AndroidAppContextHolder.context, WanciDatabase::class.java, name)
        .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
        .build()
}

actual fun createTodayKey(): TodayKey = TodayKey { LocalDate.now().toString() }
