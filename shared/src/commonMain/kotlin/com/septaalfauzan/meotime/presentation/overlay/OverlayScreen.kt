package com.septaalfauzan.meotime.presentation.overlay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.septaalfauzan.meotime.presentation.format.formatUsage
import com.septaalfauzan.meotime.presentation.theme.MeotimeButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeOutlinedButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens
import io.github.alexzhirkevich.compottie.LottieComposition
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.animateLottieCompositionAsState
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import meotime.shared.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * The limit notice, composable on top of whatever app the user is actually in.
 *
 * The root paints `colorScheme.background`, and that fill is load bearing rather than cosmetic.
 * `OverlayManager` adds this as a transparent `TYPE_APPLICATION_OVERLAY` window, so the surface
 * behind these words is an arbitrary third party app, not our own background: an unpainted root
 * drops a headline straight onto whatever the user was looking at, and the hardcoded white the
 * host used to set is only ever legible in the mode it was written for. Reading the scheme is
 * what keeps the notice readable in both, and it keeps the color decision inside the design
 * system instead of duplicated in Android code.
 *
 * The layout mirrors the warm "Time's up - break time" reference: centered title, a dynamic
 * subtitle naming the app, a `usage / limit` progress bar, then the leave/manage actions. Every
 * color is a theme role so the screen tracks the warm light/dark schemes rather than hardcoding
 * the reference hexes, and the progress is derived from the live `usageSeconds`/`limitSeconds`
 * the monitor hands in.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun OverlayScreen(
    appName: String,
    usageSeconds: Long,
    limitSeconds: Long,
    onLeave: () -> Unit,
    onManage: () -> Unit,
    sleepingCatComposition: LottieComposition? = null,
) {
    val spacing = MeotimeTokens.spacing
    val progress = if (limitSeconds > 0) {
        (usageSeconds.toFloat() / limitSeconds.toFloat()).coerceIn(0f, 1f)
    } else {
        1f
    }
    val loadedSleepingCat by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            Res.readBytes("files/sleeping_cat.json").decodeToString(),
        )
    }
    val sleepingCat = sleepingCatComposition
        ?: SleepingCatOverlayAnimation.composition
        ?: loadedSleepingCat
    val sleepingCatProgress by animateLottieCompositionAsState(
        composition = sleepingCat,
        iterations = Int.MAX_VALUE,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = rememberLottiePainter(
                    composition = sleepingCat,
                    progress = { sleepingCatProgress },
                ),
                contentDescription = "Sleeping cat",
                modifier = Modifier.size(width = 300.dp, height = 268.dp),
            )
            Text(
                text = "Time\u2019s up",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 36.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.sm))
            Text(
                text = "You\u2019ve reached your daily limit for $appName. Take a little break \u2014 you\u2019ve earned it.",
                modifier = Modifier.width(300.dp),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 17.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Box(
                    modifier = Modifier
                        .width(220.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(10.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                }
                Text(
                    text = "${formatUsage(usageSeconds)} / ${formatUsage(limitSeconds)}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            Spacer(Modifier.height(spacing.xl))
            MeotimeButton(
                onClick = onLeave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = "Leave app",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Spacer(Modifier.height(spacing.sm))
            MeotimeOutlinedButton(
                onClick = onManage,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                borderWidth = 2.dp,
            ) {
                Text(
                    text = "Manage restriction",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
