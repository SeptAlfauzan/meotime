package com.septaalfauzan.meotime.presentation.limit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.septaalfauzan.meotime.domain.model.WeeklyLimits
import com.septaalfauzan.meotime.presentation.theme.MeotimeButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeOutlinedButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens
import com.septaalfauzan.meotime.presentation.theme.MeotimeTypography

internal fun wheelCenteredIndex(
    firstVisibleIndex: Int,
    scrollOffsetPx: Int,
    rowHeightPx: Float,
): Int = firstVisibleIndex + ((scrollOffsetPx + rowHeightPx / 2f) / rowHeightPx).toInt()

internal fun wheelValue(index: Int, count: Int): Int = index % count

internal fun wheelCenteredValue(
    firstVisibleIndex: Int,
    scrollOffsetPx: Int,
    rowHeightPx: Float,
    count: Int,
): Int = wheelValue(wheelCenteredIndex(firstVisibleIndex, scrollOffsetPx, rowHeightPx), count)

/** Visible wheel values as [top, center, bottom] for the three-row band. */
internal fun wheelVisibleValues(
    firstVisibleIndex: Int,
    scrollOffsetPx: Int,
    rowHeightPx: Float,
    count: Int,
): List<Int> {
    val centered = wheelCenteredIndex(firstVisibleIndex, scrollOffsetPx, rowHeightPx)
    return listOf(
        wheelValue(centered - 1, count),
        wheelValue(centered, count),
        wheelValue(centered + 1, count),
    )
}

/**
 * The picker the week editor shows while [WeeklyLimitUiState.picker] is non-null: the title
 * names the day or "All days", the wheels set the duration in hours and minutes, the preset
 * chips are the common values, and the ghost action is the remove.
 *
 * `pickerMinutes` is the wheel position — the total in minutes. The hour wheel selects the hour
 * component and the minute wheel the minute component; a wheel tap routes through
 * [WeeklyLimitViewModel.setPickerMinutes], the only writer of the position, which coerces the
 * value into the 0..1440 range even when a wheel combination would exceed it.
 */
@Composable
fun TimePickerScreen(
    viewModel: WeeklyLimitViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val picker = state.picker ?: return
    val spacing = MeotimeTokens.spacing
    val title = when (picker) {
        is PickerTarget.Day -> with(WeeklyLimits) { picker.day.displayName() }
        PickerTarget.AllDays -> "All days"
    }
    val subtitle = if (picker is PickerTarget.Day) {
        "${state.appName} · daily limit for this day"
    } else {
        "${state.appName} · same daily limit for every day"
    }
    val hours = (state.pickerMinutes / 60).coerceIn(0, 23)
    val minutes = state.pickerMinutes % 60

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MeotimeOutlinedButton(
                onClick = onBack,
                contentPadding = MeotimeTokens.controls.textButtonContentPadding,
            ) {
                Text("Back")
            }
            Spacer(Modifier.width(spacing.sm))
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(spacing.sm))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(spacing.lg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelColumn(
                count = 24,
                selected = hours,
                label = "Hours",
                onSelect = { hour -> viewModel.setPickerMinutes(hour * 60 + minutes) },
                modifier = Modifier.weight(1f),
            )
            Text(":", style = MeotimeTypography.displayLarge)
            WheelColumn(
                count = 60,
                selected = minutes,
                label = "Minutes",
                onSelect = { minute -> viewModel.setPickerMinutes((state.pickerMinutes / 60) * 60 + minute) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(spacing.md))
        Text("Preset time")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            TimePreset("30 min") { viewModel.setPickerMinutes(30) }
            TimePreset("1 hr") { viewModel.setPickerMinutes(60) }
            TimePreset("2 hr") { viewModel.setPickerMinutes(120) }
        }
        Spacer(Modifier.height(spacing.lg))
        MeotimeButton(
            onClick = viewModel::confirmPicker,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }
        Spacer(Modifier.height(spacing.sm))
        MeotimeOutlinedButton(
            onClick = viewModel::removeFromPicker,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = MeotimeTokens.controls.textButtonContentPadding,
        ) {
            Text(
                if (picker is PickerTarget.Day) "Remove limit for ${title}" else "Remove limits for all days",
            )
        }
    }
}

/** One preset chip. */
@Composable
private fun TimePreset(label: String, onClick: () -> Unit) {
    MeotimeOutlinedButton(
        onClick = onClick,
        modifier = Modifier.width(96.dp),
        contentPadding = MeotimeTokens.controls.textButtonContentPadding,
    ) {
        Text(label)
    }
}

/**
 * One wheel column: values scroll vertically in a three-row band; the row under the band centre
 * is the selection; the list loops end-to-start; tap animates a row to the centre.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelColumn(
    count: Int,
    selected: Int,
    label: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MeotimeTokens.spacing
    val rowHeight = 64.dp
    val windowHeight = Dp(rowHeight.value * 3f)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = count + selected)
    val coroutineScope = rememberCoroutineScope()
    var suppressSelection by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val currentOnSelect by rememberUpdatedState(onSelect)

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .map { (first, offset) -> wheelCenteredIndex(first, offset, rowHeightPx) }
            .distinctUntilChanged()
            .collect { centered ->
                if (!suppressSelection) {
                    currentOnSelect(wheelValue(centered, count))
                }
            }
    }

    LaunchedEffect(selected) {
        val centered = wheelCenteredIndex(
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset,
            rowHeightPx,
        )
        if (wheelValue(centered, count) == selected) return@LaunchedEffect
        suppressSelection = true
        try {
            listState.animateScrollToItem(count + selected)
        } finally {
            suppressSelection = false
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect {
                val centered = wheelCenteredIndex(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    rowHeightPx,
                )
                if (centered < count) {
                    listState.scrollToItem(centered + count)
                } else if (centered >= 2 * count) {
                    listState.scrollToItem(centered - count)
                }
            }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(spacing.sm))

        Box(
            Modifier
                .fillMaxWidth()
                .height(windowHeight)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = rowHeight),
                flingBehavior = rememberSnapFlingBehavior(listState),
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(3 * count) { absoluteIndex ->
                    val value = wheelValue(absoluteIndex, count)
                    Box(
                        Modifier
                            .height(rowHeight)
                            .clickable {
                                coroutineScope.launch {
                                    suppressSelection = true
                                    try {
                                        listState.animateScrollToItem(count + value)
                                        currentOnSelect(value)
                                    } finally {
                                        suppressSelection = false
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            value.toString().padStart(2, '0'),
                            style = MeotimeTypography.displayMedium.copy(
                                fontWeight = if (value == selected) FontWeight.Medium else FontWeight.Normal,
                            ),
                            color = if (value == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
