# Realtime Screen-Time Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect a restricted app crossing its daily limit while the user is still inside it, and record screen time to the second.

**Architecture:** `MonitoringCore` holds one *session* at a time — the contiguous foreground run of a single monitored app. Elapsed time is never accumulated by counting; the session remembers its start on a monotonic clock and every reading of "how long has this been" is `stored + (now - startedAt)`. A 1s ticker recomputes that value and fires the overlay when it reaches the limit. Usage is written once, on close, in seconds. `UsageStatsManager` reconcile is deleted; the accessibility service is the only source of truth.

**Tech Stack:** Kotlin Multiplatform (commonMain / androidMain / iosMain), Compose Multiplatform, Room 2.8.5, kotlinx-coroutines-test virtual time, Robolectric for host-side database tests, Android `AccessibilityService` + `WindowManager` overlay.

**Spec:** `docs/superpowers/specs/2026-09-27-realtime-screentime-tracking.md`

## Global Constraints

- **Tasks 1, 2 and 6 are one unit of work and land as one commit.** They are numbered and ordered as written because that is the order they must be *performed* in, but they are performed by a single implementer and reviewed as a single change. Do not commit at the end of Task 1 or Task 2. The tree genuinely does not compile partway through — a caller is always one rename behind — and that is acceptable only because nothing broken is ever committed. The unit's commit is the one at the end of Task 6, and it must compile and pass the full suite before it is made. A green tree after every commit is worth more here than a green tree after every task.
- **Tasks 3, 4 and 5 are likewise one unit and one commit**, for a different reason: `MonitoringCore` gains a required `clock` parameter and `OverlayManager` loses its reference to the core, so `androidApp` cannot compile until both ends of that change exist. Task 3's gate is the shared suite, which stays green throughout — a passing `:shared:testAndroidHostTest` is explicitly *not* a sufficient signal that this unit is committable. The unit's commit is the one at the end of Task 5 and requires `assembleDebug` to succeed first.
- Net effect: three code commits, each green — the rename unit, the realtime unit, and Task 7's overlay text on its own.
- **The rename unit must shim `MonitoringCore`, not rewrite it.** After the repository is renamed, `MonitoringCore` would be handed seconds and would compare them against `dailyLimitMinutes` through a minutes-denominated `LimitChecker`, making every limit 60× more permissive — silently, with the overlay still appearing on schedule relative to the new scale. Task 2 Step 6 converts back at those two call sites so the commit preserves existing behaviour exactly, and Task 3 deletes the shim. Likewise `reconcileDailyUsage` survives the rename unit in minutes, because `UsageStatsReader` is its only caller and is not touched until Task 4; the two die together there.
- **The rename and the dashboard arithmetic must not be separable commits.** Once the domain model speaks seconds and the limit is still authored in minutes, `dashboardRow` is wrong by 60× while still compiling and while every existing test still passes — because all sixteen of its fixtures are whole minutes. That is a worse defect than a build break, so the conversion and the rename land together or not at all.
- **Do not trust the line numbers in any file list in this plan.** They were written against an earlier reading of the tree and are wrong in several places — `MonitoringCore` has four repository call sites, not the three Task 2 lists; `AppSettingsViewModel` has a `getTodayUsage` call outside the line range Task 6 names; the reconcile test in `ScreenTimeRepositoryImplTest` is named `reconcileUsesMaxMerge`, not the name Task 2 uses. Use them as a guide to *which file* and *what kind of change*, then let the compiler find the rest. Every such drift found during Unit 1 was mechanical, and every one of them would have left the tree non-compiling if the line number had been taken literally.
- `minSdk` is **28** and `compileSdk`/`targetSdk` are **37**. `ALTER TABLE ... DROP COLUMN` requires SQLite 3.35 (API 30) and is therefore **unavailable** — the migration must use the create-copy-drop-rename rebuild.
- `room { schemaDirectory("$projectDir/schemas") }` is **already configured** at `shared/build.gradle.kts:74-76`. Do not add a `room.schemaLocation` KSP argument; only `exportSchema` needs flipping.
- The database is created on **both** platforms via `Room.databaseBuilder`. Neither registers any migration today, so an unregistered migration means a silent destructive wipe on upgrade. Both must call `.addMigrations(MIGRATION_1_2)`.
- **A Room `Migration` cannot live in `commonMain`.** `migrate` takes a `SupportSQLiteDatabase` on Android and an `androidx.sqlite.SQLiteConnection` on native, and the Android type is absent from the native `androidx.sqlite` artifact, so one common subclass can override neither. The migration's SQL lives in `commonMain` as a list; each platform supplies a thin `Migration` shell over its own `execSQL`. Exactly one copy of the SQL, or the two platforms migrate different schemas.
- `MonitoredAppEntity.dailyLimitMinutes` is an **authoring unit and does not change**. It is converted to seconds at exactly one place: `MonitoringCore`. Do not migrate that column.
- Only the clock passed into `MonitoringCore` may be read for session arithmetic. It must **exclude deep sleep** — Android binds it to `SystemClock.uptimeMillis()`, never `elapsedRealtime()` or `currentTimeMillis()`.
- Usage is written to the database **once per session, on close**. There is no periodic checkpoint.
- Android has no instrumentation test source set (`androidApp/src` contains only `main`). Android-side changes are gated on compilation plus manual verification, not tests.
- `SyncStateDao`, `getSyncValue`, `putSyncValue`, and `observeSyncValue` **stay** — onboarding depends on `observeSyncValue("onboardingComplete")`. Only the `lastReconcileEpochMillis` key is abandoned.
- `PACKAGE_USAGE_STATS` stays in the manifest and in the onboarding flow even though nothing reads it. Do not "clean it up".
- **`remainingMinutes == 0` no longer implies `limitReached`.** Second-granularity usage floors a sub-minute remainder to zero, and `appStatusLabel` checks `limitReached` first, so a zero remainder used to be unreachable in the label. Any new reader of `remainingMinutes` must handle zero-while-under-the-limit, or the UI says "0 minutes remaining".
- Comments carry the contract and the non-obvious invariants. Do not add line-by-line narration of what the code plainly does.

### A note on TDD in this plan

