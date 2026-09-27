package com.septaalfauzan.wanci.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_1_2_SQL.forEach { connection.execSQL(it) }
    }
}
