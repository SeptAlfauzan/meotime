package com.septaalfauzan.wanci.presentation.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
            Button(onClick = { viewModel.completeOnboarding(onFinished) }, modifier = Modifier.fillMaxWidth()) {
                Text("You're ready")
            }
        } else {
            OutlinedButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                Text("Check permissions")
            }
        }
    }
}

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
        ElevatedButton(onClick = onClick) {
            Text(if (granted) "Granted" else "Grant")
        }
    }
}
