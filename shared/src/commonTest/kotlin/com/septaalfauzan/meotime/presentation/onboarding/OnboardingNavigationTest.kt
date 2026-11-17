package com.septaalfauzan.meotime.presentation.onboarding

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingNavigationTest {

    @Test
    fun welcomeAndPermissionsAreReachableWithoutPermissions() {
        assertTrue(canNavigateToPage(OnboardingPage.Welcome.ordinal, allGranted = false))
        assertTrue(canNavigateToPage(OnboardingPage.Permissions.ordinal, allGranted = false))
    }

    @Test
    fun readyIsBlockedUntilAllPermissionsAreGranted() {
        assertFalse(canNavigateToPage(OnboardingPage.Ready.ordinal, allGranted = false))
        assertTrue(canNavigateToPage(OnboardingPage.Ready.ordinal, allGranted = true))
    }

    @Test
    fun outOfRangeTargetsAreRejected() {
        assertFalse(canNavigateToPage(-1, allGranted = true))
        assertFalse(canNavigateToPage(OnboardingPageCount, allGranted = true))
    }
}
