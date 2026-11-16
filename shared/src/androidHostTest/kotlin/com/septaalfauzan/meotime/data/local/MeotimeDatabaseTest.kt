package com.septaalfauzan.meotime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.septaalfauzan.meotime.data.local.AndroidAppContextHolder
import com.septaalfauzan.meotime.data.local.HourlyUsageDao
import com.septaalfauzan.meotime.data.local.createMeotimeDatabase
import com.septaalfauzan.meotime.data.local.entity.DailyUsageEntity
import com.septaalfauzan.meotime.data.local.entity.MonitoredAppEntity
import com.septaalfauzan.meotime.data.local.entity.SyncStateEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MeotimeDatabaseTest {

    @Before
    fun setUp() {
        AndroidAppContextHolder.context = ApplicationProvider.getApplicationContext<Context>()
    }

    @Test
    fun daosPersistAndQuery() = runTest {
        val db = buildTestDatabase("daos")
        try {
            val appDao = db.monitoredAppDao()
            val usageDao = db.dailyUsageDao()
            val syncDao = db.syncStateDao()

            val id = appDao.insert(
                MonitoredAppEntity(
                    packageName = "com.instagram.android",
                    appName = "Instagram",
                    dailyLimitMinutes = 30,
                    createdAt = 1L,
                    updatedAt = 1L,
                )
            )
            assertNotNull(id)
            val fromDb = appDao.getByPackage("com.instagram.android")
            assertNotNull(fromDb)
            assertEquals("Instagram", fromDb.appName)
            assertNull(appDao.getByPackage("com.example.missing"))

            assertEquals(1, appDao.observeAll().first().size)

            usageDao.insertOrIgnore(DailyUsageEntity("com.instagram.android", "2026-09-25", 42L, 2L))
            assertEquals(42L, usageDao.getUsageSeconds("com.instagram.android", "2026-09-25"))
            usageDao.addDeltaSeconds("com.instagram.android", "2026-09-25", 3L, 3L)
            assertEquals(45L, usageDao.getUsageSeconds("com.instagram.android", "2026-09-25"))

            syncDao.put(SyncStateEntity("sync_state_sample", "1700000000000"))
            assertEquals("1700000000000", syncDao.get("sync_state_sample"))
            assertNull(syncDao.get("missing"))
        } finally {
            db.closeAndDelete()
        }
    }

    @Test
    fun migration_1_2_preservesMonitoredAppsAndConvertsUsageToSeconds() = runTest {
        val directory = createTempDirectory(prefix = "meotime-v1").toFile()
        val path = directory.resolve("v1.db").path

        // A v1 database, built the way Room built it. `room_master_table` is deliberately absent:
        // Room validates the *structure* after a migration and rewrites the identity hash itself,
        // so seeding a hash we cannot compute would only make this test lie.
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).apply {
            execSQL(
                "CREATE TABLE IF NOT EXISTS `daily_usage` (" +
                    "`packageName` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                    "`usageMinutes` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`packageName`, `date`))",
            )
            execSQL(
                "CREATE TABLE IF NOT EXISTS `monitored_apps` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, " +
                    "`appName` TEXT NOT NULL, `dailyLimitMinutes` INTEGER NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            )
            execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_monitored_apps_packageName` " +
                    "ON `monitored_apps` (`packageName`)",
            )
            // Room validates the structure of *every* table after a migration, not only the one
            // the migration touched, so a v1 database without `sync_state` fails with
            // "Migration didn't properly handle: sync_state(...)" before reaching any assertion.
            // The table is unchanged by this migration; it just has to be there to be validated.
            execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_state` (" +
                    "`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
            )
            execSQL(
                "INSERT INTO `daily_usage` VALUES ('com.instagram.android', '2026-09-25', 42, 2)",
            )
            execSQL(
                "INSERT INTO `monitored_apps` VALUES " +
                    "(1, 'com.instagram.android', 'Instagram', 30, 1, 1, 1)",
            )
            execSQL("PRAGMA user_version = 1")
            close()
        }

        val db = createMeotimeDatabase(nameOverride = path)
        try {
            assertEquals(2_520L, db.dailyUsageDao().getUsageSeconds("com.instagram.android", "2026-09-25"))
            val app = db.monitoredAppDao().getByPackage("com.instagram.android")
            assertNotNull(app, "migration dropped the user's monitored apps")
            assertEquals(30, app.dailyLimitMinutes)
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun hourlyDaoAccumulatesBucketsAndUpdatesDailyTotals() = runTest {
        val db = buildTestDatabase("hourly")
        try {
            val hourlyDao = db.hourlyUsageDao()
            hourlyDao.recordBuckets(
                packageName = "com.instagram.android",
                buckets = listOf(
                    HourlyUsageDao.Bucket("2026-11-05", 9, 20L),
                    HourlyUsageDao.Bucket("2026-11-05", 10, 5L),
                    HourlyUsageDao.Bucket("2026-11-06", 0, 7L),
                ),
                updatedAt = 99L,
            )

            val sameDay = hourlyDao.observeForDate("2026-11-05").first()
            assertEquals(listOf(9, 10), sameDay.map { it.hour })
            assertEquals(listOf(20L, 5L), sameDay.map { it.usageSeconds })
            assertEquals(25L, db.dailyUsageDao().getUsageSeconds("com.instagram.android", "2026-11-05"))
            assertEquals(7L, db.dailyUsageDao().getUsageSeconds("com.instagram.android", "2026-11-06"))

            hourlyDao.recordBuckets(
                packageName = "com.instagram.android",
                buckets = listOf(HourlyUsageDao.Bucket("2026-11-05", 9, 3L)),
                updatedAt = 100L,
            )
            assertEquals(23L, hourlyDao.observeForDate("2026-11-05").first().first().usageSeconds)
            assertEquals(28L, db.dailyUsageDao().getUsageSeconds("com.instagram.android", "2026-11-05"))
        } finally {
            db.closeAndDelete()
        }
    }

    @Test
    fun migration_2_3_preservesExistingDataAndCreatesHourlyTable() = runTest {
        val directory = createTempDirectory(prefix = "meotime-v2").toFile()
        val path = directory.resolve("v2.db").path

        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).apply {
            execSQL(
                "CREATE TABLE IF NOT EXISTS `daily_usage` (" +
                    "`packageName` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                    "`usageSeconds` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`packageName`, `date`))",
            )
            execSQL(
                "CREATE TABLE IF NOT EXISTS `monitored_apps` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, " +
                    "`appName` TEXT NOT NULL, `dailyLimitMinutes` INTEGER NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            )
            execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_monitored_apps_packageName` " +
                    "ON `monitored_apps` (`packageName`)",
            )
            execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_state` (" +
                    "`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
            )
            execSQL("INSERT INTO `daily_usage` VALUES ('com.instagram.android', '2026-11-05', 42, 2)")
            execSQL(
                "INSERT INTO `monitored_apps` VALUES " +
                    "(1, 'com.instagram.android', 'Instagram', 30, 1, 1, 1)",
            )
            execSQL("PRAGMA user_version = 2")
            close()
        }

        val db = createMeotimeDatabase(nameOverride = path)
        try {
            assertEquals(42L, db.dailyUsageDao().getUsageSeconds("com.instagram.android", "2026-11-05"))
            assertEquals("Instagram", db.monitoredAppDao().getByPackage("com.instagram.android")?.appName)
            assertTrue(db.hourlyUsageDao().observeForDate("2026-11-05").first().isEmpty())

            db.hourlyUsageDao().recordBuckets(
                "com.instagram.android",
                listOf(HourlyUsageDao.Bucket("2026-11-05", 8, 11L)),
                3L,
            )
            assertEquals(11L, db.hourlyUsageDao().observeForDate("2026-11-05").first().single().usageSeconds)
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }
}
