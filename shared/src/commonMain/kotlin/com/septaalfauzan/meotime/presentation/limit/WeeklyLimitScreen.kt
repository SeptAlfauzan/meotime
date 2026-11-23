package com.septaalfauzan.meotime.presentation.limit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.septaalfauzan.meotime.domain.limit.DEFAULT_LIMIT_MINUTES
import com.septaalfauzan.meotime.domain.model.WeeklyLimits
import com.septaalfauzan.meotime.presentation.format.formatUsage
import com.septaalfauzan.meotime.presentation.theme.MeotimeButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeOutlinedButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens
import kotlinx.datetime.DayOfWeek
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.draw.alpha

/**
 * The Pro week editor: the Quick set chips, the seven rows (a Switch per day, and the day's time
 * or "No limit"), the footer with the weekly total, and the one Save that writes the schedule.
 *
 * The four non-content states are rendered rather than left blank: a skeleton while the app and
 * its current schedule are being read, a retry when that read failed, a message with a way out
 * when the route arrived without a package, and — inside the editor — the two ways a save can
 * fail. They are the same components the set-limit screen uses, so the two screens can never
 * disagree about how they report trouble.
 *
 * When [WeeklyLimitUiState.picker] is non-null the picker takes over the screen: the list is not
 * behind it.
 */
@Composable
fun WeeklyLimitScreen(
    viewModel: WeeklyLimitViewModel,
    onClose: () -> Unit,
    onBackToApps: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val spacing = MeotimeTokens.spacing

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            state.missingArgument -> SetLimitMissingArgument(onClose = onClose)
            state.loading -> SetLimitSkeleton()
            state.error -> SetLimitReadError(onRetry = viewModel::retry)
            state.picker != null -> TimePickerScreen(
                viewModel = viewModel,
                onBack = viewModel::closePicker
            )

            else -> WeeklyLimitEditor(
                state = state,
                viewModel = viewModel,
                onBackToApps = onBackToApps,
                onSaved = onSaved,
            )
        }
    }
}

/**
 * The editor: the title and app identity, the quick-set chips, the seven day rows, the footer,
 * and the primary Save. The whole editor scrolls rather than clipping, so the Save stays
 * reachable below the fold on a short window or at a 200% font scale.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeeklyLimitEditor(
    state: WeeklyLimitUiState,
    viewModel: WeeklyLimitViewModel,
    onBackToApps: () -> Unit,
    onSaved: () -> Unit,
) {
    val spacing = MeotimeTokens.spacing
    val editing = !state.saving

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Set time limit", style = MaterialTheme.typography.titleLarge)
        Text(state.appName, style = MaterialTheme.typography.titleMedium)
        Text(
            state.packageName.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.lg))

        Text(
            "Quick set",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(spacing.sm))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            QuickSet.entries.forEach { quickSet ->
                MeotimeOutlinedButton(
                    onClick = { viewModel.applyQuickSet(quickSet) },
                    enabled = editing,
                    contentPadding = MeotimeTokens.controls.textButtonContentPadding,
                ) {
                    Text(quickSetLabel(quickSet))
                }
            }
        }
        Spacer(Modifier.height(spacing.lg))

        DayOfWeek.entries.forEach { day ->
            WeeklyLimitDayRow(day = day, state = state, viewModel = viewModel, editing = editing)
        }
        Spacer(Modifier.height(spacing.lg))

        val daysLabel = if (state.limitedDays == 1) "day" else "days"
        Text(
            "Tap a time to change it. ${state.limitedDays} $daysLabel limited, " +
                    // The weekly total is read through the opt-in day-aware rendering so a total
                    // over a day reads as days ("1 day 4 hours"), not as a clamped "24 hours".
                    "weekly total ${
                        formatUsage(
                            state.weeklyTotalMinutes * 60L,
                            includeDays = true
                        )
                    }.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.lg))

        when {
            // A failed write must be reported before the "already monitored" fact: the row can
            // exist and the write still have failed, and hiding the failure claims success.
            state.saveFailed -> {
                SetLimitNotice(
                    label = "Not saved",
                    message = "Meotime couldn't save this week's limits. Nothing was changed — try again.",
                )
                Spacer(Modifier.height(spacing.sm))
            }

            state.alreadyMonitored -> {
                SetLimitNotice(
                    label = "Already monitored",
                    message = "${state.appName} is on your list. Saving updates this week's limits.",
                )
                Spacer(Modifier.height(spacing.sm))
            }

            state.saveConflict -> {
                SetLimitNotice(
                    label = "Not saved",
                    message = "${state.appName} was added to your list somewhere else, so Meotime left its " +
                            "limits alone. Go to your apps to change them from there.",
                )
                Spacer(Modifier.height(spacing.sm))
                MeotimeOutlinedButton(
                    // Same destination as a successful save: the row that won the conflict now
                    // lives on the dashboard.
                    onClick = onBackToApps,
                    contentPadding = MeotimeTokens.controls.textButtonContentPadding,
                ) {
                    Text("Back to my apps")
                }
            }
        }

        MeotimeButton(
            onClick = { viewModel.save(onSaved) },
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // The label is the in-flight signal: the button is disabled *and* says what is
            // happening, so a second tap is refused and the wait is not silent.
            Text(if (state.saving) "Saving…" else "Save")
        }
    }
}

/** One row of the week list: the day name, its Switch, and the time button or "No limit". */
@Composable
private fun WeeklyLimitDayRow(
    day: DayOfWeek,
    state: WeeklyLimitUiState,
    viewModel: WeeklyLimitViewModel,
    editing: Boolean,
) {
    val spacing = MeotimeTokens.spacing
    val limited = day in state.enabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.sm),

        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked = limited,
            onCheckedChange = { viewModel.setDayEnabled(day, it) },
            enabled = editing,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(spacing.sm))
        Text(
            with(WeeklyLimits) { day.displayName() },
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.End) {
            if (limited) {
                Text(formatLimitMinutes(state.draft.forDay(day) ?: DEFAULT_LIMIT_MINUTES))
                IconButton(
                    onClick = { viewModel.openDayPicker(day) },
                    enabled = editing,
                ) {
                    Icon(imageVector = Icons.Default.ChevronRight, contentDescription = "Arrow")
                }
            } else {
                Text(
                    "No limit",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Arrow",
                    modifier = Modifier.alpha(0.3f)
                )
            }
        }
    }
}

/** The quick-set chip labels, one per [QuickSet] value. */
private fun quickSetLabel(quickSet: QuickSet): String = when (quickSet) {
    QuickSet.DAILY -> "Daily"
    QuickSet.WEEKDAYS -> "Weekdays"
    QuickSet.WEEKENDS -> "Weekends"
    QuickSet.CLEAR -> "Clear"
}
