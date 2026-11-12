package com.septaalfauzan.meotime.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

object AndroidAppContextHolder {
    lateinit var context: Context
}

actual fun createMeotimeDatabase(nameOverride: String?): MeotimeDatabase {
    val name = nameOverride ?: MeotimeDatabase.DB_NAME
    return Room.databaseBuilder(AndroidAppContextHolder.context, MeotimeDatabase::class.java, name)
        .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
        .addMigrations(MIGRATION_1_2)
        .build()
}

actual fun createTodayKey(): TodayKey = object : TodayKey {
    override fun todayIso(): String = LocalDate.now().toString()

    override fun dateFlow(): Flow<String> = flow {
        while (true) {
            emit(todayIso())
            val now = ZonedDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault())
            delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L))
        }
    }
}
