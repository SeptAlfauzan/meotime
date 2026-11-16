# Three-Page Onboarding Pager

**Date:** 2026-11-16  
**Status:** Approved (brainstorm)  
**Scope:** `shared` onboarding UI (Compose Multiplatform), Compose Resources illustrations, existing permission flow  
**References:** `image_1.png`, `image_2.png`, `image_3.png` (root, 602×557 PNG, alpha channel)

## Goal

Replace the single-screen onboarding with a three-page horizontal pager:

1. Welcome.
2. Permission request.
3. Permission granted / ready.

The user cannot reach page 3 until all permissions are granted. Illustrations come from the three supplied PNGs. Existing permission request mechanisms, persistence, monitoring start, and failure handling are preserved.

## Decisions (confirmed)

- **Flow:** horizontal pager inside the existing `ONBOARDING` route. No new navigation routes.
- **Gating:** page 2 → 3 requires `PermissionStatus.allGranted`. Swiping is frozen on the ungranted permission page; an explicit Back button keeps page 1 reachable. The final action on page 3 completes onboarding and starts monitoring.
- **Illustrations:** render as-is, no rounded card or background container, `ContentScale.Fit`, max height ~260dp.
- **Brand:** screens use `MeoTime`.
- **Copy:** light grammar/punctuation cleanup only (listed below).
- **Dark mode:** illustrations are accepted as supplied; no recoloring or container treatment in this scope.

## Page content

| Page | Index | Illustration | Title | Body | Primary CTA |
|---|---|---|---|---|---|
| Welcome | 0 | `onboarding_welcome` | `MeoTime` | `Manage your screen time for your better life.` | `Continue` |
| Permissions | 1 | `onboarding_permissions` | `Permission is required` | `We need some permissions to track your screen time.` | `Continue` (disabled until all granted) |
| Ready | 2 | `onboarding_ready` | `All set` | `Permission is granted. Let's make the most of MeoTime.` | `Start MeoTime` |

Page 2 also contains the existing permission rows and controls:

- `Usage Access` — `Measure how long you use apps.` — `Grant`.
- `Display over other apps` — `Show the reminder when you reach a limit.` — `Grant`.
- `Notifications` — `Show the reminder while Meotime is not open.` — `Grant`.
- `Check permissions` secondary text button.
- Helper text while ungranted: `Grant all permissions to continue.`

Granted rows render `✓ Granted` as a status label, not as a disabled button, matching the current screen's accessibility rationale.

## Layout and structure

**Outer screen**

- `Column` filling the screen, horizontal centering.
- `HorizontalPager` with `Modifier.weight(1f)`.
- Fixed bottom zone below the pager: page indicator and CTA row. The bottom zone stays visible while page content scrolls.

**Each page**

- `Column` filling the page, vertical scroll, centered horizontally and vertically.
- Horizontal content width capped at `480.dp` and centered, so tablets/desktop do not stretch text and illustrations edge to edge.
- All spacing from `MeotimeTokens.spacing`; no arbitrary dp values.
- Illustration first, then title, then body. Page 2 inserts permission rows between body and the helper text.
- Title: `MaterialTheme.typography.headlineMedium`, centered.
- Body: `MaterialTheme.typography.bodyLarge`, centered, `onSurfaceVariant`.
- Illustrations: `Image(painterResource(...), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp))`.

**Bottom zone**

- Page indicator: three dots, selected dot visually distinct (pill or filled), with container semantics `Page X of 3`.
- Page 0: full-width `Continue` (`MeotimeButton`).
- Page 1: `Back` (`MeotimeTextButton`) beside full-width `Continue` (`MeotimeButton`, disabled until granted).
- Page 2: `Back` (`MeotimeTextButton`) beside full-width `Start MeoTime` (`MeotimeButton`).
- `Back` always animates to the previous page.

**Ready page failure notice**

If `completeOnboarding` fails, show the existing `OnboardingNotice` above the bottom zone or directly above the page 3 CTA:

- Label: `Not saved`.
- Message: `Meotime couldn't finish setting up. Nothing was changed — try again.`

The notice remains until the next attempt begins, matching current ViewModel behavior.

## Gating and permission flow

Page indices are `Welcome = 0`, `Permissions = 1`, `Ready = 2`.

- `allGranted = state.permissionStatus.allGranted`.
- Pager user scrolling:
  `userScrollEnabled = allGranted || pagerState.settledPage == Welcome`.
  Swiping from Welcome to Permissions is allowed; while settled on the ungranted Permissions page (and on Ready if permissions are later revoked), swipe is frozen in both directions. The explicit `Back` button returns to the welcome page.
- `Continue` on page 2 is enabled only when `allGranted`; tapping it calls `pagerState.animateScrollToPage(Ready)`.
- If page 3 is reached and permissions later read as not granted, a `LaunchedEffect` animates back to page 2:
  `if (pagerState.currentPage == Ready && !allGranted) pagerState.animateScrollToPage(Permissions)`.
- `Start MeoTime` is enabled only when `allGranted && !state.saving`; it calls the existing `viewModel.completeOnboarding(onFinished)`.
- When saving, the label becomes `Saving…` and the button is disabled, preserving the double-submit guard.
- Reaching page 3 is therefore impossible unless all permissions are granted, including after a permission revocation.

