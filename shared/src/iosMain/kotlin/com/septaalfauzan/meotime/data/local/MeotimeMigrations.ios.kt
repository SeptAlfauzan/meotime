package com.septaalfauzan.meotime.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_1_2_SQL.forEach { connection.execSQL(it) }
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_2_3_SQL.forEach { connection.execSQL(it) }
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_3_4_SQL.forEach { connection.execSQL(it) }
    }
}
