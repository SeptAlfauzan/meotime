package com.septaalfauzan.wanci.presentation.addapp

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.septaalfauzan.wanci.domain.InstalledApp
import com.septaalfauzan.wanci.presentation.theme.WanciButton
import com.septaalfauzan.wanci.presentation.theme.WanciOutlinedButton
import com.septaalfauzan.wanci.presentation.theme.WanciTokens

/**
 * Pick the app to start monitoring.
 *
 * The list is the *monitorable* set — installed apps that are not monitored yet — so a row here is
 * always an action the repository will accept. Selection is single: one app per limit, and the next
 * step is that app's limit.
 *
 * All three non-content states are rendered rather than left blank, because "nothing to show" and
 * "not answered yet" look identical on an empty screen: a skeleton while the monitored set is
 * still being read (it decides which rows exist), a retry when that read failed, and a zero state
 * that distinguishes "your search matched nothing" from "there is nothing left to add".
 *
 * Every value comes from [WanciTokens], and every interactive element is a real control: the rows
 * are `selectable` at the token row height, the search field is a Material text field, and the
 * primary action is a [WanciButton] whose disabled state is the guard on [onContinue].
 */
@Composable
fun AddAppScreen(
    viewModel: AddAppViewModel,
    onContinue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val spacing = WanciTokens.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.screen),
    ) {
        Text("Add an app", style = MaterialTheme.typography.titleLarge)
        Text(
            "Pick an app to monitor. Wanci counts how long you use it each day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.md))

        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::updateQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search apps") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(spacing.sm))

        when {
            state.loading -> AddAppSkeleton(modifier = Modifier.weight(1f))
            state.error -> AddAppError(onRetry = viewModel::retry, modifier = Modifier.weight(1f))
            state.apps.isEmpty() -> AddAppEmpty(
                query = state.query,
                hasPlatformApps = state.hasPlatformApps,
                modifier = Modifier.weight(1f),
            )
            else -> AddAppList(
                apps = state.apps,
                selected = state.selected,
                onSelect = viewModel::select,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(spacing.md))
        WanciButton(
            onClick = { viewModel.confirm(onContinue) },
            enabled = state.canConfirm,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun AddAppList(
    apps: List<InstalledApp>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        // `selectableGroup` is what makes the rows announce as one radio group rather than as N
        // unrelated options: without it a screen reader reaches each row with no way to know that
        // choosing it deselects the others.
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(WanciTokens.spacing.xs),
    ) {
        items(apps, key = { it.packageName }) { app ->
            AddAppRow(
                app = app,
                selected = app.packageName == selected,
                onClick = { onSelect(app.packageName) },
            )
        }
    }
}

/**
 * One app. The row is the control, not the dot: it carries `Role.RadioButton` and the selected
 * state through `selectable`, so a screen reader or switch-control user reaches the whole target
 * instead of a 48dp dot inside a larger unlabelled box.
 *
 * Selection is never carried by colour alone. The radio mark is one channel, a "Selected" label is
 * the second, and the row's own selected state is the third.
 */
@Composable
private fun AddAppRow(
    app: InstalledApp,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val spacing = WanciTokens.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WanciTokens.controls.minRowHeight)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = spacing.xs, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Decorative: the row owns the click and the state, so a second onClick here would make
        // one tap fire the selection twice and add a duplicate control to the focus order.
        RadioButton(selected = selected, onClick = null, modifier = Modifier.clearAndSetSemantics { })
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = spacing.sm),
        ) {
            Text(app.appName, style = MaterialTheme.typography.titleMedium)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Text("Selected", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Placeholder rows rather than a spinner: the list is the screen's main content, so its shape is
 * known and the screen does not jump when the answer lands. The whole placeholder carries one
 * content description instead of being announced row by row, and `mergeDescendants` is what makes
 * that stick — a `contentDescription` on the container alone would leave the eight decorative bars
 * in the tree as unlabelled nodes, which is what a screen reader would then offer the user.
 */
@Composable
private fun AddAppSkeleton(modifier: Modifier = Modifier) {
    val spacing = WanciTokens.spacing
    val transition = rememberInfiniteTransition(label = "add-app-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "add-app-skeleton-alpha",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Loading installed apps" },
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        repeat(4) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = WanciTokens.controls.minRowHeight)
                    .alpha(alpha),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                SkeletonBar(widthFraction = 0.5f, height = 16.dp)
                SkeletonBar(widthFraction = 0.8f, height = 12.dp)
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

/**
 * Three different zeroes, told apart by [query] and [hasPlatformApps]: a search that matched
 * nothing is the user's own filter and is answered by clearing it, a device with no monitorable app
 * is a platform fact with nothing to retry, and "nothing left to add" means the monitored set
 * already covers every installed app.
 *
 * The first two are separated because collapsing them would tell a user with no launchable apps that
 * "every app on this device is already monitored" — a claim about their monitoring, and one that
 * makes an unfixable state look like something they can undo by deleting a limit.
 */
@Composable
private fun AddAppEmpty(
    query: String,
    hasPlatformApps: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = WanciTokens.spacing
    val searching = query.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            searching -> {
                Text("No apps match \"$query\"", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(spacing.xs))
                Text(
                    "Check the spelling, or clear the search to see every app you can monitor.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            !hasPlatformApps -> {
                Text("No apps to monitor", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(spacing.xs))
                Text(
                    "Wanci couldn't find any apps on this device to count. Install one, then try again.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            else -> {
                Text("Nothing left to add", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(spacing.xs))
                Text(
                    "Every app on this device is already monitored.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun AddAppError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = WanciTokens.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't load your apps", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(spacing.xs))
        Text(
            // Says what actually failed: the read that breaks here is the monitored-apps set, not the
            // limits. Reassuring the user about a read nobody made is the kind of copy that makes
            // an error look like a design.
            "Wanci couldn't read which apps are already monitored. Try again.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.md))
        WanciOutlinedButton(onClick = onRetry) {
            Text("Try again")
        }
    }
}