**Pure navigation policy**

Add a small pure function used by the UI and unit tests:

```kotlin
internal fun canNavigateToPage(target: Int, allGranted: Boolean): Boolean
```

Rules:

- Targets `Welcome` and `Permissions` are always allowed.
- Target `Ready` is allowed only when `allGranted`.
- Out-of-range targets are rejected.

The screen uses the function as a final guard before programmatic navigation; tests cover it directly.

**Permission status refresh**

- `LaunchedEffect(Unit)` still refreshes once when the screen enters composition.
- `LifecycleResumeEffect` refreshes whenever the app returns to the foreground, so returning from Android Settings updates the rows without requiring `Check permissions`.
- `Check permissions` still refreshes manually.
- The notification request callback still refreshes after the runtime dialog resolves.

## Assets and Compose Resources

Copy the supplied PNGs into the shared Compose Resources drawable directory:

| Source | Destination | Generated accessor |
|---|---|---|
| `image_1.png` | `shared/src/commonMain/composeResources/drawable/onboarding_welcome.png` | `Res.drawable.onboarding_welcome` |
| `image_2.png` | `shared/src/commonMain/composeResources/drawable/onboarding_permissions.png` | `Res.drawable.onboarding_permissions` |
| `image_3.png` | `shared/src/commonMain/composeResources/drawable/onboarding_ready.png` | `Res.drawable.onboarding_ready` |

- Root originals may remain in place; no deletion is required by this spec.
- Accessors are imported from `meotime.shared.generated.resources`.
- Images are decorative: `contentDescription = null`.
- Total added asset size is ~730 KB as supplied; no recompression in this scope.

## Files

**Modified**

- `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingScreen.kt` — pager scaffold, bottom controls, gating, refresh effects. Its public signature stays `OnboardingScreen(viewModel, onFinished, modifier)`, so `AppNavHost.kt` does not change.

**Added**

- `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingPages.kt` — the three page composables, illustration atom, permission row, notice.
- `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingNavigation.kt` — page constants and `canNavigateToPage`.
- `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingNavigationTest.kt` — gating policy tests.
- The three drawable PNGs listed above.

**Unchanged**

- `OnboardingViewModel.kt` and its existing tests. Permission request methods, persistence key, monitoring start, and failure handling stay as they are.

## Accessibility

- Illustrations are decorative and excluded from the accessibility tree.
- Each permission `Grant` button gets an accessible label including the permission name, e.g. `Grant Usage Access`, because the visible label repeats three times.
- Granted rows expose a status label, not a disabled button.
- Page indicator announces `Page X of 3`; individual dots are not separate focus targets.
- Titles use heading semantics.
- `Continue`, `Back`, `Check permissions`, and `Start MeoTime` remain at least 48dp tall through the existing `Meotime*` buttons.
- The disabled `Continue` is paired with `Grant all permissions to continue.`, so state is not communicated by button state alone.
- Every page scrolls when content exceeds the viewport at large font scales.

## Testing

- New pure policy tests in `OnboardingNavigationTest.kt`:
  - Welcome and Permissions are reachable with no permissions.
  - Ready is blocked when `allGranted` is false.
  - Ready is reachable when `allGranted` is true.
  - Out-of-range page indices are rejected.
- Existing `OnboardingViewModelTest` cases must continue to pass unchanged.
- Verification commands:
  - `./gradlew :shared:testAndroidHostTest`
  - `./gradlew :shared:iosSimulatorArm64Test`
  - `./gradlew :androidApp:assembleDebug --no-configuration-cache`
- A failing test or build blocks completion; do not proceed on an unverified build.
- Manual checks on Android:
  - Fresh install starts on Welcome.
  - Swipe to Permissions; verify swipe to Ready is blocked while ungranted.
  - Grant all permissions via the rows; returning from Settings updates status.
  - `Continue` enables only when all three are granted.
  - Ready page shows the third illustration; `Start MeoTime` persists the flag, starts monitoring, and lands on the dashboard.
  - Back navigation from Permissions works while swipe is frozen.
  - Dark mode legibility of text against the warm palette.
  - Large font scale: every page scrolls and the bottom controls remain reachable.

## Out of scope

- New navigation routes or a separate permission screen.
- Changes to permission request implementations, manifest permissions, or monitoring logic.
- Onboarding for already-onboarded users (the persisted `onboardingComplete` gate is unchanged).
- Image recoloring, cropping, or recompression.
- Localization or string resources.
- Redesigning unrelated screens.

## Risks and notes

- Freezing pager swipe on the ungranted permission page also freezes back-swipe; the explicit `Back` button is required and is part of the design.
- The supplied PNGs may have opaque or light backgrounds that clash with the dark warm palette. Render-as-is is the confirmed choice; revisit a container treatment only after visual QA.
- `LifecycleResumeEffect` must remain multiplatform-safe; the project already depends on `androidx.lifecycle.runtimeCompose` 2.11.0.
- Compose Resources accessor names derive from file names; keep the destination names lowercase and stable to avoid import churn.
