package com.septaalfauzan.meotime

import com.septaalfauzan.meotime.data.local.MeotimeDatabase
import com.septaalfauzan.meotime.data.local.createMeotimeDatabase
import java.io.File
import kotlin.io.path.createTempDirectory

private val testDatabaseDirectories = mutableMapOf<MeotimeDatabase, File>()

internal fun buildTestDatabase(label: String): MeotimeDatabase {
    val directory = createTempDirectory(prefix = "meotime-$label").toFile()
    val database = createMeotimeDatabase(nameOverride = directory.resolve("test.db").path)
    testDatabaseDirectories[database] = directory
    return database
}

internal fun MeotimeDatabase.closeAndDelete() {
    close()
    testDatabaseDirectories.remove(this)?.deleteRecursively()
}
