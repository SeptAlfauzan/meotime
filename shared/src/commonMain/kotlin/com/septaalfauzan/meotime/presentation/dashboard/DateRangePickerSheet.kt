package com.septaalfauzan.meotime.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.septaalfauzan.meotime.presentation.format.formatPeriodSummary
import com.septaalfauzan.meotime.presentation.theme.MeotimeButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeOutlinedButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTextButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens
import kotlinx.datetime.LocalDate

private val WeekdayHeaders = listOf("S", "M", "T", "W", "T", "F", "S")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangePickerSheet(
    initialFilter: StatsFilter,
    today: LocalDate,
    onDismissRequest: () -> Unit,
    onApply: (StatsFilter) -> Unit,
) {
    var draft by remember(initialFilter, today) {
        mutableStateOf(initialDatePickerState(initialFilter, today))
    }
    val spacing = MeotimeTokens.spacing

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screen)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text("Select dates", style = MaterialTheme.typography.titleLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                if (draft.mode == PickerMode.SINGLE_DAY) {
                    MeotimeButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Single day") }
                } else {
                    MeotimeOutlinedButton(
                        onClick = { draft = draft.selectMode(PickerMode.SINGLE_DAY, today) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Single day") }
                }
                if (draft.mode == PickerMode.DATE_RANGE) {
                    MeotimeButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Date range") }
                } else {
                    MeotimeOutlinedButton(
                        onClick = { draft = draft.selectMode(PickerMode.DATE_RANGE, today) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Date range") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                PresetButton("Today", Modifier.weight(1f)) {
                    draft = draft.selectPreset(PickerPreset.TODAY, today)
                }
                PresetButton("Yesterday", Modifier.weight(1f)) {
                    draft = draft.selectPreset(PickerPreset.YESTERDAY, today)
                }
                PresetButton("Last 7 days", Modifier.weight(1f)) {
                    draft = draft.selectPreset(PickerPreset.LAST_7_DAYS, today)
                }
            }

            MonthHeader(
                draft = draft,
                today = today,
                onPrevious = { draft = draft.previousMonth(today) },
                onNext = { draft = draft.nextMonth(today) },
            )
            WeekdayHeader()
            CalendarGrid(
                draft = draft,
                today = today,
                onDateClick = { date -> draft = draft.tapDate(date, today) },
            )
            Text(
                formatPeriodSummary(draft.toFilter(today), today),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                MeotimeOutlinedButton(onClick = onDismissRequest, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                MeotimeButton(
                    onClick = { onApply(draft.toFilter(today)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Apply")
                }
            }
        }
    }
}

@Composable
private fun PresetButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    MeotimeOutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MonthHeader(
    draft: DatePickerState,
    today: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val canGoNext = draft.nextMonth(today) != draft
    Row(verticalAlignment = Alignment.CenterVertically) {
        MeotimeTextButton(onClick = onPrevious) { Text("Previous") }
        Text(
            formatPickerMonth(draft.visibleMonth),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        MeotimeTextButton(onClick = onNext, enabled = canGoNext) { Text("Next") }
    }
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth()) {
        WeekdayHeaders.forEach { label ->
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CalendarGrid(
    draft: DatePickerState,
    today: LocalDate,
    onDateClick: (LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        monthGrid(draft.visibleMonth).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f).heightIn(min = 48.dp))
                    } else {
                        DayCell(
                            date = date,
                            draft = draft,
                            today = today,
                            onClick = { onDateClick(date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    draft: DatePickerState,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectable = draft.isSelectable(date, today)
    val endpoint = date == draft.start || date == draft.end
    val inSelection = date >= draft.start && date <= draft.end
    val background = when {
        endpoint -> MaterialTheme.colorScheme.primary
        inSelection -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val foreground = when {
        endpoint -> MaterialTheme.colorScheme.onPrimary
        inSelection -> MaterialTheme.colorScheme.onPrimaryContainer
        selectable -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        enabled = selectable,
        modifier = modifier
            .heightIn(min = 48.dp)
            .padding(2.dp)
            .alpha(if (selectable) 1f else 0.4f),
        shape = CircleShape,
        color = background,
        contentColor = foreground,
    ) {
        Box(Modifier, contentAlignment = Alignment.Center) {
            Text(date.day.toString(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
