package com.septaalfauzan.wanci

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.septaalfauzan.wanci.data.local.AndroidAppContextHolder
import com.septaalfauzan.wanci.data.local.TodayKey
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepositoryImpl
import kotlinx.coroutines.flow.first
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

    private val today = TodayKey { "2026-09-25" }

    @Before
    fun setUp() {
        AndroidAppContextHolder.context = ApplicationProvider.getApplicationContext<Context>()
    }

    private suspend fun newRepo() = run {
        val db = buildTestDatabase("repo")
        val repo = ScreenTimeRepositoryImpl(
            monitoredAppDao = db.monitoredAppDao(),
            dailyUsageDao = db.dailyUsageDao(),
            syncStateDao = db.syncStateDao(),
            todayKey = today,
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
    fun reconcileUsesMaxMerge() = runTest {
        val (repo, db) = newRepo()
        try {
            repo.addMonitoredApp("com.instagram.android", "Instagram", 30)
            repo.recordUsage("com.instagram.android", 50, "2026-09-25")
            repo.reconcileDailyUsage(mapOf("com.instagram.android" to 40L), "2026-09-25")
            assertEquals(50L, repo.getTodayUsage("com.instagram.android", "2026-09-25"))

            repo.reconcileDailyUsage(mapOf("com.instagram.android" to 70L), "2026-09-25")
            assertEquals(70L, repo.getTodayUsage("com.instagram.android", "2026-09-25"))
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
}
