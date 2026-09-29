package com.septaalfauzan.wanci.monitoring

import android.app.usage.UsageEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun resumed(pkg: String) =
    RawUsageEvent(UsageEvents.Event.ACTIVITY_RESUMED, pkg)

private fun paused(pkg: String) =
    RawUsageEvent(UsageEvents.Event.ACTIVITY_PAUSED, pkg)

private fun legacyForeground(pkg: String) =
    RawUsageEvent(UsageEvents.Event.MOVE_TO_FOREGROUND, pkg)

private fun legacyBackground(pkg: String) =
    RawUsageEvent(UsageEvents.Event.MOVE_TO_BACKGROUND, pkg)

private fun noise(pkg: String) =
    RawUsageEvent(UsageEvents.Event.SCREEN_INTERACTIVE, pkg)

class ForegroundEventParserTest {

    @Test
    fun anEmptyWindowResolvesToNoPackage() {
        assertNull(parseForegroundPackage(emptyList()))
    }

    @Test
    fun aResumedEventIsAForegroundSwitch() {
        assertEquals("com.foo", parseForegroundPackage(listOf(resumed("com.foo"))))
    }

    @Test
    fun theLegacyForegroundEventIsHandledForMinSdk28() {
        assertEquals("com.foo", parseForegroundPackage(listOf(legacyForeground("com.foo"))))
    }

    @Test
    fun theLastEventInAWindowWins() {
        val events = listOf(resumed("com.a"), paused("com.a"), resumed("com.b"))

        assertEquals("com.b", parseForegroundPackage(events))
    }

    @Test
    fun endingOnAPauseMeansTheForegroundIsEmpty() {
        assertNull(parseForegroundPackage(listOf(resumed("com.a"), paused("com.a"))))
    }

    @Test
    fun endingOnTheLegacyBackgroundEventMeansTheForegroundIsEmpty() {
        assertNull(parseForegroundPackage(listOf(legacyForeground("com.a"), legacyBackground("com.a"))))
    }

    @Test
    fun anActivitySwitchInsideOneAppResolvesToThatApp() {
        // A dialog or a permission prompt pauses and resumes the *same* package. The pause must not
        // be read as "left the foreground", which is why only the last event is consulted.
        val events = listOf(resumed("com.a"), paused("com.a"), resumed("com.a"))

        assertEquals("com.a", parseForegroundPackage(events))
    }

    @Test
    fun irrelevantEventTypesAreIgnored() {
        val around = listOf(noise("com.a"), resumed("com.b"), noise("com.b"))
        // A trailing noise event is the one that would be read as a switch: the window must still
        // resolve to the last *relevant* event, not to whatever the last package happened to be.
        val trailing = listOf(resumed("com.b"), noise("com.a"))

        assertEquals("com.b", parseForegroundPackage(around))
        assertEquals("com.b", parseForegroundPackage(trailing))
    }
    @Test
    fun typeSixteenIsIgnoredSoScreenOffIsNotCreditedAsUsage() {
        // Pins the type-16 decision in the direction the platform actually supports. 16 is
        // SCREEN_NON_INTERACTIVE and collides with nothing, so it is ignored. A screen-off event
        // must not re-open the app the user just left, and it must not cancel the pause that
        // preceded it either: the last *relevant* event here is still the pause, so the answer
        // stays null. The second case is the one that matters — if 16 were read as foreground it
        // would resolve to the null package the event carries and wipe a live session.
        val afterLeaving = listOf(
            resumed("com.a"),
            paused("com.a"),
            RawUsageEvent(UsageEvents.Event.SCREEN_NON_INTERACTIVE, null),
        )
        val duringAWindow = listOf(
            resumed("com.a"),
            RawUsageEvent(UsageEvents.Event.SCREEN_NON_INTERACTIVE, null),
        )

        assertNull(parseForegroundPackage(afterLeaving))
        assertEquals("com.a", parseForegroundPackage(duringAWindow))
    }

    @Test
    fun anEventWithNoPackageIsNotAForegroundSwitch() {
        val events = listOf(resumed("com.a"), RawUsageEvent(UsageEvents.Event.ACTIVITY_RESUMED, null))

        assertNull(parseForegroundPackage(events))
    }

    @Test
    fun aWindowWithNoForegroundEventsCarriesNoSignal() {
        // The distinction the poll loop lives on: an empty window and a window where the app left
        // both parse to null, but only the second one means anything. Without this, a quiet second
        // would close the session of whatever the user is using.
        assertFalse(hasForegroundSignal(emptyList()))
        assertFalse(hasForegroundSignal(listOf(noise("com.a"))))
    }

    @Test
    fun aWindowWhereTheAppLeftCarriesSignal() {
        assertTrue(hasForegroundSignal(listOf(resumed("com.a"), paused("com.a"))))
        assertTrue(hasForegroundSignal(listOf(legacyForeground("com.a"))))
        // The poll loop queries per window, so the resume that opened the session is usually in
        // the *previous* window and this one holds nothing but the pause. Without this case a
        // window of exactly that shape would read as "nothing happened" and leave the session
        // accruing to a foreground app the user has already left.
        assertTrue(hasForegroundSignal(listOf(paused("com.a"))))
    }
}
