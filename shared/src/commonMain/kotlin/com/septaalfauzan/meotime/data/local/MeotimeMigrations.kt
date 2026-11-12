package com.septaalfauzan.meotime.data.local

/**
 * v1 -> v2: `daily_usage.usageMinutes` became `usageSeconds`.
 *
 * The table is rebuilt rather than altered because `minSdk` is 28 and `ALTER TABLE ... DROP
 * COLUMN` needs SQLite 3.35, which is API 30. Existing rows are multiplied by 60 so a user
 * upgrading mid-day keeps the usage they have already accrued against today's limits — the
 * alternative, a destructive migration, would cost them their monitored apps too, since they
 * live in another table this migration does not touch.
 *
 * Registered on both platforms in `createMeotimeDatabase`. An unregistered migration is not a
 * no-op: Room falls back to dropping every table.
 *
 * The statements, not the `Migration`, are what lives here: Room's `migrate` takes a
 * `SupportSQLiteDatabase` on Android and an `SQLiteConnection` on native, so the object cannot
 * be common. Both platforms take a plain `String` statement, which is the only thing these need
 * in common — but on Android `execSQL` is a member of `SupportSQLiteDatabase` while on native it
 * is an *extension* in package `androidx.sqlite`, so the native shell needs
 * `import androidx.sqlite.execSQL` or `execSQL` does not resolve.
 */
internal val MIGRATION_1_2_SQL: List<String> = listOf(
    """
    CREATE TABLE IF NOT EXISTS `daily_usage_new` (
      `packageName` TEXT NOT NULL,
      `date` TEXT NOT NULL,
      `usageSeconds` INTEGER NOT NULL,
      `updatedAt` INTEGER NOT NULL,
      PRIMARY KEY(`packageName`, `date`)
    )
    """.trimIndent(),
    """
    INSERT OR REPLACE INTO `daily_usage_new`
      (`packageName`, `date`, `usageSeconds`, `updatedAt`)
    SELECT `packageName`, `date`, `usageMinutes` * 60, `updatedAt` FROM `daily_usage`
    """.trimIndent(),
    "DROP TABLE `daily_usage`",
    "ALTER TABLE `daily_usage_new` RENAME TO `daily_usage`",
)
