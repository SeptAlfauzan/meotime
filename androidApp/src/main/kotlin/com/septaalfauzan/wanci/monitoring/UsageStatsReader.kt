package com.septaalfauzan.wanci.monitoring

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.septaalfauzan.wanci.data.repository.ScreenTimeRepository
import kotlinx.coroutines.launch

class UsageStatsReader(
    private val context: Context,
) {
    fun queryForegroundMinutes(fromEpoch: Long, toEpoch: Long): Map<String, Long> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(fromEpoch, toEpoch)
        val isForeground = mutableMapOf<String, Boolean>()
        val lastStamp = mutableMapOf<String, Long>()
        val totals = mutableMapOf<String, Long>()

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val stamp = event.timeStamp
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    isForeground[pkg] = true
                    lastStamp[pkg] = stamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (isForeground[pkg] == true) {
                        totals[pkg] = (totals[pkg] ?: 0L) + (stamp - (lastStamp[pkg] ?: fromEpoch))
                        isForeground[pkg] = false
                    }
                }
            }
        }
        return totals.mapValues { (pkg, ms) -> ms / 60_000L }
    }

    suspend fun reconcile(repository: ScreenTimeRepository, todayKey: () -> String) {
        val now = System.currentTimeMillis()
        val marker = repository.getSyncValue("lastReconcileEpochMillis")?.toLongOrNull() ?: (now - 24 * 3600_000L)
        if (now - marker < 60_000L) return
        val deltas = queryForegroundMinutes(marker, now)
        repository.reconcileDailyUsage(deltas, todayKey())
        repository.putSyncValue("lastReconcileEpochMillis", now.toString())
        val sevenDaysAgo = java.time.LocalDate.now().minusDays(7).toString()
        repository.deleteUsageBefore(sevenDaysAgo)
    }
}
