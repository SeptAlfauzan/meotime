package com.septaalfauzan.meotime.presentation.onboarding

internal enum class OnboardingPage {
    Welcome,
    Permissions,
    Ready,
}

internal val OnboardingPageCount: Int = OnboardingPage.entries.size

/**
 * Page 0 and page 1 are always walkable. Page 2 is the "All set" page and is only
 * reachable once every onboarding permission is granted; an out-of-range index is
 * never reachable.
 */
internal fun canNavigateToPage(target: Int, allGranted: Boolean): Boolean {
    if (target !in OnboardingPage.entries.indices) return false
    return OnboardingPage.entries[target] != OnboardingPage.Ready || allGranted
}
