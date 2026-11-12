package com.septaalfauzan.meotime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.septaalfauzan.meotime.presentation.theme.MonochromeTheme
import com.septaalfauzan.meotime.presentation.theme.MeotimeOutlinedButton
import org.jetbrains.compose.resources.painterResource

import meotime.shared.generated.resources.Res
import meotime.shared.generated.resources.compose_multiplatform

/**
 * Host-less demo entry point kept for the iOS shell (`MainViewController`) while the Android
 * app starts at `com.septaalfauzan.meotime.presentation.App`. It deliberately goes through
 * [MonochromeTheme] and the 48dp `MeotimeOutlinedButton` like every real screen, so the demo cannot
 * drift away from the design system or regress the 48dp interactive-target rule.
 */
@Composable
@Preview
fun App() {
    MonochromeTheme {
        var showContent by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MeotimeOutlinedButton(onClick = { showContent = !showContent }) {
                Text("Click me!")
            }
            AnimatedVisibility(showContent) {
                val greeting = remember { Greeting().greet() }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(painterResource(Res.drawable.compose_multiplatform), null)
                    Text("Compose: $greeting")
                }
            }
        }
    }
}