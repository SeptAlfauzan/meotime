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
