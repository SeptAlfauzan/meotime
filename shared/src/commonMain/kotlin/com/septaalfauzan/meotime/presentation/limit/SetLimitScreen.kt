package com.septaalfauzan.meotime.presentation.limit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.septaalfauzan.meotime.presentation.theme.MeotimeButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeOutlinedButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens

/**
 * Set the daily limit for an app, and persist the app itself.
 *
 * This is the destination the add-app step hands its selection to, and the first place in the flow
 * that writes: the row is created *with* the limit the user chose here, because creating it earlier
 * would store a default the user never picked and make this screen's own insert a rejected
 * duplicate.
 *
 * The stepper is the precise control (±1 minute) and the presets are the shortcut; both write the
 * same [SetLimitUiState.minutes], so a value chosen either way is indistinguishable to the save. The
 * stepper stops at both ends instead of staying enabled and inert, and the current value is stated
 * three ways — the number, the value in words, and "Preset: …" or "Custom limit" — so the choice is
 * never carried by the tint of a chip alone.
 *
 * The four non-content states are rendered rather than left blank: a skeleton while the app and its
 * current limit are being read, a retry when that read failed, a message with a way out when the
 * route arrived without a package, and — inside the editor — an explanation for either way a save
 * can fail. Nothing here is a spinner, and every spacing, control, colour and type value comes from
 * [MeotimeTokens] and the theme; the only literals are the skeleton's own bar geometry.
 *
 * The two ways out are separate callbacks because they promise different destinations. [onClose]
 * walks *back* one step, which is true to the missing-argument state's "Go back and pick it from
 * your apps". [onBackToApps] leaves for the *list of apps*, which is where the conflict state says
 * to go: on the dashboard -> add-app -> limit stack, a single pop lands on add-app, the step that
 * sent us here, so a button labelled "Back to my apps" would be pointing at a screen that is not
 * the user's apps.
 */
@Composable
fun SetLimitScreen(
    viewModel: SetLimitViewModel,
    onClose: () -> Unit,
    onBackToApps: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val spacing = MeotimeTokens.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.screen),
    ) {
        when {
            state.missingArgument -> SetLimitMissingArgument(onClose = onClose)
            state.loading -> SetLimitSkeleton()
            state.error -> SetLimitReadError(onRetry = viewModel::retry)
            else -> SetLimitEditor(
                state = state,
                onSetMinutes = viewModel::setMinutes,
                onIncrement = viewModel::increment,
                onDecrement = viewModel::decrement,
                onSave = { viewModel.save(onSaved) },
                onBackToApps = onBackToApps,
            )
        }
    }
}

