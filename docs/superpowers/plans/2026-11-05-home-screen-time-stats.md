# Home Screen Time Stats Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an hourly-backed screen-time stats card and a single-day/date-range picker to Home while preserving the existing monitored-app rows and limit behavior.

**Architecture:** Extend Room with an `hourly_usage` table and split each monitoring session across local hour buckets at close time. Add common `kotlinx-datetime` date math, build pure stats/picker models in presentation code, then compose the approved card, calendar sheet, and dashboard wiring.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Room 2.8.5 KMP, kotlinx-coroutines, kotlinx-datetime 0.8.0, Robolectric Android host tests.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-11-05-home-screen-time-stats-design.md`.
- Stats cover currently monitored apps only; removed apps' historical rows are excluded.
- Keep all history. Remove the cold-start 7-day prune call; keep `deleteUsageBefore` as a maintenance API.
- `hourly_usage` primary key is `(packageName, date, hour)`; hour is `0..23`.
- Single-day chart has six 4-hour buckets labeled `12a`, `4a`, `8a`, `12p`, `4p`, `8p`.
- Range chart is a horizontal `LazyRow`, one stacked bar per day.
- Top 3 apps plus `Others`; 3 or fewer monitored apps show all, no `Others`.
- Fixed chart colors: 1st `#FF5A5F`, 2nd `#A78BFA`, 3rd `#60A5FA`, `Others` `#5C5C5C`.
- Comparison fell: `#6EE7A0` on `#12301E`; rose: `#FF8A80` on `#301212`.
- Comparison labels: Today `vs yesterday`, other single day `vs prior day`, range `vs prior period`.
- Comparison percent is `(current - previous) / previous * 100`, one decimal, trailing `.0` removed; prior zero shows `—`.
- Compact stats duration examples: `0 sec`, `15m`, `1h 12m`, `38m`. Existing `formatUsage` stays for single-day row copy.
- Home remains inside `MonochromeTheme`; do not force dark mode.
- `kotlinx-datetime` version is `0.8.0`; use `kotlin.time.Instant` and `kotlinx.datetime.toLocalDateTime`.
- Do not commit, amend, push, or create a PR unless the user explicitly asks.
- Verification commands: `./gradlew :shared:testAndroidHostTest` and `./gradlew :androidApp:assembleDebug`.

---

### Task 1: Hourly storage and Room migration 2 -> 3

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `shared/build.gradle.kts`
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/local/entity/HourlyUsageEntity.kt`
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/local/HourlyUsageDao.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/local/DailyUsageDao.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/local/MeotimeDatabase.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/local/MeotimeMigrations.kt`
- Modify: `shared/src/androidMain/kotlin/com/septaalfauzan/meotime/data/local/MeotimeMigrations.android.kt`
- Modify: `shared/src/iosMain/kotlin/com/septaalfauzan/meotime/data/local/MeotimeMigrations.ios.kt`
- Modify: `shared/src/androidMain/kotlin/com/septaalfauzan/meotime/data/local/PlatformDatabase.android.kt`
- Modify: `shared/src/iosMain/kotlin/com/septaalfauzan/meotime/data/local/PlatformDatabase.ios.kt`
- Test: `shared/src/androidHostTest/kotlin/com/septaalfauzan/meotime/data/local/MeotimeDatabaseTest.kt`
- Generated: `shared/schemas/com.septaalfauzan.meotime.data.local.MeotimeDatabase/3.json`

**Interfaces:**
- Consumes: existing `DailyUsageEntity`, `DailyUsageDao`, `MeotimeDatabase`.
- Produces:
  - `HourlyUsageEntity(packageName: String, date: String, hour: Int, usageSeconds: Long, updatedAt: Long)`
  - `HourlyUsageDao.observeForDate(date: String): Flow<List<HourlyUsageEntity>>`
  - `HourlyUsageDao.recordBuckets(packageName: String, buckets: List<HourlyUsageDao.Bucket>, updatedAt: Long)`
  - `HourlyUsageDao.deleteBefore(date: String)`
  - `DailyUsageDao.observeForRange(startDate: String, endDate: String): Flow<List<DailyUsageEntity>>`
  - `MIGRATION_2_3_SQL`, `MIGRATION_2_3`.

- [ ] **Step 1: Add the failing database tests**

Add these tests to `shared/src/androidHostTest/kotlin/com/septaalfauzan/meotime/data/local/MeotimeDatabaseTest.kt`.

```kotlin
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
```

Add imports used by the tests:

```kotlin
import com.septaalfauzan.meotime.data.local.HourlyUsageDao
import kotlin.test.assertTrue
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests '*MeotimeDatabaseTest*'`

Expected: compilation fails because `hourlyUsageDao()` and `HourlyUsageDao` do not exist yet.

- [ ] **Step 3: Add the dependency**

In `gradle/libs.versions.toml`, add version and library entries:

```toml
[versions]
# ...
datetime = "0.8.0"

[libraries]
# ...
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "datetime" }
```

In `shared/build.gradle.kts`, add to `commonMain.dependencies`:

```kotlin
implementation(libs.kotlinx.datetime)
```

- [ ] **Step 4: Create the entity**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/local/entity/HourlyUsageEntity.kt`:

```kotlin
package com.septaalfauzan.meotime.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One app's recorded screen time in one local hour, in seconds.
 *
 * The hour is local civil time: the same hour number can occur twice on a daylight-saving
 * fall-back day. Those two physical hours intentionally aggregate into the same row because the
 * chart has one bar per civil hour, not per offset.
 *
 * The `date` index serves the date-scoped reads; the primary key starts with `packageName`, so it
 * cannot serve a query that filters only on `date`.
 */
@Entity(
    tableName = "hourly_usage",
    primaryKeys = ["packageName", "date", "hour"],
    indices = [Index(value = ["date"])],
)
data class HourlyUsageEntity(
    val packageName: String,
    val date: String,
    val hour: Int,
    val usageSeconds: Long,
    val updatedAt: Long,
)
```

- [ ] **Step 5: Create the DAO and transactional write path**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/local/HourlyUsageDao.kt`:

```kotlin
package com.septaalfauzan.meotime.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.septaalfauzan.meotime.data.local.entity.DailyUsageEntity
import com.septaalfauzan.meotime.data.local.entity.HourlyUsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HourlyUsageDao {
    data class Bucket(
        val date: String,
        val hour: Int,
        val seconds: Long,
    )

    @Query("SELECT * FROM hourly_usage WHERE date = :date ORDER BY hour")
    fun observeForDate(date: String): Flow<List<HourlyUsageEntity>>

    @Query(
        "UPDATE hourly_usage SET usageSeconds = usageSeconds + :delta, updatedAt = :updatedAt " +
            "WHERE packageName = :packageName AND date = :date AND hour = :hour",
    )
    suspend fun addDelta(
        packageName: String,
        date: String,
        hour: Int,
        delta: Long,
        updatedAt: Long,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(usage: HourlyUsageEntity): Long

    @Query(
        "UPDATE daily_usage SET usageSeconds = usageSeconds + :delta, updatedAt = :updatedAt " +
            "WHERE packageName = :packageName AND date = :date",
    )
    suspend fun addDailyDelta(packageName: String, date: String, delta: Long, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDailyIfAbsent(usage: DailyUsageEntity): Long

    @Query("DELETE FROM hourly_usage WHERE date < :date")
    suspend fun deleteHourlyBefore(date: String)

    @Query("DELETE FROM daily_usage WHERE date < :date")
    suspend fun deleteDailyBefore(date: String)

    /**
     * Writes every bucket for one session and the matching daily totals in one transaction.
     *
     * Update-before-insert is deliberate: `ON CONFLICT DO UPDATE` needs SQLite 3.24, while
     * `minSdk` is 28. The update is a no-op when the row is absent, then the insert creates it
     * with the first value. A second bucket for the same day takes the update branch.
     */
    @Transaction
    suspend fun recordBuckets(packageName: String, buckets: List<Bucket>, updatedAt: Long) {
        buckets.forEach { bucket ->
            addDelta(packageName, bucket.date, bucket.hour, bucket.seconds, updatedAt)
            insertIfAbsent(
                HourlyUsageEntity(
                    packageName = packageName,
                    date = bucket.date,
                    hour = bucket.hour,
                    usageSeconds = bucket.seconds,
                    updatedAt = updatedAt,
                ),
            )
            addDailyDelta(packageName, bucket.date, bucket.seconds, updatedAt)
            insertDailyIfAbsent(
                DailyUsageEntity(
                    packageName = packageName,
                    date = bucket.date,
                    usageSeconds = bucket.seconds,
                    updatedAt = updatedAt,
                ),
            )
        }
    }

    @Transaction
    suspend fun deleteBefore(date: String) {
        deleteDailyBefore(date)
        deleteHourlyBefore(date)
    }
}
```

- [ ] **Step 6: Add the range query**

Add to `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/local/DailyUsageDao.kt`:

```kotlin
@Query("SELECT * FROM daily_usage WHERE date BETWEEN :startDate AND :endDate ORDER BY date, packageName")
fun observeForRange(startDate: String, endDate: String): Flow<List<DailyUsageEntity>>
```

- [ ] **Step 7: Register the entity and DAO**

Modify `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/local/MeotimeDatabase.kt`:

```kotlin
import com.septaalfauzan.meotime.data.local.entity.HourlyUsageEntity

@Database(
    entities = [
        MonitoredAppEntity::class,
        DailyUsageEntity::class,
        HourlyUsageEntity::class,
        SyncStateEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class MeotimeDatabase : RoomDatabase() {
    abstract fun monitoredAppDao(): MonitoredAppDao
    abstract fun dailyUsageDao(): DailyUsageDao
    abstract fun hourlyUsageDao(): HourlyUsageDao
    abstract fun syncStateDao(): SyncStateDao
}
```

- [ ] **Step 8: Add migration SQL and platform migrations**

Append to `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/local/MeotimeMigrations.kt`:

```kotlin
/**
 * v2 -> v3: add hourly buckets. Existing daily totals are untouched because they remain the
 * authoritative source for limits and for days recorded before hourly history existed.
 */
internal val MIGRATION_2_3_SQL: List<String> = listOf(
    """
    CREATE TABLE IF NOT EXISTS `hourly_usage` (
      `packageName` TEXT NOT NULL,
      `date` TEXT NOT NULL,
      `hour` INTEGER NOT NULL,
      `usageSeconds` INTEGER NOT NULL,
      `updatedAt` INTEGER NOT NULL,
      PRIMARY KEY(`packageName`, `date`, `hour`)
    )
    """.trimIndent(),
    """
    CREATE INDEX IF NOT EXISTS `index_hourly_usage_date`
      ON `hourly_usage` (`date`)
    """.trimIndent(),
)
```

In `shared/src/androidMain/kotlin/com/septaalfauzan/meotime/data/local/MeotimeMigrations.android.kt`, add:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_2_3_SQL.forEach { db.execSQL(it) }
    }
}
```

In `shared/src/iosMain/kotlin/com/septaalfauzan/meotime/data/local/MeotimeMigrations.ios.kt`, add:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_2_3_SQL.forEach { connection.execSQL(it) }
    }
}
```

In both platform database files, register the migration:

```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3)
```

- [ ] **Step 9: Generate schema 3 and run the tests**

Run: `./gradlew :shared:kspAndroidMain`

Expected: `shared/schemas/com.septaalfauzan.meotime.data.local.MeotimeDatabase/3.json` is created and contains `hourly_usage`.

Run: `./gradlew :shared:testAndroidHostTest --tests '*MeotimeDatabaseTest*'`

Expected: PASS.

