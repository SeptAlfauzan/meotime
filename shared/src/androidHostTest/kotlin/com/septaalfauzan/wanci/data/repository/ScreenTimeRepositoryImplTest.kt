package com.septaalfauzan.wanci

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.septaalfauzan.wanci.data.local.AndroidAppContextHolder
import com.septaalfauzan.wanci.data.local.TodayKey
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepositoryImpl
import com.septaalfauzan.wanci.domain.model.MonitoredApp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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

    private suspend fun newRepo(todayKey: TodayKey = today) = run {
        val db = buildTestDatabase("repo")
        val repo = ScreenTimeRepositoryImpl(
            monitoredAppDao = db.monitoredAppDao(),
            dailyUsageDao = db.dailyUsageDao(),
            syncStateDao = db.syncStateDao(),
            todayKey = todayKey,
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
     * [ScreenTimeRepository.observeMonitoredApps] once and asserts the *second* emission, so the
     * `dateFlow` seam is exercised with the `combine` and the per-app join still in place.
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

            assertEquals(42L, emissions.receive().first().todayUsageSeconds)

            todayKey.date.value = "2026-09-26"

            assertEquals(0L, emissions.receive().first().todayUsageSeconds)
        } finally {
            collection.cancel()
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
}

private class MutableTodayKey(initialDate: String) : TodayKey {
    val date = MutableStateFlow(initialDate)

    override fun todayIso(): String = date.value

    override fun dateFlow(): Flow<String> = date
}
