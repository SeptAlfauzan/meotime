package com.septaalfauzan.meotime.presentation.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.septaalfauzan.meotime.presentation.theme.MeotimeButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTextButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens
import kotlinx.coroutines.launch

/**
 * Three-page onboarding:
 *
 * 1. Welcome.
 * 2. Permission request.
 * 3. All set.
 *
 * Page 3 is unreachable until every permission is granted. While the user is on the
 * ungranted permission page, the pager's own swipe is frozen (with an explicit Back
 * button as the way back), and a revocation after reaching page 3 snaps the pager
 * back to the permission page.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    // Permissions are granted in another app (Settings) or in a runtime dialog, so the
    // snapshot has to be re-read when the app resumes; the manual "Check permissions"
    // button remains for a user who does not leave the app.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val pagerState = rememberPagerState(
        initialPage = OnboardingPage.Welcome.ordinal,
        pageCount = { OnboardingPageCount },
    )
    val scope = rememberCoroutineScope()
    val allGranted = state.permissionStatus.allGranted

    // Second line of defence: even if a future change lets the pager settle on the
    // Ready page, a snapshot without all permissions returns the user to page 2.
    // Keyed on `allGranted` alone: `currentPage` flips at the midpoint of this very
    // animation, and re-keying on it would cancel the scroll halfway between pages.
    LaunchedEffect(allGranted) {
        if (pagerState.currentPage == OnboardingPage.Ready.ordinal && !allGranted) {
            pagerState.animateScrollToPage(OnboardingPage.Permissions.ordinal)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            // Freeze swiping while the permission page is ungranted. This also freezes
            // back-swipe there, which is why the screen exposes an explicit Back button.
            userScrollEnabled = allGranted ||
                pagerState.settledPage == OnboardingPage.Welcome.ordinal,
        ) { page ->
            when (OnboardingPage.entries[page]) {
                OnboardingPage.Welcome -> WelcomePage()
                OnboardingPage.Permissions -> PermissionsPage(
                    state = state,
                    onRequestUsageAccess = viewModel::requestUsageAccess,
                    onRequestOverlay = viewModel::requestOverlay,
                    onRequestNotification = viewModel::requestNotification,
                    onCheckPermissions = viewModel::refresh,
                )
                OnboardingPage.Ready -> ReadyPage(state = state)
            }
        }

        OnboardingControls(
            currentPage = pagerState.currentPage,
            allGranted = allGranted,
            saving = state.saving,
            onBack = {
                scope.launch {
                    pagerState.animateScrollToPage(
                        (pagerState.currentPage - 1).coerceAtLeast(0),
                    )
                }
            },
            onWelcomeContinue = {
                scope.launch {
                    pagerState.animateScrollToPage(OnboardingPage.Permissions.ordinal)
                }
            },
            onPermissionsContinue = {
                if (canNavigateToPage(OnboardingPage.Ready.ordinal, allGranted)) {
                    scope.launch {
                        pagerState.animateScrollToPage(OnboardingPage.Ready.ordinal)
                    }
                }
            },
            onStart = { viewModel.completeOnboarding(onFinished) },
        )
    }
}

@Composable
private fun OnboardingControls(
    currentPage: Int,
    allGranted: Boolean,
    saving: Boolean,
    onBack: () -> Unit,
    onWelcomeContinue: () -> Unit,
    onPermissionsContinue: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .padding(
                horizontal = MeotimeTokens.spacing.screen,
                vertical = MeotimeTokens.spacing.lg,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OnboardingPageIndicator(
            currentPage = currentPage,
            pageCount = OnboardingPageCount,
        )
        Spacer(Modifier.height(MeotimeTokens.spacing.md))

        when (OnboardingPage.entries.getOrNull(currentPage)) {
            OnboardingPage.Welcome -> {
                MeotimeButton(
                    onClick = onWelcomeContinue,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue")
                }
            }

            OnboardingPage.Permissions -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MeotimeTextButton(onClick = onBack) {
                        Text("Back")
                    }
                    Spacer(Modifier.width(MeotimeTokens.spacing.sm))
                    MeotimeButton(
                        onClick = onPermissionsContinue,
                        enabled = allGranted,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Continue")
                    }
                }
            }

            OnboardingPage.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MeotimeTextButton(onClick = onBack) {
                        Text("Back")
                    }
                    Spacer(Modifier.width(MeotimeTokens.spacing.sm))
                    MeotimeButton(
                        onClick = onStart,
                        enabled = allGranted && !saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (saving) "Saving…" else "Start MeoTime")
                    }
                }
            }

            null -> Unit
        }
    }
}
