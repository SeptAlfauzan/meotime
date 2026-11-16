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
