package com.septaalfauzan.wanci.presentation.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.septaalfauzan.wanci.presentation.theme.WanciButton
import com.septaalfauzan.wanci.presentation.theme.WanciTokens
import com.septaalfauzan.wanci.presentation.theme.WanciOutlinedButton

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        // Scrollable because the notice and the button are both below the fold at a large font
        // scale, and a centred column that cannot scroll strands the user on a screen whose only
        // exit is off-screen.
        modifier = modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Take back control of your screen time.", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Wanci limits the apps you spend too much time on. All data stays on your device.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))

        PermissionRow(
            title = "Usage Access",
            detail = "Measure how long you use apps.",
            granted = state.permissionStatus.usageAccess,
            onClick = viewModel::requestUsageAccess,
        )
        PermissionRow(
            title = "Display over other apps",
            detail = "Show the reminder when you reach a limit.",
            granted = state.permissionStatus.overlay,
            onClick = viewModel::requestOverlay,
        )
        PermissionRow(
            title = "Notifications",
            detail = "Show the reminder while Wanci is not open.",
            granted = state.permissionStatus.notification,
            onClick = viewModel::requestNotification,
        )
        Spacer(Modifier.height(32.dp))

        if (state.saveFailed) {
            OnboardingNotice(
                label = "Not saved",
                message = "Wanci couldn't finish setting up. Nothing was changed — try again.",
            )
            Spacer(Modifier.height(WanciTokens.spacing.md))
        }

        if (state.permissionStatus.allGranted) {
            WanciButton(
                onClick = { viewModel.completeOnboarding(onFinished) },
                // Double-submit guard: `saving` is lowered on every way out of the save, so a
                // second tap would otherwise queue a second write and a second navigation.
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // The label is the in-flight signal, as on the set-limit screen: the button is
                // disabled *and* says what is happening, so the wait is not silent.
                Text(if (state.saving) "Saving…" else "You're ready")
            }
        } else {
            WanciOutlinedButton(
                onClick = viewModel::refresh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Check permissions")
            }
        }
    }
}

/**
 * A label plus its message, for the same reason [SetLimitScreen]'s notice is built this way: a
 * notice carried by colour alone disappears for a user who cannot see it, and the label is what
 * says which kind of failure this is. The scheme here is deliberately achromatic, so
 * [androidx.compose.material3.MaterialTheme.colorScheme.error] is a heavier onSurface and would
 * add no signal the sentence does not already carry.
 */
@Composable
private fun OnboardingNotice(label: String, message: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(WanciTokens.spacing.xxs))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A granted permission is rendered as a status, not as a live control: there is nothing left to
 * request, so the row exposes nothing to a screen reader or a switch-control user instead of a
 * button that only *looks* finished. A disabled Material button would also drop the label to the
 * disabled alpha and fail text contrast, which is why the state is a plain label.
 */
@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (granted) {
            Text("\u2713 Granted", style = MaterialTheme.typography.labelMedium)
        } else {
            WanciOutlinedButton(onClick = onClick) {
                Text("Grant")
            }
        }
    }
}
