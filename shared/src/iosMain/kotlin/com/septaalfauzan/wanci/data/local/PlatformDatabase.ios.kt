package com.septaalfauzan.wanci.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSHomeDirectory

@OptIn(ExperimentalForeignApi::class)
actual fun createWanciDatabase(nameOverride: String?): WanciDatabase {
    val name = nameOverride ?: (NSHomeDirectory() + "/Documents/" + WanciDatabase.DB_NAME)
    return Room.databaseBuilder<WanciDatabase>(name = name)
        .setDriver(BundledSQLiteDriver())
        .build()
}

actual fun createTodayKey(): TodayKey = TodayKey {
    platform.Foundation.NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd"
    }.stringFromDate(platform.Foundation.NSDate())
}
