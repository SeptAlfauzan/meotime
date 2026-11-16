# Time’s Up Screen & Global Warm Theme Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign `OverlayScreen` to match the warm “Time’s up – break time” reference and replace the app’s global monochrome theme with a warm palette in both light and dark modes.

**Architecture:** Update shared Compose Multiplatform theme tokens/schemes in `Theme.kt` so all screens and the Android overlay host inherit consistent colors; rewrite `OverlayScreen` to a centered warm layout with dynamic subtitle, usage/limit progress block, and styled primary/outlined actions. Keep monitoring/navigation logic unchanged and avoid new assets/fonts.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Material3, Android overlay host (`OverlayManager`), Gradle.

## Global Constraints

- Apply warm palette **globally** in **light and dark** modes (replace `MonochromeLight`/`MonochromeDark` behavior with warm schemes; keep theme entrypoint used app-wide).
- **All 48 Material3 `ColorScheme` roles must be explicitly authored** in both schemes (no default purple fallbacks).
- Preserve accessibility/contrast intent from existing theme: text/surface pairs >= 4.5:1, borders/hairlines >= 3:1 where they act as borders (per theme standards in file).
- Time-up screen must match reference layout/style intent: bg `#FDF3E3`, title `#3B2620`, body `#6B4A3D`, track `#F6DDBE`, fill `#FF8F45`, primary `#3B2620/#FFF8F0`, outlined border `#8A6A5A` text `#4A3128`, 56dp buttons, ~20dp corners, 2dp outlined border, centered content, progress `usage/limit`.
- Keep dynamic content/callbacks in overlay: `appName`, `usageSeconds`, `limitSeconds`, `onLeave`, `onManage`, `formatUsage(...)`; **no illustration asset**, **no Nunito/font bundling**.
- No changes to monitoring logic, trigger conditions, or navigation flows.

---

### Task 1: Replace global light/dark color schemes with warm palette

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/theme/Theme.kt`

**Interfaces:**
- Consumes: Existing `MonochromeTheme`, `MonochromeLight`, `MonochromeDark`, `MeotimeTypography`, `MeotimeShapes`, tokens, and button composables.
- Produces: Warm light and warm dark `ColorScheme` values with all 48 roles explicitly set; `MonochromeTheme` applies them so every screen/overlay using it inherits globally.

- [ ] **Step 1: Locate and replace scheme definitions**

Open `Theme.kt` and replace the `MonochromeLight = lightColorScheme(...)` and `MonochromeDark = darkColorScheme(...)` blocks (including their KDocs as appropriate) with warm equivalents. Keep object/property names used by the rest of the file (`MonochromeLight`, `MonochromeDark`) to avoid ripple changes.

Use these reference anchors for light mode (exact ref colors where specified, stepped warm neutrals for containers/surfaces):

```kotlin
/**
 * Warm light scheme derived from "Time’s up – break time" reference:
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
```

- [ ] **Step 2: Replace dark scheme with warm-dark palette**

Replace `MonochromeDark` with a warm-dark counterpart (espresso surfaces, cream/peach text, orange accent) with all 48 roles explicit:

```kotlin
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
```

- [ ] **Step 3: Update top-of-file KDoc to reflect warm design system**

Replace the opening block comment (lines describing “monochrome, border-based, no gradients…” and achromatic palette) with an accurate description of the warm break-time palette, keeping notes about all roles authored, contrast intent, and token usage. Keep code style/structure intact.

- [ ] **Step 4: Compile shared Kotlin to verify theme compiles**

Run: `./gradlew :shared:compileKotlinMetadata :shared:compileDebugKotlinAndroid 2>&1 | tail -40`  
Expected: BUILD SUCCESSFUL (or equivalent compile success for shared targets configured locally). If task names differ in this repo, run `./gradlew :shared:tasks --all | rg compile` and use the available shared compile task(s).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/theme/Theme.kt
git commit -m "feat: apply warm global theme palette for light and dark"
```

### Task 2: Redesign `OverlayScreen` to match reference

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/overlay/OverlayScreen.kt`

**Interfaces:**
- Consumes: `MaterialTheme`, `MeotimeTokens`, `MeotimeButton`, `MeotimeOutlinedButton`, `formatUsage(seconds)`, inputs `(appName, usageSeconds, limitSeconds, onLeave, onManage)`.
- Produces: Warm, centered time-up UI with dynamic subtitle/app+usage, progress track/fill/label, and reference-styled actions; behavior/callbacks unchanged.

- [ ] **Step 1: Rewrite `OverlayScreen` implementation**

Replace file contents with a reference-matched implementation (no illustration). Use theme colors for adaptability in dark mode while matching reference intent:

```kotlin
package com.septaalfauzan.meotime.presentation.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * Limit/break notice shown over the foreground app. Root fill is required because the host
 * window is transparent over third-party content.
 */