### Task 2: Split sessions across local hour buckets

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/repository/SessionBucketSplitter.kt`
- Test: `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/data/repository/SessionBucketSplitterTest.kt`

**Interfaces:**
- Consumes: no project types.
- Produces:
  - `internal data class SessionBucket(val date: LocalDate, val hour: Int, val seconds: Long)`
  - `internal fun splitSession(deltaSeconds: Long, startedAtEpochMillis: Long, endedAtEpochMillis: Long, timeZone: TimeZone): List<SessionBucket>`

- [ ] **Step 1: Write the failing splitter tests**

Create `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/data/repository/SessionBucketSplitterTest.kt`:

```kotlin
package com.septaalfauzan.meotime.data.repository

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun epoch(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

class SessionBucketSplitterTest {

    @Test
    fun oneBucketWhenTheSessionStaysInsideOneHour() {
        val buckets = splitSession(
            deltaSeconds = 20L,
            startedAtEpochMillis = epoch("2026-11-05T10:00:30Z"),
            endedAtEpochMillis = epoch("2026-11-05T10:00:50Z"),
            timeZone = TimeZone.UTC,
        )

        assertEquals(listOf(SessionBucket(LocalDate(2026, 11, 5), 10, 20L)), buckets)
    }

    @Test
    fun adjacentHoursGetProportionalShares() {
        val buckets = splitSession(
            deltaSeconds = 20L,
            startedAtEpochMillis = epoch("2026-11-05T10:59:50Z"),
            endedAtEpochMillis = epoch("2026-11-05T11:00:10Z"),
            timeZone = TimeZone.UTC,
        )

        assertEquals(
            listOf(
                SessionBucket(LocalDate(2026, 11, 5), 10, 10L),
                SessionBucket(LocalDate(2026, 11, 5), 11, 10L),
            ),
            buckets,
        )
    }

    @Test
    fun midnightSplitsAcrossDates() {
        val buckets = splitSession(
            deltaSeconds = 20L,
            startedAtEpochMillis = epoch("2026-11-05T23:59:50Z"),
            endedAtEpochMillis = epoch("2026-11-06T00:00:10Z"),
            timeZone = TimeZone.UTC,
        )

        assertEquals(
            listOf(
                SessionBucket(LocalDate(2026, 11, 5), 23, 10L),
                SessionBucket(LocalDate(2026, 11, 6), 0, 10L),
            ),
            buckets,
        )
    }

    @Test
    fun largestRemainderKeepsTheExactDelta() {
        val buckets = splitSession(
            deltaSeconds = 10L,
            startedAtEpochMillis = epoch("2026-11-05T10:50:00Z"),
            endedAtEpochMillis = epoch("2026-11-05T11:20:00Z"),
            timeZone = TimeZone.UTC,
        )

        assertEquals(10L, buckets.sumOf { it.seconds })
        assertEquals(3L, buckets.first { it.hour == 10 }.seconds)
        assertEquals(7L, buckets.first { it.hour == 11 }.seconds)
    }

    @Test
    fun reversedWallSpanFallsBackToTheEndBucket() {
        val buckets = splitSession(
            deltaSeconds = 7L,
            startedAtEpochMillis = epoch("2026-11-05T10:10:00Z"),
            endedAtEpochMillis = epoch("2026-11-05T10:00:00Z"),
            timeZone = TimeZone.UTC,
        )

        assertEquals(listOf(SessionBucket(LocalDate(2026, 11, 5), 10, 7L)), buckets)
    }

    @Test
    fun zeroOrNegativeDeltaWritesNothing() {
        assertTrue(
            splitSession(
                deltaSeconds = 0L,
                startedAtEpochMillis = epoch("2026-11-05T10:00:00Z"),
                endedAtEpochMillis = epoch("2026-11-05T10:10:00Z"),
                timeZone = TimeZone.UTC,
            ).isEmpty(),
        )
        assertTrue(
            splitSession(
                deltaSeconds = -1L,
                startedAtEpochMillis = epoch("2026-11-05T10:00:00Z"),
                endedAtEpochMillis = epoch("2026-11-05T10:10:00Z"),
                timeZone = TimeZone.UTC,
            ).isEmpty(),
        )
    }

    @Test
    fun daylightSavingWallSpanStillAllocatesTheWholeDelta() {
        val buckets = splitSession(
            deltaSeconds = 7_200L,
            startedAtEpochMillis = epoch("2026-03-08T06:30:00Z"),
            endedAtEpochMillis = epoch("2026-03-08T07:30:00Z"),
            timeZone = TimeZone.of("America/New_York"),
        )

        assertEquals(7_200L, buckets.sumOf { it.seconds })
        assertTrue(buckets.all { it.date == LocalDate(2026, 3, 8) })
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests '*SessionBucketSplitterTest*'`

Expected: compilation fails because `splitSession` and `SessionBucket` do not exist.

- [ ] **Step 3: Implement the splitter**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/repository/SessionBucketSplitter.kt`:

```kotlin
package com.septaalfauzan.meotime.data.repository

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal data class SessionBucket(
    val date: LocalDate,
    val hour: Int,
    val seconds: Long,
)

/**
 * Distributes a monotonic session duration over the local civil hours the wall-clock span
 * overlaps. The monotonic duration is authoritative; wall-clock overlap is only the shape of the
 * distribution, so a clock jump or a DST gap changes where seconds land, never how many exist.
 */
internal fun splitSession(
    deltaSeconds: Long,
    startedAtEpochMillis: Long,
    endedAtEpochMillis: Long,
    timeZone: TimeZone,
): List<SessionBucket> {
    if (deltaSeconds <= 0L) return emptyList()
    val start = Instant.fromEpochMilliseconds(startedAtEpochMillis)
    val end = Instant.fromEpochMilliseconds(endedAtEpochMillis)
    if (end <= start) return listOf(endBucket(end, deltaSeconds, timeZone))

    val weighted = mutableListOf<WeightedBucket>()
    var cursor = start
    while (cursor < end) {
        val local = cursor.toLocalDateTime(timeZone)
        val nextLocal = nextHour(local)
        val next = nextLocal.toInstant(timeZone)
        val segmentEnd = when {
            next <= cursor -> minOf(end, cursor.plus(1, DateTimeUnit.HOUR))
            else -> minOf(end, next)
        }
        val overlapMillis = segmentEnd.toEpochMilliseconds() - cursor.toEpochMilliseconds()
        if (overlapMillis > 0L) {
            weighted += WeightedBucket(
                bucket = SessionBucket(local.date, local.hour, 0L),
                overlapMillis = overlapMillis.toDouble(),
            )
        }
        cursor = segmentEnd
    }

    if (weighted.isEmpty()) return listOf(endBucket(end, deltaSeconds, timeZone))

    val totalMillis = weighted.sumOf { it.overlapMillis }
    val allocations = weighted.map { item ->
        val raw = deltaSeconds.toDouble() * item.overlapMillis / totalMillis
        Allocation(
            bucket = item.bucket,
            whole = raw.toLong(),
            fraction = raw - raw.toLong(),
        )
    }
    val extra = (deltaSeconds - allocations.sumOf { it.whole })
        .coerceIn(0L, allocations.size.toLong())
        .toInt()
    val bonusIndices = allocations.indices
        .sortedWith(compareByDescending<Int> { allocations[it].fraction }.thenBy { it })
        .take(extra)
        .toSet()

    val grouped = linkedMapOf<Pair<LocalDate, Int>, Long>()
    allocations.forEachIndexed { index, allocation ->
        val seconds = allocation.whole + if (index in bonusIndices) 1L else 0L
        if (seconds <= 0L) return@forEachIndexed
        val key = allocation.bucket.date to allocation.bucket.hour
        grouped[key] = (grouped[key] ?: 0L) + seconds
    }
    return grouped
        .map { (key, seconds) -> SessionBucket(key.first, key.second, seconds) }
        .sortedWith(compareBy<SessionBucket> { it.date }.thenBy { it.hour })
}

private data class WeightedBucket(
    val bucket: SessionBucket,
    val overlapMillis: Double,
)

private data class Allocation(
    val bucket: SessionBucket,
    val whole: Long,
    val fraction: Double,
)

private fun nextHour(local: LocalDateTime): LocalDateTime =
    if (local.hour == 23) {
        local.date.plus(1, DateTimeUnit.DAY).atTime(0, 0)
    } else {
        local.date.atTime(local.hour + 1, 0)
    }

private fun endBucket(end: Instant, deltaSeconds: Long, timeZone: TimeZone): SessionBucket {
    val local = end.toLocalDateTime(timeZone)
    return SessionBucket(local.date, local.hour, deltaSeconds)
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :shared:testAndroidHostTest --tests '*SessionBucketSplitterTest*'`

Expected: PASS.

### Task 3: Record split sessions and expose usage reads

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/domain/model/UsagePoints.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/repository/ScreenTimeRepository.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/data/repository/ScreenTimeRepositoryImpl.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/domain/useCase/MeotimeUseCases.kt`
- Modify: `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/FakeRepository.kt`
- Test: `shared/src/androidHostTest/kotlin/com/septaalfauzan/meotime/data/repository/ScreenTimeRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `splitSession`, `HourlyUsageDao`, `DailyUsageDao.observeForRange`.
- Produces:
  - `data class DailyUsagePoint(val packageName: String, val date: LocalDate, val usageSeconds: Long)`
  - `data class HourlyUsagePoint(val packageName: String, val date: LocalDate, val hour: Int, val usageSeconds: Long)`
  - `ScreenTimeRepository.recordUsageSession(packageName: String, deltaSeconds: Long, startedAtEpochMillis: Long, endedAtEpochMillis: Long)`
  - `ScreenTimeRepository.observeDailyUsage(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyUsagePoint>>`
  - `ScreenTimeRepository.observeHourlyUsage(date: LocalDate): Flow<List<HourlyUsagePoint>>`
  - Matching `MeotimeUseCases` methods.

- [ ] **Step 1: Add the failing repository tests**

In `ScreenTimeRepositoryImplTest.kt`, replace the `newRepo` helper with:

```kotlin
private suspend fun newRepo(
    todayKey: TodayKey = today,
    timeZone: TimeZone = TimeZone.UTC,
) = run {
    val db = buildTestDatabase("repo")
    val repo = ScreenTimeRepositoryImpl(
        monitoredAppDao = db.monitoredAppDao(),
        dailyUsageDao = db.dailyUsageDao(),
        hourlyUsageDao = db.hourlyUsageDao(),
        syncStateDao = db.syncStateDao(),
        todayKey = todayKey,
        timeZone = timeZone,
    )
    repo to db
}
```

Add imports:

```kotlin
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
```

Add tests:

```kotlin
@Test
fun recordUsageSessionSplitsAcrossHoursAndDates() = runTest {
    val (repo, db) = newRepo()
    try {
        repo.recordUsageSession(
            packageName = "com.instagram.android",
            deltaSeconds = 20L,
            startedAtEpochMillis = Instant.parse("2026-11-05T23:59:50Z").toEpochMilliseconds(),
            endedAtEpochMillis = Instant.parse("2026-11-06T00:00:10Z").toEpochMilliseconds(),
        )

        assertEquals(10L, repo.getTodayUsageSeconds("com.instagram.android", "2026-11-05"))
        assertEquals(10L, repo.getTodayUsageSeconds("com.instagram.android", "2026-11-06"))
        assertEquals(
            listOf(10L),
            db.hourlyUsageDao().observeForDate("2026-11-05").first().map { it.usageSeconds },
        )
        assertEquals(
            listOf(10L),
            db.hourlyUsageDao().observeForDate("2026-11-06").first().map { it.usageSeconds },
        )
    } finally {
        db.closeAndDelete()
    }
}

@Test
fun recordUsageSessionIgnoresNonPositiveDelta() = runTest {
    val (repo, db) = newRepo()
    try {
        repo.recordUsageSession(
            packageName = "com.instagram.android",
            deltaSeconds = 0L,
            startedAtEpochMillis = Instant.parse("2026-11-05T10:00:00Z").toEpochMilliseconds(),
            endedAtEpochMillis = Instant.parse("2026-11-05T10:10:00Z").toEpochMilliseconds(),
        )

        assertTrue(db.hourlyUsageDao().observeForDate("2026-11-05").first().isEmpty())
        assertEquals(null, db.dailyUsageDao().getUsageSeconds("com.instagram.android", "2026-11-05"))
    } finally {
        db.closeAndDelete()
    }
}

@Test
fun observeDailyUsageReadsTheRequestedRange() = runTest {
    val (repo, db) = newRepo()
    try {
        repo.recordUsageSeconds("com.instagram.android", 1L, "2026-11-03")
        repo.recordUsageSeconds("com.instagram.android", 2L, "2026-11-04")
        repo.recordUsageSeconds("com.instagram.android", 3L, "2026-11-05")
        repo.recordUsageSeconds("com.instagram.android", 4L, "2026-11-06")

        val rows = repo.observeDailyUsage(LocalDate(2026, 11, 4), LocalDate(2026, 11, 5)).first()

        assertEquals(listOf(2L, 3L), rows.map { it.usageSeconds })
    } finally {
        db.closeAndDelete()
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests '*ScreenTimeRepositoryImplTest*'`

Expected: compilation fails because the new constructor parameter and methods do not exist.

- [ ] **Step 3: Add domain usage points**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/domain/model/UsagePoints.kt`:

```kotlin
package com.septaalfauzan.meotime.domain.model

import kotlinx.datetime.LocalDate

data class DailyUsagePoint(
    val packageName: String,
    val date: LocalDate,
    val usageSeconds: Long,
)

data class HourlyUsagePoint(
    val packageName: String,
    val date: LocalDate,
    val hour: Int,
    val usageSeconds: Long,
)
```

- [ ] **Step 4: Extend the repository contract**

In `ScreenTimeRepository.kt`, add imports and methods:

```kotlin
import kotlinx.datetime.LocalDate

fun observeDailyUsage(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyUsagePoint>>
fun observeHourlyUsage(date: LocalDate): Flow<List<HourlyUsagePoint>>
suspend fun recordUsageSession(
    packageName: String,
    deltaSeconds: Long,
    startedAtEpochMillis: Long,
    endedAtEpochMillis: Long,
)
```

and import:

```kotlin
import com.septaalfauzan.meotime.domain.model.DailyUsagePoint
import com.septaalfauzan.meotime.domain.model.HourlyUsagePoint
```

- [ ] **Step 5: Implement the repository**

Modify `ScreenTimeRepositoryImpl.kt`. Add imports:

```kotlin
import com.septaalfauzan.meotime.data.local.HourlyUsageDao
import com.septaalfauzan.meotime.domain.model.DailyUsagePoint
import com.septaalfauzan.meotime.domain.model.HourlyUsagePoint
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
```

Add constructor parameters:

```kotlin
class ScreenTimeRepositoryImpl(
    private val monitoredAppDao: MonitoredAppDao,
    private val dailyUsageDao: DailyUsageDao,
    private val hourlyUsageDao: HourlyUsageDao,
    private val syncStateDao: SyncStateDao,
    private val todayKey: TodayKey,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ScreenTimeRepository {
```

Add implementations:

```kotlin
override fun observeDailyUsage(
    startDate: LocalDate,
    endDate: LocalDate,
): Flow<List<DailyUsagePoint>> =
    dailyUsageDao.observeForRange(startDate.toString(), endDate.toString()).map { rows ->
        rows.map { row ->
            DailyUsagePoint(
                packageName = row.packageName,
                date = LocalDate.parse(row.date),
                usageSeconds = row.usageSeconds,
            )
        }
    }

override fun observeHourlyUsage(date: LocalDate): Flow<List<HourlyUsagePoint>> =
    hourlyUsageDao.observeForDate(date.toString()).map { rows ->
        rows.map { row ->
            HourlyUsagePoint(
                packageName = row.packageName,
                date = date,
                hour = row.hour,
                usageSeconds = row.usageSeconds,
            )
        }
    }

override suspend fun recordUsageSession(
    packageName: String,
    deltaSeconds: Long,
    startedAtEpochMillis: Long,
    endedAtEpochMillis: Long,
) {
    val buckets = splitSession(
        deltaSeconds = deltaSeconds,
        startedAtEpochMillis = startedAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        timeZone = timeZone,
    )
    if (buckets.isEmpty()) return
    hourlyUsageDao.recordBuckets(
        packageName = packageName,
        buckets = buckets.map { HourlyUsageDao.Bucket(it.date.toString(), it.hour, it.seconds) },
        updatedAt = Clock.System.now().toEpochMilliseconds(),
    )
}
```

Change `deleteUsageBefore` to:

```kotlin
override suspend fun deleteUsageBefore(date: String) {
    hourlyUsageDao.deleteBefore(date)
}
```

- [ ] **Step 6: Expose the reads through use cases**

In `MeotimeUseCases.kt`, add imports and methods:

```kotlin
import com.septaalfauzan.meotime.domain.model.DailyUsagePoint
import com.septaalfauzan.meotime.domain.model.HourlyUsagePoint
import kotlinx.datetime.LocalDate

fun observeDailyUsage(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyUsagePoint>> =
    repository.observeDailyUsage(startDate, endDate)

fun observeHourlyUsage(date: LocalDate): Flow<List<HourlyUsagePoint>> =
    repository.observeHourlyUsage(date)
```

- [ ] **Step 7: Update `FakeRepository`**

Add these imports to `FakeRepository.kt`, then add the fields and overrides inside the `FakeRepository` class:

```kotlin
import com.septaalfauzan.meotime.domain.model.DailyUsagePoint
import com.septaalfauzan.meotime.domain.model.HourlyUsagePoint
import kotlinx.datetime.LocalDate

data class RecordedSession(
    val packageName: String,
    val deltaSeconds: Long,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
)

val sessions = mutableListOf<RecordedSession>()
val dailyUsage = MutableStateFlow<Map<LocalDate, Map<String, Long>>>(emptyMap())
val hourlyUsage = MutableStateFlow<Map<LocalDate, Map<Int, Map<String, Long>>>>(emptyMap())

override fun observeDailyUsage(
    startDate: LocalDate,
    endDate: LocalDate,
): Flow<List<DailyUsagePoint>> = dailyUsage.map { byDate ->
    byDate
        .filterKeys { it >= startDate && it <= endDate }
        .flatMap { (date, byPackage) ->
            byPackage.map { (packageName, seconds) -> DailyUsagePoint(packageName, date, seconds) }
        }
}

override fun observeHourlyUsage(date: LocalDate): Flow<List<HourlyUsagePoint>> = hourlyUsage.map { byDate ->
    byDate[date].orEmpty().flatMap { (hour, byPackage) ->
        byPackage.map { (packageName, seconds) -> HourlyUsagePoint(packageName, date, hour, seconds) }
    }
}

override suspend fun recordUsageSession(
    packageName: String,
    deltaSeconds: Long,
    startedAtEpochMillis: Long,
    endedAtEpochMillis: Long,
) {
    if (failOnRecord) throw IllegalStateException("simulated Room failure")
    sessions += RecordedSession(packageName, deltaSeconds, startedAtEpochMillis, endedAtEpochMillis)
    usage.update { it + (packageName to ((it[packageName] ?: 0L) + deltaSeconds)) }
}
```

Also make `recordUsageSeconds` update `dailyUsage` so stats tests can create history through the fake:

```kotlin
override suspend fun recordUsageSeconds(packageName: String, deltaSeconds: Long, date: String) {
    if (failOnRecord) throw IllegalStateException("simulated Room failure")
    recorded += Triple(packageName, deltaSeconds, date)
    usage.update { it + (packageName to ((it[packageName] ?: 0L) + deltaSeconds)) }
    dailyUsage.update { byDate ->
        val parsed = LocalDate.parse(date)
        val byPackage = byDate[parsed].orEmpty()
        byDate + (parsed to (byPackage + (packageName to ((byPackage[packageName] ?: 0L) + deltaSeconds))))
    }
}
```

- [ ] **Step 8: Run the repository tests**

Run: `./gradlew :shared:testAndroidHostTest --tests '*ScreenTimeRepositoryImplTest*'`

Expected: PASS.

### Task 4: Capture wall-clock session spans in MonitoringCore

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/monitoring/WallClock.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/monitoring/MonitoringCore.kt`
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/meotime/AppContainer.kt`
- Test: `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/monitoring/MonitoringCoreTest.kt`

**Interfaces:**
- Consumes: `ScreenTimeRepository.recordUsageSession`.
- Produces:
  - `fun interface WallClock { fun nowEpochMillis(): Long }`
  - `SystemWallClock`
  - `MonitoringCore.wallClock` constructor parameter.

- [ ] **Step 1: Add the failing wall-clock test**

Add to `MonitoringCoreTest.kt`:

```kotlin
@Test
fun sessionCloseRecordsTheWallClockSpanAlongsideTheMonotonicDelta() = runTest {
    val repo = repository()
    var wallNow = 1_700_000_000_000L
    val core = MonitoringCore(
        repository = repo,
        todayKey = today,
        selfPackageName = selfPkg,
        clock = MonotonicClock { currentTime },
        wallClock = WallClock { wallNow },
        scope = this,
        onOverlayDecision = { decisions += it },
    ).apply { attach() }

    core.onForegroundChange(INSTAGRAM)
    advanceTimeBy(30_000L)
    wallNow += 30_000L
    core.onForegroundChange(LAUNCHER)

    val session = repo.sessions.single()
    assertEquals(INSTAGRAM, session.packageName)
    assertEquals(30L, session.deltaSeconds)
    assertEquals(1_700_000_000_000L, session.startedAtEpochMillis)
    assertEquals(1_700_000_030_000L, session.endedAtEpochMillis)
}
```

Add import:

```kotlin
import com.septaalfauzan.meotime.monitoring.WallClock
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :shared:testAndroidHostTest --tests '*MonitoringCoreTest*'`

Expected: compilation fails because `wallClock` and `SystemWallClock` do not exist.

- [ ] **Step 3: Add the wall clock**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/monitoring/WallClock.kt`:

```kotlin
package com.septaalfauzan.meotime.monitoring

import kotlin.time.Clock

/**
 * Wall-clock epoch time, separate from [MonotonicClock].
 *
 * The monotonic clock decides how many seconds a session lasted; this one decides which local
 * hours those seconds belong to. Keeping the two apart is what lets a doze-stalled ticker stay
 * honest without letting a device clock change alter the duration.
 */
fun interface WallClock {
    fun nowEpochMillis(): Long
}

val SystemWallClock = WallClock { Clock.System.now().toEpochMilliseconds() }
```

- [ ] **Step 4: Wire the wall clock into sessions**

In `MonitoringCore.kt`, add the parameter after `clock`:

```kotlin
private val wallClock: WallClock = SystemWallClock,
```

Add the field to `Session`:

```kotlin
private class Session(
    val packageName: String,
    val appName: String,
    val startedAtMillis: Long,
    val startedAtEpochMillis: Long,
    val storedSeconds: Long,
    val limitSeconds: Long,
    var overlayShown: Boolean,
) {
```

In `createSession`, pass the wall time captured at open:

```kotlin
return Session(
    packageName = packageName,
    appName = app.appName,
    startedAtMillis = now,
    startedAtEpochMillis = wallClock.nowEpochMillis(),
    storedSeconds = repository.getTodayUsageSeconds(packageName, todayKey.todayIso()),
    limitSeconds = app.dailyLimitMinutes * 60L,
    overlayShown = false,
)
```

In `closeCurrentSession`, replace the single write with:

```kotlin
val elapsed = (now - session.startedAtMillis) / 1000
if (elapsed <= 0L) return
try {
    repository.recordUsageSession(
        packageName = session.packageName,
        deltaSeconds = elapsed,
        startedAtEpochMillis = session.startedAtEpochMillis,
        endedAtEpochMillis = wallClock.nowEpochMillis(),
    )
} catch (_: Exception) {
    // The session is closed and the state is consistent, so a failed write costs the
    // seconds it carried and nothing else. Letting it escape would abort before the next
    // session is opened, so one database error would leave whatever app the user switched
    // to untracked for the rest of the foreground period.
}
```

- [ ] **Step 5: Wire the clock in the app container**

In `AppContainer.kt`, import and pass the system wall clock explicitly:

```kotlin
import com.septaalfauzan.meotime.monitoring.SystemWallClock
```

```kotlin
val core = MonitoringCore(
    repository = repository,
    todayKey = todayKey,
    selfPackageName = context.packageName,
    clock = clock,
    wallClock = SystemWallClock,
    scope = scope,
    onOverlayDecision = { decision ->
        // existing body
    },
    onSessionChanged = { snapshot -> notificationController.onSessionChanged(snapshot) },
)
```

- [ ] **Step 6: Run the monitoring tests**

Run: `./gradlew :shared:testAndroidHostTest --tests '*MonitoringCoreTest*'`

Expected: PASS. Existing session tests still assert the same totals because `FakeRepository.recordUsageSession` adds the same `deltaSeconds` to `usage`.

### Task 5: Stats models and formatting

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/ScreenTimeStats.kt`
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/ScreenTimeTokens.kt`
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/format/StatFormat.kt`
- Test: `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/format/StatFormatTest.kt`

**Interfaces:**
- Consumes: `kotlinx.datetime.LocalDate`.
- Produces:
  - `StatsFilter.Today`, `StatsFilter.SingleDay`, `StatsFilter.DateRange`
  - `ResolvedPeriod`
  - `ScreenTimeStats`, `ScreenTimeSlice`, `ScreenTimeChartColumn`, `ScreenTimeChartSegment`, `AppUsageSummary`
  - `ComparisonDirection`
  - `ScreenTimeTokens`
  - `formatStatDuration`, `formatComparisonPercent`, `formatPeriodButton`, `formatPeriodSubtitle`, `formatPeriodSummary`, `formatCardPeriodLabel`, `formatWeekdayInitial`.

- [ ] **Step 1: Write the failing formatter tests**

Create `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/format/StatFormatTest.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.format

import com.septaalfauzan.meotime.presentation.dashboard.StatsFilter
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class StatFormatTest {

    @Test
    fun compactDurationUsesTheStatsShapes() {
        assertEquals("0 sec", formatStatDuration(0L))
        assertEquals("15m", formatStatDuration(15L * 60L))
        assertEquals("1h 12m", formatStatDuration(72L * 60L))
        assertEquals("38m", formatStatDuration(38L * 60L))
        assertEquals("2h", formatStatDuration(120L * 60L))
    }

    @Test
    fun comparisonPercentKeepsOneDecimalAndDropsTrailingZero() {
        assertEquals("+8.5%", formatComparisonPercent(8.5))
        assertEquals("-4.2%", formatComparisonPercent(-4.2))
        assertEquals("+8%", formatComparisonPercent(8.0))
        assertEquals("0%", formatComparisonPercent(0.0))
        assertEquals("—", formatComparisonPercent(null))
    }

    @Test
    fun periodLabelsFollowTheApprovedCopy() {
        val today = LocalDate(2026, 11, 5)

        assertEquals("Today", formatPeriodButton(StatsFilter.Today, today))
        assertEquals("Today", formatPeriodSubtitle(StatsFilter.Today, today))

        assertEquals("Nov 4", formatPeriodButton(StatsFilter.SingleDay(LocalDate(2026, 11, 4)), today))
        assertEquals(
            "Nov 4, 2026",
            formatPeriodSubtitle(StatsFilter.SingleDay(LocalDate(2026, 11, 4)), today),
        )

        val range = StatsFilter.DateRange(LocalDate(2026, 11, 1), LocalDate(2026, 11, 5))
        assertEquals("Nov 1 – 5", formatPeriodButton(range, today))
        assertEquals("Nov 1 – Nov 5, 2026", formatPeriodSubtitle(range, today))
        assertEquals("Nov 1 – Nov 5 · 5 days", formatPeriodSummary(range, today))
    }

    @Test
    fun crossMonthAndCrossYearRangesIncludeTheNeededEndpoints() {
        val today = LocalDate(2026, 11, 5)
        val crossMonth = StatsFilter.DateRange(LocalDate(2026, 11, 28), LocalDate(2026, 12, 3))
        val crossYear = StatsFilter.DateRange(LocalDate(2025, 12, 30), LocalDate(2026, 1, 3))

        assertEquals("Nov 28 – Dec 3", formatPeriodButton(crossMonth, today))
        assertEquals("Nov 28 – Dec 3, 2026", formatPeriodSubtitle(crossMonth, today))
        assertEquals("Dec 30, 2025 – Jan 3, 2026", formatPeriodButton(crossYear, today))
        assertEquals("Dec 30, 2025 – Jan 3, 2026", formatPeriodSubtitle(crossYear, today))
    }

    @Test
    fun cardRightLabelIsTheWeekdayForOneDayAndCountForARange() {
        val today = LocalDate(2026, 11, 5)
        assertEquals(
            "Thu, Nov 5",
            formatCardPeriodLabel(StatsFilter.SingleDay(today), ResolvedPeriod(today, today)),
        )
        assertEquals(
            "5 days",
            formatCardPeriodLabel(
                StatsFilter.DateRange(LocalDate(2026, 11, 1), today),
                ResolvedPeriod(LocalDate(2026, 11, 1), today),
            ),
        )
    }

    @Test
    fun weekdayInitialIsSingleLetter() {
        assertEquals("S", formatWeekdayInitial(LocalDate(2026, 11, 1)))
        assertEquals("M", formatWeekdayInitial(LocalDate(2026, 11, 2)))
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :shared:testAndroidHostTest --tests '*StatFormatTest*'`

Expected: compilation fails because the stats types and formatters do not exist.

- [ ] **Step 3: Create the stats model**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/ScreenTimeStats.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.dashboard

import androidx.compose.ui.graphics.Color
import kotlinx.datetime.LocalDate

sealed interface StatsFilter {
    data object Today : StatsFilter
    data class SingleDay(val date: LocalDate) : StatsFilter
    data class DateRange(val start: LocalDate, val end: LocalDate) : StatsFilter
}

data class ResolvedPeriod(val start: LocalDate, val end: LocalDate) {
    val days: Int get() = (end.toEpochDays() - start.toEpochDays() + 1L).toInt()
    val isRange: Boolean get() = start != end
}

data class AppUsageSummary(
    val totalSeconds: Long,
    val dailyAverageSeconds: Long,
)

data class ScreenTimeSlice(
    val packageName: String?,
    val label: String,
    val color: Color,
    val usageSeconds: Long,
)

data class ScreenTimeChartSegment(
    val color: Color,
    val seconds: Long,
)

data class ScreenTimeChartColumn(
    val date: LocalDate,
    val segments: List<ScreenTimeChartSegment>,
)

enum class ComparisonDirection {
    UP,
    DOWN,
    FLAT,
}

data class ScreenTimeStats(
    val filter: StatsFilter,
    val period: ResolvedPeriod,
    val eyebrow: String,
    val periodLabel: String,
    val totalSeconds: Long,
    val primarySeconds: Long,
    val comparisonLabel: String,
    val comparisonPercent: Double?,
    val comparisonDirection: ComparisonDirection,
    val slices: List<ScreenTimeSlice>,
    val columns: List<ScreenTimeChartColumn>,
    val appUsage: Map<String, AppUsageSummary>,
)
```

- [ ] **Step 4: Add chart colors**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/ScreenTimeTokens.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.dashboard

import androidx.compose.ui.graphics.Color

object ScreenTimeTokens {
    val chartFirst = Color(0xFFFF5A5F)
    val chartSecond = Color(0xFFA78BFA)
    val chartThird = Color(0xFF60A5FA)
    val chartOthers = Color(0xFF5C5C5C)

    val fellText = Color(0xFF6EE7A0)
    val fellBackground = Color(0xFF12301E)
    val roseText = Color(0xFFFF8A80)
    val roseBackground = Color(0xFF301212)

    val chartColors = listOf(chartFirst, chartSecond, chartThird)
}
```

- [ ] **Step 5: Implement formatting**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/format/StatFormat.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.format

import com.septaalfauzan.meotime.presentation.dashboard.ResolvedPeriod
import com.septaalfauzan.meotime.presentation.dashboard.StatsFilter
import kotlin.math.roundToInt
import kotlinx.datetime.LocalDate

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun formatStatDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    if (total < 60L) return "$total sec"
    val minutes = total / 60L
    val hours = minutes / 60L
    val restMinutes = minutes % 60L
    return when {
        hours == 0L -> "${minutes}m"
        restMinutes == 0L -> "${hours}h"
        else -> "${hours}h ${restMinutes}m"
    }
}

fun formatComparisonPercent(percent: Double?): String {
    if (percent == null) return "—"
    val rounded = (percent * 10.0).roundToInt() / 10.0
    val body = if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
    return if (rounded > 0.0) "+$body%" else "$body%"
}

fun formatPeriodButton(filter: StatsFilter, today: LocalDate): String = when (filter) {
    StatsFilter.Today -> "Today"
    is StatsFilter.SingleDay -> formatMonthDay(filter.date)
    is StatsFilter.DateRange -> formatRangeButton(filter.start, filter.end)
}

fun formatPeriodSubtitle(filter: StatsFilter, today: LocalDate): String = when (filter) {
    StatsFilter.Today -> "Today"
    is StatsFilter.SingleDay -> formatMonthDayYear(filter.date)
    is StatsFilter.DateRange -> formatRangeWithYear(filter.start, filter.end)
}

fun formatPeriodSummary(filter: StatsFilter, today: LocalDate): String = when (filter) {
    StatsFilter.Today -> formatWeekdayMonthDay(today)
    is StatsFilter.SingleDay -> formatWeekdayMonthDay(filter.date)
    is StatsFilter.DateRange -> {
        val start = minOf(filter.start, filter.end)
        val end = maxOf(filter.start, filter.end)
        val period = ResolvedPeriod(start, end)
        "${formatRangeLong(start, end)} · ${period.days} days"
    }
}

fun formatCardPeriodLabel(filter: StatsFilter, period: ResolvedPeriod): String = when (filter) {
    StatsFilter.Today -> formatWeekdayMonthDay(period.start)
    is StatsFilter.SingleDay -> formatWeekdayMonthDay(period.start)
    is StatsFilter.DateRange -> "${period.days} days"
}

fun formatWeekdayInitial(date: LocalDate): String =
    WEEKDAYS[date.dayOfWeek.ordinal].take(1)

private fun formatMonthDay(date: LocalDate): String =
    "${MONTHS[date.month.ordinal]} ${date.dayOfMonth}"

private fun formatMonthDayYear(date: LocalDate): String =
    "${formatMonthDay(date)}, ${date.year}"

private fun formatWeekdayMonthDay(date: LocalDate): String =
    "${WEEKDAYS[date.dayOfWeek.ordinal]}, ${formatMonthDay(date)}"

private fun formatRangeButton(start: LocalDate, end: LocalDate): String =
    when {
        start.year != end.year -> "${formatMonthDayYear(start)} – ${formatMonthDayYear(end)}"
        start.month == end.month -> "${formatMonthDay(start)} – ${end.dayOfMonth}"
        else -> "${formatMonthDay(start)} – ${formatMonthDay(end)}"
    }

private fun formatRangeWithYear(start: LocalDate, end: LocalDate): String =
    if (start.year == end.year) {
        "${formatMonthDay(start)} – ${formatMonthDay(end)}, ${start.year}"
    } else {
        "${formatMonthDayYear(start)} – ${formatMonthDayYear(end)}"
    }

private fun formatRangeLong(start: LocalDate, end: LocalDate): String =
    if (start.year == end.year) {
        "${formatMonthDay(start)} – ${formatMonthDay(end)}"
    } else {
        "${formatMonthDayYear(start)} – ${formatMonthDayYear(end)}"
    }
```

- [ ] **Step 6: Run the formatter tests**

Run: `./gradlew :shared:testAndroidHostTest --tests '*StatFormatTest*'`

Expected: PASS.

### Task 6: Build screen-time stats from stored usage

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/ScreenTimeStatsBuilder.kt`
- Test: `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/dashboard/ScreenTimeStatsBuilderTest.kt`

**Interfaces:**
- Consumes: `StatsFilter`, `ResolvedPeriod`, `ScreenTimeStats`, `DailyUsagePoint`, `HourlyUsagePoint`, `formatCardPeriodLabel`.
- Produces:
  - `internal class ScreenTimeStatsBuilder`
  - `internal fun build(filter: StatsFilter, today: LocalDate, apps: List<MonitoredApp>, current: List<DailyUsagePoint>, previous: List<DailyUsagePoint>, hourly: List<HourlyUsagePoint>): ScreenTimeStats`

- [ ] **Step 1: Write the failing builder tests**

Create `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/dashboard/ScreenTimeStatsBuilderTest.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.dashboard

import com.septaalfauzan.meotime.domain.model.DailyUsagePoint
import com.septaalfauzan.meotime.domain.model.HourlyUsagePoint
import com.septaalfauzan.meotime.domain.model.MonitoredApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

private fun app(packageName: String, name: String, limit: Int = 60) = MonitoredApp(
    id = packageName.hashCode().toLong(),
    packageName = packageName,
    appName = name,
    dailyLimitMinutes = limit,
    enabled = true,
    createdAt = 0,
    updatedAt = 0,
    todayUsageSeconds = 0,
)

class ScreenTimeStatsBuilderTest {

    private val today = LocalDate(2026, 11, 5)
    private val builder = ScreenTimeStatsBuilder()

    @Test
    fun singleDayBuildsSixColumnsTopThreeAndOthers() {
        val apps = listOf(
            app("a", "Alpha"),
            app("b", "Bravo"),
            app("c", "Charlie"),
            app("d", "Delta"),
        )
        val current = listOf(
            DailyUsagePoint("a", today, 3_600L),
            DailyUsagePoint("b", today, 1_800L),
            DailyUsagePoint("c", today, 1_200L),
            DailyUsagePoint("d", today, 600L),
        )
        val hourly = listOf(
            HourlyUsagePoint("a", today, 0, 100L),
            HourlyUsagePoint("b", today, 4, 200L),
            HourlyUsagePoint("c", today, 8, 300L),
            HourlyUsagePoint("d", today, 20, 400L),
        )

        val stats = builder.build(
            filter = StatsFilter.Today,
            today = today,
            apps = apps,
            current = current,
            previous = listOf(DailyUsagePoint("a", LocalDate(2026, 11, 4), 3_600L)),
            hourly = hourly,
        )

        assertEquals("Total today", stats.eyebrow)
        assertEquals("Thu, Nov 5", stats.periodLabel)
        assertEquals(7_200L, stats.totalSeconds)
        assertEquals(7_200L, stats.primarySeconds)
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Others"), stats.slices.map { it.label })
        assertEquals(ScreenTimeTokens.chartFirst, stats.slices[0].color)
        assertEquals(ScreenTimeTokens.chartOthers, stats.slices[3].color)
        assertEquals(600L, stats.slices[3].usageSeconds)
        assertEquals(6, stats.columns.size)
        assertEquals(listOf(100L, 0L, 0L, 0L, 0L, 0L), stats.columns[0].segments.map { it.seconds })
        assertEquals(400L, stats.columns[5].segments[3].seconds)
        assertEquals(3_600L, stats.appUsage["a"]?.totalSeconds)
    }

    @Test
    fun threeOrFewerAppsDoNotGetAnOthersSlice() {
        val apps = listOf(app("a", "Alpha"), app("b", "Bravo"))
        val stats = builder.build(
            filter = StatsFilter.Today,
            today = today,
            apps = apps,
            current = listOf(DailyUsagePoint("a", today, 60L)),
            previous = emptyList(),
            hourly = emptyList(),
        )

        assertEquals(listOf("Alpha", "Bravo"), stats.slices.map { it.label })
        assertEquals(0L, stats.slices[1].usageSeconds)
    }

    @Test
    fun rangeUsesDailyAverageAndEqualLengthPriorPeriod() {
        val apps = listOf(app("a", "Alpha"), app("b", "Bravo"))
        val start = LocalDate(2026, 11, 1)
        val end = LocalDate(2026, 11, 2)
        val current = listOf(
            DailyUsagePoint("a", start, 100L),
            DailyUsagePoint("b", start, 50L),
            DailyUsagePoint("a", end, 200L),
            DailyUsagePoint("b", end, 50L),
        )
        val previous = listOf(
            DailyUsagePoint("a", LocalDate(2026, 10, 30), 100L),
            DailyUsagePoint("b", LocalDate(2026, 10, 31), 200L),
        )

        val stats = builder.build(
            filter = StatsFilter.DateRange(start, end),
            today = today,
            apps = apps,
            current = current,
            previous = previous,
            hourly = emptyList(),
        )

        assertEquals("Daily average", stats.eyebrow)
        assertEquals("2 days", stats.periodLabel)
        assertEquals(400L, stats.totalSeconds)
        assertEquals(200L, stats.primarySeconds)
        assertEquals(2, stats.columns.size)
        assertEquals(300L, stats.columns[0].segments.sumOf { it.seconds })
        assertEquals(250L, stats.columns[1].segments.sumOf { it.seconds })
        assertEquals(100L, stats.appUsage["b"]?.dailyAverageSeconds)
        assertEquals(33.333333333333336, stats.comparisonPercent!!, absoluteTolerance = 0.0001)
    }

    @Test
    fun zeroPriorPeriodProducesNoInventedPercentage() {
        val stats = builder.build(
            filter = StatsFilter.SingleDay(LocalDate(2026, 11, 4)),
            today = today,
            apps = listOf(app("a", "Alpha")),
            current = listOf(DailyUsagePoint("a", LocalDate(2026, 11, 4), 60L)),
            previous = emptyList(),
            hourly = emptyList(),
        )

        assertEquals("vs prior day", stats.comparisonLabel)
        assertNull(stats.comparisonPercent)
        assertEquals(ComparisonDirection.FLAT, stats.comparisonDirection)
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests '*ScreenTimeStatsBuilderTest*'`

Expected: compilation fails because `ScreenTimeStatsBuilder` does not exist.

- [ ] **Step 3: Implement the builder**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/ScreenTimeStatsBuilder.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.dashboard

import com.septaalfauzan.meotime.domain.model.DailyUsagePoint
import com.septaalfauzan.meotime.domain.model.HourlyUsagePoint
import com.septaalfauzan.meotime.domain.model.MonitoredApp
import com.septaalfauzan.meotime.presentation.format.formatCardPeriodLabel
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

internal class ScreenTimeStatsBuilder {

    fun build(
        filter: StatsFilter,
        today: LocalDate,
        apps: List<MonitoredApp>,
        current: List<DailyUsagePoint>,
        previous: List<DailyUsagePoint>,
        hourly: List<HourlyUsagePoint>,
    ): ScreenTimeStats {
        val period = resolve(filter, today)
        val monitoredPackages = apps.mapTo(linkedSetOf()) { it.packageName }
        val currentRows = current.filter {
            it.packageName in monitoredPackages && it.date >= period.start && it.date <= period.end
        }
        val previousRows = previous.filter { it.packageName in monitoredPackages }
        val currentByApp = currentRows
            .groupBy { it.packageName }
            .mapValues { (_, rows) -> rows.sumOf { it.usageSeconds } }
        val total = currentByApp.values.sum()
        val previousTotal = previousRows.sumOf { it.usageSeconds }
        val primary = if (filter is StatsFilter.DateRange) total / period.days else total
        val definitions = sliceDefinitions(apps, currentByApp)

        val columns = if (filter is StatsFilter.DateRange) {
            rangeColumns(period, definitions, currentRows)
        } else {
            singleDayColumns(period.start, definitions, hourly, monitoredPackages)
        }

        val comparisonPercent = if (previousTotal <= 0L) {
            null
        } else {
            (total - previousTotal).toDouble() / previousTotal.toDouble() * 100.0
        }
        val direction = when {
            comparisonPercent == null || total == previousTotal -> ComparisonDirection.FLAT
            total > previousTotal -> ComparisonDirection.UP
            else -> ComparisonDirection.DOWN
        }

        return ScreenTimeStats(
            filter = filter,
            period = period,
            eyebrow = when (filter) {
                StatsFilter.Today -> "Total today"
                is StatsFilter.SingleDay -> "Total"
                is StatsFilter.DateRange -> "Daily average"
            },
            periodLabel = formatCardPeriodLabel(filter, period),
            totalSeconds = total,
            primarySeconds = primary,
            comparisonLabel = when (filter) {
                StatsFilter.Today -> "vs yesterday"
                is StatsFilter.SingleDay -> "vs prior day"
                is StatsFilter.DateRange -> "vs prior period"
            },
            comparisonPercent = comparisonPercent,
            comparisonDirection = direction,
            slices = definitions.map { it.slice },
            columns = columns,
            appUsage = apps.associate { app ->
                val appTotal = currentByApp[app.packageName] ?: 0L
                app.packageName to AppUsageSummary(
                    totalSeconds = appTotal,
                    dailyAverageSeconds = appTotal / period.days,
                )
            },
        )
    }

    private fun sliceDefinitions(
        apps: List<MonitoredApp>,
        currentByApp: Map<String, Long>,
    ): List<SliceDefinition> {
        val ordered = apps.sortedByDescending { currentByApp[it.packageName] ?: 0L }
        val top = if (ordered.size <= 3) ordered else ordered.take(3)
        val remainder = if (ordered.size <= 3) emptyList() else ordered.drop(3)
        val definitions = top.mapIndexed { index, app ->
            SliceDefinition(
                packageNames = setOf(app.packageName),
                slice = ScreenTimeSlice(
                    packageName = app.packageName,
                    label = app.appName,
                    color = ScreenTimeTokens.chartColors[index],
                    usageSeconds = currentByApp[app.packageName] ?: 0L,
                ),
            )
        }.toMutableList()
        if (remainder.isNotEmpty()) {
            definitions += SliceDefinition(
                packageNames = remainder.mapTo(mutableSetOf()) { it.packageName },
                slice = ScreenTimeSlice(
                    packageName = null,
                    label = "Others",
                    color = ScreenTimeTokens.chartOthers,
                    usageSeconds = remainder.sumOf { currentByApp[it.packageName] ?: 0L },
                ),
            )
        }
        return definitions
    }

    private fun singleDayColumns(
        date: LocalDate,
        definitions: List<SliceDefinition>,
        hourly: List<HourlyUsagePoint>,
        monitoredPackages: Set<String>,
    ): List<ScreenTimeChartColumn> {
        val rows = hourly.filter { it.date == date && it.packageName in monitoredPackages }
        val byPackageAndHour = rows.associate { (it.packageName to it.hour) to it.usageSeconds }
        return (0 until 6).map { bucketIndex ->
            val startHour = bucketIndex * 4
            ScreenTimeChartColumn(
                date = date,
                segments = definitions.map { definition ->
                    val seconds = definition.packageNames.sumOf { packageName ->
                        (startHour until startHour + 4).sumOf { hour ->
                            byPackageAndHour[packageName to hour] ?: 0L
                        }
                    }
                    ScreenTimeChartSegment(definition.slice.color, seconds)
                },
            )
        }
    }

    private fun rangeColumns(
        period: ResolvedPeriod,
        definitions: List<SliceDefinition>,
        currentRows: List<DailyUsagePoint>,
    ): List<ScreenTimeChartColumn> {
        val byDateAndPackage = currentRows.associate { (it.date to it.packageName) to it.usageSeconds }
        val columns = mutableListOf<ScreenTimeChartColumn>()
        var date = period.start
        while (date <= period.end) {
            columns += ScreenTimeChartColumn(
                date = date,
                segments = definitions.map { definition ->
                    val seconds = definition.packageNames.sumOf { packageName ->
                        byDateAndPackage[date to packageName] ?: 0L
                    }
                    ScreenTimeChartSegment(definition.slice.color, seconds)
                },
            )
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return columns
    }

    private fun resolve(filter: StatsFilter, today: LocalDate): ResolvedPeriod = when (filter) {
        StatsFilter.Today -> ResolvedPeriod(today, today)
        is StatsFilter.SingleDay -> ResolvedPeriod(filter.date, filter.date)
        is StatsFilter.DateRange -> if (filter.start <= filter.end) {
            ResolvedPeriod(filter.start, filter.end)
        } else {
            ResolvedPeriod(filter.end, filter.start)
        }
    }

    private data class SliceDefinition(
        val packageNames: Set<String>,
        val slice: ScreenTimeSlice,
    )
}
```

- [ ] **Step 4: Run the builder tests**

Run: `./gradlew :shared:testAndroidHostTest --tests '*ScreenTimeStatsBuilderTest*'`

Expected: PASS.

### Task 7: Date-picker state machine

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DateRangePickerModel.kt`
- Test: `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DateRangePickerModelTest.kt`

**Interfaces:**
- Consumes: `StatsFilter`, `LocalDate`.
- Produces:
  - `enum class PickerMode { SINGLE_DAY, DATE_RANGE }`
  - `enum class PickerPreset { TODAY, YESTERDAY, LAST_7_DAYS }`
  - `data class DatePickerState(...)`
  - `initialDatePickerState(selection: StatsFilter, today: LocalDate): DatePickerState`
  - `DatePickerState.selectMode`, `tapDate`, `selectPreset`, `previousMonth`, `nextMonth`, `toFilter`, `isSelectable`
  - `monthGrid(firstOfMonth: LocalDate): List<LocalDate?>`
  - `formatPickerMonth(firstOfMonth: LocalDate): String`

- [ ] **Step 1: Write the failing picker tests**

Create `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DateRangePickerModelTest.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class DateRangePickerModelTest {

    private val today = LocalDate(2026, 11, 5)

    @Test
    fun presetsReplaceTheDraftSelection() {
        val initial = initialDatePickerState(StatsFilter.Today, today)

        val yesterday = initial.selectPreset(PickerPreset.YESTERDAY, today)
        assertEquals(PickerMode.SINGLE_DAY, yesterday.mode)
        assertEquals(LocalDate(2026, 11, 4), yesterday.start)
        assertEquals(LocalDate(2026, 11, 4), yesterday.end)
        assertEquals(StatsFilter.SingleDay(LocalDate(2026, 11, 4)), yesterday.toFilter(today))

        val lastWeek = initial.selectPreset(PickerPreset.LAST_7_DAYS, today)
        assertEquals(PickerMode.DATE_RANGE, lastWeek.mode)
        assertEquals(LocalDate(2026, 10, 30), lastWeek.start)
        assertEquals(today, lastWeek.end)
        assertEquals(StatsFilter.DateRange(LocalDate(2026, 10, 30), today), lastWeek.toFilter(today))
    }

    @Test
    fun singleModeTapSelectsOneDateAndTodayStaysRelative() {
        val state = initialDatePickerState(StatsFilter.SingleDay(LocalDate(2026, 11, 2)), today)
            .tapDate(today, today)

        assertEquals(PickerMode.SINGLE_DAY, state.mode)
        assertEquals(today, state.start)
        assertEquals(today, state.end)
        assertEquals(StatsFilter.Today, state.toFilter(today))
    }

    @Test
    fun rangeModeSecondTapCompletesAndSwapsReversedEndpoints() {
        val anchored = initialDatePickerState(StatsFilter.Today, today)
            .selectMode(PickerMode.DATE_RANGE, today)
            .tapDate(LocalDate(2026, 11, 4), today)

        assertEquals(LocalDate(2026, 11, 4), anchored.anchor)
        assertEquals(LocalDate(2026, 11, 4), anchored.start)
        assertEquals(LocalDate(2026, 11, 4), anchored.end)

        val completed = anchored.tapDate(LocalDate(2026, 11, 1), today)
        assertNull(completed.anchor)
        assertEquals(LocalDate(2026, 11, 1), completed.start)
        assertEquals(LocalDate(2026, 11, 4), completed.end)
    }

    @Test
    fun futureDatesAreNotSelectable() {
        val state = initialDatePickerState(StatsFilter.Today, today)

        assertFalse(state.isSelectable(LocalDate(2026, 11, 6), today))
        assertTrue(state.isSelectable(today, today))
        assertEquals(state, state.tapDate(LocalDate(2026, 11, 6), today))
    }

    @Test
    fun switchingToSingleModeKeepsTheRangeStart() {
        val range = initialDatePickerState(
            StatsFilter.DateRange(LocalDate(2026, 11, 1), LocalDate(2026, 11, 3)),
            today,
        ).selectMode(PickerMode.SINGLE_DAY, today)

        assertEquals(LocalDate(2026, 11, 1), range.start)
        assertEquals(LocalDate(2026, 11, 1), range.end)
        assertNull(range.anchor)
    }

    @Test
    fun monthNavigationCannotPassTheCurrentMonth() {
        val thisMonth = initialDatePickerState(StatsFilter.Today, today)

        assertEquals(thisMonth, thisMonth.nextMonth(today))
        assertEquals(LocalDate(2026, 10, 1), thisMonth.previousMonth(today).visibleMonth)
        assertEquals(
            LocalDate(2026, 10, 1),
            thisMonth.previousMonth(today).nextMonth(today).visibleMonth,
        )
    }

    @Test
    fun caldendarIsSundayFirstAndOnlyContainsRealDays() {
        val grid = monthGrid(LocalDate(2026, 11, 1))

        assertEquals(35, grid.size)
        assertEquals(LocalDate(2026, 11, 1), grid[0])
        assertEquals(LocalDate(2026, 11, 30), grid[29])
        assertNull(grid[30])
        assertNull(grid[34])
    }

    @Test
    fun pickerMonthLabelIncludesTheYear() {
        assertEquals("November 2026", formatPickerMonth(LocalDate(2026, 11, 1)))
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests '*DateRangePickerModelTest*'`

Expected: compilation fails because the picker model does not exist.

- [ ] **Step 3: Implement the picker model**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DateRangePickerModel.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.dashboard

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

enum class PickerMode {
    SINGLE_DAY,
    DATE_RANGE,
}

enum class PickerPreset {
    TODAY,
    YESTERDAY,
    LAST_7_DAYS,
}

data class DatePickerState(
    val mode: PickerMode,
    val start: LocalDate,
    val end: LocalDate,
    val anchor: LocalDate?,
    val visibleMonth: LocalDate,
)

fun initialDatePickerState(selection: StatsFilter, today: LocalDate): DatePickerState =
    when (selection) {
        StatsFilter.Today -> DatePickerState(
            mode = PickerMode.SINGLE_DAY,
            start = today,
            end = today,
            anchor = null,
            visibleMonth = firstOfMonth(today),
        )
        is StatsFilter.SingleDay -> DatePickerState(
            mode = PickerMode.SINGLE_DAY,
            start = selection.date,
            end = selection.date,
            anchor = null,
            visibleMonth = firstOfMonth(selection.date),
        )
        is StatsFilter.DateRange -> DatePickerState(
            mode = PickerMode.DATE_RANGE,
            start = minOf(selection.start, selection.end),
            end = maxOf(selection.start, selection.end),
            anchor = null,
            visibleMonth = firstOfMonth(selection.end),
        )
    }

fun DatePickerState.selectMode(mode: PickerMode, today: LocalDate): DatePickerState = when (mode) {
    PickerMode.SINGLE_DAY -> copy(
        mode = mode,
        start = start,
        end = start,
        anchor = null,
        visibleMonth = firstOfMonth(start),
    )
    PickerMode.DATE_RANGE -> copy(
        mode = mode,
        anchor = null,
        visibleMonth = firstOfMonth(end),
    )
}

fun DatePickerState.tapDate(date: LocalDate, today: LocalDate): DatePickerState {
    if (!isSelectable(date, today)) return this
    return when (mode) {
        PickerMode.SINGLE_DAY -> copy(
            start = date,
            end = date,
            anchor = null,
            visibleMonth = firstOfMonth(date),
        )
        PickerMode.DATE_RANGE -> {
            val anchorDate = anchor
            if (anchorDate == null) {
                copy(
                    start = date,
                    end = date,
                    anchor = date,
                    visibleMonth = firstOfMonth(date),
                )
            } else {
                copy(
                    start = minOf(anchorDate, date),
                    end = maxOf(anchorDate, date),
                    anchor = null,
                    visibleMonth = firstOfMonth(date),
                )
            }
        }
    }
}

fun DatePickerState.selectPreset(preset: PickerPreset, today: LocalDate): DatePickerState = when (preset) {
    PickerPreset.TODAY -> DatePickerState(
        mode = PickerMode.SINGLE_DAY,
        start = today,
        end = today,
        anchor = null,
        visibleMonth = firstOfMonth(today),
    )
    PickerPreset.YESTERDAY -> {
        val yesterday = today.plus(-1, DateTimeUnit.DAY)
        DatePickerState(
            mode = PickerMode.SINGLE_DAY,
            start = yesterday,
            end = yesterday,
            anchor = null,
            visibleMonth = firstOfMonth(yesterday),
        )
    }
    PickerPreset.LAST_7_DAYS -> DatePickerState(
        mode = PickerMode.DATE_RANGE,
        start = today.plus(-6, DateTimeUnit.DAY),
        end = today,
        anchor = null,
        visibleMonth = firstOfMonth(today),
    )
}

fun DatePickerState.previousMonth(today: LocalDate): DatePickerState =
    copy(visibleMonth = visibleMonth.plus(-1, DateTimeUnit.MONTH))

fun DatePickerState.nextMonth(today: LocalDate): DatePickerState {
    val next = visibleMonth.plus(1, DateTimeUnit.MONTH)
    return if (next > firstOfMonth(today)) this else copy(visibleMonth = next)
}

fun DatePickerState.toFilter(today: LocalDate): StatsFilter =
    if (mode == PickerMode.SINGLE_DAY) {
        if (start == today) StatsFilter.Today else StatsFilter.SingleDay(start)
    } else {
        StatsFilter.DateRange(start, end)
    }

fun DatePickerState.isSelectable(date: LocalDate, today: LocalDate): Boolean = date <= today

fun monthGrid(firstOfMonth: LocalDate): List<LocalDate?> {
    val leading = (firstOfMonth.dayOfWeek.ordinal + 1) % 7
    val nextMonth = firstOfMonth.plus(1, DateTimeUnit.MONTH)
    val daysInMonth = nextMonth.plus(-1, DateTimeUnit.DAY).dayOfMonth
    val cells = mutableListOf<LocalDate?>()
    repeat(leading) { cells += null }
    for (day in 1..daysInMonth) {
        cells += LocalDate(firstOfMonth.year, firstOfMonth.month, day)
    }
    while (cells.size % 7 != 0) cells += null
    return cells
}

fun formatPickerMonth(firstOfMonth: LocalDate): String {
    val months = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    return "${months[firstOfMonth.month.ordinal]} ${firstOfMonth.year}"
}

private fun firstOfMonth(date: LocalDate): LocalDate = LocalDate(date.year, date.month, 1)
```

- [ ] **Step 4: Run the picker tests**

Run: `./gradlew :shared:testAndroidHostTest --tests '*DateRangePickerModelTest*'`

Expected: PASS.

### Task 8: Dashboard stats state and ViewModel wiring

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/domain/useCase/MeotimeUseCases.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DashboardViewModel.kt`
- Test: `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DashboardViewModelTest.kt`

**Interfaces:**
- Consumes: `MeotimeUseCases.observeDailyUsage`, `observeHourlyUsage`, `ScreenTimeStatsBuilder`.
- Produces:
  - `MeotimeUseCases.observeToday(): Flow<LocalDate>`
  - `DashboardUiState.statsFilter`, `stats`, `statsLoading`, `statsError`
  - `DashboardViewModel.applyFilter(filter: StatsFilter)`
  - `DashboardViewModel.refreshStats()`.

- [ ] **Step 1: Add the failing ViewModel tests**

In `DashboardViewModelTest.kt`, update the `newViewModel` helper to accept a today key:

```kotlin
private fun newViewModel(
    repository: ScreenTimeRepository = this.repository,
    iconProvider: AppIconProvider = AppIconProvider { null },
    todayKey: TodayKey = today,
    installed: (String) -> Boolean = { true },
) = DashboardViewModel(
    useCases = MeotimeUseCases(repository, todayKey),
    appIconProvider = iconProvider,
    installedAppCheck = InstalledAppCheck { installed(it) },
)
```

Add imports:

```kotlin
import com.septaalfauzan.meotime.data.local.TodayKey
import com.septaalfauzan.meotime.presentation.dashboard.StatsFilter
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
```

Add a mutable today key and failing-stats repository at file scope:

```kotlin
private class MutableTodayKey(initial: String) : TodayKey {
    val date = MutableStateFlow(initial)
    override fun todayIso(): String = date.value
    override fun dateFlow(): Flow<String> = date
}

private class FailingStatsRepository(delegate: ScreenTimeRepository) : ScreenTimeRepository by delegate {
    override fun observeDailyUsage(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyUsagePoint>> =
        flow { throw IllegalStateException("stats unavailable") }
}
```

Add import:

```kotlin
import com.septaalfauzan.meotime.domain.model.DailyUsagePoint
```

Add tests:

```kotlin
@Test
fun buildsStatsForTheAppliedRangeFilter() = runTest {
    val start = LocalDate(2026, 11, 1)
    val end = LocalDate(2026, 11, 2)
    repository.addMonitoredApp("com.instagram.android", "Instagram", 30)
    repository.dailyUsage.value = mapOf(
        start to mapOf("com.instagram.android" to 100L),
        end to mapOf("com.instagram.android" to 200L),
    )
    val viewModel = newViewModel()

    viewModel.applyFilter(StatsFilter.DateRange(start, end))

    val state = viewModel.state.value
    assertEquals(StatsFilter.DateRange(start, end), state.statsFilter)
    assertEquals(300L, state.stats?.totalSeconds)
    assertEquals(150L, state.stats?.primarySeconds)
    assertFalse(state.statsLoading)
    assertFalse(state.statsError)
}

@Test
fun statsFailureDoesNotTakeDownTheAppList() = runTest {
    repository.addMonitoredApp("com.instagram.android", "Instagram", 30)
    val viewModel = newViewModel(FailingStatsRepository(repository))

    val state = viewModel.state.value
    assertEquals(1, state.apps.size)
    assertTrue(state.statsError)
    assertFalse(state.statsLoading)
}

@Test
fun todayFilterFollowsMidnightRollover() = runTest {
    val todayKey = MutableTodayKey("2026-11-05")
    repository.addMonitoredApp("com.instagram.android", "Instagram", 30)
    repository.dailyUsage.value = mapOf(
        LocalDate(2026, 11, 5) to mapOf("com.instagram.android" to 100L),
    )
    val viewModel = newViewModel(todayKey = todayKey)

    assertEquals(100L, viewModel.state.value.stats?.totalSeconds)

    todayKey.date.value = "2026-11-06"

    assertEquals(0L, viewModel.state.value.stats?.totalSeconds)
    assertEquals(LocalDate(2026, 11, 6), viewModel.state.value.today)
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests '*DashboardViewModelTest*'`

Expected: compilation fails because the stats state and methods do not exist.

- [ ] **Step 3: Expose today's date through use cases**

Add to `MeotimeUseCases.kt`:

```kotlin
fun observeToday(): Flow<LocalDate> =
    todayKey.dateFlow().map { LocalDate.parse(it) }
```

`LocalDate` is already imported for the usage reads.

- [ ] **Step 4: Add stats state to the ViewModel**

In `DashboardViewModel.kt`, add imports:

```kotlin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import com.septaalfauzan.meotime.domain.model.HourlyUsagePoint
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
```

Extend `DashboardUiState`:

```kotlin
data class DashboardUiState(
    val apps: List<MonitoredApp> = emptyList(),
    val appIcons: Map<String, ImageBitmap> = emptyMap(),
    val loading: Boolean = true,
    val error: Boolean = false,
    val statsFilter: StatsFilter = StatsFilter.Today,
    val stats: ScreenTimeStats? = null,
    val statsLoading: Boolean = true,
    val statsError: Boolean = false,
    val today: LocalDate? = null,
)
```

Add fields and methods to `DashboardViewModel`:

```kotlin
private val statsBuilder = ScreenTimeStatsBuilder()
private var statsJob: Job? = null

    observe()
    observeStats()
    viewModelScope.launch {
        useCases.observeToday().collect { date ->
            _state.update { it.copy(today = date) }
        }
    }
}

fun applyFilter(filter: StatsFilter) {
    _state.update {
        it.copy(
            statsFilter = filter,
            stats = null,
            statsLoading = true,
            statsError = false,
        )
    }
    observeStats()
}

fun refreshStats() {
    _state.update { it.copy(statsLoading = true, statsError = false) }
    observeStats()
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun observeStats() {
    statsJob?.cancel()
    val filter = _state.value.statsFilter
    statsJob = viewModelScope.launch {
        val periodFlow = when (filter) {
            StatsFilter.Today -> useCases.observeToday().map { ResolvedPeriod(it, it) }
            is StatsFilter.SingleDay -> flowOf(ResolvedPeriod(filter.date, filter.date))
            is StatsFilter.DateRange -> flowOf(
                if (filter.start <= filter.end) {
                    ResolvedPeriod(filter.start, filter.end)
                } else {
                    ResolvedPeriod(filter.end, filter.start)
                },
            )
        }

        periodFlow
            .flatMapLatest { period ->
                val previous = previousPeriod(period)
                combine(
                    useCases.observeMonitoredApps(),
                    useCases.observeDailyUsage(period.start, period.end),
                    useCases.observeDailyUsage(previous.start, previous.end),
                    if (filter is StatsFilter.DateRange) {
                        flowOf(emptyList<HourlyUsagePoint>())
                    } else {
                        useCases.observeHourlyUsage(period.start)
                    },
                ) { apps, current, prior, hourly ->
                    statsBuilder.build(
                        filter = filter,
                        today = period.start,
                        apps = apps,
                        current = current,
                        previous = prior,
                        hourly = hourly,
                    )
                }
            }
            .catch {
                _state.update { it.copy(statsLoading = false, statsError = true) }
            }
            .collect { stats ->
                _state.update {
                    it.copy(stats = stats, statsLoading = false, statsError = false)
                }
            }
    }
}

private fun previousPeriod(period: ResolvedPeriod): ResolvedPeriod {
    val end = period.start.plus(-1, DateTimeUnit.DAY)
    val start = period.start.plus(-period.days, DateTimeUnit.DAY)
    return ResolvedPeriod(start, end)
}
```

Keep `init` as one block; do not add a second `init`.

- [ ] **Step 5: Run the ViewModel tests**

Run: `./gradlew :shared:testAndroidHostTest --tests '*DashboardViewModelTest*'`

Expected: PASS.

### Task 9: Stats card UI

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/ScreenTimeStatsCard.kt`

**Interfaces:**
- Consumes: `ScreenTimeStats`, `StatsFilter`, formatters, `ScreenTimeTokens`, `MeotimeTokens`.
- Produces: `internal fun ScreenTimeStatsCard(stats, loading, error, today, onRetry, modifier)`.

- [ ] **Step 1: Create the stats card**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/ScreenTimeStatsCard.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.septaalfauzan.meotime.presentation.format.formatComparisonPercent
import com.septaalfauzan.meotime.presentation.format.formatPeriodSubtitle
import com.septaalfauzan.meotime.presentation.format.formatStatDuration
import com.septaalfauzan.meotime.presentation.theme.MeotimeOutlinedButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens
import kotlinx.datetime.LocalDate

private val ChartHeight = 96.dp
private val RangeBarWidth = 24.dp
private val BarCornerRadius = 4.dp
private val BarGap = 2.dp
private val SingleDayLabels = listOf("12a", "4a", "8a", "12p", "4p", "8p")

@Composable
internal fun ScreenTimeStatsCard(
    stats: ScreenTimeStats?,
    loading: Boolean,
    error: Boolean,
    today: LocalDate?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MeotimeTokens.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(MeotimeTokens.controls.borderWidth, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SCREEN TIME", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    stats?.periodLabel ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                loading -> StatsCardSkeleton()
                error || stats == null -> StatsCardError(onRetry)
                else -> StatsCardContent(stats, today ?: stats.period.start)
            }
        }
    }
}

@Composable
private fun StatsCardContent(stats: ScreenTimeStats, today: LocalDate) {
    val spacing = MeotimeTokens.spacing
    Text(stats.eyebrow, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(formatStatDuration(stats.primarySeconds), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.weight(1f))
        ComparisonPill(stats)
    }
    Text(
        "${stats.comparisonLabel} · ${formatPeriodSubtitle(stats.filter, today)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    UsageBarChart(stats)
    if (stats.totalSeconds == 0L) {
        Text(
            "No usage recorded in this period",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    SliceLegend(stats.slices)
    Spacer(Modifier.height(spacing.xxs))
}
```

- [ ] **Step 2: Add the chart, comparison pill, legend, and loading/error states**

Append to the same file:

```kotlin
@Composable
private fun ComparisonPill(stats: ScreenTimeStats) {
    val background = when (stats.comparisonDirection) {
        ComparisonDirection.DOWN -> ScreenTimeTokens.fellBackground
        ComparisonDirection.UP -> ScreenTimeTokens.roseBackground
        ComparisonDirection.FLAT -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when (stats.comparisonDirection) {
        ComparisonDirection.DOWN -> ScreenTimeTokens.fellText
        ComparisonDirection.UP -> ScreenTimeTokens.roseText
        ComparisonDirection.FLAT -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = background, contentColor = foreground, shape = CircleShape) {
        Text(
            formatComparisonPercent(stats.comparisonPercent),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = MeotimeTokens.spacing.xs, vertical = MeotimeTokens.spacing.xxs),
        )
    }
}

@Composable
private fun UsageBarChart(stats: ScreenTimeStats) {
    val columns = stats.columns
    if (columns.isEmpty()) return
    val maxTotal = columns.maxOfOrNull { column -> column.segments.sumOf { it.seconds } } ?: 0L
    val horizontalScroll = stats.filter is StatsFilter.DateRange
    val chartModifier = Modifier
        .fillMaxWidth()
        .height(ChartHeight + 24.dp)
        .clearAndSetSemantics { }

    if (horizontalScroll) {
        LazyRow(
            modifier = chartModifier,
            horizontalArrangement = Arrangement.spacedBy(BarGap),
        ) {
            items(columns, key = { it.date.toEpochDays() }) { column ->
                BarColumn(
                    column = column,
                    maxTotal = maxTotal,
                    label = column.date.dayOfMonth.toString(),
                    modifier = Modifier.width(RangeBarWidth),
                )
            }
        }
    } else {
        Row(
            modifier = chartModifier,
            horizontalArrangement = Arrangement.spacedBy(MeotimeTokens.spacing.xs),
        ) {
            columns.forEachIndexed { index, column ->
                BarColumn(
                    column = column,
                    maxTotal = maxTotal,
                    label = SingleDayLabels.getOrElse(index) { column.date.dayOfMonth.toString() },
                    modifier = Modifier.width(RangeBarWidth),
                )
            }
        }
    }
}

@Composable
private fun BarColumn(
    column: ScreenTimeChartColumn,
    maxTotal: Long,
    label: String,
    modifier: Modifier = Modifier,
) {
    val total = column.segments.sumOf { it.seconds }
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChartHeight),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (total <= 0L || maxTotal <= 0L) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(total.toFloat() / maxTotal.toFloat()),
                    verticalArrangement = Arrangement.spacedBy(BarGap, Alignment.Bottom),
                ) {
                    column.segments.forEach { segment ->
                        if (segment.seconds > 0L) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(segment.seconds.toFloat())
                                    .background(segment.color, androidx.compose.foundation.shape.RoundedCornerShape(BarCornerRadius)),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(MeotimeTokens.spacing.xxs))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SliceLegend(slices: List<ScreenTimeSlice>) {
    Column(verticalArrangement = Arrangement.spacedBy(MeotimeTokens.spacing.xs)) {
        slices.forEach { slice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(slice.color, CircleShape))
                Spacer(Modifier.width(MeotimeTokens.spacing.sm))
                Text(slice.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(formatStatDuration(slice.usageSeconds), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatsCardSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(MeotimeTokens.spacing.sm)) {
        Box(Modifier.fillMaxWidth(0.35f).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant))
        Box(Modifier.fillMaxWidth(0.55f).height(32.dp).background(MaterialTheme.colorScheme.surfaceVariant))
        Box(Modifier.fillMaxWidth().height(ChartHeight).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
private fun StatsCardError(onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MeotimeTokens.spacing.xs)) {
        Text("Couldn't load screen time", style = MaterialTheme.typography.titleSmall)
        Text(
            "Your usage is safe. Try reading it again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MeotimeOutlinedButton(onClick = onRetry) {
            Text("Try again")
        }
    }
}

```

The card has no date control of its own: the header pill in Task 11 owns selection, and the card shows the applied period label on the right.

- [ ] **Step 3: Compile the shared module**

Run: `./gradlew :shared:testAndroidHostTest`

Expected: BUILD SUCCESSFUL. If this task is run before Task 11, no screen references the card yet.

### Task 10: Calendar and date-range bottom sheet

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DateRangePickerSheet.kt`

**Interfaces:**
- Consumes: picker model from Task 7, `formatPeriodSummary`, `MeotimeButton`, `MeotimeOutlinedButton`, `MeotimeTextButton`.
- Produces: `@Composable internal fun DateRangePickerSheet(initialFilter, today, onDismissRequest, onApply)`.

- [ ] **Step 1: Create the sheet**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DateRangePickerSheet.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.septaalfauzan.meotime.presentation.format.formatPeriodSummary
import com.septaalfauzan.meotime.presentation.theme.MeotimeButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeOutlinedButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTextButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens
import kotlinx.datetime.LocalDate

private val WeekdayHeaders = listOf("S", "M", "T", "W", "T", "F", "S")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangePickerSheet(
    initialFilter: StatsFilter,
    today: LocalDate,
    onDismissRequest: () -> Unit,
    onApply: (StatsFilter) -> Unit,
) {
    var draft by remember(initialFilter, today) {
        mutableStateOf(initialDatePickerState(initialFilter, today))
    }
    val spacing = MeotimeTokens.spacing

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screen)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text("Select dates", style = MaterialTheme.typography.titleLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                if (draft.mode == PickerMode.SINGLE_DAY) {
                    MeotimeButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Single day") }
                } else {
                    MeotimeOutlinedButton(
                        onClick = { draft = draft.selectMode(PickerMode.SINGLE_DAY, today) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Single day") }
                }
                if (draft.mode == PickerMode.DATE_RANGE) {
                    MeotimeButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Date range") }
                } else {
                    MeotimeOutlinedButton(
                        onClick = { draft = draft.selectMode(PickerMode.DATE_RANGE, today) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Date range") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                PresetButton("Today", Modifier.weight(1f)) {
                    draft = draft.selectPreset(PickerPreset.TODAY, today)
                }
                PresetButton("Yesterday", Modifier.weight(1f)) {
                    draft = draft.selectPreset(PickerPreset.YESTERDAY, today)
                }
                PresetButton("Last 7 days", Modifier.weight(1f)) {
                    draft = draft.selectPreset(PickerPreset.LAST_7_DAYS, today)
                }
            }

            MonthHeader(
                draft = draft,
                today = today,
                onPrevious = { draft = draft.previousMonth(today) },
                onNext = { draft = draft.nextMonth(today) },
            )
            WeekdayHeader()
            CalendarGrid(
                draft = draft,
                today = today,
                onDateClick = { date -> draft = draft.tapDate(date, today) },
            )
            Text(
                formatPeriodSummary(draft.toFilter(today), today),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                MeotimeOutlinedButton(onClick = onDismissRequest, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                MeotimeButton(
                    onClick = { onApply(draft.toFilter(today)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Apply")
                }
            }
        }
    }
}

@Composable
private fun PresetButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    MeotimeOutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
```

- [ ] **Step 2: Add the month header, weekday header, calendar grid, and day cell**

Append to the same file:

```kotlin
@Composable
private fun MonthHeader(
    draft: DatePickerState,
    today: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val canGoNext = draft.nextMonth(today) != draft
    Row(verticalAlignment = Alignment.CenterVertically) {
        MeotimeTextButton(onClick = onPrevious) { Text("Previous") }
        Text(
            formatPickerMonth(draft.visibleMonth),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        MeotimeTextButton(onClick = onNext, enabled = canGoNext) { Text("Next") }
    }
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth()) {
        WeekdayHeaders.forEach { label ->
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CalendarGrid(
    draft: DatePickerState,
    today: LocalDate,
    onDateClick: (LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        monthGrid(draft.visibleMonth).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f).heightIn(min = 48.dp))
                    } else {
                        DayCell(
                            date = date,
                            draft = draft,
                            today = today,
                            onClick = { onDateClick(date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    draft: DatePickerState,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectable = draft.isSelectable(date, today)
    val endpoint = date == draft.start || date == draft.end
    val inSelection = date >= draft.start && date <= draft.end
    val background = when {
        endpoint -> MaterialTheme.colorScheme.primary
        inSelection -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val foreground = when {
        endpoint -> MaterialTheme.colorScheme.onPrimary
        inSelection -> MaterialTheme.colorScheme.onPrimaryContainer
        selectable -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        enabled = selectable,
        modifier = modifier
            .heightIn(min = 48.dp)
            .padding(2.dp)
            .alpha(if (selectable) 1f else 0.4f),
        shape = CircleShape,
        color = background,
        contentColor = foreground,
    ) {
        Box(Modifier, contentAlignment = Alignment.Center) {
            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
```

The month header's Previous button has no lower bound: usage history is never pruned. Next is disabled once the visible month is the current month, so a future date cannot be selected.

- [ ] **Step 3: Compile the shared module**

Run: `./gradlew :shared:testAndroidHostTest`

Expected: BUILD SUCCESSFUL. The sheet is not yet opened by the screen.

### Task 11: Wire the header, stats card, picker, and range row copy into Home

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DashboardScreen.kt`
- Test: `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DashboardRangeRowTest.kt`

**Interfaces:**
- Consumes: `ScreenTimeStatsCard`, `DateRangePickerSheet`, `formatPeriodButton`, `formatPeriodSubtitle`, `formatStatDuration`.
- Produces:
  - `internal data class DashboardRangeRow(val progress: Float, val status: String)`
  - `internal fun dashboardRangeRow(app: MonitoredApp, usage: AppUsageSummary?): DashboardRangeRow`

- [ ] **Step 1: Add range-row tests**

Create `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DashboardRangeRowTest.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.dashboard

import com.septaalfauzan.meotime.domain.model.MonitoredApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun monitoredApp(limitMinutes: Int = 30, enabled: Boolean = true) = MonitoredApp(
    id = 1,
    packageName = "com.instagram.android",
    appName = "Instagram",
    dailyLimitMinutes = limitMinutes,
    enabled = enabled,
    createdAt = 0,
    updatedAt = 0,
)

class DashboardRangeRowTest {

    @Test
    fun averageAgainstTheLimitDrivesTheBarAndCopy() {
        val row = dashboardRangeRow(
            monitoredApp(limitMinutes = 30),
            AppUsageSummary(totalSeconds = 6_000L, dailyAverageSeconds = 1_200L),
        )

        assertEquals(1_200f / 1_800f, row.progress, absoluteTolerance = 0.0001f)
        assertEquals("20m per day on average · limit 30 min", row.status)
    }

    @Test
    fun pausedAppKeepsAnExplicitMarkerAndAnEmptyBar() {
        val row = dashboardRangeRow(
            monitoredApp(limitMinutes = 30, enabled = false),
            AppUsageSummary(totalSeconds = 6_000L, dailyAverageSeconds = 1_200L),
        )

        assertEquals(0f, row.progress)
        assertTrue(row.status.startsWith("Monitoring paused · "))
        assertTrue(row.status.endsWith("per day on average · limit 30 min"))
    }

    @Test
    fun nonPositiveLimitDoesNotProduceNaN() {
        val row = dashboardRangeRow(
            monitoredApp(limitMinutes = 0),
            AppUsageSummary(totalSeconds = 100L, dailyAverageSeconds = 100L),
        )

        assertEquals(1f, row.progress)
        assertFalse(row.progress.isNaN())
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests '*DashboardRangeRowTest*'`

Expected: compilation fails because `DashboardRangeRow` does not exist.

- [ ] **Step 3: Replace the DashboardScreen header and body**

In `DashboardScreen.kt`, add imports:

```kotlin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import com.septaalfauzan.meotime.presentation.format.formatPeriodButton
import com.septaalfauzan.meotime.presentation.format.formatPeriodSubtitle
import com.septaalfauzan.meotime.presentation.format.formatStatDuration
```

Replace `DashboardScreen` with:

```kotlin
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onAddApp: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val spacing = MeotimeTokens.spacing
    val anchor = state.today ?: state.stats?.period?.start
    var showPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.screen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Meotime",
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 34.sp),
                )
                Text(
                    anchor?.let { formatPeriodSubtitle(state.statsFilter, it) } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            DatePill(
                label = anchor?.let { formatPeriodButton(state.statsFilter, it) } ?: "Dates",
                enabled = anchor != null,
                onClick = {
                    if (anchor != null) showPicker = true
                },
            )
        }
        Spacer(Modifier.height(spacing.md))

        ScreenTimeStatsCard(
            stats = state.stats,
            loading = state.statsLoading,
            error = state.statsError,
            today = anchor,
            onRetry = viewModel::refreshStats,
        )
        Spacer(Modifier.height(spacing.md))

        when {
            state.loading -> DashboardSkeleton(modifier = Modifier.weight(1f))
            state.error -> DashboardError(onRetry = viewModel::refresh)
            state.apps.isEmpty() -> DashboardEmpty(onAddApp = onAddApp)
            else -> MonitoredAppList(
                apps = state.apps,
                icons = state.appIcons,
                statsFilter = state.statsFilter,
                appUsage = state.stats?.appUsage.orEmpty(),
                onOpenApp = onOpenApp,
                modifier = Modifier.weight(1f),
            )
        }

        if (!state.loading && !state.error && state.apps.isNotEmpty()) {
            Spacer(Modifier.height(spacing.md))
            MeotimeButton(onClick = onAddApp, modifier = Modifier.fillMaxWidth()) {
                Text("Add app")
            }
        }
    }

    if (showPicker && anchor != null) {
        DateRangePickerSheet(
            initialFilter = state.statsFilter,
            today = anchor,
            onDismissRequest = { showPicker = false },
            onApply = { filter ->
                showPicker = false
                viewModel.applyFilter(filter)
            },
        )
    }
}
```

Add the date pill and calendar glyph below `DashboardScreen`:

```kotlin
@Composable
private fun DatePill(label: String, enabled: Boolean, onClick: () -> Unit) {
    MeotimeOutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        CalendarGlyph()
        Spacer(Modifier.width(MeotimeTokens.spacing.xs))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun CalendarGlyph() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(14.dp)) {
        val stroke = 1.5.dp.toPx()
        val radius = CornerRadius(2.dp.toPx())
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, size.height * 0.1f),
            size = Size(size.width, size.height * 0.9f),
            cornerRadius = radius,
            style = Stroke(stroke),
        )
        drawLine(
            color = color,
            start = Offset(0f, size.height * 0.35f),
            end = Offset(size.width, size.height * 0.35f),
            strokeWidth = stroke,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.3f, 0f),
            end = Offset(size.width * 0.3f, size.height * 0.22f),
            strokeWidth = stroke,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.7f, 0f),
            end = Offset(size.width * 0.7f, size.height * 0.22f),
            strokeWidth = stroke,
        )
    }
}
```

- [ ] **Step 4: Pass the filter and per-app usage into the row list**

Replace `MonitoredAppList` with:

```kotlin
@Composable
private fun MonitoredAppList(
    apps: List<MonitoredApp>,
    icons: Map<String, androidx.compose.ui.graphics.ImageBitmap>,
    statsFilter: StatsFilter,
    appUsage: Map<String, AppUsageSummary>,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MeotimeTokens.spacing.sm),
    ) {
        items(apps, key = { it.packageName }) { app ->
            MonitoredAppRow(
                app = app,
                appIcon = icons[app.packageName],
                statsFilter = statsFilter,
                usage = appUsage[app.packageName],
                onClick = { onOpenApp(app.packageName) },
            )
        }
    }
}
```

Change the `MonitoredAppRow` signature and its two data lines:

```kotlin
@Composable
private fun MonitoredAppRow(
    app: MonitoredApp,
    appIcon: ImageBitmap?,
    statsFilter: StatsFilter,
    usage: AppUsageSummary?,
    onClick: () -> Unit,
) {
    val row = dashboardRow(app)
    val range = statsFilter is StatsFilter.DateRange
    val rangeRow = if (range) dashboardRangeRow(app, usage) else null
    val spacing = MeotimeTokens.spacing

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MeotimeTokens.controls.minRowHeight),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (appIcon != null) {
                    Image(appIcon, contentDescription = null, modifier = Modifier.size(24.dp))
                } else {
                    Spacer(Modifier.size(24.dp))
                }
                Spacer(Modifier.width(spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.appName, style = MaterialTheme.typography.titleMedium)
                    if (!app.installed) {
                        Text(
                            "No longer installed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    if (range) {
                        "${formatStatDuration(usage?.totalSeconds ?: 0L)} total"
                    } else {
                        "${formatUsage(app.todayUsageSeconds)} / ${app.dailyLimitMinutes} min"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { rangeRow?.progress ?: row.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
            )
            Text(
                rangeRow?.status ?: appStatusLabel(row),
                style = if (!range && row.limitReached) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = if (!range && row.limitReached) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
```

Add the pure range-row helper at the bottom of the file:

```kotlin
internal data class DashboardRangeRow(
    val progress: Float,
    val status: String,
)

internal fun dashboardRangeRow(app: MonitoredApp, usage: AppUsageSummary?): DashboardRangeRow {
    val average = usage?.dailyAverageSeconds ?: 0L
    val limitSeconds = app.dailyLimitMinutes * 60L
    val progress = when {
        !app.enabled -> 0f
        limitSeconds <= 0L -> 1f
        else -> (average.toFloat() / limitSeconds.toFloat()).coerceIn(0f, 1f)
    }
    val copy = "${formatStatDuration(average)} per day on average · limit ${app.dailyLimitMinutes} min"
    return DashboardRangeRow(
        progress = progress,
        status = if (app.enabled) copy else "Monitoring paused · $copy",
    )
}
```

- [ ] **Step 5: Run the range-row tests and the shared suite**

Run: `./gradlew :shared:testAndroidHostTest`

Expected: PASS, including `DashboardRangeRowTest` and the existing dashboard tests.

- [ ] **Step 6: Compile the Android app**

Run: `./gradlew :androidApp:assembleDebug`

Expected: BUILD SUCCESSFUL.

### Task 12: Stop pruning history and run final verification

**Files:**
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/meotime/MeotimeApplication.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: startup no longer deletes any usage rows.

- [ ] **Step 1: Remove the cold-start prune**

Replace the body of `MeotimeApplication.kt` with:

```kotlin
package com.septaalfauzan.meotime

import android.app.Application
import com.septaalfauzan.meotime.data.local.AndroidAppContextHolder

class MeotimeApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        AndroidAppContextHolder.context = applicationContext
        container = AppContainer(this)
    }
}
```

Delete the now-unused `java.time.LocalDate` and `kotlinx.coroutines.launch` imports with the launch block. `ScreenTimeRepository.deleteUsageBefore` stays in the contract and still deletes daily and hourly rows through `HourlyUsageDao.deleteBefore`; nothing calls it at startup.

- [ ] **Step 2: Run the complete shared test suite**

Run: `./gradlew :shared:testAndroidHostTest`

Expected: PASS. This covers the migration/database tests, splitter, repository, monitoring, formatters, stats builder, picker model, dashboard ViewModel, and range-row copy.

- [ ] **Step 3: Compile the Android application**

Run: `./gradlew :androidApp:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Regenerate and inspect the Room schema**

Run: `./gradlew :shared:kspAndroidMain`

Expected: `shared/schemas/com.septaalfauzan.meotime.data.local.MeotimeDatabase/3.json` exists. Confirm it contains `hourly_usage` with primary key `(packageName, date, hour)` and the `date` index from Task 1, and that `daily_usage` is unchanged.

- [ ] **Step 5: Manual checks**

On a device or emulator:

1. Open Home with existing monitored apps. Confirm the stats card shows today's total, six columns, top three apps plus `Others`, and the app rows keep their current copy.
2. Tap the date pill, apply `Last 7 days`. Confirm the card shows `Daily average`, a horizontal range chart, `vs prior period`, and the app rows switch to total/average copy.
3. Tap the pill again. Confirm the sheet reopens on the applied selection's month, not the current month.
4. Choose a cross-month range and confirm the pill and subtitle include both endpoints, with the year.
5. Rotate or resize the app. Confirm the card, chart, and calendar remain usable and every control keeps a 48dp target.
6. Enable a screen reader. Confirm the chart is skipped, the legend and summary read the values, selected dates announce their selection, and future dates announce unavailable.
7. Let midnight pass with `Today` selected. Confirm the card and rows roll to the new day; then select an explicit past date and confirm it does not move at midnight.
8. Remove an app from monitoring and confirm it disappears from the current stats and reappears when monitored again, using its stored history.

- [ ] **Step 6: Final self-review**

- [ ] No `TODO`, `TBD`, `FIXME`, or placeholder text remains.
- [ ] Every task names its files and exact verification command.
- [ ] No task instructs a commit, amend, push, or PR.
- [ ] `deleteUsageBefore` still deletes both tables transactionally.
- [ ] No 7-day prune remains in `MeotimeApplication`.
- [ ] Stats exclude apps that are no longer monitored but never delete their history.
- [ ] `Today` alone follows `TodayKey.dateFlow()`; explicit dates do not drift.
- [ ] Single-day and range charts use the approved geometry, colors, and labels.
- [ ] Comparison direction is written, not color-only.
- [ ] Loading, error, empty-app, and no-usage states are all represented.

Do not commit. Report the test, build, and schema results when the plan is complete.

