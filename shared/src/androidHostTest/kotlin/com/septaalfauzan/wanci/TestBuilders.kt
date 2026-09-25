package com.septaalfauzan.wanci

import com.septaalfauzan.wanci.data.local.WanciDatabase
import com.septaalfauzan.wanci.data.local.createWanciDatabase
import java.io.File
import kotlin.io.path.createTempDirectory

private val testDatabaseDirectories = mutableMapOf<WanciDatabase, File>()

internal fun buildTestDatabase(label: String): WanciDatabase {
    val directory = createTempDirectory(prefix = "wanci-$label").toFile()
    val database = createWanciDatabase(nameOverride = directory.resolve("test.db").path)
    testDatabaseDirectories[database] = directory
    return database
}

internal fun WanciDatabase.closeAndDelete() {
    close()
    testDatabaseDirectories.remove(this)?.deleteRecursively()
}
