package com.septaalfauzan.wanci.presentation.appsettings

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.septaalfauzan.wanci.presentation.dashboard.appStatusLabel
import com.septaalfauzan.wanci.presentation.limit.formatLimitMinutes
import com.septaalfauzan.wanci.presentation.theme.WanciButton
import com.septaalfauzan.wanci.presentation.theme.WanciOutlinedButton
import com.septaalfauzan.wanci.presentation.theme.WanciTokens

/**
 * One app's settings: what it has used today, whether Wanci is watching it, and how to stop.
 *
 * This destination is reached two ways, and it has to be the same screen either way: a tap on a
 * dashboard row, and the overlay's "Manage restriction" deep link. The deep link is the reason the
 * removed-app case is rendered rather than assumed away — the app the overlay named may already have
 * been deleted, and the honest screen for "Wanci is not watching this app" is a message with a way
 * out, not an editor for a row that does not exist.
 *
 * **The numbers come from the same arithmetic as the list.** The status line is
 * [appStatusLabel] over the [com.septaalfauzan.wanci.presentation.dashboard.dashboardRow] of this
 * app, so "Monitoring paused" and the remaining-minutes wording are one function shared with the
 * dashboard rather than a second copy of the same rule. A settings page that disagreed with the list
 * one tap away would be worse than no progress bar at all.
 *
 * **The limit is edited on the set-limit screen, not here.** [onChangeLimit] hands the app over to
 * that destination, which already owns the stepper, the presets and the honest update-then-re-read
 * save; a second editor here would be a second implementation of the same write. Coming back
 * re-reads the row, so a limit changed over there — or the user backing out of it without saving —
 * cannot leave a stale number on screen.
 *
 * **The writes are not optimistic.** The switch only moves once the row has been re-read saying so,
 * and the destructive action is a two-step confirmation whose second step is verified the same way.
 * Every state that is not content is rendered rather than left blank: a skeleton while the row, the
 * usage and the installed probe are being read, a retry when that read failed, a message for a route
 * that arrived without a package, a message for an app that is not monitored, and an explanation for
 * either way a write can fail. Nothing here is a spinner, and every spacing, control, colour and type
 * value comes from [WanciTokens] and the theme; the only literals are the skeleton's own bar geometry.
 */
@Composable
fun AppSettingsScreen(
    viewModel: AppSettingsViewModel,
    onBack: () -> Unit,
    onChangeLimit: (String) -> Unit,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val spacing = WanciTokens.spacing

    // Returning to this destination re-runs the read: the limit editor may have changed the row
    // (or the user may have backed out of it), and an app that was uninstalled or reinstalled while
    // the screen sat in the back stack has to be re-probed. The *first* resume is skipped because the
    // view model already read in its constructor — refreshing there would only repeat the row read
    // and the PackageManager probe. Every later resume keeps the resolved row on screen while it
    // re-reads, so this is never a flash of skeleton.
    var hasResumed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (hasResumed) viewModel.refresh() else hasResumed = true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.screen),
    ) {
        val app = state.app
        when {
            state.missingArgument -> AppSettingsMessage(
                title = "Couldn't open this app",
                message = "Wanci doesn't know which app this page is for. Go back and pick it from your apps.",
                actionLabel = "Go back",
                onAction = onBack,
            )

            state.loading && app == null -> AppSettingsSkeleton()

            state.error -> AppSettingsMessage(
                title = "Couldn't load this app",
                // Names no app: the error state is not a full reset any more, but the label it would
                // print could be a stale one, and a stale name is worse than none.
                message = "Wanci couldn't read this app's settings. Your limits are safe. Try again.",
                actionLabel = "Try again",
                onAction = viewModel::retry,
            )

            app == null -> AppSettingsMessage(
                title = "Not monitored any more",
                message = "Wanci isn't watching this app, so there's nothing to change here. " +
                    "Add it again from your apps if you want a limit on it.",
                actionLabel = "Back to my apps",
                onAction = onBack,
            )

            else -> AppSettingsContent(
                state = state,
                onBack = onBack,
                onChangeLimit = { onChangeLimit(app.packageName) },
                onSetMonitoringEnabled = viewModel::setMonitoringEnabled,
                onRequestRemove = viewModel::requestRemove,
                onCancelRemove = viewModel::cancelRemove,
                onConfirmRemove = { viewModel.remove(onRemoved) },
            )
        }
    }
}

