package com.septaalfauzan.wanci.presentation.overlay

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import com.septaalfauzan.wanci.presentation.theme.WanciButton
import com.septaalfauzan.wanci.presentation.theme.WanciOutlinedButton
import com.septaalfauzan.wanci.presentation.format.formatUsage

@Composable
fun OverlayScreen(
    appName: String,
    usageSeconds: Long,
    limitSeconds: Long,
    onLeave: () -> Unit,
    onManage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Time's up", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "You've reached your daily limit for $appName.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "${formatUsage(usageSeconds)} / ${formatUsage(limitSeconds)}",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(32.dp))
        WanciButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
            Text("Leave app")
        }
        Spacer(Modifier.height(10.dp))
        WanciOutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
            Text("Manage restriction")
        }
    }
}