This is a rename-heavy refactor across five layers, so several tasks cannot produce a red test: a task that renames `getTodayUsage` to `getTodayUsageSeconds` has no new behaviour to assert, and writing a test that fails only because a symbol is missing is theatre. For those tasks the cycle is **rename → run the existing suite → it must still pass**, which is a real gate because the existing tests are what break if a rename is missed somewhere. Tasks that change behaviour (1, 2's pruning, 3, 5, 6, 7) follow the strict write-fail-fix-pass cycle. Do not invent failing tests to satisfy a ritual.

### Commands

```bash
# Shared module unit tests (commonTest + androidHostTest). This is the workhorse.
./gradlew :shared:testAndroidHostTest

# iOS-side tests, needed only when a commonMain change breaks the iOS actuals.
./gradlew :shared:iosSimulatorArm64Test

# Android app compiles.
./gradlew :androidApp:assembleDebug
```

---

### Task 1: Store seconds, with a migration that keeps the user's data

The schema is the floor everything else stands on: no later task compiles until the database can hold seconds, and a wrong migration silently wipes every user's monitored apps on upgrade.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/data/local/WanciMigrations.kt` — the SQL only; the `Migration` cannot be common
- Create: `shared/src/androidMain/kotlin/com/septaalfauzan/wanci/data/local/WanciMigrations.android.kt`
- Create: `shared/src/iosMain/kotlin/com/septaalfauzan/wanci/data/local/WanciMigrations.ios.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/data/local/WanciDatabase.kt:11-19`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/data/local/entity/DailyUsageEntity.kt:6-11`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/data/local/DailyUsageDao.kt:12-28`
- Modify: `shared/src/androidMain/kotlin/com/septaalfauzan/wanci/data/local/PlatformDatabase.android.kt:20-22`
- Modify: `shared/src/iosMain/kotlin/com/septaalfauzan/wanci/data/local/PlatformDatabase.ios.kt:18-21`
- Modify: `shared/src/androidHostTest/kotlin/com/septaalfauzan/wanci/data/local/WanciDatabaseTest.kt`
- Create (generated, commit it): `shared/schemas/…/2.json`

**Interfaces:**
- Consumes: nothing. This is the first task.
- Produces:
  - `DailyUsageEntity(packageName: String, date: String, usageSeconds: Long, updatedAt: Long)`
  - `DailyUsageDao.getUsageSeconds(packageName: String, date: String): Long?`
  - `DailyUsageDao.insertOrIgnore(usage: DailyUsageEntity): Long`
  - `DailyUsageDao.addDeltaSeconds(packageName: String, date: String, delta: Long, updatedAt: Long)`
  - `DailyUsageDao.observeForDate(date: String): Flow<List<DailyUsageEntity>>`
  - `DailyUsageDao.deleteBefore(date: String)`
  - `MIGRATION_1_2: androidx.room.migration.Migration`

- [ ] **Step 1: Write the failing migration test**

Append to `WanciDatabaseTest.kt`. The test builds a real v1 database with raw SQLite, then lets Room migrate it — this is the only thing standing between this change and a data wipe.

```kotlin
    @Test
    fun migration_1_2_preservesMonitoredAppsAndConvertsUsageToSeconds() = runTest {
        val directory = createTempDirectory(prefix = "wanci-v1").toFile()
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

        val db = createWanciDatabase(nameOverride = path)
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
```

Add these imports to `WanciDatabaseTest.kt` if absent: `kotlin.io.path.createTempDirectory`, `com.septaalfauzan.wanci.data.local.createWanciDatabase`, `kotlinx.coroutines.test.runTest`, `kotlinx.coroutines.test.runTest` is only needed because the assertions call `suspend` DAO reads — a plain `fun` will not compile.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:testAndroidHostTest --tests '*WanciDatabaseTest*'`

Expected: FAIL — `getUsageSeconds` does not exist (unresolved reference).

- [ ] **Step 3: Change the entity and the DAO**

`DailyUsageEntity.kt` — replace the whole file body:

```kotlin
package com.septaalfauzan.wanci.data.local.entity

import androidx.room.Entity

/**
 * One app's recorded screen time on one date, in **seconds**.
 *
 * Seconds rather than minutes because usage is now written exactly once, when a session closes,
 * and a session can be shorter than a minute — forty sub-minute check-ins used to round to zero
 * and a limit built on them never tripped. The `dailyLimitMinutes` on a monitored app stays in
 * minutes; that is the unit the user authors a limit in, and it is converted at the one place
 * that compares the two.
 */
@Entity(tableName = "daily_usage", primaryKeys = ["packageName", "date"])
data class DailyUsageEntity(
    val packageName: String,
    val date: String,
    val usageSeconds: Long,
    val updatedAt: Long,
)
```

`DailyUsageDao.kt` — replace the whole file. `raiseToAtLeast` is **renamed, not deleted**: it is renamed to `raiseToAtLeastSeconds` and stays for this unit. Its only caller is the reconcile, which survives until Task 4, and reconcile's contract is that it must never lower an existing total — which is only true if the raise is a single SQL statement. Dropping the method here and substituting a read-then-delta in Task 2 silently reintroduces a race with `recordUsageSeconds`, which runs on a different coroutine against the same row: a reconcile that reads `null` while a record inserts drops the platform's number, and one that reads 120 while a record adds 300 lands 900 instead of 420. Both are false over-limit reports. Task 4 deletes the method along with reconcile.

```kotlin
package com.septaalfauzan.wanci.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.septaalfauzan.wanci.data.local.entity.DailyUsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyUsageDao {
    @Query("SELECT usageSeconds FROM daily_usage WHERE packageName = :packageName AND date = :date")
    suspend fun getUsageSeconds(packageName: String, date: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(usage: DailyUsageEntity): Long

    @Query("UPDATE daily_usage SET usageSeconds = usageSeconds + :delta, updatedAt = :updatedAt WHERE packageName = :packageName AND date = :date")
    suspend fun addDeltaSeconds(packageName: String, date: String, delta: Long, updatedAt: Long)

    @Query("SELECT * FROM daily_usage WHERE date = :date")
    fun observeForDate(date: String): Flow<List<DailyUsageEntity>>

    @Query("DELETE FROM daily_usage WHERE date < :date")
    suspend fun deleteBefore(date: String)
}
```

- [ ] **Step 4: Write the migration**

The migration cannot live in `commonMain`, which is the whole shape of this step. Room's `Migration.migrate` takes a **different parameter per platform** — `SupportSQLiteDatabase` on Android, `androidx.sqlite.SQLiteConnection` on native — and `androidx.sqlite.db.SupportSQLiteDatabase` is not in the native `androidx.sqlite` artifact at all, so the import does not resolve for an iOS compile and no dependency change fixes it. One `commonMain` subclass cannot override both signatures.

So the SQL lives in `commonMain` once, and each platform gets a three-line shell that hands it to its own `execSQL`. The statements are the part carrying the contract, so there must be exactly one copy of them; the alternative, duplicating the SQL per platform, is how a migration ends up migrating two different schemas.

**`commonMain/…/WanciMigrations.kt`:**

```kotlin
package com.septaalfauzan.wanci.data.local

/**
 * v1 -> v2: `daily_usage.usageMinutes` became `usageSeconds`.
 *
 * The table is rebuilt rather than altered because `minSdk` is 28 and `ALTER TABLE ... DROP
 * COLUMN` needs SQLite 3.35, which is API 30. Existing rows are multiplied by 60 so a user
 * upgrading mid-day keeps the usage they have already accrued against today's limits — the
 * alternative, a destructive migration, would cost them their monitored apps too, since they
 * live in another table this migration does not touch.
 *
 * Registered on both platforms in `createWanciDatabase`. An unregistered migration is not a
 * no-op: Room falls back to dropping every table.
 *
 * The statements, not the `Migration`, are what lives here: Room's `migrate` takes a
 * `SupportSQLiteDatabase` on Android and an `SQLiteConnection` on native, so the object cannot
 * be common. Both platforms expose the same `execSQL(String)`, which is the only thing these
 * need in common.
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
```

**`androidMain/…/WanciMigrations.android.kt`:**

```kotlin
package com.septaalfauzan.wanci.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_1_2_SQL.forEach { db.execSQL(it) }
    }
}
```

**`iosMain/…/WanciMigrations.ios.kt`:**

```kotlin
package com.septaalfauzan.wanci.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        MIGRATION_1_2_SQL.forEach { connection.execSQL(it) }
    }
}
```

Both `MIGRATION_1_2` declarations are public and un-annotated because they are consumed from `commonMain`'s `expect fun createWanciDatabase`, which resolves per platform.

- [ ] **Step 5: Bump the version and turn on schema export**

`WanciDatabase.kt` — change `version = 1` to `version = 2` and `exportSchema = false` to `exportSchema = true`. Add a KDoc line to the class explaining that `exportSchema` is what makes the next migration generatable:

```kotlin
/**
 * ...
 *
 * `exportSchema` is on and `schemas/` is committed, so a future version's migration can be
 * generated from the recorded schema instead of written from memory — which is the only way to
 * catch a column that was renamed in the entity but not in the SQL.
 */
@ConstructedBy(WanciDatabaseConstructor::class)
@Database(
    entities = [
        MonitoredAppEntity::class,
        DailyUsageEntity::class,
        SyncStateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
```

- [ ] **Step 6: Register the migration on both platforms**

`PlatformDatabase.android.kt`, `createWanciDatabase`:

```kotlin
actual fun createWanciDatabase(nameOverride: String?): WanciDatabase {
    val name = nameOverride ?: WanciDatabase.DB_NAME
    return Room.databaseBuilder(AndroidAppContextHolder.context, WanciDatabase::class.java, name)
        .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
        .addMigrations(MIGRATION_1_2)
        .build()
}
```

`PlatformDatabase.ios.kt`, `createWanciDatabase`:

```kotlin
actual fun createWanciDatabase(nameOverride: String?): WanciDatabase {
    val name = nameOverride ?: (NSHomeDirectory() + "/Documents/" + WanciDatabase.DB_NAME)
    return Room.databaseBuilder<WanciDatabase>(name = name)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(MIGRATION_1_2)
        .build()
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :shared:testAndroidHostTest --tests '*WanciDatabaseTest*'`

Expected: PASS. `shared/schemas/` now exists on disk with a `2.json`.

- [ ] **Step 8: Do not commit**

This is the first third of the rename unit (see Global Constraints). `git add` nothing yet. Run `git status`, confirm the expected files are modified, and continue to Task 2.

---

### Task 2: Repository speaks seconds, with the monitoring call sites shimmed

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/data/repository/ScreenTimeRepository.kt:4-21`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/data/repository/ScreenTimeRepositoryImpl.kt`
- Modify: `shared/src/commonTest/kotlin/com/septaalfauzan/wanci/FakeRepository.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/septaalfauzan/wanci/data/repository/ScreenTimeRepositoryImplTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCore.kt` — **call sites only**, lines 35, 61, 114
- Modify: `shared/src/commonTest/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCoreTest.kt` — **call sites only**, lines 94, 115

`MonitoringCore` and its test are in this task's file list for a narrow reason, explained in Step 6. Task 3 rewrites both files in full; this task only keeps them compiling.

**Interfaces:**
- Consumes: `DailyUsageDao.getUsageSeconds` / `addDeltaSeconds` / `insertOrIgnore` from Task 1.
- Produces the repository interface every later task codes against:
  - `suspend fun getTodayUsageSeconds(packageName: String, date: String): Long`
  - `suspend fun recordUsageSeconds(packageName: String, deltaSeconds: Long, date: String)`
  - `fun observeTodayUsageSeconds(packageName: String): Flow<Long>`
  - `suspend fun deleteUsageBefore(date: String)` (unchanged signature, new caller in Task 4)
  - `reconcileDailyUsage` **stays on the interface, in minutes**, and dies in Task 4 together with `UsageStatsReader`. See Step 4.
  - `FakeRepository.failOnRecord: Boolean` — when true, `recordUsageSeconds` throws. Used by Task 3's test 11.

- [ ] **Step 1: Update the repository test to seconds**

In `ScreenTimeRepositoryImplTest.kt`, **keep** `reconcileDailyUsageNeverLowersAnExistingTotal` — reconcile still exists after this task, in minutes, and Task 4 is what deletes it. Rename the `recordUsage`/`getTodayUsage` calls to the new names. Change `42L` to `42L` — the numbers stay the same, only the unit label changes, because the fake treats them as opaque. The prune test keeps its shape:

```kotlin
    @Test
    fun recordUsageSecondsAccumulatesDeltas() = runTest {
        val repo = buildRepository()

        repo.recordUsageSeconds("com.instagram.android", 42L, "2026-09-25")
        repo.recordUsageSeconds("com.instagram.android", 8L, "2026-09-25")

        assertEquals(50L, repo.getTodayUsageSeconds("com.instagram.android", "2026-09-25"))
    }
```

Also change the `todayUsageMinutes` assertions in this file to `todayUsageSeconds` (lines 61, 147, 151).

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:testAndroidHostTest --tests '*ScreenTimeRepositoryImplTest*'`

Expected: FAIL — unresolved reference `recordUsageSeconds`.

- [ ] **Step 3: Update the interface**

`ScreenTimeRepository.kt` — three lines change and one disappears:

```kotlin
interface ScreenTimeRepository {
    fun observeMonitoredApps(): Flow<List<MonitoredApp>>
    fun observeTodayUsageSeconds(packageName: String): Flow<Long>
    suspend fun getMonitoredApp(packageName: String): MonitoredApp?
    suspend fun getTodayUsageSeconds(packageName: String, date: String): Long
    suspend fun addMonitoredApp(packageName: String, appName: String, dailyLimitMinutes: Int): Boolean
    suspend fun updateDailyLimit(packageName: String, dailyLimitMinutes: Int)
    suspend fun setMonitoringEnabled(packageName: String, enabled: Boolean)
    suspend fun removeMonitoredApp(packageName: String)
    suspend fun recordUsageSeconds(packageName: String, deltaSeconds: Long, date: String)
    suspend fun getSyncValue(key: String): String?
    suspend fun putSyncValue(key: String, value: String)
    fun observeSyncValue(key: String): Flow<String?>
    suspend fun deleteUsageBefore(date: String)
}
```

- [ ] **Step 4: Update the implementation**

`ScreenTimeRepositoryImpl.kt` — the four affected members:

```kotlin
    override fun observeTodayUsageSeconds(packageName: String): Flow<Long> =
        todayKey.dateFlow().flatMapLatest { date ->
            dailyUsageDao.observeForDate(date).map { usageEntities ->
                usageEntities.firstOrNull { it.packageName == packageName }?.usageSeconds ?: 0L
            }
        }

    override suspend fun getTodayUsageSeconds(packageName: String, date: String): Long =
        dailyUsageDao.getUsageSeconds(packageName, date) ?: 0L

    override suspend fun recordUsageSeconds(packageName: String, deltaSeconds: Long, date: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (dailyUsageDao.insertOrIgnore(DailyUsageEntity(packageName, date, deltaSeconds, now)) == -1L) {
            dailyUsageDao.addDeltaSeconds(packageName, date, deltaSeconds, now)
        }
    }
```

Rename the `toDomain` parameter to `todayUsageSeconds`.

Leave `reconcileDailyUsage` (lines 87-97) on the interface and in the implementation, but convert at its boundary so it keeps meaning what it meant before this task, in minutes:

- `addDeltaSeconds(deltaMinutes * 60L)`
- `raiseToAtLeastSeconds(max(existingSeconds, totalMinutes * 60L))`

Keep the original two-statement shape — `insertOrIgnore` first, and only when it returns `-1L` do the `raiseToAtLeastSeconds`. Do not "simplify" it into a read followed by a delta. The raise must stay one statement: see the `raiseToAtLeastSeconds` note in Task 1 Step 3 for the two ways collapsing it over-counts a concurrent write.

`UsageStatsReader` is the only caller and it is not touched until Task 4, so leaving this method in minutes is what keeps `androidApp` compiling. The unit is converted here rather than left as raw seconds precisely so that nothing outside `androidApp` starts reading a second-denominated number as though it were minutes.

- [ ] **Step 5: Update the fake**

In `FakeRepository.kt`, rename the three usage methods to match, **keep** `reconcileDailyUsage` in minutes, change the `apps` combine to set `todayUsageSeconds`, and add the failure toggle:

```kotlin
    /**
     * Makes [recordUsageSeconds] throw, so a test can prove that a failed write costs seconds
     * rather than costing the *next* app its session. The real failure here is a Room error;
     * what matters downstream is only that something was thrown from inside the core's close path.
     */
    var failOnRecord = false

    override suspend fun recordUsageSeconds(packageName: String, deltaSeconds: Long, date: String) {
        if (failOnRecord) throw IllegalStateException("simulated Room failure")
        recorded += Triple(packageName, deltaSeconds, date)
        usage.update { it + (packageName to ((it[packageName] ?: 0L) + deltaSeconds)) }
    }
```

- [ ] **Step 6: Shim the two monitoring call sites so the tree compiles**

`MonitoringCore` reads usage and compares it against `app.dailyLimitMinutes` through `LimitChecker.exceeds(usageMinutes, limitMinutes)`. After the rename it would be handed **seconds** and would compare them to a **minute-denominated** limit — making every limit 60× more permissive, silently, with the overlay still appearing on time relative to the new scale. That is the same class of bug Task 6 exists to prevent, so it cannot be allowed to stand even for one commit.

Convert back at the call site and change nothing else in this task. `MonitoringCore.kt` lines 35, 61, 114:

- line 35, the usage read: `repository.getTodayUsageSeconds(packageName, todayIso()) / 60L`
- line 61, the limit comparison: unchanged, still minutes on both sides
- line 114, the write: `repository.recordUsageSeconds(packageName, deltaMinutes * 60L, todayIso())`

`MonitoringCoreTest.kt` lines 94 and 115: rename `r.recordUsage(...)` to `r.recordUsageSeconds(...)` and multiply the delta by 60 so the assertions still describe the same number of minutes they did before.

The result is a commit in which stored data is in seconds, every existing behaviour is bit-for-bit what it was, and `MonitoringCore` is exactly as imperfect as it was. Task 3 then rewrites both files properly and deletes the shim. Do not improve `MonitoringCore` here — Task 3 owns it, and a partial improvement here is what creates the 60× regression.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :shared:testAndroidHostTest --tests '*ScreenTimeRepositoryImplTest*' --tests '*WanciDatabaseTest*'`

Expected: PASS. The domain-model rename lands in Task 6, so expect *compile* errors in `MonitoredApp.kt` and the presentation layer until then — those are expected and are not this task's to fix. If they block the test run, comment nothing out; just confirm the failures are all `todayUsageMinutes` / `observeTodayUsage` / `getTodayUsage` name errors and continue.

- [ ] **Step 8: Do not commit**

Second third of the rename unit. `git add` nothing. Continue to Task 6, which finishes the rename, restores the build, and makes the one commit for all three tasks.

---

### Task 3: Rewrite `MonitoringCore` around a session

The core of the change. Everything before it was preparation; this is the behaviour the whole spec exists for.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/MonotonicClock.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/LimitChecker.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCore.kt` (full rewrite)
- Modify: `shared/src/commonTest/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCoreTest.kt` (full rewrite)

**Interfaces:**
- Consumes: `getTodayUsageSeconds`, `recordUsageSeconds`, `getMonitoredApp` from Task 2; `FailRepository.failOnRecord` from Task 2.
- Produces:
  - `fun interface MonotonicClock { fun uptimeMillis(): Long }`
  - `MonitoringCore(repository, todayKey, selfPackageName, clock, scope, onOverlayDecision, tickIntervalMillis = 1_000L)` — note the new `clock` and `tickIntervalMillis` parameters, in that order, after `scope`.
  - `suspend fun onForegroundChange(packageName: String?)` — returns `Unit`, and no longer takes a timestamp.
  - `fun attach()` / `fun detach()`
  - `MonitoringCore.OverlayDecision(packageName, appName, usageSeconds, limitSeconds)`
  - `internal val activeTick: Job?` — for tests only.
  - `LimitChecker.exceeds(usageSeconds: Long, limitSeconds: Long): Boolean`

- [ ] **Step 1: Write the failing core tests**

Replace `MonitoringCoreTest.kt` in full. The clock is the test's, so every timing assertion is virtual and instant.

```kotlin
package com.septaalfauzan.wanci

import com.septaalfauzan.wanci.data.local.TodayKey
import com.septaalfauzan.wanci.monitoring.LimitChecker
import com.septaalfauzan.wanci.monitoring.MonitoringCore
import com.septaalfauzan.wanci.monitoring.MonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestScope.currentTime
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

class FakeTodayKey(private val date: String) : TodayKey {
    override fun todayIso(): String = date
    override fun dateFlow(): Flow<String> = flowOf(date)
}

private const val INSTAGRAM = "com.instagram.android"
private const val YOUTUBE = "com.youtube.android"
private const val LAUNCHER = "launcher.pkg"

class MonitoringCoreTest {

    private val today = FakeTodayKey("2026-09-25")
    private val selfPkg = "com.septaalfauzan.wanci"
    private val decisions = mutableListOf<MonitoringCore.OverlayDecision>()

    /** 30 minutes, which at seconds precision is the boundary every test below is probing. */
    private fun repository(): FakeRepository = FakeRepository().apply {
        addMonitoredAppBlocking(INSTAGRAM, "Instagram", 30)
        addMonitoredAppBlocking(YOUTUBE, "YouTube", 30)
    }

    private fun TestScope.core(repository: FakeRepository) = MonitoringCore(
        repository = repository,
        todayKey = today,
        selfPackageName = selfPkg,
        clock = MonotonicClock { currentTime },
        scope = this,
        onOverlayDecision = { decisions += it },
    ).apply {
        // The core is inert until a service attaches it, exactly as AppContainer builds it. A
        // test that skipped this would watch a core that ignores every event and pass vacuously.
        attach()
    }

    @Test
    fun overlayFiresOnTheTickTheTotalReachesTheLimit() = runTest {
        val repo = repository()
        repo.recordUsageSeconds(INSTAGRAM, 29 * 60L, today.todayIso())
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        assertTrue(decisions.isEmpty(), "one minute under the limit is not over the limit")

        advanceTimeBy(59_000L)
        assertTrue(decisions.isEmpty(), "still under one second before the crossing")

        advanceTimeBy(1_000L)
        assertEquals(1, decisions.size)
        assertEquals(INSTAGRAM, decisions[0].packageName)
        assertEquals(1_800L, decisions[0].usageSeconds)
        assertEquals(1_800L, decisions[0].limitSeconds)
    }

    @Test
    fun enteringAnAlreadyOverAppFiresImmediatelyAndStartsNoTicker() = runTest {
        val repo = repository()
        repo.recordUsageSeconds(INSTAGRAM, 30 * 60L, today.todayIso())
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)

        assertEquals(1, decisions.size, "an app over its limit must be announced on entry")
        assertNull(core.activeTick, "there is nothing left to watch for, so nothing should tick")
    }

    @Test
    fun usageKeepsAccruingAfterTheOverlayFires() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(1_800L)
        assertEquals(1, decisions.size)

        advanceTimeBy(2_400L)
        core.onForegroundChange(LAUNCHER)

        assertEquals(4_200L, repo.usage.value[INSTAGRAM], "the 40 minutes after the overlay are usage too")
        assertEquals(1, decisions.size)
    }

    @Test
    fun oneOverlayPerEntry() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(1_800L)
        advanceTimeBy(3_600L)
        assertEquals(1, decisions.size, "an hour past the limit is not a second notice")

        core.onForegroundChange(LAUNCHER)
        core.onForegroundChange(INSTAGRAM)
        assertEquals(2, decisions.size, "re-entering an over-limit app announces it again")
    }

    @Test
    fun aSubMinuteSessionIsRecordedExactly() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(30_000L)
        core.onForegroundChange(LAUNCHER)

        assertEquals(30L, repo.usage.value[INSTAGRAM])
        assertEquals(1, repo.recorded.size)
    }

    @Test
    fun anInAppDialogDoesNotSplitTheSession() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(20_000L)
        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(10_000L)
        core.onForegroundChange(LAUNCHER)

        assertEquals(30L, repo.usage.value[INSTAGRAM], "the time before the dialog is still this session's")
        assertEquals(1, repo.recorded.size, "one contiguous run is one write")
    }

    @Test
    fun screenOffEndsTheSessionAndRecordsElapsed() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(45_000L)
        core.onForegroundChange(null)

        assertEquals(45L, repo.usage.value[INSTAGRAM])
    }

    @Test
    fun theClockAloneDecidesTheTotalNotTheTickCount() = runTest {
        // The deep-sleep guard. A doze stalls the ticker without moving the clock this core reads,
        // so the total must come from the clock and never from how many ticks have gone by. A
        // counter-based implementation fails this; a start-timestamp one cannot.
        val repo = repository()
        var now = 0L
        val core = MonitoringCore(
            repository = repo,
            todayKey = today,
            selfPackageName = selfPkg,
            clock = MonotonicClock { now },
            scope = this,
            onOverlayDecision = { decisions += it },
            tickIntervalMillis = 1_000L,
        ).apply { attach() }

        core.onForegroundChange(INSTAGRAM)
        now = 29 * 60_000L
        advanceTimeBy(1_000L)
        assertTrue(decisions.isEmpty(), "the clock says 29 minutes, so it is 29 minutes")

        now = 30 * 60_000L
        advanceTimeBy(1_000L)
        assertEquals(1, decisions.size)
    }

    @Test
    fun unmonitoredAndDisabledAppsOpenNoSession() = runTest {
        val repo = repository()
        repo.setMonitoringEnabledBlocking(INSTAGRAM, false)
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        assertNull(core.activeTick)

        core.onForegroundChange(LAUNCHER)
        advanceTimeBy(60_000L)
        core.onForegroundChange(null)

        assertTrue(repo.recorded.isEmpty())
    }

    @Test
    fun theSelfPackageClosesThePreviousSessionAndOpensNone() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(60_000L)
        core.onForegroundChange(selfPkg)

        assertEquals(60L, repo.usage.value[INSTAGRAM], "leaving for Wanci is still leaving")
        assertNull(repo.usage.value[selfPkg], "Wanci never counts itself")
        assertNull(core.activeTick)
    }

    @Test
    fun aFailedWriteStillLetsTheNextAppOpenItsSession() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(60_000L)
        repo.failOnRecord = true
        core.onForegroundChange(LAUNCHER)

        assertTrue(repo.usage.value.isEmpty(), "the failed write persisted nothing")

        core.onForegroundChange(INSTAGRAM)
        assertNotNull(core.activeTick, "one database error must not stop tracking the next app")
    }

    @Test
    fun aLimitRaisedBeforeReentryIsHonoured() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(20 * 60_000L)
        core.onForegroundChange(LAUNCHER)
        assertEquals(1_200L, repo.usage.value[INSTAGRAM])

        repo.updateDailyLimitBlocking(INSTAGRAM, 60)
        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(25 * 60_000L)

        assertTrue(decisions.isEmpty(), "1200 + 1500 is under the new 3600-second limit")
    }

    @Test
    fun detachStopsEverythingAndAttachResumes() = runTest {
        val repo = repository()
        val core = core(repo)

        core.onForegroundChange(INSTAGRAM)
        core.detach()
        assertNull(core.activeTick, "teardown must be able to stop the ticker synchronously")

        advanceTimeBy(3_600L)
        core.onForegroundChange(LAUNCHER)
        assertTrue(decisions.isEmpty(), "a detached core ignores foreground changes")

        core.attach()
        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(1_800L)
        assertEquals(1, decisions.size, "the service can reconnect to the same core")
    }
}

class LimitCheckerTest {
    @Test
    fun oneSecondUnderIsNotOver() = assertTrue(!LimitChecker.exceeds(1_799L, 1_800L))

    @Test
    fun exactlyAtTheLimitIsOver() = assertTrue(LimitChecker.exceeds(1_800L, 1_800L))
}
```

Two helpers are needed on the fake because `addMonitoredApp`, `setMonitoringEnabled`, and `updateDailyLimit` are `suspend` and the `repository()` setup function is not in a coroutine. Add to `FakeRepository.kt`:

```kotlin
    fun addMonitoredAppBlocking(packageName: String, appName: String, dailyLimitMinutes: Int) {
        apps.update {
            it + MonitoredApp(
                id = it.size.toLong(),
                packageName = packageName,
                appName = appName,
                dailyLimitMinutes = dailyLimitMinutes,
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
            )
        }
    }

    fun setMonitoringEnabledBlocking(packageName: String, enabled: Boolean) {
        apps.update { list -> list.map { if (it.packageName == packageName) it.copy(enabled = enabled) else it } }
    }

    fun updateDailyLimitBlocking(packageName: String, dailyLimitMinutes: Int) {
        apps.update { list ->
            list.map { if (it.packageName == packageName) it.copy(dailyLimitMinutes = dailyLimitMinutes) else it }
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests '*MonitoringCoreTest*' --tests '*LimitCheckerTest*'`

Expected: FAIL — `MonotonicClock` does not exist.

- [ ] **Step 3: Add the clock interface**

Create `MonotonicClock.kt`:

```kotlin
package com.septaalfauzan.wanci.monitoring

/**
 * The only clock session arithmetic is allowed to read.
 *
 * A parameter rather than a direct `SystemClock` call for two reasons. On Android it binds to
 * `SystemClock.uptimeMillis()` rather than `elapsedRealtime()`: uptime does not advance while the
 * device is asleep, so a session spanning a doze records awake time only, and the ticker's own
 * `delay` — which *is* driven by `elapsedRealtime` — stalling under doze is harmless because the
 * reading it produces on wake comes from here, which never moved. In tests it binds to
 * `TestScope.currentTime`, which is what makes every timing assertion in `MonitoringCoreTest`
 * virtual and instant.
 *
 * It is also monotonic, so a user changing the wall clock or the timezone mid-session cannot
 * corrupt a running session. The one wall-clock-derived value in the monitoring path is
 * `TodayKey.todayIso()`, and it is only read at the moment of writing.
 */
fun interface MonotonicClock {
    fun uptimeMillis(): Long
}
```

- [ ] **Step 4: Move `LimitChecker` to seconds**

`LimitChecker.kt` in full:

```kotlin
package com.septaalfauzan.wanci.monitoring

/**
 * The one definition of "over the limit", shared by the monitoring core and the dashboard so
 * the two cannot disagree about whether an app has been blocked.
 *
 * Seconds on both sides, and `>=` rather than `>`: a limit of 30 minutes is reached *at* 1800
 * seconds, not one tick past it.
 */
object LimitChecker {
    fun exceeds(usageSeconds: Long, limitSeconds: Long): Boolean = usageSeconds >= limitSeconds
}
```

- [ ] **Step 5: Rewrite `MonitoringCore`**

Replace `MonitoringCore.kt` in full:

```kotlin
package com.septaalfauzan.wanci.monitoring

import com.septaalfauzan.wanci.data.local.TodayKey
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Decides when a restricted app has had enough. One app is in the foreground at a time, so this
 * holds exactly one **session** at a time: a contiguous foreground run, opened when a monitored,
 * enabled, non-Wanci package takes the foreground and closed when any other package does, or when
 * the screen turns off (which arrives as a null package). Repeated events for the same package —
 * an activity change, an in-app dialog, a permission prompt — are not switches and do not restart it.
 *
 * Elapsed time is never accumulated by counting. The session remembers when it started and every
 * reading of "how long has this been" is [Session.liveSeconds] against [clock], so a tick cannot
 * drift from the clock because it never holds a counter to drift. That is also what makes the
 * whole thing testable: hand [clock] a value the test controls and time stops being a wall-clock
 * cost.
 *
 * Usage is written exactly once, when the session closes. Nothing is written during a session, so
 * an open session is unpersisted and a process death loses it. That is a deliberate trade: the
 * `UsageStatsManager` reconcile this replaces was the previous answer to that loss and it could
 * not cover it, because it only ever totalled packages it saw *close*.
 *
 * Four things are true here that the code does not spell out:
 *
 *  - [clock] must exclude deep sleep, or a session spanning a doze bills the user for sleep.
 *    See [MonotonicClock].
 *  - The ticker stopping does not stop accrual. It exits when it fires on the crossing, but the
 *    session stays open with its original start time, so time spent on a blocked app after the
 *    overlay is dismissed is still recorded on close. Accrual lives in the start timestamp, not
 *    in the ticker.
 *  - The previous session is closed in the same call that opens the next one, and it is closed
 *    *first*. They are different packages, and the close writes to the database the open reads.
 *  - [Session.overlayShown] is per session, not per day. An app already over its limit is
 *    announced on every entry, every time. A per-day suppression set would let a user who hit
 *    the limit once walk past it for the rest of the day.
 */
class MonitoringCore(
    private val repository: ScreenTimeRepository,
    private val todayKey: TodayKey,
    private val selfPackageName: String,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    private val onOverlayDecision: (OverlayDecision) -> Unit,
    private val tickIntervalMillis: Long = 1_000L,
) {
    data class OverlayDecision(
        val packageName: String,
        val appName: String,
        val usageSeconds: Long,
        val limitSeconds: Long,
    )

    private class Session(
        val packageName: String,
        val appName: String,
        val startedAtMillis: Long,
        val storedSeconds: Long,
        val limitSeconds: Long,
        var overlayShown: Boolean,
    ) {
        fun liveSeconds(nowMillis: Long): Long = storedSeconds + (nowMillis - startedAtMillis) / 1000

        fun toDecision(liveSeconds: Long) =
            OverlayDecision(packageName, appName, liveSeconds, limitSeconds)
    }

    private var currentSession: Session? = null
    private var tickJob: Job? = null
    private var detached = true

    /** Exposed for tests: a ticking session is the only reason this core holds a coroutine. */
    internal val activeTick: Job? get() = tickJob

    /**
     * Begins accepting foreground changes. The core is a process-wide singleton and Android
     * reconnects the accessibility service to the same process, so [detach] has to be reversible
     * rather than terminal. Called from `onServiceConnected`.
     */
    fun attach() {
        detached = false
    }

    /**
     * Stops everything immediately and synchronously, for `AccessibilityService.onDestroy`,
     * which cannot wait for a coroutine.
     *
     * Cancelling the ticker *before returning* is the point: once this returns, no tick can fire,
     * so the caller can remove the overlay knowing nothing will put it straight back. Closing the
     * session instead would queue a coroutine and leave exactly the race this avoids. The open
     * session's unpersisted seconds are dropped, which is the same loss as any other process
     * death — the two paths now fail identically instead of one of them racing.
     */
    fun detach() {
        tickJob?.cancel()
        tickJob = null
        currentSession = null
        detached = true
    }

    suspend fun onForegroundChange(packageName: String?) {
        if (detached) return
        val now = clock.uptimeMillis()
        closeCurrentSession(now)
        val session = createSession(packageName, now) ?: return
        currentSession = session

        val live = session.liveSeconds(now)
        if (LimitChecker.exceeds(live, session.limitSeconds)) {
            // Over before a second has passed. Announced now and never ticked — there is nothing
            // left to watch for. The session still closes normally, so time spent on the blocked
            // app is still recorded.
            session.overlayShown = true
            onOverlayDecision(session.toDecision(live))
            return
        }
        startTicking()
    }

    private suspend fun closeCurrentSession(now: Long) {
        tickJob?.cancel()
        tickJob = null
        val session = currentSession ?: return
        currentSession = null
        val elapsed = (now - session.startedAtMillis) / 1000
        if (elapsed <= 0L) return
        try {
            repository.recordUsageSeconds(session.packageName, elapsed, todayKey.todayIso())
        } catch (_: Exception) {
            // The session is closed and the state is consistent, so a failed write costs the
            // seconds it carried and nothing else. Letting it escape would abort before the next
            // session is opened, so one database error would leave whatever app the user switched
            // to untracked for the rest of the foreground period.
        }
    }

    private suspend fun createSession(packageName: String?, now: Long): Session? {
        if (packageName == null || packageName == selfPackageName) return null
        val app = repository.getMonitoredApp(packageName) ?: return null
        if (!app.enabled) return null
        return Session(
            packageName = packageName,
            appName = app.appName,
            startedAtMillis = now,
            storedSeconds = repository.getTodayUsageSeconds(packageName, todayKey.todayIso()),
            // The one place the limit's authoring unit becomes an arithmetic unit.
            limitSeconds = app.dailyLimitMinutes * 60L,
            overlayShown = false,
        )
    }

    private fun startTicking() {
        tickJob = scope.launch {
            while (isActive) {
                delay(tickIntervalMillis)
                val session = currentSession ?: return@launch
                if (session.overlayShown) return@launch
                val live = session.liveSeconds(clock.uptimeMillis())
                if (!LimitChecker.exceeds(live, session.limitSeconds)) continue
                session.overlayShown = true
                tickJob = null
                onOverlayDecision(session.toDecision(live))
                return@launch
            }
        }
    }
}
```

Note `detached` starts `true`. The core does nothing until something calls [attach], which is the safe default for a singleton constructed at app start but not yet owned by a running service.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :shared:testAndroidHostTest --tests '*MonitoringCoreTest*' --tests '*LimitCheckerTest*'`

Expected: PASS, all 14.

- [ ] **Step 7: Run the full shared suite**

Run: `./gradlew :shared:testAndroidHostTest`

Expected: PASS, the whole suite. The rename unit (Tasks 1, 2 and 6) already restored a green tree, so there is nothing to forgive here — any failure at all is a real regression. The minutes-denominated shim Task 2 left in `MonitoringCore` is what this task replaces, so `MonitoringCoreTest` is expected to be rewritten wholesale rather than adjusted.

- [ ] **Step 8: Do not commit**

First third of the realtime unit. `git add` nothing. Note that `:shared:testAndroidHostTest` passing is *not* sufficient here — the new `MonitoringCore` constructor has a `clock` parameter that `AppContainer` does not pass yet, so `androidApp` will not compile until Task 5. Continue to Task 4.

---

### Task 4: Wire the core into the Android service lifecycle

No tests are possible here — `androidApp/src` has only `main`. The gate is compilation plus the manual checks at the end.

**Files:**
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/AppContainer.kt:34-45`
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/WanciApplication.kt`
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringAccessibilityService.kt`
- Delete: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/UsageStatsReader.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/data/repository/ScreenTimeRepository.kt` — delete `reconcileDailyUsage`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/data/repository/ScreenTimeRepositoryImpl.kt` — delete its body
- Modify: `shared/src/commonTest/kotlin/com/septaalfauzan/wanci/FakeRepository.kt` — delete its fake
- Modify: `shared/src/androidHostTest/kotlin/com/septaalfauzan/wanci/data/repository/ScreenTimeRepositoryImplTest.kt` — delete `reconcileDailyUsageNeverLowersAnExistingTotal`

`UsageStatsReader` is the only reason `reconcileDailyUsage` still exists: Task 2 kept it alive, in minutes, precisely so that `androidApp` would keep compiling. Deleting the reader and its one call is therefore a single act, and it belongs here rather than in Task 2 where the method was renamed out from under it.

**Interfaces:**
- Consumes: `MonitoringCore(repository, todayKey, selfPackageName, clock, scope, onOverlayDecision, tickIntervalMillis)`, `attach()`, `detach()` from Task 3; `deleteUsageBefore(date)` from Task 2.
- Produces: `AppContainer.todayKey`, `AppContainer.repository`, `AppContainer.scope`, `AppContainer.core`, `AppContainer.coreMutex`, `AppContainer.overlayManager`, `MonitoringCore.OverlayDecision`.

- [ ] **Step 1: Bind the clock and drop the usage-stats reader from the container**

`AppContainer.kt` — replace lines 34-45:

```kotlin
    val core = MonitoringCore(
        repository = repository,
        todayKey = todayKey,
        selfPackageName = context.packageName,
        // Uptime, not elapsedRealtime: a session must not bill the user for time the device spent
        // asleep. See MonotonicClock for the rest of the reasoning.
        clock = MonotonicClock { SystemClock.uptimeMillis() },
        scope = scope,
        // No extra launch hop: OverlayManager.show posts to the main thread itself, so wrapping
        // this in scope.launch would only add a queue between the decision and the screen.
        onOverlayDecision = { decision -> overlayManager.show(decision) },
    )
    val coreMutex = Mutex()
    internal lateinit var overlayManager: OverlayManager
        private set
```

Add `import android.os.SystemClock` and `import com.septaalfauzan.wanci.monitoring.MonotonicClock`. Delete the `usageStatsReader` property and the `UsageStatsReader` import.

- [ ] **Step 2: Move the 7-day prune to application start**

`WanciApplication.kt` in full:

```kotlin
package com.septaalfauzan.wanci

import android.app.Application
import com.septaalfauzan.wanci.data.local.AndroidAppContextHolder
import java.time.LocalDate
import kotlinx.coroutines.launch

class WanciApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        AndroidAppContextHolder.context = applicationContext
        container = AppContainer(this)
        container.scope.launch {
            // Derived from the injected clock rather than a second LocalDate.now(), so the prune
            // cannot disagree with which day the rest of the app thinks it is.
            val cutoff = LocalDate.parse(container.todayKey.todayIso()).minusDays(7).toString()
            container.repository.deleteUsageBefore(cutoff)
        }
    }
}
```

The prune lost its caller when reconcile was deleted, and the table would otherwise grow without bound.

- [ ] **Step 3: Strip reconcile out of the service**

`MonitoringAccessibilityService.kt` in full:

```kotlin
package com.septaalfauzan.wanci.monitoring

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import com.septaalfauzan.wanci.AppContainer
import com.septaalfauzan.wanci.WanciApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * The only source of truth for screen time. There is no `UsageStatsManager` fallback: the
 * service's window-state events plus the screen-off broadcast are the complete account of what
 * the user did, and a second source would only be a second thing to drift.
 *
 * Every event funnels through [enqueueForegroundChange] and takes the core mutex, so the core's
 * single session is only ever advanced by one coroutine at a time.
 */
class MonitoringAccessibilityService : AccessibilityService() {

    private val ready = AtomicBoolean(false)
    private var container: AppContainer? = null
    private var screenReceiverRegistered = false

    /**
     * Screen-off is not a window-state event, so without this the session of whatever was in the
     * foreground would stay open — and keep accruing — for as long as the screen was dark.
     * A null package is how the core is told to close it.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            enqueueForegroundChange(null)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val appContainer = (application as? WanciApplication)?.container ?: return
        container = appContainer
        registerScreenReceiver()
        ready.set(true)
        appContainer.core.attach()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ready.get() || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // The event's own timestamp is deliberately not passed on. The core reads its injected
        // clock when it processes the change, so there is no second time source to keep in step.
        enqueueForegroundChange(event.packageName?.toString())
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        ready.set(false)
        unregisterScreenReceiver()
        // Order matters, and both calls are synchronous. detach() stops the ticker before it
        // returns, so no tick can fire between here and the view being removed. Closing the
        // session instead would queue a coroutine and leave that race open.
        container?.core?.detach()
        container?.overlayManager?.dismiss()
        container = null
        super.onDestroy()
    }

    private fun enqueueForegroundChange(packageName: String?) {
        val appContainer = container ?: return
        appContainer.scope.launch {
            appContainer.coreMutex.withLock {
                appContainer.core.onForegroundChange(packageName)
            }
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        @Suppress("DEPRECATION")
        registerReceiver(screenReceiver, filter)
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        unregisterReceiver(screenReceiver)
        screenReceiverRegistered = false
    }
}
```

Delete `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/UsageStatsReader.kt` and the now-unused `SystemClock` import.

- [ ] **Step 4: Delete `reconcileDailyUsage` and its test**

Now that `UsageStatsReader.kt` is gone, nothing calls `reconcileDailyUsage` and the method Task 2 kept alive in minutes has no reason to exist. Remove it from all four places:

- `ScreenTimeRepository.kt` — the interface declaration
- `ScreenTimeRepositoryImpl.kt` — the body, lines 87-97
- `FakeRepository.kt` — the fake's implementation
- `ScreenTimeRepositoryImplTest.kt` — `reconcileDailyUsageNeverLowersAnExistingTotal`

Do not leave a deprecated stub. It would compile, it would be untested, and it would be a second way to write usage totals sitting next to the one live path — which is exactly the drift this whole change exists to remove.

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :androidApp:assembleDebug`

Expected: compile errors in `OverlayManager.kt` only — it still passes `core` and `coreMutex` to a constructor that no longer wants them. That is Task 5. Confirm there are no other errors, then continue.

- [ ] **Step 6: Do not commit**

Second third of the realtime unit. `git add` nothing. Continue to Task 5, which is the step that finally lets `androidApp` compile and which makes the unit's single commit.

---

### Task 5: BACK on the overlay exits the app

**Files:**
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/OverlayManager.kt`

**Interfaces:**
- Consumes: `MonitoringCore.OverlayDecision` from Task 3.
- Produces: `OverlayManager(context: Context)` with `fun show(decision: MonitoringCore.OverlayDecision)` and `fun dismiss()`. It no longer holds a reference to the core.

- [ ] **Step 1: Reduce the constructor and route BACK to `leaveApp`**

`OverlayManager.kt` — change the class declaration and delete the now-unused imports (`Mutex`, `withLock`, `CoroutineScope`, `launch`):

```kotlin
class OverlayManager(private val context: Context) {
```

`showOnMain` — replace the failure path so a revoked overlay permission is diagnosable rather than silent:

```kotlin
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // Usually SYSTEM_ALERT_WINDOW revoked in system settings after onboarding granted it.
            // Not retried: this runs on the main thread via a handler post, so a success signal
            // would need a two-way handshake back into the core, and a user who revoked the
            // permission has said what they want. Logged so it is at least answerable when
            // someone reports the app is not stopping them.
            Log.w(TAG, "Overlay not shown for ${decision.packageName}", e)
            return
        }
```

`dismissOnMain` — drop the token round trip, which existed only to release a per-day dedupe entry that no longer exists:

```kotlin
    private fun dismissOnMain() {
        val view = overlayView ?: return
        overlayView = null
        visiblePackage = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }
```

The BACK listener — this is the behaviour change:

```kotlin
            // BACK is an exit, not a dismissal. With both buttons leaving as well, no action
            // closes the overlay while leaving the user inside the restricted app, which is what
            // makes "one notice per entry" enforceable rather than advisory. Still consumed, so
            // the app underneath never sees it.
            setOnKeyListener { _, keyCode, _ ->
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    leaveApp()
                    true
                } else {
                    false
                }
            }
```

Delete the `core`, `coreMutex`, and `scope` properties, the `OverlayLifecycleOwner`'s reference to them, and add:

```kotlin
    private companion object {
        const val TAG = "WanciOverlay"
    }
```

Add `import android.util.Log`.

- [ ] **Step 2: Update the construction site**

`AppContainer.kt`:

```kotlin
        overlayManager = OverlayManager(context)
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :androidApp:assembleDebug`

Expected: BUILD SUCCESSFUL. Any error here is a leftover reference to the removed constructor parameters.

- [ ] **Step 4: Commit the realtime unit**

This is the single commit for Tasks 3, 4 and 5, and the first moment since Task 3 that `androidApp` can compile. Verify before committing: `./gradlew :shared:testAndroidHostTest` green and `./gradlew :androidApp:assembleDebug` succeeding. If either fails, the unit is not finished.

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring \
        shared/src/commonTest/kotlin/com/septaalfauzan/wanci/monitoring \
        shared/src/commonTest/kotlin/com/septaalfauzan/wanci/FakeRepository.kt \
        androidApp/src/main/kotlin/com/septaalfauzan/wanci
git commit -m "feat: track screen time live from a session start timestamp

The old core polled Room every five seconds, which cannot change a day
total while a restricted app holds the foreground, so the limit was never
noticed until the user left. Replace polling with one session per
foreground run: the total is recomputed each second from the session's
start timestamp on a monotonic clock, and the overlay fires on the tick
that reaches the limit.

UsageStatsManager reconcile is deleted rather than repaired. It could not
cover an open session at all, because it only ever totalled packages it
saw close, so a user who hit the limit mid-session and then force-stopped
the app lost that time. Its 60-second idempotency window is a second
accounting path that disagrees with the live one by construction.

Committed as one unit because androidApp does not compile until
OverlayManager stops taking the core: the new constructor needs a clock
that AppContainer cannot yet supply, and the old one needs a core that
OverlayManager is about to stop accepting."
```

---

### Task 6: Domain and dashboard arithmetic in seconds

This is the task that ships a user-visible wrong number if it is done carelessly. `dashboardRow` divides usage by the limit, compares them, and subtracts them; leave the usage in seconds and the limit in minutes and all three are wrong by 60×, silently, with the row still rendering.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/format/UsageFormat.kt`
- Create: `shared/src/commonTest/kotlin/com/septaalfauzan/wanci/presentation/format/UsageFormatTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/domain/model/MonitoredApp.kt:20`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/domain/useCase/WanciUseCases.kt:36-37`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/dashboard/DashboardViewModel.kt:61-77`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/dashboard/DashboardScreen.kt:154`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/appsettings/AppSettingsViewModel.kt:43,103,131-141,408,416`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/appsettings/AppSettingsScreen.kt:188`
- Modify: `shared/src/commonTest/kotlin/com/septaalfauzan/wanci/presentation/dashboard/DashboardViewModelTest.kt`
- Modify: `shared/src/commonTest/kotlin/com/septaalfauzan/wanci/presentation/appsettings/AppSettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `observeTodayUsageSeconds` / `getTodayUsageSeconds` from Task 2.
- Produces:
  - `fun formatUsage(seconds: Long): String` in `com.septaalfauzan.wanci.presentation.format`
  - `MonitoredApp.todayUsageSeconds: Long` (replacing `todayUsageMinutes`)
  - `WanciUseCases.getTodayUsageSeconds(packageName: String): Long`
  - `AppSettingsUiState.usageSeconds: Long` (replacing `usageMinutes`)

- [ ] **Step 1: Write the failing formatter test**

Create `UsageFormatTest.kt`:

```kotlin
package com.septaalfauzan.wanci

import com.septaalfauzan.wanci.presentation.format.formatUsage
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageFormatTest {
    @Test
    fun formatsBelowAMinuteInSeconds() {
        assertEquals("0 sec", formatUsage(0L))
        assertEquals("8 sec", formatUsage(8L))
        assertEquals("45 sec", formatUsage(45L))
    }

    @Test
    fun doesNotClampADayThatOverranItsLimit() {
        // The no-clamp invariant. A day can overrun its limit by more than 24 hours, and a
        // formatter that clamped into 1..1440 the way formatLimitMinutes does would render a
        // 25-hour day as "24 hours" — a wrong number, in the one place the user is reading their
        // own usage back.
        assertEquals("25 hours", formatUsage(90_000L))
        assertEquals("25 hours 1 min", formatUsage(90_060L))
    }

    @Test
    fun formatsMinutesAndHours() {
        assertEquals("1 min", formatUsage(60L))
        assertEquals("12 min", formatUsage(720L))
        assertEquals("1 hour", formatUsage(3_600L))
        assertEquals("1 hour 1 min", formatUsage(3_660L))
        assertEquals("1 hour 4 min", formatUsage(3_840L))
        assertEquals("2 hours 30 min", formatUsage(9_000L))
    }

    @Test
    fun floorsNegativeReadingsAtZero() {
        assertEquals("0 sec", formatUsage(-5L))
    }
}
```

- [ ] **Step 2: Write the failing dashboard test**

`DashboardViewModelTest.kt` already has an `app(usageMinutes, limitMinutes, enabled)` helper at line 240 feeding about ten `dashboardRow` tests and six `appStatusLabel` tests. Step 7 converts inside that helper, which keeps all of those assertions meaning exactly what they meant. But every one of them uses whole-minute fixtures, so all of them would still pass if the seconds conversion were missing entirely.

Add the one test that cannot:

```kotlin
    @Test
    fun dashboardRowComparesSecondsAgainstAMinuteLimitWithoutDividingBySixty() {
        // Deliberately not routed through the `app(...)` helper, which takes minutes. The other
        // ten dashboardRow cases use whole-minute fixtures and would pass even with the
        // conversion missing; this one is the only thing standing between a silent 60x error and
        // a shipped row that reads "limit reached" for an app at 29m30s.
        val row = dashboardRow(
            MonitoredApp(
                id = 1,
                packageName = "com.instagram.android",
                appName = "Instagram",
                dailyLimitMinutes = 30,
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
                todayUsageSeconds = 1_770L,
            ),
        )

        assertFalse(row.limitReached, "29m30s against a 30m limit is not over the limit")
        assertEquals(0L, row.remainingMinutes, "the half-minute left rounds down to no minutes")
        assertTrue(row.progress < 1f, "the bar must not be full at 29m30s")
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests '*UsageFormatTest*' --tests '*DashboardViewModelTest*'`

Expected: FAIL — `formatUsage` and `todayUsageSeconds` do not exist.

- [ ] **Step 4: Add the formatter**

Create `UsageFormat.kt`. This is the existing `formatUsageMinutes` from `AppSettingsViewModel.kt:131`, moved and re-based on seconds. It keeps the app's word style on purpose — the abbreviated `"1h 04m"` form this spec first proposed would be the only place in the UI not speaking like its neighbours.

```kotlin
package com.septaalfauzan.wanci.presentation.format

/**
 * A recorded duration in the words a person would use.
 *
 * Lives in its own file rather than beside the settings view model because the overlay — which
 * runs in another process's worth of context, over another app, with no view model in sight —
 * needs to print a number too.
 *
 * Deliberately *not* `com.septaalfauzan.wanci.presentation.limit.formatLimitMinutes`, which
 * clamps into `1..1440` because it formats a limit and 0 minutes is not a limit a user can
 * store. A usage reading has the opposite shape: 0 is the common case, and a day can overrun its
 * limit by more than 24 hours. So no clamp, and a negative reading is floored at zero rather
 * than rendered as one.
 *
 * Seconds appear only below a minute. Above that it would be noise on a dashboard row, and
 * minute granularity is what the rest of the screen already speaks in.
 */
fun formatUsage(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    if (total < 60L) return "$total sec"
    val minutes = total / 60
    val hours = minutes / 60
    val restMinutes = minutes % 60
    val hourLabel = if (hours == 1L) "hour" else "hours"
    return when {
        hours == 0L -> "$minutes min"
        restMinutes == 0L -> "$hours $hourLabel"
        else -> "$hours $hourLabel $restMinutes min"
    }
}
```

- [ ] **Step 5: Rename the domain field and the use case**

`MonitoredApp.kt` line 20: `val todayUsageMinutes: Long = 0` → `val todayUsageSeconds: Long = 0`. Update the KDoc references to it in the same file.

`WanciUseCases.kt`:

```kotlin
    suspend fun getTodayUsageSeconds(packageName: String): Long =
        repository.getTodayUsageSeconds(packageName, todayKey.todayIso())
```

- [ ] **Step 6: Fix `dashboardRow`**

`DashboardViewModel.kt` — replace lines 61-77. The conversion happens once, at the top, and everything below it is in seconds:

```kotlin
internal fun dashboardRow(app: MonitoredApp): DashboardAppRow {
    // Converted once, here, because the limit is stored in minutes and the usage is read in
    // seconds. Doing the arithmetic across the two units would leave the ratio, the comparison
    // and the subtraction each wrong by a factor of 60 — with a row that still renders, so the
    // bug would ship looking like a design choice.
    val limitSeconds = app.dailyLimitMinutes * 60L
    val usage = app.todayUsageSeconds
    val measurable = limitSeconds > 0
    val paused = !app.enabled
    val progress = when {
        paused -> 0f
        measurable -> (usage.toFloat() / limitSeconds.toFloat()).coerceIn(0f, 1f)
        else -> 1f
    }
    val remainingSeconds = if (measurable && !paused) (limitSeconds - usage).coerceAtLeast(0L) else 0L
    return DashboardAppRow(
        limitReached = !measurable || usage >= limitSeconds,
        remainingMinutes = remainingSeconds / 60,
        progress = progress,
        paused = paused,
    )
}
```

`DashboardAppRow.remainingMinutes` keeps its type, so `DashboardScreen` is unaffected. Update the KDoc on the class above it (lines 30-45) to say seconds where it named `todayUsageMinutes`.

Then `appStatusLabel`, which is the only reader of `remainingMinutes`:

```kotlin
internal fun appStatusLabel(row: DashboardAppRow): String = when {
    row.limitReached -> "Limit reached"
    row.paused -> "Monitoring paused"
    // Reachable for the first time now that usage is in seconds: under the limit with less than
    // a minute left floors to zero, and "0 minutes remaining" is not a thing anyone has ever
    // said. The minute cases below are unaffected.
    row.remainingMinutes == 0L -> "Less than a minute remaining"
    row.remainingMinutes == 1L -> "1 minute remaining"
    else -> "${row.remainingMinutes} minutes remaining"
}
```

The branch goes after `paused` and before the `== 1L` case, so a paused app still reads "Monitoring paused" rather than "Less than a minute remaining".

- [ ] **Step 7: Update the two view models and screens**

`AppSettingsViewModel.kt` — four edits:
- line 43: `val usageMinutes: Long = 0` → `val usageSeconds: Long = 0`
- line 103: `dashboardRow(it.copy(todayUsageMinutes = usageMinutes))` → `dashboardRow(it.copy(todayUsageSeconds = usageSeconds))`
- delete `formatUsageMinutes` (lines 122-141, KDoc included) and add `import com.septaalfauzan.wanci.presentation.format.formatUsage`
- lines 408 and 416: `usageMinutes = 0` / `usageMinutes = usage` → `usageSeconds = …`

`AppSettingsScreen.kt:188`: `formatUsageMinutes(state.usageMinutes)` → `formatUsage(state.usageSeconds)`, and swap the import. Line 189's `formatLimitMinutes(state.limitMinutes)` is correct as-is — the limit is still minutes.

`DashboardScreen.kt:154`:

```kotlin
                    "${formatUsage(app.todayUsageSeconds)} / ${app.dailyLimitMinutes} min",
```

Add `import com.septaalfauzan.wanci.presentation.format.formatUsage`.

- [ ] **Step 8: Update the existing test fixtures**

In `DashboardViewModelTest.kt`, change the `app(...)` helper at line 240 so it converts on the way in. This is the only edit that file's other ~16 call sites need — their fixtures stay in minutes and their assertions keep their exact meaning:

```kotlin
    private fun app(usageMinutes: Long, limitMinutes: Int, enabled: Boolean = true) = MonitoredApp(
        id = 1,
        packageName = "com.instagram.android",
        appName = "Instagram",
        dailyLimitMinutes = limitMinutes,
        enabled = enabled,
        createdAt = 0,
        updatedAt = 0,
        // The helper speaks minutes because that is how these fixtures read; the model speaks
        // seconds because that is what is stored. Converting here keeps sixteen call sites
        // unchanged and honest at the same time.
        todayUsageSeconds = usageMinutes * 60,
    )
```

Rename `todayUsageMinutes` to `todayUsageSeconds` in any other `MonitoredApp` literal or `copy(...)` in `DashboardViewModelTest.kt` and `AppSettingsViewModelTest.kt`. In `AppSettingsViewModelTest.kt`, rename `usageMinutes` to `usageSeconds` in the settings state literals too (line 397 builds a row via `app.copy(...)`).

The minute values in the fixtures (42, 7, 20, 60, 95) stay as they are. `FakeRepository`'s usage map is unit-agnostic, so `recordUsageSeconds(pkg, 42L, date)` still means "some usage" to every assertion that reads it back.

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew :shared:testAndroidHostTest`

Expected: PASS, whole suite green for the first time since Task 1.

- [ ] **Step 10: Commit the rename unit**

This is the single commit for Tasks 1, 2 and 6. The tree has not compiled since Task 1, so this is the first moment it can be committed at all. Verify before committing: `./gradlew :shared:testAndroidHostTest` is green and `./gradlew :androidApp:assembleDebug` succeeds. If either fails, the unit is not finished — do not commit.

```bash
git add shared/schemas \
        shared/src/commonMain/kotlin/com/septaalfauzan/wanci/data/local \
        shared/src/androidMain/kotlin/com/septaalfauzan/wanci/data/local \
        shared/src/iosMain/kotlin/com/septaalfauzan/wanci/data/local \
        shared/src/commonMain/kotlin/com/septaalfauzan/wanci/data/repository \
        shared/src/commonMain/kotlin/com/septaalfauzan/wanci/domain \
        shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring \
        shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation \
        shared/src/commonTest/kotlin/com/septaalfauzan/wanci \
        shared/src/androidHostTest/kotlin/com/septaalfauzan/wanci
git commit -m "refactor: move screen-time accounting to seconds end to end

The stored unit changes from minutes to seconds so usage is recorded
exactly rather than to the nearest minute, which is what makes an overlay
appear at the limit instead of a minute past it.

Landed as one commit because it cannot land in pieces: the database, the
repository, the domain model and the dashboard all have to speak seconds at
the same moment. Any intermediate state either fails to compile or, worse,
compiles and passes its tests while dashboardRow compares seconds to
minutes and reports a 60x error that no whole-minute fixture can see."
```

Do not stage `task-13-report.md` or anything else that is not part of this unit. `git status` before committing should list only the paths above.

---

### Task 7: The overlay's own text

The last code change. The composable itself gets no automated test: `shared/build.gradle.kts:65-68` gives `commonTest` only `kotlin.test` and `kotlinx-coroutines-test`, there is no Compose UI test artifact in `gradle/libs.versions.toml`, and no composable in this project has ever been rendered in a test. Adding a test dependency to cover two static strings is not worth it, so the argument wiring is checked by hand in Task 8 instead. `formatUsage` — the part with actual logic — is covered by `UsageFormatTest` in Task 6.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/overlay/OverlayScreen.kt:21-46`
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/OverlayManager.kt` (the `setContent` block)

**Interfaces:**
- Consumes: `formatUsage` from Task 6, `MonitoringCore.OverlayDecision` from Task 3.
- Produces: `OverlayScreen(appName: String, usageSeconds: Long, limitSeconds: Long, onLeave: () -> Unit, onManage: () -> Unit)` — `usageMinutes: Long` and `limitMinutes: Int` are both replaced by seconds.

- [ ] **Step 1: Update the signature and the usage line**

`OverlayScreen.kt` — change the signature:

```kotlin
@Composable
fun OverlayScreen(
    appName: String,
    usageSeconds: Long,
    limitSeconds: Long,
    onLeave: () -> Unit,
    onManage: () -> Unit,
) {
```

and replace lines 43-46:

```kotlin
        Text(
            "${formatUsage(usageSeconds)} / ${formatUsage(limitSeconds)}",
            style = MaterialTheme.typography.titleMedium,
        )
```

Add `import com.septaalfauzan.wanci.presentation.format.formatUsage`. The number is deliberately not re-ticked while the overlay is up: the session is still accruing behind it, but a counter climbing under a dialog you are about to dismiss is noise, and the figure at the crossing is the one that explains why the overlay is there.

- [ ] **Step 2: Update the call site**

`OverlayManager.kt`, inside `setContent`:

```kotlin
            setContent {
                OverlayScreen(
                    appName = decision.appName,
                    usageSeconds = decision.usageSeconds,
                    limitSeconds = decision.limitSeconds,
                    onLeave = { leaveApp() },
                    onManage = { openWanci(decision.packageName) },
                )
            }
```

- [ ] **Step 3: Run the full suite and the build**

Run: `./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug`

Expected: PASS and BUILD SUCCESSFUL. Every suite is green for the first time since Task 1.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/overlay \
        androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/OverlayManager.kt
git commit -m "feat: show formatted over-limit usage on the overlay"
```

---

### Task 8: Manual verification on a device

Nothing here is automatable — `androidApp/src` has no instrumentation source set, and the two things worth checking are exactly the two a JVM test cannot reach.

**Files:** none.

**Interfaces:** none.

- [ ] **Step 1: Check whether the overlay window generates its own accessibility event**

This is the one open question the design could not settle from the code. If the overlay window produces a `TYPE_WINDOW_STATE_CHANGED` attributed to Wanci's own package, feeding it back through `onForegroundChange("com.septaalfauzan.wanci")` closes the session and writes the time the instant the overlay appears.

Procedure: enable the service, add a monitored app with a 1-minute limit, set `adb logcat` filtering on `MonitoringCore`, open the app, and cross the limit. Watch whether the session is written at the moment the overlay appears rather than when you leave.

The behaviour is correct either way — you are over the limit and you get the overlay again on re-entry — but if the session churns, the fix is to ignore window events while `overlayView != null`, which is a one-line guard in the service.

- [ ] **Step 2: Verify the three target behaviours end to end**

1. Add an app with a 2-minute limit, open it, and stay. The overlay must appear within a second or two of the 2-minute mark, with no need to leave the app. Its text must read `2 min / 2 min` or similar, formatted — not a raw number.
2. Press BACK. The overlay must disappear and the launcher must come forward. Re-open the app: the overlay must appear immediately.
3. Check the dashboard: the usage shown must be at least the 2 minutes plus the time spent on the blocked app, proving accrual continued after the overlay fired.
4. Confirm the "Manage restriction" button reaches that app's settings, and that raising its limit there stops the overlay on the next entry.

- [ ] **Step 3: Verify the migration on a real upgrade**

Install a build from before this change, add two monitored apps and some usage, then install this build over it. Both apps and their limits must survive, and the day's usage must read high by a factor of 60 relative to what the old build showed. This is the check that a passing migration test cannot fully substitute for, because it exercises the real `room_master_table` the test omits.
