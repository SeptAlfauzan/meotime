package com.septaalfauzan.meotime.presentation.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.septaalfauzan.meotime.monitoring.TrackerFrame
import com.septaalfauzan.meotime.presentation.format.formatOverlayDuration
import com.septaalfauzan.meotime.presentation.format.formatShortDuration
import com.septaalfauzan.meotime.presentation.theme.MonochromeTheme

/**
 * Reference-exact screen-time tracker pill. A dedicated overlay palette and fixed dp sizes — not
 * app theme roles — because this composable copies the supplied overlay board and is rendered in
 * a small transparent window over another app.
 *
 * The root is the whole hit area and the painted pill: 250×40dp compact or 358×110dp expanded.
 * Anything outside those bounds stays transparent, so touches there reach the app underneath.
 */
@Composable
fun UsageTrackerOverlayScreen(
    frame: TrackerFrame,
    appIcon: ImageBitmap?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillDescription =
        "${frame.appName}, ${frame.usageSeconds} seconds of ${frame.limitSeconds}, " +
                "${frame.percent}% of daily limit used. " +
                (if (frame.expanded) "Tap to collapse." else "Tap to expand.")

    Box(
        modifier = modifier
            .size(
                width = if (frame.expanded) 358.dp else 250.dp,
                height = if (frame.expanded) 110.dp else 40.dp,
            )
            .clip(RoundedCornerShape(if (frame.expanded) 36.dp else 20.dp))
            .clickable(onClick = onTap)
            .semantics { contentDescription = pillDescription },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .size(
                    width = if (frame.expanded) 358.dp else 250.dp,
                    height = if (frame.expanded) 110.dp else 40.dp,
                )
                .clip(RoundedCornerShape(if (frame.expanded) 36.dp else 20.dp))
                .background(TrackerOverlayPalette.Pill),
        ) {
            if (frame.expanded) ExpandedContent(frame, appIcon) else CompactContent(frame, appIcon)
        }
    }
}

@Composable
private fun ExpandedContent(frame: TrackerFrame, appIcon: ImageBitmap?) {
    val headline = if (frame.nearLimit) {
        "${formatOverlayDuration(frame.usageSeconds)} " +
                "${formatOverlayDuration(frame.limitSeconds - frame.usageSeconds)} left"
    } else {
        "${formatOverlayDuration(frame.usageSeconds)} of ${formatOverlayDuration(frame.limitSeconds)}"
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(appIcon = appIcon, size = 44.dp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${frame.appName} \u00B7 screen time today",
                fontSize = 12.sp,
                color = TrackerOverlayPalette.SecondaryText,
                maxLines = 1,
            )
            Text(
                text = headline,
                fontSize = 22.sp,
                fontWeight = FontWeight.W500,
                color = TrackerOverlayPalette.PrimaryText,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        TrackerRing(
            percent = frame.percent,
            nearLimit = frame.nearLimit,
            expanded = true,
        )
    }
}

@Composable
private fun CompactContent(frame: TrackerFrame, appIcon: ImageBitmap?) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(appIcon = appIcon, size = 24.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatShortDuration(frame.usageSeconds),
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = FontWeight.W500,
            color = TrackerOverlayPalette.PrimaryText,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        TrackerRing(
            percent = frame.percent,
            nearLimit = frame.nearLimit,
            expanded = false,
        )
    }
}

/** Real app icon when supplied; otherwise the reference's white tile with its blue top stripe. */
@Composable
private fun AppIcon(appIcon: ImageBitmap?, size: Dp) {
    val shape = RoundedCornerShape(size * 0.2f)
    if (appIcon != null) {
        Image(
            bitmap = appIcon,
            contentDescription = null,
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(Color.White),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(TrackerOverlayPalette.IconAccent),
            )
        }
    }
}

@Composable
private fun TrackerRing(
    percent: Int,
    nearLimit: Boolean,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = "$percent% of daily limit used"
    Box(
        modifier = modifier
            .size(if (expanded) 52.dp else 28.dp)
            // Replacing — not merging into — the clickable parent's semantics keeps the ring's
            // own "percent of daily limit used" announcement separate from the pill's label.
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val arcStyle = Stroke(width = stroke, cap = StrokeCap.Round)

            drawArc(
                color = TrackerOverlayPalette.RingTrack,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = arcStyle,
            )
            if (percent > 0) {
                drawArc(
                    color = if (nearLimit) TrackerOverlayPalette.NearLimit else TrackerOverlayPalette.Safe,
                    startAngle = -90f,
                    sweepAngle = 360f * (percent.coerceIn(0, 100) / 100f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = arcStyle,
                )
            }
        }
        if (expanded) {
            Text(
                text = "$percent%",
                fontSize = 13.sp,
                fontWeight = FontWeight.W500,
                color = TrackerOverlayPalette.PrimaryText,
            )
        }
    }
}

/** Dedicated overlay palette from the reference board; never app theme colours. */
private object TrackerOverlayPalette {
    val Pill = Color(0xFF000000)
    val PrimaryText = Color(0xFFFFFFFF)
    val SecondaryText = Color(0xFFB0B0B5)
    val Camera = Color(0xFF1A1A1A)
    val IconAccent = Color(0xFF1A73E8)
    val RingTrack = Color.White.copy(alpha = 0.18f)
    val Safe = Color(0xFF30D158)
    val NearLimit = Color(0xFFFF9F0A)
}

// Previews mirror the real host: MonochromeTheme and no app icon, so the fallback tile is visible.
private fun previewTrackerFrame(
    usageSeconds: Long = 2_520L, // 42 min
    limitSeconds: Long = 3_600L, // 1 hr
    nearLimit: Boolean = false,
    expanded: Boolean = false,
) = TrackerFrame(
    packageName = "com.instagram.android",
    appName = "Instagram",
    usageSeconds = usageSeconds,
    limitSeconds = limitSeconds,
    percent = (usageSeconds * 100 / limitSeconds).toInt(),
    nearLimit = nearLimit,
    expanded = expanded,
)

@Composable
@Preview(
    name = "Compact · 42 min of 1 hr",
    group = "Usage tracker",
    widthDp = 266,
    heightDp = 56,
    showBackground = true,
    backgroundColor = 0xFFF2F2F7L,
)
private fun UsageTrackerCompactPreview() {
    MonochromeTheme {
        UsageTrackerOverlayScreen(
            frame = previewTrackerFrame(),
            appIcon = null,
            onTap = {},
        )
    }
}

@Composable
@Preview(
    name = "Expanded · 42 min of 1 hr",
    group = "Usage tracker",
    widthDp = 374,
    heightDp = 126,
    showBackground = true,
    backgroundColor = 0xFFF2F2F7L,
)
private fun UsageTrackerExpandedPreview() {
    MonochromeTheme {
        UsageTrackerOverlayScreen(
            frame = previewTrackerFrame(expanded = true),
            appIcon = null,
            onTap = {},
        )
    }
}

@Composable
@Preview(
    name = "Expanded · near limit",
    group = "Usage tracker",
    widthDp = 374,
    heightDp = 126,
    showBackground = true,
    backgroundColor = 0xFFF2F2F7L,
)
private fun UsageTrackerNearLimitPreview() {
    MonochromeTheme {
        UsageTrackerOverlayScreen(
            frame = previewTrackerFrame(
                usageSeconds = 3_300L, // 55 min
                nearLimit = true,
                expanded = true,
            ),
            appIcon = null,
            onTap = {},
        )
    }
}
