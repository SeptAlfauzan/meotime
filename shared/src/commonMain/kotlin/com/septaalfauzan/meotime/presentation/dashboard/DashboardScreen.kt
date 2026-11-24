package com.septaalfauzan.meotime.presentation.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.septaalfauzan.meotime.domain.limit.effectiveLimitMinutes
import com.septaalfauzan.meotime.domain.model.MonitoredApp
import com.septaalfauzan.meotime.presentation.format.formatPeriodButton
import com.septaalfauzan.meotime.presentation.format.formatPeriodSubtitle
import com.septaalfauzan.meotime.presentation.format.formatStatDuration
import com.septaalfauzan.meotime.presentation.format.formatUsage
import com.septaalfauzan.meotime.presentation.limit.formatLimitMinutes
import com.septaalfauzan.meotime.presentation.theme.MeotimeButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeOutlinedButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens
import kotlinx.datetime.LocalDate

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
    val anchor = state.today ?: state.stats?.period?.start
    // The date the rows resolve their limits against. Rows are rendered only from the branches
    // below, so a row never computes against a state that claims to be resolved without a date.
    val today = state.today
    var showPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Meotime",
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 34.sp),
                )
                Text(
                    anchor?.let { formatPeriodSubtitle(state.statsFilter, it) } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            DatePill(
                label = anchor?.let { formatPeriodButton(state.statsFilter, it) } ?: "Dates",
                enabled = anchor != null,
                onClick = {
                    if (anchor != null) showPicker = true
                },
            )
        }
        Spacer(Modifier.height(spacing.md))

        ScreenTimeStatsCard(
            stats = state.stats,
            loading = state.statsLoading,
            error = state.statsError,
            today = anchor,
            onRetry = viewModel::refreshStats,
        )
        Spacer(Modifier.height(spacing.md))

        when {
            state.loading || today == null -> DashboardSkeleton(modifier = Modifier.weight(1f))
            state.error -> DashboardError(onRetry = viewModel::refresh)
            state.apps.isEmpty() -> DashboardEmpty(onAddApp = onAddApp)
            else -> MonitoredAppList(
                apps = state.apps,
                icons = state.appIcons,
                statsFilter = state.statsFilter,
                appUsage = state.stats?.appUsage.orEmpty(),
                today = today,
                onOpenApp = onOpenApp,
                modifier = Modifier.weight(1f),
            )
        }

        if (!state.loading && today != null && !state.error && state.apps.isNotEmpty()) {
            Spacer(Modifier.height(spacing.md))
            MeotimeButton(onClick = onAddApp, modifier = Modifier.fillMaxWidth()) {
                Text("Add app")
            }
        }
    }

    if (showPicker && anchor != null) {
        DateRangePickerSheet(
            initialFilter = state.statsFilter,
            today = anchor,
            onDismissRequest = { showPicker = false },
            onApply = { filter ->
                showPicker = false
                viewModel.applyFilter(filter)
            },
        )
    }
}

@Composable
private fun DatePill(label: String, enabled: Boolean, onClick: () -> Unit) {
    MeotimeOutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        CalendarGlyph()
        Spacer(Modifier.width(MeotimeTokens.spacing.xs))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun CalendarGlyph() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(14.dp)) {
        val stroke = 1.5.dp.toPx()
        val radius = CornerRadius(2.dp.toPx())
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, size.height * 0.1f),
            size = Size(size.width, size.height * 0.9f),
            cornerRadius = radius,
            style = Stroke(stroke),
        )
        drawLine(
            color = color,
            start = Offset(0f, size.height * 0.35f),
            end = Offset(size.width, size.height * 0.35f),
            strokeWidth = stroke,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.3f, 0f),
            end = Offset(size.width * 0.3f, size.height * 0.22f),
            strokeWidth = stroke,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.7f, 0f),
            end = Offset(size.width * 0.7f, size.height * 0.22f),
            strokeWidth = stroke,
        )
    }
}

@Composable
private fun MonitoredAppList(
    apps: List<MonitoredApp>,
    icons: Map<String, ImageBitmap>,
    statsFilter: StatsFilter,
    appUsage: Map<String, AppUsageSummary>,
    today: LocalDate,
    onOpenApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MeotimeTokens.spacing.sm),
    ) {
        items(apps, key = { it.packageName }) { app ->
            MonitoredAppRow(
                app = app,
                today = today,
                appIcon = icons[app.packageName],
                statsFilter = statsFilter,
                usage = appUsage[app.packageName],
                onClick = { onOpenApp(app.packageName) },
            )
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
 *
 * A weekly app's single-day line shows the limit its weekday carries (`formatLimitMinutes`) rather
 * than the stored daily limit; on a day the schedule is empty there it shows only the usage, and
 * the status line underneath says "No limit today".
 */
@Composable
private fun MonitoredAppRow(
    app: MonitoredApp,
    today: LocalDate,
    appIcon: ImageBitmap?,
    statsFilter: StatsFilter,
    usage: AppUsageSummary?,
    onClick: () -> Unit,
) {
    val row = dashboardRow(app, today)
    val range = statsFilter is StatsFilter.DateRange
    val rangeRow = if (range) dashboardRangeRow(app, usage) else null
    val effectiveLimit = effectiveLimitMinutes(app, today)
    val spacing = MeotimeTokens.spacing

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
                    Image(appIcon, contentDescription = null, modifier = Modifier.size(24.dp))
                } else {
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
                    if (range) {
                        "${formatStatDuration(usage?.totalSeconds ?: 0L)} total"
                    } else {
                        when {
                            // Free rows keep the stored daily limit beside the usage, exactly as
                            // they have always printed it.
                            !app.usesWeeklyLimits -> "${formatUsage(app.todayUsageSeconds)} / ${app.dailyLimitMinutes} min"
                            // A weekly row with a limit shows the value its weekday carries, so the
                            // two screens that print limits say the same number.
                            effectiveLimit != null -> "${formatUsage(app.todayUsageSeconds)} / ${formatLimitMinutes(effectiveLimit)} min"
                            // A weekly row on a day the schedule leaves unlimited says only the
                            // usage; the status line under it carries the "No limit today" reading.
                            else -> formatUsage(app.todayUsageSeconds)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { rangeRow?.progress ?: row.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
            )
            Text(
                rangeRow?.status ?: appStatusLabel(row),
                style = if (!range && row.limitReached) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = if (!range && row.limitReached) {
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

internal data class DashboardRangeRow(
    val progress: Float,
    val status: String,
)

internal fun dashboardRangeRow(app: MonitoredApp, usage: AppUsageSummary?): DashboardRangeRow {
    val average = usage?.dailyAverageSeconds ?: 0L
    // A weekly app's range row speaks about the week: the average, and nothing about a daily
    // budget — the `· limit N min` copy and its progress arithmetic are the Free row's.
    val isWeekly = app.usesWeeklyLimits
    // The daily budget exists only for the Free row; a weekly row short-circuits to 0f above it.
    val progress = when {
        !app.enabled -> 0f
        isWeekly -> 0f
        app.dailyLimitMinutes <= 0 -> 1f
        else -> (average.toFloat() / (app.dailyLimitMinutes * 60L).toFloat()).coerceIn(0f, 1f)
    }
    val copy = if (isWeekly) {
        "${formatStatDuration(average)} per day on average"
    } else {
        "${formatStatDuration(average)} per day on average · limit ${app.dailyLimitMinutes} min"
    }
    return DashboardRangeRow(
        progress = progress,
        status = if (app.enabled) copy else "Monitoring paused · $copy",
    )
}