/**
 * The settings themselves: identity, today's reading, the limit, the switch, and the removal.
 *
 * A fixed column rather than a list, so it scrolls rather than clipping — the Back button stays
 * reachable at a 200% font scale or in a short window instead of falling off the layout.
 */
@Composable
private fun AppSettingsContent(
    state: AppSettingsUiState,
    onBack: () -> Unit,
    onChangeLimit: () -> Unit,
    onSetMonitoringEnabled: (Boolean) -> Unit,
    onRequestRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onConfirmRemove: () -> Unit,
) {
    val spacing = WanciTokens.spacing
    val app = state.app ?: return
    val row = state.row

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(state.displayName, style = MaterialTheme.typography.titleLarge)
        Text(
            app.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.md))

        if (state.showsUninstallNotice) {
            AppSettingsNotice(
                label = "No longer installed",
                message = "${state.displayName} is not on this device any more. Wanci keeps its limit " +
                    "in case you install it again, and no longer counts time in it.",
            )
            Spacer(Modifier.height(spacing.sm))
        }

        AppSettingsStatRow(label = "Used today", value = formatUsageMinutes(state.usageMinutes))
        AppSettingsStatRow(label = "Daily limit", value = formatLimitMinutes(state.limitMinutes))
        if (row != null) {
            AppSettingsStatRow(label = "Status", value = appStatusLabel(row), emphasised = true)
            // The bar is a redundant second channel: the row above states the same thing in words,
        // so it is hidden from the screen reader rather than read out as a bare percentage.
            LinearProgressIndicator(
                progress = { row.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
            )
        }
        Spacer(Modifier.height(spacing.md))

        WanciOutlinedButton(
            onClick = onChangeLimit,
            enabled = !state.removing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Change daily limit")
        }
        Spacer(Modifier.height(spacing.md))

        // The row is a clickable-free control strip: the switch carries the state, and the text
        // beside it states the same state in words, so nothing is carried by the thumb's position
        // alone. The row is floored at the token height to clear the 48dp minimum target.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = WanciTokens.controls.minRowHeight),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Monitoring", style = MaterialTheme.typography.titleSmall)
                    Text(
                        // Also the reason a pause stops counting rather than just warning: a paused
                        // app is not measured at all, so its usage reading is a frozen number.
                        if (state.paused) {
                            "Paused — time in this app is not being counted"
                        } else {
                            "On — Wanci counts time in this app"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = !state.paused,
                    // Disabled while a write is in flight rather than left enabled and inert: the
                    // next press would be refused, and a switch that ignores you is worse than one
                    // that says it is busy.
                    enabled = state.canToggleMonitoring,
                    onCheckedChange = onSetMonitoringEnabled,
                )
            }
        }
        if (state.updateFailed) {
            AppSettingsNotice(
                label = "Not changed",
                // The verb comes from what was *asked for*, not from the row: a write that did not
                // land leaves the row as it was, so the row cannot name which action failed.
                message = "Wanci couldn't ${if (state.requestedEnabled == false) "pause" else "resume"} " +
                    "${state.displayName}. Nothing was changed — try again.",
            )
            Spacer(Modifier.height(spacing.sm))
        }
        Spacer(Modifier.height(spacing.md))

        AppSettingsRemoval(
            state = state,
            onRequestRemove = onRequestRemove,
            onCancelRemove = onCancelRemove,
            onConfirmRemove = onConfirmRemove,
        )

        Spacer(Modifier.height(spacing.lg))
        WanciOutlinedButton(onClick = onBack, enabled = !state.removing, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

/**
 * The removal, as two steps.
 *
 * The confirmation names what actually happens and nothing more: `removeMonitoredApp` deletes the
 * monitored row and leaves today's recorded minutes in place, so the copy says the app leaves the
 * list and the recording stops — and does not claim the recorded time is deleted, which it is not.
 */
@Composable
private fun AppSettingsRemoval(
    state: AppSettingsUiState,
    onRequestRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onConfirmRemove: () -> Unit,
) {
    val spacing = WanciTokens.spacing

    if (!state.removeRequested) {
        WanciOutlinedButton(
            onClick = onRequestRemove,
            enabled = state.canRequestRemove,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Remove from my apps")
        }
        return
    }

    AppSettingsNotice(
        label = "Remove this app?",
        message = if (state.showsUninstallNotice) {
            "${state.displayName} is already uninstalled. Removing it clears its limit and stops " +
                "Wanci watching it."
        } else {
            "Wanci will stop counting time in ${state.displayName} and take it off your list. " +
                "The time recorded today is kept."
        },
    )
    if (state.removeFailed) {
        Spacer(Modifier.height(spacing.xs))
        AppSettingsNotice(
            label = "Not removed",
            message = "Wanci couldn't remove ${state.displayName}. Nothing was changed — try again.",
        )
    }
    Spacer(Modifier.height(spacing.sm))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        // The label is the in-flight signal: the buttons are disabled *and* say what is happening,
        // so a second tap is refused and the wait is not silent.
        WanciButton(
            onClick = onConfirmRemove,
            enabled = state.canConfirmRemove,
            modifier = Modifier.weight(1f),
        ) {
            Text(if (state.removing) "Removing…" else "Yes, remove")
        }
        WanciOutlinedButton(
            onClick = onCancelRemove,
            enabled = !state.removing,
            modifier = Modifier.weight(1f),
        ) {
            Text("Keep it")
        }
    }
}

