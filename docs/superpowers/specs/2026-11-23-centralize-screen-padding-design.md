# Centralize screen padding in the app root — short design

## Intent

`spacing.screen` (24dp) is currently applied by each destination and by several child
composables. Apply it exactly once at the app's root wrapper so the gutter is owned in one
place and screens stop re-declaring it.

## Goals / non-goals / anti-goals

- Goal: one `.padding(spacing.screen)` in the main content hierarchy, in `App.kt`.
- Non-goal: changing any spacing value, the token definition, or other `spacing.*` usage.
- Anti-goal: screens/content going edge-to-edge or losing the existing 24dp gutter.

## Constraints

- `App.kt` already applies `safeContentPadding()`; the new padding goes **after** it, so layout
  is pixel-identical to today (screens previously padded inside that inset).
- `background()` stays before the padding so the gutter is painted by the app background.
- `ModalBottomSheet` composes in a separate window, outside `App`'s Box. Its gutter therefore
  cannot be inherited and is the one deliberate carve-out.
- `TimePickerScreen.kt` already has this padding removed in the working tree; leave that edit
  intact — the root now supplies it.
- Docs under `docs/superpowers/` are historical records; leave their snippets alone.

## Approaches

- **A — App root only (picked).** Add the padding to `App.kt`; remove all 8 in-content sites.
  Single source, no per-screen drift. Needs the sheet carve-out.
- **B — Destination roots only.** Keep padding on the five screen roots, strip nested ones.
  Rejected: still five declarations, and onboarding's two nested paddings would have to be
  moved rather than removed.
- **C — Dashboard only.** Rejected: leaves every other destination edge-to-edge.

## Decisions

- Root modifier chain in `App.kt`: `fillMaxSize() → background() → safeContentPadding() →
  padding(MeotimeTokens.spacing.screen)`.
- Remove from: `DashboardScreen.kt`, `AppSettingsScreen.kt`, `AddAppScreen.kt`,
  `SetLimitScreen.kt`, `WeeklyLimitScreen.kt`, `OnboardingScreen.kt` (controls),
  `OnboardingPages.kt` (page layout).
- Keep in `DateRangePickerSheet.kt` with a short comment stating the separate-window reason.
- No new dependencies. `spacing` locals and `padding` imports stay in every touched screen:
  all still use them for non-screen spacing.

## Left to the builder

- Mechanical edits only; no behavior changes.
- Verify with grep that `spacing.screen` in common code appears only in `App.kt` and
  `DateRangePickerSheet.kt`, and run the project's usual compile check.
