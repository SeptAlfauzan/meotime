/**
 * Meotime design system — warm break-time palette, border-based, no gradients and no shadows.
 *
 * Everything a screen needs is centralised here: color, type, shape, spacing and
 * control sizing. Screens read tokens from [androidx.compose.material3.MaterialTheme],
 * [MeotimeTokens.spacing] and [MeotimeTokens.controls] instead of hard-coding dp values or
 * picking colours.
 *
 * The palette is a warm "break time" system: cream backgrounds, deep-brown text, orange
 * progress/accent and warm outlines in light mode, mirrored by espresso surfaces and
 * cream/peach text in dark mode. **All 48 [androidx.compose.material3.ColorScheme] roles
 * are authored explicitly in both [MonochromeLight] and [MonochromeDark]** — an omitted
 * role silently falls back to Material3's default purple ramp, which would put an
 * unintended hue into the app the first time a screen touched it. Every text/surface pair
 * below is authored at >= 4.5:1 contrast and the structural `outline` border at >= 3:1
 * against `surface`/`background` (where a bordered control actually sits); the lighter
 * `outlineVariant` hairline measures 1.99:1 (light) and 2.50:1 (dark) against `surface`,
 * so a divider is never the sole carrier of meaning. See `task-1-report.md` for the
 * measured ratios and exact worst cases.
 */
package com.septaalfauzan.meotime.presentation.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 4dp-based spacing scale — the single source of truth for rhythm in every screen.
 * Mirrors a CSS custom-property scale: `MeotimeTokens.spacing.md` instead of a magic `16.dp`.
 */
@Immutable
class MeotimeSpacing(private val unit: Dp = 4.dp) {
    val xxs: Dp get() = unit
    val xs: Dp get() = unit * 2
    val sm: Dp get() = unit * 3
    val md: Dp get() = unit * 4
    val lg: Dp get() = unit * 6
    val xl: Dp get() = unit * 8
    val xxl: Dp get() = unit * 12

    /** Default horizontal/vertical screen gutter. */
    val screen: Dp get() = lg

    companion object {
        val Default = MeotimeSpacing()
    }
}

/**
 * Control sizing tokens. Material3's stock `Button`/`OutlinedButton`/`TextButton` are
 * 40dp tall, below the 48dp minimum interactive target; the `Meotime*` buttons at the
 * bottom of this file apply these instead.
 *
 * Deliberately **not** annotated `@Immutable`: [buttonContentPadding] and
 * [textButtonContentPadding] hold a [PaddingValues], which Compose's stability inference
 * does not treat as a stable type, so an immutable contract here would be a lie.
 */
class MeotimeControls(
    val minTouchTarget: Dp = 48.dp,
    val minButtonHeight: Dp = 48.dp,
    /** Minimum height for a list/settings row. Used by the Task 9 screens. */
    val minRowHeight: Dp = 56.dp,
    val borderWidth: Dp = 1.dp,
    val buttonContentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    /** Text buttons are label-only, so they get a tighter gutter than the filled buttons. */
    val textButtonContentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
) {
    companion object {
        val Default = MeotimeControls()
    }
}

val LocalMeotimeSpacing = staticCompositionLocalOf { MeotimeSpacing.Default }
val LocalMeotimeControls = staticCompositionLocalOf { MeotimeControls.Default }

/** Token accessors, e.g. `MeotimeTokens.spacing.md`. Not a theme composable. */
object MeotimeTokens {
    val spacing: MeotimeSpacing
        @Composable @ReadOnlyComposable get() = LocalMeotimeSpacing.current

    val controls: MeotimeControls
        @Composable @ReadOnlyComposable get() = LocalMeotimeControls.current
}

/**
 * Warm light scheme derived from "Time's up – break time" reference:
 * cream background, deep-brown text, orange accent/progress, warm outlines.
 * All 48 roles authored explicitly to avoid Material default hues.
 */