/**
 * One label and one value. [emphasised] is typography, not colour: the status line is the row's
 * conclusion, so it is set in the label style the rest of the app uses for a conclusion.
 */
@Composable
private fun AppSettingsStatRow(label: String, value: String, emphasised: Boolean = false) {
    val spacing = WanciTokens.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = if (emphasised) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * A label plus its message. Two pieces of text, not a tinted box: a notice that is only a colour
 * disappears for a user who cannot see it, and the label is what says which kind of failure this is.
 */
@Composable
private fun AppSettingsNotice(label: String, message: String) {
    val spacing = WanciTokens.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(spacing.xxs))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A title, an explanation and the one action that helps. Used for all three "there is nothing to
 * edit" states — a missing argument, a failed read and an app that is not monitored — because they
 * are the same shape: say what happened, then offer the way out.
 */
@Composable
private fun AppSettingsMessage(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val spacing = WanciTokens.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(spacing.xs))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.md))
        WanciOutlinedButton(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

/**
 * Placeholder rows rather than a spinner: the settings' shape is known, so the screen does not jump
 * when the read lands. The whole placeholder carries one content description instead of being
 * announced bar by bar, and `mergeDescendants` is what makes that stick.
 */
@Composable
private fun AppSettingsSkeleton() {
    val spacing = WanciTokens.spacing
    val transition = rememberInfiniteTransition(label = "app-settings-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "app-settings-skeleton-alpha",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Loading app settings" },
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SkeletonBar(widthFraction = 0.6f, height = 22.dp, alpha = alpha)
        SkeletonBar(widthFraction = 0.4f, height = 14.dp, alpha = alpha)
        SkeletonBar(widthFraction = 0.9f, height = 12.dp, alpha = alpha)
        SkeletonBar(widthFraction = 0.9f, height = 12.dp, alpha = alpha)
        SkeletonBar(widthFraction = 0.5f, height = 12.dp, alpha = alpha)
        SkeletonBar(widthFraction = 1f, height = 48.dp, alpha = alpha)
        SkeletonBar(widthFraction = 0.7f, height = 48.dp, alpha = alpha)
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: Dp, alpha: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .alpha(alpha)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraSmall,
            ),
    )
}
