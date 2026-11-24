package com.septaalfauzan.meotime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.septaalfauzan.meotime.data.local.AndroidAppContextHolder
import com.septaalfauzan.meotime.data.local.TodayKey
import com.septaalfauzan.meotime.data.repository.ScreenTimeRepositoryImpl
import com.septaalfauzan.meotime.domain.limit.effectiveLimitMinutes
import com.septaalfauzan.meotime.domain.model.MonitoredApp
import com.septaalfauzan.meotime.domain.model.WeeklyLimits
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.time.Instant
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ScreenTimeRepositoryImplTest {

    private val today = MutableTodayKey("2026-09-25")

    @Before
    fun setUp() {
        AndroidAppContextHolder.context = ApplicationProvider.getApplicationContext<Context>()
    }

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

    @Test
    fun addRefusesDuplicateAndDailyResetYieldsZero() = runTest {
        val (repo, db) = newRepo()
        try {
            assertTrue(
                repo.addMonitoredApp("com.instagram.android", "Instagram", 30)
            )
            assertFalse(
                repo.addMonitoredApp("com.instagram.android", "Instagram", 60)
            )

            repo.recordUsageSeconds("com.instagram.android", 42L, "2026-09-25")
            assertEquals(42L, repo.getTodayUsageSeconds("com.instagram.android", "2026-09-25"))
            assertEquals(0L, repo.getTodayUsageSeconds("com.instagram.android", "2026-09-26"))

            val apps = repo.observeMonitoredApps().first()
            assertEquals(1, apps.size)
            assertEquals(42L, apps.first().todayUsageSeconds)
        } finally {
            db.closeAndDelete()
        }
    }

    @Test
    fun recordUsageSecondsAccumulatesDeltas() = runTest {
        val (repo, db) = newRepo()
        try {
            repo.recordUsageSeconds("com.instagram.android", 42L, "2026-09-25")
            repo.recordUsageSeconds("com.instagram.android", 8L, "2026-09-25")
            assertEquals(50L, repo.getTodayUsageSeconds("com.instagram.android", "2026-09-25"))
        } finally {
            db.closeAndDelete()
        }
    }

    @Test
    fun observeTodayUsageSecondsSwitchesDate() = runTest {
        val todayKey = MutableTodayKey("2026-09-25")
        val (repo, db) = newRepo(todayKey)
        try {
            val packageName = "com.instagram.android"
            repo.recordUsageSeconds(packageName, 24L, "2026-09-24")
            repo.recordUsageSeconds(packageName, 42L, "2026-09-25")

            val usage = repo.observeTodayUsageSeconds(packageName)
            assertEquals(42L, usage.first())
            todayKey.date.value = "2026-09-24"
            assertEquals(24L, usage.first())
        } finally {
            db.closeAndDelete()
        }
    }

    @Test
    fun deleteUsageBeforePrunesOldDays() = runTest {
        val (repo, db) = newRepo()
        try {
            repo.addMonitoredApp("com.instagram.android", "Instagram", 30)
            repo.recordUsageSeconds("com.instagram.android", 10L, "2026-09-25")
            repo.recordUsageSeconds("com.instagram.android", 5L, "2026-09-24")
            repo.deleteUsageBefore("2026-09-25")
            assertEquals(10L, repo.getTodayUsageSeconds("com.instagram.android", "2026-09-25"))
            assertEquals(null, db.dailyUsageDao().getUsageSeconds("com.instagram.android", "2026-09-24"))
        } finally {
            db.closeAndDelete()
        }
    }

    /**
     * The dashboard's own read path across midnight. `observeTodayUsageSwitchesDate` covers the
     * per-package flow, which nothing on the dashboard collects; this collects
     * [ScreenTimeRepository.observeMonitoredApps] once and asserts the value *after* the rollover,
     * so the `dateFlow` seam is exercised with the `combine` and the per-app join still in place.
     *
     * The app list and the usage row land on the flow separately, so the assertions wait for the
     * value they expect rather than assuming a particular emission order.
     */
    @Test
    fun observeMonitoredAppsSwitchesDateOnRollover() = runTest {
        val todayKey = MutableTodayKey("2026-09-25")
        val (repo, db) = newRepo(todayKey)
        val emissions = Channel<List<MonitoredApp>>(Channel.UNLIMITED)
        val collection = backgroundScope.launch {
            repo.observeMonitoredApps().collect { emissions.send(it) }
        }
        try {
            val packageName = "com.instagram.android"
            repo.addMonitoredApp(packageName, "Instagram", 30)
            repo.recordUsageSeconds(packageName, 42L, "2026-09-25")

            assertEquals(42L, awaitTodayUsage(emissions, expected = 42L))

            todayKey.date.value = "2026-09-26"

            assertEquals(0L, awaitTodayUsage(emissions, expected = 0L))
        } finally {
            collection.cancel()
            db.closeAndDelete()
        }
    }

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

    @Test
    fun monitorControlOperationsPersist() = runTest {
        val (repo, db) = newRepo()
        try {
            repo.addMonitoredApp("com.instagram.android", "Instagram", 30)
            repo.setMonitoringEnabled("com.instagram.android", false)
            var app = repo.getMonitoredApp("com.instagram.android")!!
            assertFalse(app.enabled)

            repo.updateDailyLimit("com.instagram.android", 45)
            app = repo.getMonitoredApp("com.instagram.android")!!
            assertEquals(45, app.dailyLimitMinutes)

            repo.removeMonitoredApp("com.instagram.android")
            assertEquals(null, repo.getMonitoredApp("com.instagram.android"))
        } finally {
            db.closeAndDelete()
        }
    }

    @Test
    fun freeAddWritesAllWeekdaysAndLeavesMarkerFalse() = runTest {
        val (repo, db) = newRepo()
        try {
            assertTrue(repo.addMonitoredApp("com.instagram.android", "Instagram", 30))
            val app = repo.getMonitoredApp("com.instagram.android")!!
            assertFalse(app.usesWeeklyLimits)
            assertEquals(
                WeeklyLimits(
                    monday = 30, tuesday = 30, wednesday = 30, thursday = 30,
                    friday = 30, saturday = 30, sunday = 30,
                ),
                app.weeklyLimits,
            )
        } finally {
            db.closeAndDelete()
        }
    }

    @Test
    fun weeklyAddAndUpdatePersistMarkerAndScheduleTogether() = runTest {
        val (repo, db) = newRepo()
        try {
            assertTrue(
                repo.addMonitoredAppWithWeeklyLimits("com.calendar.app", "Calendar", WeeklyLimits(monday = 30, wednesday = 60))
            )
            var app = repo.getMonitoredApp("com.calendar.app")!!
            assertTrue(app.usesWeeklyLimits)
            assertEquals(WeeklyLimits(monday = 30, wednesday = 60), app.weeklyLimits)

            repo.updateWeeklyLimits("com.calendar.app", WeeklyLimits())
            app = repo.getMonitoredApp("com.calendar.app")!!
            assertTrue(app.usesWeeklyLimits)
            assertEquals(WeeklyLimits(), app.weeklyLimits)
        } finally {
            db.closeAndDelete()
        }
    }

    @Test
    fun allNullWeeklyRowNeverFallsBackToDaily() = runTest {
        val (repo, db) = newRepo()
        try {
            repo.addMonitoredAppWithWeeklyLimits("com.calendar.app", "Calendar", WeeklyLimits(monday = 30))
            repo.updateWeeklyLimits("com.calendar.app", WeeklyLimits())

            val app = repo.getMonitoredApp("com.calendar.app")!!
            assertTrue(app.usesWeeklyLimits)
            assertEquals(WeeklyLimits(), app.weeklyLimits)

            val mondays = generateSequence(LocalDate(2026, 9, 28)) { it.plus(7, DateTimeUnit.DAY) }.take(3)
            mondays.forEach { assertEquals(null, effectiveLimitMinutes(app, it)) }
        } finally {
            db.closeAndDelete()
        }
    }

    @Test
    fun updateDailyLimitSwitchesRowBackToDaily() = runTest {
        val (repo, db) = newRepo()
        try {
            repo.addMonitoredAppWithWeeklyLimits("com.calendar.app", "Calendar", WeeklyLimits(monday = 30))
            repo.updateDailyLimit("com.calendar.app", 45)

            val app = repo.getMonitoredApp("com.calendar.app")!!
            assertFalse(app.usesWeeklyLimits)
            assertEquals(
                WeeklyLimits(
                    monday = 45, tuesday = 45, wednesday = 45, thursday = 45,
                    friday = 45, saturday = 45, sunday = 45,
                ),
                app.weeklyLimits,
            )
        } finally {
            db.closeAndDelete()
        }
    }
}

private class MutableTodayKey(initialDate: String) : TodayKey {
    val date = MutableStateFlow(initialDate)

    override fun todayIso(): String = date.value

    override fun dateFlow(): Flow<String> = date
}

/**
 * Waits for the emission that carries [expected] today-usage, skipping the intermediate ones Room
 * produces as the app list and the usage row land separately. `observeMonitoredApps` re-emits on
 * every upstream change, so a caller must not assume a particular emission order.
 */
private suspend fun awaitTodayUsage(
    emissions: Channel<List<MonitoredApp>>,
    expected: Long,
): Long {
    while (true) {
        val row = emissions.receive().firstOrNull() ?: continue
        if (row.todayUsageSeconds == expected) return row.todayUsageSeconds
    }
}
