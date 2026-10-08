/**
 * Wanci design system — monochrome, border-based, no gradients and no shadows.
 *
 * Everything a screen needs is centralised here: color, type, shape, spacing and
 * control sizing. Screens read tokens from [androidx.compose.material3.MaterialTheme],
 * [WanciTokens.spacing] and [WanciTokens.controls] instead of hard-coding dp values or
 * picking colours.
 *
 * The palette is intentionally achromatic (warm neutrals only) per the approved design
 * spec. **All 48 [androidx.compose.material3.ColorScheme] roles are authored explicitly in
 * both [MonochromeLight] and [MonochromeDark]** — an omitted role silently falls back to
 * Material3's default purple ramp, which would put a hue into a monochrome app the first
 * time a screen touched it. Every text/surface pair below is verified at >= 4.5:1 contrast
 * and the hairline border at >= 3:1 against `surface`/`background` (where a bordered control
 * actually sits); against the deepest container fills `outlineVariant` measures 2.65–2.80:1,
 * so a border is never the sole carrier of meaning. See `task-8-report.md` for the measured
 * ratios and the exact worst cases.
 */
package com.septaalfauzan.wanci.presentation.theme

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
 * Mirrors a CSS custom-property scale: `WanciTokens.spacing.md` instead of a magic `16.dp`.
 */
@Immutable
class WanciSpacing(private val unit: Dp = 4.dp) {
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
        val Default = WanciSpacing()
    }
}

/**
 * Control sizing tokens. Material3's stock `Button`/`OutlinedButton`/`TextButton` are
 * 40dp tall, below the 48dp minimum interactive target; the `Wanci*` buttons at the
 * bottom of this file apply these instead.
 *
 * Deliberately **not** annotated `@Immutable`: [buttonContentPadding] and
 * [textButtonContentPadding] hold a [PaddingValues], which Compose's stability inference
 * does not treat as a stable type, so an immutable contract here would be a lie.
 */
class WanciControls(
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
        val Default = WanciControls()
    }
}

val LocalWanciSpacing = staticCompositionLocalOf { WanciSpacing.Default }
val LocalWanciControls = staticCompositionLocalOf { WanciControls.Default }

/** Token accessors, e.g. `WanciTokens.spacing.md`. Not a theme composable. */
object WanciTokens {
    val spacing: WanciSpacing
        @Composable @ReadOnlyComposable get() = LocalWanciSpacing.current

    val controls: WanciControls
        @Composable @ReadOnlyComposable get() = LocalWanciControls.current
}

/**
 * Light scheme: off-white background, near-black text, hairline borders as the only
 * separator. Neutrals carry a slight warm cast so surfaces do not read as blue-grey.
 */
val MonochromeLight = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDEDEA),
    onPrimaryContainer = Color(0xFF111111),
    inversePrimary = Color(0xFFF2F2EF),
    secondary = Color(0xFF3A3A38),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2F2EF),
    onSecondaryContainer = Color(0xFF1A1A19),
    tertiary = Color(0xFF4A4A47),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAFAF7),
    onTertiaryContainer = Color(0xFF1A1A19),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEDEDE9),
    onSurfaceVariant = Color(0xFF55554F),
    surfaceTint = Color(0x00000000),
    inverseSurface = Color(0xFF1A1A19),
    inverseOnSurface = Color(0xFFF2F2EF),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE7E7E3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAF8),
    surfaceContainer = Color(0xFFF3F3F0),
    surfaceContainerHigh = Color(0xFFEDEDEA),
    surfaceContainerHighest = Color(0xFFE7E7E3),
    outline = Color(0xFF111111),
    outlineVariant = Color(0xFF8A8A84),
    // Achromatic on purpose: the design spec forbids non-monochrome accents, so a
    // destructive state must be carried by its label and icon, never by hue alone.
    error = Color(0xFF1A1A19),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFE5E5E1),
    onErrorContainer = Color(0xFF1A1A19),
    scrim = Color(0xFF000000),
    // Fixed roles (theme-invariant elements: FABs, badges, docked bars). Material3 would
    // otherwise default these to its purple ramp, so each one is mapped onto this
    // scheme's own warm-neutral ladder: *Fixed == the matching *Container tone,
    // *FixedDim == one step down, on*Fixed == the matching on*Container colour.
    primaryFixed = Color(0xFFEDEDEA),
    primaryFixedDim = Color(0xFFE7E7E3),
    onPrimaryFixed = Color(0xFF111111),
    onPrimaryFixedVariant = Color(0xFF55554F),
    secondaryFixed = Color(0xFFF2F2EF),
    secondaryFixedDim = Color(0xFFE7E7E3),
    onSecondaryFixed = Color(0xFF1A1A19),
    onSecondaryFixedVariant = Color(0xFF55554F),
    tertiaryFixed = Color(0xFFFAFAF7),
    tertiaryFixedDim = Color(0xFFE7E7E3),
    onTertiaryFixed = Color(0xFF1A1A19),
    onTertiaryFixedVariant = Color(0xFF55554F),
)