val MonochromeLight = lightColorScheme(
    primary = Color(0xFF3B2620),
    onPrimary = Color(0xFFFFF8F0),
    primaryContainer = Color(0xFFF6DDBE),
    onPrimaryContainer = Color(0xFF3B2620),
    inversePrimary = Color(0xFFFFC79A),
    secondary = Color(0xFF6B4A3D),
    onSecondary = Color(0xFFFFF8F0),
    secondaryContainer = Color(0xFFF6E6D6),
    onSecondaryContainer = Color(0xFF3B2620),
    tertiary = Color(0xFFFF8F45),
    onTertiary = Color(0xFF3B2620),
    tertiaryContainer = Color(0xFFF6DDBE),
    onTertiaryContainer = Color(0xFF3B2620),
    background = Color(0xFFFDF3E3),
    onBackground = Color(0xFF3B2620),
    surface = Color(0xFFFDF8F0),
    onSurface = Color(0xFF3B2620),
    surfaceVariant = Color(0xFFF6E6D6),
    onSurfaceVariant = Color(0xFF6B4A3D),
    surfaceTint = Color(0x00000000),
    inverseSurface = Color(0xFF3B2620),
    inverseOnSurface = Color(0xFFFDF3E3),
    surfaceBright = Color(0xFFFFFBF6),
    surfaceDim = Color(0xFFF3E4D1),
    surfaceContainerLowest = Color(0xFFFFFBF6),
    surfaceContainerLow = Color(0xFFFDF3E3),
    surfaceContainer = Color(0xFFFAEAD8),
    surfaceContainerHigh = Color(0xFFF6DDBE),
    surfaceContainerHighest = Color(0xFFF1D3B0),
    outline = Color(0xFF8A6A5A),
    outlineVariant = Color(0xFFC9A996),
    error = Color(0xFF8C2F2F),
    onError = Color(0xFFFFF8F0),
    errorContainer = Color(0xFFF6D8D4),
    onErrorContainer = Color(0xFF5C1D1D),
    scrim = Color(0xFF000000),
    primaryFixed = Color(0xFFF6DDBE),
    primaryFixedDim = Color(0xFFF1D3B0),
    onPrimaryFixed = Color(0xFF3B2620),
    onPrimaryFixedVariant = Color(0xFF6B4A3D),
    secondaryFixed = Color(0xFFF6E6D6),
    secondaryFixedDim = Color(0xFFEED8C4),
    onSecondaryFixed = Color(0xFF3B2620),
    onSecondaryFixedVariant = Color(0xFF6B4A3D),
    tertiaryFixed = Color(0xFFFFD8B8),
    tertiaryFixedDim = Color(0xFFFFB07A),
    onTertiaryFixed = Color(0xFF3B2620),
    onTertiaryFixedVariant = Color(0xFF6B4A3D),
)

/** Warm dark scheme counterpart: deep espresso surfaces, cream text, orange accent. */
val MonochromeDark = darkColorScheme(
    primary = Color(0xFFFFE6D2),
    onPrimary = Color(0xFF3B2620),
    primaryContainer = Color(0xFF5A3A2E),
    onPrimaryContainer = Color(0xFFFFF1E6),
    inversePrimary = Color(0xFF3B2620),
    secondary = Color(0xFFE7C9B8),
    onSecondary = Color(0xFF2A1A15),
    secondaryContainer = Color(0xFF4A332B),
    onSecondaryContainer = Color(0xFFF6E6D6),
    tertiary = Color(0xFFFF9B5C),
    onTertiary = Color(0xFF2A1A15),
    tertiaryContainer = Color(0xFF5A3623),
    onTertiaryContainer = Color(0xFFFFE6D2),
    background = Color(0xFF1C1411),
    onBackground = Color(0xFFF8EBDD),
    surface = Color(0xFF1C1411),
    onSurface = Color(0xFFF8EBDD),
    surfaceVariant = Color(0xFF3A2A24),
    onSurfaceVariant = Color(0xFFD8C0B2),
    surfaceTint = Color(0x00000000),
    inverseSurface = Color(0xFFF8EBDD),
    inverseOnSurface = Color(0xFF2A1A15),
    surfaceBright = Color(0xFF3A2C27),
    surfaceDim = Color(0xFF1C1411),
    surfaceContainerLowest = Color(0xFF140E0C),
    surfaceContainerLow = Color(0xFF221815),
    surfaceContainer = Color(0xFF2A1E1A),
    surfaceContainerHigh = Color(0xFF352621),
    surfaceContainerHighest = Color(0xFF412F29),
    outline = Color(0xFFB99684),
    outlineVariant = Color(0xFF6B5146),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF3A0A08),
    errorContainer = Color(0xFF6B221D),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
    primaryFixed = Color(0xFFF6DDBE),
    primaryFixedDim = Color(0xFFE6C29E),
    onPrimaryFixed = Color(0xFF2A1A15),
    onPrimaryFixedVariant = Color(0xFF5A3A2E),
    secondaryFixed = Color(0xFFF6E6D6),
    secondaryFixedDim = Color(0xFFD8C0B2),
    onSecondaryFixed = Color(0xFF2A1A15),
    onSecondaryFixedVariant = Color(0xFF4A332B),
    tertiaryFixed = Color(0xFFFFD8B8),
    tertiaryFixedDim = Color(0xFFFFB07A),
    onTertiaryFixed = Color(0xFF2A1A15),
    onTertiaryFixedVariant = Color(0xFF5A3623),
)

