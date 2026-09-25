package com.septaalfauzan.wanci.data.local

import androidx.room.RoomDatabaseConstructor
import kotlin.experimental.ExperimentalNativeApi

@Suppress("KotlinNoActualForExpect")
expect object WanciDatabaseConstructor : RoomDatabaseConstructor<WanciDatabase> {
    override fun initialize(): WanciDatabase
}

@OptIn(ExperimentalNativeApi::class)
expect fun createWanciDatabase(nameOverride: String? = null): WanciDatabase

expect fun createTodayKey(): TodayKey

fun interface TodayKey {
    fun todayIso(): String
}
