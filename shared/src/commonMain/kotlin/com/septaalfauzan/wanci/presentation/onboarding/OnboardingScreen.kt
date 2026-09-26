package com.septaalfauzan.wanci.presentation.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
        modifier = modifier.fillMaxSize().padding(24.dp),
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
            title = "Accessibility",
            detail = "Detect the app currently in the foreground.",
            granted = state.permissionStatus.accessibility,
            onClick = viewModel::requestAccessibility,
        )
        Spacer(Modifier.height(32.dp))

        if (state.permissionStatus.allGranted) {
            WanciButton(
                onClick = { viewModel.completeOnboarding(onFinished) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("You're ready")
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
