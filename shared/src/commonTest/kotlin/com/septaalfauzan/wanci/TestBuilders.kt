package com.septaalfauzan.wanci

import androidx.room.RoomDatabase
import com.septaalfauzan.wanci.data.local.WanciDatabase
import com.septaalfauzan.wanci.data.local.createWanciDatabase
import kotlin.io.path.createTempDirectory

internal fun buildTestDatabase(label: String): WanciDatabase {
    val dir = createTempDirectory(prefix = "wanci-$label")
    return createWanciDatabase(nameOverride = dir.resolve("test.db").toString())
}

internal fun WanciDatabase.closeAndDelete() {
    close()
}