/**
 * The limit editor: the stepper, the presets, the notices, and the one action that writes.
 *
 * The whole editor locks while a save is in flight ([SetLimitUiState.saving]) — not just the button.
 * A value that can change after the write was read is a value the screen would then be showing next
 * to a limit that was not stored.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetLimitEditor(
    state: SetLimitUiState,
    onSetMinutes: (Int) -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onSave: () -> Unit,
    onBackToApps: () -> Unit,
) {
    val spacing = MeotimeTokens.spacing
    val editing = !state.saving

    // The editor is a fixed column rather than a list, so it is the one screen in this flow that can
    // run out of height — a 200% font scale, or a short window. It scrolls rather than clipping, so
    // the action stays reachable below the fold instead of off the layout.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            if (state.alreadyMonitored) "Change daily limit" else "Set daily limit",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(state.appName, style = MaterialTheme.typography.titleMedium)
        Text(
            state.packageName.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.lg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MeotimeOutlinedButton(
                onClick = onDecrement,
                enabled = editing && state.canDecrement,
                contentPadding = MeotimeTokens.controls.textButtonContentPadding,
            ) {
                // The glyph alone would be announced as a minus sign; the label says what it changes.
                Text("−", modifier = Modifier.semantics { contentDescription = "One minute less" })
            }
            Spacer(Modifier.width(spacing.lg))
            Text(
                state.minutes.toString(),
                style = MaterialTheme.typography.displaySmall,
                // Restates the value in words, which the line below already announces. Describing
                // both would read the limit twice; this one is kept for sight, the line is the
                // single spoken answer.
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.width(spacing.lg))
            MeotimeOutlinedButton(
                onClick = onIncrement,
                enabled = editing && state.canIncrement,
                contentPadding = MeotimeTokens.controls.textButtonContentPadding,
            ) {
                Text("+", modifier = Modifier.semantics { contentDescription = "One minute more" })
            }
        }
        Spacer(Modifier.height(spacing.xs))
        Text(
            "${formatLimitMinutes(state.minutes)} per day",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            // The non-colour carrier of "which of these am I on", for the chips below.
            presetFor(state.minutes)?.let { "Preset: ${formatLimitMinutes(it)}" } ?: "Custom limit",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(spacing.lg))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            LIMIT_PRESETS.forEach { preset ->
                MeotimeOutlinedButton(
                    onClick = { onSetMinutes(preset) },
                    enabled = editing,
                    contentPadding = MeotimeTokens.controls.textButtonContentPadding,
                ) {
                    Text(formatLimitMinutes(preset))
                }
            }
        }
        Spacer(Modifier.height(spacing.lg))

        if (state.alreadyMonitored) {
            SetLimitNotice(
                label = "Already monitored",
                message = "${state.appName} is on your list. Saving will change its limit to " +
                    "${formatLimitMinutes(state.minutes)} a day.",
            )
            Spacer(Modifier.height(spacing.sm))
        }
        when {
            state.saveConflict -> {
                SetLimitNotice(
                    label = "Not saved",
                    message = "${state.appName} was added to your list somewhere else, so Meotime left its " +
                        "limit alone. Go to your apps to change it from there.",
                )
                Spacer(Modifier.height(spacing.sm))
                MeotimeOutlinedButton(
                    // Not `onClose`: the label says where this goes, and on the add-app -> limit
                    // stack one pop up lands on add-app, which is not the user's apps.
                    onClick = onBackToApps,
                    contentPadding = MeotimeTokens.controls.textButtonContentPadding,
                ) {
                    Text("Back to my apps")
                }
            }

            state.saveFailed -> {
                SetLimitNotice(
                    label = "Not saved",
                    message = "Meotime couldn't save this limit. Nothing was changed — try again.",
                )
                Spacer(Modifier.height(spacing.sm))
            }
        }

        MeotimeButton(
            onClick = onSave,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // The label is the in-flight signal: the button is disabled *and* says what is
            // happening, so a second tap is refused and the wait is not silent.
            Text(if (state.saving) "Saving…" else "Save")
        }
    }
}

/**
 * A label plus its message. Two pieces of text, not a tinted box: a notice that is only a colour
 * disappears for a user who cannot see it, and the label is what says which of the two failure
 * kinds this is.
 */
@Composable
private fun SetLimitNotice(label: String, message: String) {
    val spacing = MeotimeTokens.spacing
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
 * The route arrived without a package. Nothing can be resolved, retried or saved, so the only
 * honest action is to leave — the message says so rather than rendering an editor that cannot work.
 */
@Composable
private fun SetLimitMissingArgument(onClose: () -> Unit) {
    val spacing = MeotimeTokens.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't open this app", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Meotime doesn't know which app this limit is for. Go back and pick it from your apps.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.md))
        MeotimeOutlinedButton(onClick = onClose) {
            Text("Go back")
        }
    }
}

@Composable
private fun SetLimitReadError(onRetry: () -> Unit) {
    val spacing = MeotimeTokens.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't read this app", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(spacing.xs))
        Text(
            // Names no app on purpose: the error state is a full reset, so there is no label to
            // print, and inventing one would be a guess about which app failed.
            "Meotime couldn't look up this app before setting a limit. Try again.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.md))
        MeotimeOutlinedButton(onClick = onRetry) {
            Text("Try again")
        }
    }
}

/**
 * Placeholder rows rather than a spinner: the editor's shape is known, so the screen does not jump
 * when the read lands. The whole placeholder carries one content description instead of being
 * announced bar by bar, and `mergeDescendants` is what makes that stick.
 */
@Composable
private fun SetLimitSkeleton() {
    val spacing = MeotimeTokens.spacing
    val transition = rememberInfiniteTransition(label = "set-limit-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "set-limit-skeleton-alpha",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Loading app" },
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SkeletonBar(widthFraction = 0.6f, height = 20.dp, alpha = alpha)
        SkeletonBar(widthFraction = 0.4f, height = 14.dp, alpha = alpha)
        SkeletonBar(widthFraction = 0.5f, height = 44.dp, alpha = alpha)
        SkeletonBar(widthFraction = 0.9f, height = 48.dp, alpha = alpha)
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