/** Tight tracking on display/headline, generous leading on body copy. */
private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double) = TextStyle(
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

val MeotimeTypography = Typography(
    displayLarge = style(57, 64, FontWeight.Normal, -0.25),
    displayMedium = style(45, 52, FontWeight.Normal, -0.25),
    displaySmall = style(36, 44, FontWeight.Normal, -0.25),
    headlineLarge = style(32, 40, FontWeight.Normal, -0.4),
    headlineMedium = style(28, 36, FontWeight.Normal, -0.3),
    headlineSmall = style(24, 32, FontWeight.Normal, -0.2),
    titleLarge = style(22, 28, FontWeight.Normal, -0.2),
    titleMedium = style(16, 24, FontWeight.Medium, 0.15),
    titleSmall = style(14, 20, FontWeight.Medium, 0.1),
    bodyLarge = style(16, 26, FontWeight.Normal, 0.15),
    bodyMedium = style(14, 22, FontWeight.Normal, 0.25),
    bodySmall = style(12, 18, FontWeight.Normal, 0.4),
    labelLarge = style(14, 20, FontWeight.Medium, 0.1),
    labelMedium = style(12, 16, FontWeight.Medium, 0.5),
    labelSmall = style(11, 16, FontWeight.Medium, 0.5),
)

/** Restrained corner hierarchy — not everything is a pill. */
val MeotimeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Dark-mode override. `null` means "follow the system"; hosts that expose an in-app
 * toggle wrap [MonochromeTheme] in
 * `CompositionLocalProvider(LocalDarkTheme provides true) { MonochromeTheme { ... } }`.
 */
val LocalDarkTheme = compositionLocalOf<Boolean?> { null }

@Composable
fun MonochromeTheme(
    darkTheme: Boolean = LocalDarkTheme.current ?: isSystemInDarkTheme(),
    spacing: MeotimeSpacing = MeotimeSpacing.Default,
    controls: MeotimeControls = MeotimeControls.Default,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalMeotimeSpacing provides spacing,
        LocalMeotimeControls provides controls,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) MonochromeDark else MonochromeLight,
            typography = MeotimeTypography,
            shapes = MeotimeShapes,
        ) {
            // A `Surface` provides its own content color; a `Box(Modifier.background(...))` does
            // not. Any `Text`, `Icon` or `contentColorFor` outside one therefore falls through to
            // `LocalContentColor`'s own default, which is `Color.Black` and knows nothing about
            // this scheme. In light mode that reads as near-black text on near-white and looks
            // deliberate; in dark mode it is black on #0A0A0A, i.e. invisible. Seeding the
            // content color here means a screen gets themed text from the theme itself rather
            // than from whichever container it happened to be nested in, and a `Surface` further
            // down still shadows it with the role matching its own color.
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            ) {
                content()
            }
        }
    }
}

@Composable
fun MeotimeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = MeotimeTokens.controls.buttonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = MeotimeTokens.controls.minButtonHeight)
            .widthIn(min = MeotimeTokens.controls.minTouchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun MeotimeOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = MeotimeTokens.controls.buttonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    // Take the brush from the stock border so the enabled/disabled colour logic stays
    // Material3's, but drive the width from our own token.
    val border = ButtonDefaults.outlinedButtonBorder(enabled)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = MeotimeTokens.controls.minButtonHeight)
            .widthIn(min = MeotimeTokens.controls.minTouchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(),
        border = BorderStroke(MeotimeTokens.controls.borderWidth, border.brush),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun MeotimeTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = MeotimeTokens.controls.textButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = MeotimeTokens.controls.minButtonHeight)
            .widthIn(min = MeotimeTokens.controls.minTouchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.textButtonColors(),
        contentPadding = contentPadding,
        content = content,
    )
}
