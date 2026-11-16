package com.septaalfauzan.meotime.presentation.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.septaalfauzan.meotime.presentation.format.formatComparisonPercent
import com.septaalfauzan.meotime.presentation.format.formatPeriodSubtitle
import com.septaalfauzan.meotime.presentation.format.formatStatDuration
import com.septaalfauzan.meotime.presentation.theme.MeotimeOutlinedButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens
import kotlinx.datetime.LocalDate

private val ChartHeight = 96.dp
private val RangeBarWidth = 24.dp
private val BarCornerRadius = 4.dp
private val BarGap = 2.dp
private val SingleDayLabels = listOf("12a", "4a", "8a", "12p", "4p", "8p")

@Composable
internal fun ScreenTimeStatsCard(
    stats: ScreenTimeStats?,
    loading: Boolean,
    error: Boolean,
    today: LocalDate?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MeotimeTokens.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(MeotimeTokens.controls.borderWidth, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SCREEN TIME", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    stats?.periodLabel ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                loading -> StatsCardSkeleton()
                error || stats == null -> StatsCardError(onRetry)
                else -> StatsCardContent(stats, today ?: stats.period.start)
            }
        }
    }
}

@Composable
private fun StatsCardContent(stats: ScreenTimeStats, today: LocalDate) {
    val spacing = MeotimeTokens.spacing
    Text(stats.eyebrow, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(formatStatDuration(stats.primarySeconds), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.weight(1f))
        ComparisonPill(stats)
    }
    Text(
        "${stats.comparisonLabel} · ${formatPeriodSubtitle(stats.filter, today)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    UsageBarChart(stats)
    if (stats.totalSeconds == 0L) {
        Text(
            "No usage recorded in this period",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    SliceLegend(stats.slices)
    Spacer(Modifier.height(spacing.xxs))
}

@Composable
private fun ComparisonPill(stats: ScreenTimeStats) {
    val background = when (stats.comparisonDirection) {
        ComparisonDirection.DOWN -> ScreenTimeTokens.fellBackground
        ComparisonDirection.UP -> ScreenTimeTokens.roseBackground
        ComparisonDirection.FLAT -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when (stats.comparisonDirection) {
        ComparisonDirection.DOWN -> ScreenTimeTokens.fellText
        ComparisonDirection.UP -> ScreenTimeTokens.roseText
        ComparisonDirection.FLAT -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = background, contentColor = foreground, shape = CircleShape) {
        Text(
            formatComparisonPercent(stats.comparisonPercent),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = MeotimeTokens.spacing.xs, vertical = MeotimeTokens.spacing.xxs),
        )
    }
}

@Composable
private fun UsageBarChart(stats: ScreenTimeStats) {
    val columns = stats.columns
    if (columns.isEmpty()) return
    val maxTotal = columns.maxOfOrNull { column -> column.segments.sumOf { it.seconds } } ?: 0L
    val horizontalScroll = stats.filter is StatsFilter.DateRange
    val chartModifier = Modifier
        .fillMaxWidth()
        .height(ChartHeight + 24.dp)
        .clearAndSetSemantics { }

    if (horizontalScroll) {
        LazyRow(
            modifier = chartModifier,
            horizontalArrangement = Arrangement.spacedBy(BarGap),
        ) {
            items(columns, key = { it.date.toEpochDays() }) { column ->
                BarColumn(
                    column = column,
                    maxTotal = maxTotal,
                    label = column.date.day.toString(),
                    modifier = Modifier.width(RangeBarWidth),
                )
            }
        }
    } else {
        Row(
            modifier = chartModifier,
            horizontalArrangement = Arrangement.spacedBy(MeotimeTokens.spacing.xs),
        ) {
            columns.forEachIndexed { index, column ->
                BarColumn(
                    column = column,
                    maxTotal = maxTotal,
                    label = SingleDayLabels.getOrElse(index) { column.date.day.toString() },
                    modifier = Modifier.width(RangeBarWidth),
                )
            }
        }
    }
}

@Composable
private fun BarColumn(
    column: ScreenTimeChartColumn,
    maxTotal: Long,
    label: String,
    modifier: Modifier = Modifier,
) {
    val total = column.segments.sumOf { it.seconds }
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChartHeight),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (total <= 0L || maxTotal <= 0L) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(total.toFloat() / maxTotal.toFloat()),
                    verticalArrangement = Arrangement.spacedBy(BarGap, Alignment.Bottom),
                ) {
                    column.segments.forEach { segment ->
                        if (segment.seconds > 0L) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(segment.seconds.toFloat())
                                    .background(segment.color, RoundedCornerShape(BarCornerRadius)),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(MeotimeTokens.spacing.xxs))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SliceLegend(slices: List<ScreenTimeSlice>) {
    Column(verticalArrangement = Arrangement.spacedBy(MeotimeTokens.spacing.xs)) {
        slices.forEach { slice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(slice.color, CircleShape))
                Spacer(Modifier.width(MeotimeTokens.spacing.sm))
                Text(slice.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(formatStatDuration(slice.usageSeconds), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatsCardSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(MeotimeTokens.spacing.sm)) {
        Box(Modifier.fillMaxWidth(0.35f).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant))
        Box(Modifier.fillMaxWidth(0.55f).height(32.dp).background(MaterialTheme.colorScheme.surfaceVariant))
        Box(Modifier.fillMaxWidth().height(ChartHeight).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
private fun StatsCardError(onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MeotimeTokens.spacing.xs)) {
        Text("Couldn't load screen time", style = MaterialTheme.typography.titleSmall)
        Text(
            "Your usage is safe. Try reading it again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MeotimeOutlinedButton(onClick = onRetry) {
            Text("Try again")
        }
    }
}
