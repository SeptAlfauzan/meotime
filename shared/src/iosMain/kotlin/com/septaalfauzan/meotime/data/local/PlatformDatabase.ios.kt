package com.septaalfauzan.meotime.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSHomeDirectory

@OptIn(ExperimentalForeignApi::class)
actual fun createMeotimeDatabase(nameOverride: String?): MeotimeDatabase {
    val name = nameOverride ?: (NSHomeDirectory() + "/Documents/" + MeotimeDatabase.DB_NAME)
    return Room.databaseBuilder<MeotimeDatabase>(name = name)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(MIGRATION_1_2)
        .build()
}

actual fun createTodayKey(): TodayKey = object : TodayKey {
    override fun todayIso(): String = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd"
    }.stringFromDate(NSDate())

    override fun dateFlow(): Flow<String> = flow {
        val calendar = NSCalendar.currentCalendar
        while (true) {
            emit(todayIso())
            val now = NSDate()
            val nextDay = checkNotNull(calendar.dateByAddingUnit(NSCalendarUnitDay, 1L, now, 0uL))
            val nextMidnight = calendar.startOfDayForDate(nextDay)
            val delayMillis = ((nextMidnight.timeIntervalSinceReferenceDate - now.timeIntervalSinceReferenceDate) * 1000.0)
                .toLong()
                .coerceAtLeast(1L)
            delay(delayMillis)
        }
    }
}
