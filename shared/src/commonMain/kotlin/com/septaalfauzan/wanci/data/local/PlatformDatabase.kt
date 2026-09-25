package com.septaalfauzan.wanci.data.local

import androidx.room.RoomDatabaseConstructor
import kotlinx.coroutines.flow.Flow
import kotlin.experimental.ExperimentalNativeApi

@Suppress("KotlinNoActualForExpect")
expect object WanciDatabaseConstructor : RoomDatabaseConstructor<WanciDatabase> {
    override fun initialize(): WanciDatabase
}

@OptIn(ExperimentalNativeApi::class)
expect fun createWanciDatabase(nameOverride: String? = null): WanciDatabase

expect fun createTodayKey(): TodayKey

interface TodayKey {
    fun todayIso(): String
    fun dateFlow(): Flow<String>
}
