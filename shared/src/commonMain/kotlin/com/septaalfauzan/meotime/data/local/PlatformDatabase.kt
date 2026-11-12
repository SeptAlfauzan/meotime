package com.septaalfauzan.meotime.data.local

import androidx.room.RoomDatabaseConstructor
import kotlinx.coroutines.flow.Flow
import kotlin.experimental.ExperimentalNativeApi

@Suppress("KotlinNoActualForExpect")
expect object MeotimeDatabaseConstructor : RoomDatabaseConstructor<MeotimeDatabase> {
    override fun initialize(): MeotimeDatabase
}

@OptIn(ExperimentalNativeApi::class)
expect fun createMeotimeDatabase(nameOverride: String? = null): MeotimeDatabase

expect fun createTodayKey(): TodayKey

interface TodayKey {
    fun todayIso(): String
    fun dateFlow(): Flow<String>
}
