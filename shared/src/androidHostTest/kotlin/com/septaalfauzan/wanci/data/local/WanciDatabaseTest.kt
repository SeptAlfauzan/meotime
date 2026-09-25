package com.septaalfauzan.wanci

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.septaalfauzan.wanci.data.local.AndroidAppContextHolder
import com.septaalfauzan.wanci.data.local.entity.DailyUsageEntity
import com.septaalfauzan.wanci.data.local.entity.MonitoredAppEntity
import com.septaalfauzan.wanci.data.local.entity.SyncStateEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class WanciDatabaseTest {

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

            usageDao.upsert(DailyUsageEntity("com.instagram.android", "2026-09-25", 42L, 2L))
            assertEquals(42L, usageDao.getUsage("com.instagram.android", "2026-09-25"))
            usageDao.upsert(DailyUsageEntity("com.instagram.android", "2026-09-25", 45L, 3L))
            assertEquals(45L, usageDao.getUsage("com.instagram.android", "2026-09-25"))

            syncDao.put(SyncStateEntity("lastReconcileEpochMillis", "1700000000000"))
            assertEquals("1700000000000", syncDao.get("lastReconcileEpochMillis"))
            assertNull(syncDao.get("missing"))
        } finally {
            db.closeAndDelete()
        }
    }
}
