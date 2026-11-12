package com.septaalfauzan.meotime.presentation.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.septaalfauzan.meotime.domain.model.MonitoredApp
import com.septaalfauzan.meotime.presentation.format.formatUsage
import com.septaalfauzan.meotime.presentation.theme.MeotimeButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeOutlinedButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens

/**
 * Today's monitored apps.
 *
 * Usage is live: the rows come from [DashboardViewModel.state], which re-derives on every emission
 * of the observed app list, so a minute recorded by the monitoring service shows up here while
 * the screen is open and every row returns to 0 at midnight. Nothing on this screen polls or owns
 * a clock.
 *
 * All three non-content states are rendered rather than left blank — a skeleton while the first
 * read is in flight, a retry on failure, and a zero state with a call to action when nothing is
 * monitored — because an empty list and an unanswered question look identical on a blank screen.
 *
 * Every value comes from [MeotimeTokens]. The row is a [Surface] with an `onClick`, so it carries a
 * real click target and semantics rather than a click modifier on a `Column`, and it is floored at
 * the token row height to clear the 48dp minimum touch target.
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onAddApp: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val spacing = MeotimeTokens.spacing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.screen),
    ) {
        Text("Meotime", style = MaterialTheme.typography.titleLarge)
        Text("Today", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(spacing.md))

        when {
            state.loading -> DashboardSkeleton(modifier = Modifier.weight(1f))
            state.error -> DashboardError(onRetry = viewModel::refresh)
            state.apps.isEmpty() -> DashboardEmpty(onAddApp = onAddApp)
            else -> MonitoredAppList(
                apps = state.apps,
                icons = state.appIcons,
                onOpenApp = onOpenApp,
                modifier = Modifier.weight(1f),
            )
        }

        // The zero state carries its own call to action; this is the one for a populated list.
        if (!state.loading && !state.error && state.apps.isNotEmpty()) {
            Spacer(Modifier.height(spacing.md))
            MeotimeButton(onClick = onAddApp, modifier = Modifier.fillMaxWidth()) {
                Text("Add app")
            }
        }
    }
}

@Composable
private fun MonitoredAppList(
    apps: List<MonitoredApp>,
    icons: Map<String, androidx.compose.ui.graphics.ImageBitmap>,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MeotimeTokens.spacing.sm),
    ) {
        items(apps, key = { it.packageName }) { app ->
            MonitoredAppRow(app = app, appIcon = icons[app.packageName], onClick = { onOpenApp(app.packageName) })
        }
    }
}

/**
 * One app. The limit state is carried by text, never by the bar alone: a screen reader user and a
 * colour-blind user both get "Limit reached" as words, and the bar is a redundant second channel.
 *
 * A paused app ([DashboardAppRow.paused]) is not being measured, so it is labelled as such and
 * gets an empty bar instead of a live-looking budget: "60 minutes remaining" over a number nothing
 * is updating is the exact claim the label must not make. A limit that was already reached before
 * the app was paused still reads "Limit reached", because that is a real reading and the usage
 * line beside it is still true. The wording itself is [appStatusLabel], shared with the app's own
 * settings screen so the two can never describe the same row differently.
 */
@Composable
private fun MonitoredAppRow(app: MonitoredApp, appIcon: ImageBitmap?, onClick: () -> Unit) {
    val row = dashboardRow(app)
    val spacing = MeotimeTokens.spacing
    val status = appStatusLabel(row)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MeotimeTokens.controls.minRowHeight),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (appIcon != null) {
                    // Decorative: the app name is the text right beside this, so a label here would make a screen
                    // reader announce the row twice. What it must never be is a debug string — a literal
                    // "app_icon_com.foo" is not a description, it is the variable name.
                    Image(appIcon, contentDescription = null, modifier = Modifier.size(24.dp))
                } else {
                    // Same footprint either way, so a row does not shift sideways once its icon finishes decoding.
                    Spacer(Modifier.size(24.dp))
                }
                Spacer(Modifier.width(spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.appName, style = MaterialTheme.typography.titleMedium)
                    if (!app.installed) {
                        Text(
                            "No longer installed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "${formatUsage(app.todayUsageSeconds)} / ${app.dailyLimitMinutes} min",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { row.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
            )
            Text(
                status,
                style = if (row.limitReached) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = if (row.limitReached) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * Placeholder rows rather than a spinner: the shape of the incoming list is known, so the screen
 * does not jump when it lands, and the whole placeholder carries one content description instead of
 * being read out row by row.
 */
@Composable
private fun DashboardSkeleton(modifier: Modifier = Modifier) {
    val spacing = MeotimeTokens.spacing
    val transition = rememberInfiniteTransition(label = "dashboard-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dashboard-skeleton-alpha",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Loading today's app usage" },
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        repeat(3) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MeotimeTokens.controls.minRowHeight)
                    .alpha(alpha),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                SkeletonBar(widthFraction = 0.45f, height = 16.dp)
                SkeletonBar(widthFraction = 1f, height = 8.dp)
                SkeletonBar(widthFraction = 0.3f, height = 12.dp)
            }
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraSmall,
            ),
    )
}

@Composable
private fun DashboardEmpty(onAddApp: () -> Unit) {
    val spacing = MeotimeTokens.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No monitored apps", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Add an app and Meotime will start counting how long you use it today.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.md))
        MeotimeButton(onClick = onAddApp) {
            Text("Add your first app")
        }
    }
}

@Composable
private fun DashboardError(onRetry: () -> Unit) {
    val spacing = MeotimeTokens.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't load your apps", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Your limits and usage are safe. Try reading them again.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.md))
        MeotimeOutlinedButton(onClick = onRetry) {
            Text("Try again")
        }
    }
}