@Composable
fun OverlayScreen(
    appName: String,
    usageSeconds: Long,
    limitSeconds: Long,
    onLeave: () -> Unit,
    onManage: () -> Unit,
) {
    val spacing = MeotimeTokens.spacing
    val progress = if (limitSeconds > 0) {
        (usageSeconds.toFloat() / limitSeconds.toFloat()).coerceIn(0f, 1f)
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
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
                            .fillMaxSize()
                            .let { base ->
                                // fill fraction
                                base
                            },
                    )
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
```

Note: if Compose in this project lacks `fillMaxWidth(fraction)` on `Modifier` in this form, replace the fill bar with `Box(Modifier.width(220.dp * progress).height(10.dp)... )` or a `Row` fraction approach using available APIs. Keep visual result identical.

- [ ] **Step 2: Ensure required imports compile**

Add/remove imports as needed (`androidx.compose.foundation.layout.width`, `fillMaxWidth` fraction overload availability, `RoundedCornerShape`, `FontWeight`, `dp`, `sp`). Keep `formatUsage` usage and function signature unchanged.

- [ ] **Step 3: Compile shared to verify overlay**

Run: `./gradlew :shared:compileDebugKotlinAndroid 2>&1 | tail -40`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/overlay/OverlayScreen.kt
git commit -m "feat: redesign time-up overlay to match warm break screen"
```

### Task 3: Align shared button styling for time-up fidelity (without regressions)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/theme/Theme.kt`

**Interfaces:**
- Consumes: `MeotimeButton`, `MeotimeOutlinedButton`, `MeotimeTokens.controls`.
- Produces: Buttons that better match warm reference when used in time-up (rounded corners, outlined border visibility/weight where appropriate) while remaining valid for existing screens.

- [ ] **Step 1: Adjust button shapes/border defaults thoughtfully**

In `MeotimeButton` and `MeotimeOutlinedButton`, set shape to better reflect reference (e.g. `RoundedCornerShape(20.dp)` or `MaterialTheme.shapes.large` if mapped accordingly) **only if** this improves global consistency with the new warm system. If changing global defaults risks other screens, instead keep defaults and rely on call-site `modifier`/wrappers in `OverlayScreen` and add a comment in code explaining choice. Prefer minimal, consistent change:
- Outlined button: ensure border uses theme `outline` with sufficient visibility in both modes (2dp in time-up via a parameter is acceptable if API change is localized and backward-compatible with default). If adding a `borderWidth` parameter, default it to existing token:

```kotlin
@Composable
fun MeotimeOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    borderWidth: Dp = MeotimeTokens.controls.borderWidth,
    contentPadding: PaddingValues = MeotimeTokens.controls.buttonContentPadding,
    content: @Composable RowScope.() -> Unit,
) { ... BorderStroke(borderWidth, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.5f)) ... }
```

Then in `OverlayScreen` pass `borderWidth = 2.dp` if desired. Do not break existing call sites.

- [ ] **Step 2: Verify compile across shared**

Run: `./gradlew :shared:compileDebugKotlinAndroid 2>&1 | tail -40`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/theme/Theme.kt shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/overlay/OverlayScreen.kt
git commit -m "refine: align shared button styling with warm time-up design"
```

(Include only files actually changed in this step.)

### Task 4: Verification

**Files:**
- Test/verify (no new test files required by default; existing format/logic tests unchanged)

**Interfaces:**
- Consumes: Updated theme and overlay implementations.
- Produces: Passing shared tests/build checks and manual verification notes for overlay appearance.

- [ ] **Step 1: Run shared host/unit tests relevant to changed area**

Run: `./gradlew :shared:testDebugUnitTest 2>&1 | tail -60`  
If unavailable, run: `./gradlew :shared:allTests 2>&1 | tail -80` or `./gradlew :shared:testAndroidHostTest 2>&1 | tail -80` (pick tasks that exist).  
Expected: PASS / BUILD SUCCESSFUL. Existing `UsageFormatTest` and others unaffected.

- [ ] **Step 2: Assemble Android debug to ensure overlay host compiles**

Run: `./gradlew :androidApp:assembleDebug 2>&1 | tail -60`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual verification checklist (report in completion message)**

- Launch app and confirm global warm palette visible in key screens in light and dark modes.
- Trigger time-up overlay for a monitored app: verify centered “Time’s up”, dynamic app name/subtitle, progress track/fill/label, primary “Leave app” and outlined “Manage restriction” styling/behavior.
- Confirm overlay remains readable over underlying app (themed background fill).

- [ ] **Step 4: Final commit if any fixups**

```bash
git add -A
git commit -m "fix: address warm theme and time-up polish from verification"
```

(Only if there are verification-driven changes; otherwise skip.)
