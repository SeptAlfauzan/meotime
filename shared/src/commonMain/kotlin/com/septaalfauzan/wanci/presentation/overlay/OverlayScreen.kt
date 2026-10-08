package com.septaalfauzan.wanci.presentation.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.septaalfauzan.wanci.presentation.format.formatUsage
import com.septaalfauzan.wanci.presentation.theme.WanciButton
import com.septaalfauzan.wanci.presentation.theme.WanciOutlinedButton
import com.septaalfauzan.wanci.presentation.theme.WanciTokens

/**
 * The limit notice, composable on top of whatever app the user is actually in.
 *
 * The root paints `colorScheme.background`, and that fill is load bearing rather than cosmetic.
 * `OverlayManager` adds this as a transparent `TYPE_APPLICATION_OVERLAY` window, so the surface
 * behind these words is an arbitrary third party app, not our own background: an unpainted root
 * drops a headline straight onto whatever the user was looking at, and the hardcoded white the
 * host used to set is only ever legible in the mode it was written for. Reading the scheme is
 * what keeps the notice readable in both, and it keeps the color decision inside the design
 * system instead of duplicated in Android code.
 */
@Composable
fun OverlayScreen(
    appName: String,
    usageSeconds: Long,
    limitSeconds: Long,
    onLeave: () -> Unit,
    onManage: () -> Unit,
) {
    val spacing = WanciTokens.spacing

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Time's up", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(spacing.sm))
            Text(
                "You've reached your daily limit for $appName.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(spacing.md))
            Text(
                "${formatUsage(usageSeconds)} / ${formatUsage(limitSeconds)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(spacing.xl))
            WanciButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                Text("Leave app")
            }
            Spacer(Modifier.height(spacing.sm))
            WanciOutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                Text("Manage restriction")
            }
        }
    }
}