/** Dark scheme: structural counterpart of [MonochromeLight], not merely dimmed. */
val MonochromeDark = darkColorScheme(
    primary = Color(0xFFF5F5F2),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF2A2A28),
    onPrimaryContainer = Color(0xFFF5F5F2),
    inversePrimary = Color(0xFF1A1A19),
    secondary = Color(0xFFC9C9C3),
    onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF232321),
    onSecondaryContainer = Color(0xFFE7E7E3),
    tertiary = Color(0xFFA8A8A2),
    onTertiary = Color(0xFF111111),
    tertiaryContainer = Color(0xFF1F1F1E),
    onTertiaryContainer = Color(0xFFDEDEDA),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFF5F5F2),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFF5F5F2),
    surfaceVariant = Color(0xFF232321),
    onSurfaceVariant = Color(0xFFA3A39D),
    surfaceTint = Color(0x00000000),
    inverseSurface = Color(0xFFF2F2EF),
    inverseOnSurface = Color(0xFF1A1A19),
    surfaceBright = Color(0xFF242422),
    surfaceDim = Color(0xFF0A0A0A),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF121211),
    surfaceContainer = Color(0xFF1A1A19),
    surfaceContainerHigh = Color(0xFF242422),
    surfaceContainerHighest = Color(0xFF2E2E2B),
    outline = Color(0xFFF5F5F2),
    outlineVariant = Color(0xFF6E6E68),
    error = Color(0xFFF5F5F2),
    onError = Color(0xFF111111),
    errorContainer = Color(0xFF2E2E2B),
    onErrorContainer = Color(0xFFF5F5F2),
    scrim = Color(0xFF000000),
    // Fixed roles: same warm-neutral mapping as the light scheme, stepped onto the dark
    // ladder. surfaceBright above is deliberately held at surfaceContainerHigh rather
    // than lifted — the extra lightness had no light-side counterpart and pushed
    // onSurfaceVariant to the system's tightest text margin.
    primaryFixed = Color(0xFF2A2A28),
    primaryFixedDim = Color(0xFF1A1A19),
    onPrimaryFixed = Color(0xFFF5F5F2),
    onPrimaryFixedVariant = Color(0xFFA3A39D),
    secondaryFixed = Color(0xFF232321),
    secondaryFixedDim = Color(0xFF1A1A19),
    onSecondaryFixed = Color(0xFFE7E7E3),
    onSecondaryFixedVariant = Color(0xFFA3A39D),
    tertiaryFixed = Color(0xFF1F1F1E),
    tertiaryFixedDim = Color(0xFF1A1A19),
    onTertiaryFixed = Color(0xFFDEDEDA),
    onTertiaryFixedVariant = Color(0xFFA3A39D),
)

/** Tight tracking on display/headline, generous leading on body copy. */
private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double) = TextStyle(
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

val WanciTypography = Typography(
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
val WanciShapes = Shapes(
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
    spacing: WanciSpacing = WanciSpacing.Default,
    controls: WanciControls = WanciControls.Default,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalWanciSpacing provides spacing,
        LocalWanciControls provides controls,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) MonochromeDark else MonochromeLight,
            typography = WanciTypography,
            shapes = WanciShapes,
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
fun WanciButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = WanciTokens.controls.buttonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = WanciTokens.controls.minButtonHeight)
            .widthIn(min = WanciTokens.controls.minTouchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun WanciOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = WanciTokens.controls.buttonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    // Take the brush from the stock border so the enabled/disabled colour logic stays
    // Material3's, but drive the width from our own token.
    val border = ButtonDefaults.outlinedButtonBorder(enabled)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = WanciTokens.controls.minButtonHeight)
            .widthIn(min = WanciTokens.controls.minTouchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(),
        border = BorderStroke(WanciTokens.controls.borderWidth, border.brush),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun WanciTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = WanciTokens.controls.textButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = WanciTokens.controls.minButtonHeight)
            .widthIn(min = WanciTokens.controls.minTouchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.textButtonColors(),
        contentPadding = contentPadding,
        content = content,
    )
}
