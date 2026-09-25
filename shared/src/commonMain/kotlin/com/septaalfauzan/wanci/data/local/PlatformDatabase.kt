package com.septaalfauzan.wanci.data.local

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
expect fun createWanciDatabase(nameOverride: String? = null): WanciDatabase

expect fun createTodayKey(): TodayKey

fun interface TodayKey {
    fun todayIso(): String
}
