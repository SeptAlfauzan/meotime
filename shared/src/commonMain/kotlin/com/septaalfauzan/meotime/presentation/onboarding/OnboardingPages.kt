package com.septaalfauzan.meotime.presentation.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.septaalfauzan.meotime.presentation.theme.MeotimeOutlinedButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTextButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens
import meotime.shared.generated.resources.Res
import meotime.shared.generated.resources.onboarding_permissions
import meotime.shared.generated.resources.onboarding_ready
import meotime.shared.generated.resources.onboarding_welcome
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun WelcomePage(modifier: Modifier = Modifier) {
    OnboardingPageLayout(
        illustration = Res.drawable.onboarding_welcome,
        title = "MeoTime",
        body = "Manage your screen time for your better life.",
        modifier = modifier,
    )
}

@Composable
internal fun PermissionsPage(
    state: OnboardingUiState,
    onRequestUsageAccess: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestNotification: () -> Unit,
    onCheckPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingPageLayout(
        illustration = Res.drawable.onboarding_permissions,
        title = "Permission is required",
        body = "We need some permissions to track your screen time.",
        modifier = modifier,
    ) {
        Spacer(Modifier.height(MeotimeTokens.spacing.lg))
        PermissionRow(
            title = "Usage Access",
            detail = "Measure how long you use apps.",
            granted = state.permissionStatus.usageAccess,
            onClick = onRequestUsageAccess,
        )
        PermissionRow(
            title = "Display over other apps",
            detail = "Show the reminder when you reach a limit.",
            granted = state.permissionStatus.overlay,
            onClick = onRequestOverlay,
        )
        PermissionRow(
            title = "Notifications",
            detail = "Show the reminder while Meotime is not open.",
            granted = state.permissionStatus.notification,
            onClick = onRequestNotification,
        )
        Spacer(Modifier.height(MeotimeTokens.spacing.md))
        MeotimeTextButton(
            onClick = onCheckPermissions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Check permissions")
        }
        if (!state.permissionStatus.allGranted) {
            Spacer(Modifier.height(MeotimeTokens.spacing.sm))
            Text(
                text = "Grant all permissions to continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun ReadyPage(
    state: OnboardingUiState,
    modifier: Modifier = Modifier,
) {
    OnboardingPageLayout(
        illustration = Res.drawable.onboarding_ready,
        title = "All set",
        body = "Permission is granted. Let's make the most of MeoTime.",
        modifier = modifier,
    ) {
        if (state.saveFailed) {
            Spacer(Modifier.height(MeotimeTokens.spacing.lg))
            OnboardingNotice(
                label = "Not saved",
                message = "Meotime couldn't finish setting up. Nothing was changed — try again.",
            )
        }
    }
}

@Composable
private fun OnboardingPageLayout(
    illustration: DrawableResource,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = MeotimeTokens.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OnboardingIllustration(illustration)
            Spacer(Modifier.height(MeotimeTokens.spacing.md))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(MeotimeTokens.spacing.sm))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            content()
        }
    }
}

@Composable
private fun OnboardingIllustration(illustration: DrawableResource) {
    Image(
        painter = painterResource(illustration),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp),
    )
}

@Composable
internal fun OnboardingPageIndicator(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics {
            contentDescription = "Page ${currentPage + 1} of $pageCount"
        },
        horizontalArrangement = Arrangement.spacedBy(MeotimeTokens.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                modifier = Modifier
                    .height(MeotimeTokens.spacing.xs)
                    .width(if (selected) MeotimeTokens.spacing.lg else MeotimeTokens.spacing.xs)
                    .background(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun OnboardingNotice(label: String, message: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(MeotimeTokens.spacing.xxs))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MeotimeTokens.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(MeotimeTokens.spacing.sm))
        if (granted) {
            Text(
                text = "\u2713 Granted",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics {
                    contentDescription = "$title granted"
                },
            )
        } else {
            MeotimeOutlinedButton(
                onClick = onClick,
                modifier = Modifier.semantics {
                    contentDescription = "Grant $title"
                },
            ) {
                Text("Grant")
            }
        }
    }
}
