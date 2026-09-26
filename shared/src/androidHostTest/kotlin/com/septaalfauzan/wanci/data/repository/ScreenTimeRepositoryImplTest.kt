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

            repo.recordUsage("com.instagram.android", 42, "2026-09-25")
            assertEquals(42L, repo.getTodayUsage("com.instagram.android", "2026-09-25"))
            assertEquals(0L, repo.getTodayUsage("com.instagram.android", "2026-09-26"))

            val apps = repo.observeMonitoredApps().first()
            assertEquals(1, apps.size)
            assertEquals(42L, apps.first().todayUsageMinutes)
        } finally {
            db.closeAndDelete()
        }
    }

    @Test
    fun recordUsageAccumulatesDeltas() = runTest {
        val (repo, db) = newRepo()
        try {
            repo.recordUsage("com.instagram.android", 42, "2026-09-25")
            repo.recordUsage("com.instagram.android", 8, "2026-09-25")
            assertEquals(50L, repo.getTodayUsage("com.instagram.android", "2026-09-25"))
        } finally {
            db.closeAndDelete()
        }
    }

    @Test
    fun reconcileUsesMaxMerge() = runTest {
        val (repo, db) = newRepo()
        try {
            repo.addMonitoredApp("com.instagram.android", "Instagram", 30)
            repo.recordUsage("com.instagram.android", 40, "2026-09-25")
            repo.reconcileDailyUsage(mapOf("com.instagram.android" to 70L), "2026-09-25")
            assertEquals(70L, repo.getTodayUsage("com.instagram.android", "2026-09-25"))

            repo.reconcileDailyUsage(mapOf("com.instagram.android" to 30L), "2026-09-25")
            assertEquals(70L, repo.getTodayUsage("com.instagram.android", "2026-09-25"))
        } finally {
            db.closeAndDelete()
        }
    }

    @Test
    fun observeTodayUsageSwitchesDate() = runTest {
        val todayKey = MutableTodayKey("2026-09-25")
        val (repo, db) = newRepo(todayKey)
        try {
            val packageName = "com.instagram.android"
            repo.recordUsage(packageName, 24, "2026-09-24")
            repo.recordUsage(packageName, 42, "2026-09-25")

            val usage = repo.observeTodayUsage(packageName)
            assertEquals(42L, usage.first())
            todayKey.date.value = "2026-09-24"
            assertEquals(24L, usage.first())
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
            repo.recordUsage(packageName, 42, "2026-09-25")

            assertEquals(42L, emissions.receive().first().todayUsageMinutes)

            todayKey.date.value = "2026-09-26"

            assertEquals(0L, emissions.receive().first().todayUsageMinutes)
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
