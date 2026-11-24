# Centralize Screen Padding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the `spacing.screen` gutter once at the app root instead of in seven per-screen and nested composables.

**Architecture:** `App.kt` is the single entry point every host composes; its Box wraps `AppNavHost`, so padding there is inherited by every destination. `DateRangePickerSheet` is a `ModalBottomSheet` rendered in a separate window outside that Box, so it keeps its own horizontal gutter.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Material3, Gradle KTS.

**Spec:** `docs/superpowers/specs/2026-11-23-centralize-screen-padding-design.md`

## Global Constraints

- `MeotimeTokens.spacing.screen` is 24dp (`lg`, 4dp base unit). Do not change the token.
- Root modifier order: `fillMaxSize() → background() → safeContentPadding() → padding(spacing.screen)`.
- `background()` stays **before** the new padding so the gutter is painted.
- `DateRangePickerSheet.kt` keeps its `.padding(horizontal = spacing.screen)` as the separate-window carve-out, with a short comment saying why.
- Leave the existing uncommitted `TimePickerScreen.kt` edit intact; do not add padding back.
- Leave `docs/superpowers/` history snippets and all `.opencode/index/*` files untouched.
- No new dependencies; no behavior or copy changes.

## Review Focus

- **Bottom sheet window:** `ModalBottomSheet` is outside `App`'s Box, so its content must not lose the gutter → assertion 3 below.
- **Safe-area insets:** padding placed before `safeContentPadding()` would be swallowed by the inset → assertion 2 below.
- **Deep-link cold start:** `appsettings/{pkg}` can be the start destination; it must still inherit the gutter → assertion 1 below (root covers every destination).
- **Time picker overlay:** it renders inside `WeeklyLimitScreen`; a restored local padding would double the gutter → assertion 4 below.
- **Double gutter on any screen:** a missed removal yields 48dp left/right → assertion 5 below.

---

### Task 1: Move the gutter to the app root

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/App.kt:3-21,96-101`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DashboardScreen.kt:93-97`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/appsettings/AppSettingsScreen.kt:101-105`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/addapp/AddAppScreen.kt:73-77`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/limit/SetLimitScreen.kt:79-83`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/limit/WeeklyLimitScreen.kt:58-62`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingScreen.kt:141-144`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingPages.kt:144-147`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DateRangePickerSheet.kt:59`

**Interfaces:**
- Consumes: `MeotimeTokens.spacing.screen` from `presentation/theme/Theme.kt`; `App`'s existing Box modifier chain.
- Produces: no new API. Every `AppNavHost` destination inherits a 24dp gutter; the bottom sheet keeps its own.

- [ ] **Step 1: Run the structural check to confirm it fails today**

Run from repo root:

```bash
SRC=shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation
fail=0
# 1. root applies the gutter
grep -q 'padding(MeotimeTokens.spacing.screen)' "$SRC/App.kt" || { echo 'FAIL: no root gutter'; fail=1; }
# 2. root order: safeContentPadding before padding
python3 - <<'PY' || fail=1
from pathlib import Path
s = Path('shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/App.kt').read_text()
assert s.index('.safeContentPadding()') < s.index('.padding(MeotimeTokens.spacing.screen)'), 'wrong modifier order'
PY
# 3. sheet keeps its own gutter
grep -q 'spacing.screen' "$SRC/dashboard/DateRangePickerSheet.kt" || { echo 'FAIL: sheet gutter lost'; fail=1; }
# 4. time picker has none
! grep -q 'spacing.screen' "$SRC/limit/TimePickerScreen.kt" || { echo 'FAIL: picker double gutter'; fail=1; }
# 5. no other source site
count=$(grep -R 'spacing\.screen' "$SRC" --include='*.kt' | wc -l | tr -d ' ')
[ "$count" -eq 2 ] || { echo "FAIL: expected 2 sites, got $count"; fail=1; }
exit $fail
```

Expected: FAIL with `no root gutter` and `expected 2 sites, got 8`.

- [ ] **Step 2: Add the gutter to `App.kt`**

Add imports `androidx.compose.foundation.layout.padding` and `com.septaalfauzan.meotime.presentation.theme.MeotimeTokens`. Append `.padding(MeotimeTokens.spacing.screen)` to the Box modifier chain **after** `.safeContentPadding()`, leaving `fillMaxSize()` and `background()` ahead of it. Update the KDoc's last paragraph to say the root also owns the 24dp gutter for every destination.

- [ ] **Step 3: Remove the seven per-site paddings**

In each file listed above except `DateRangePickerSheet.kt`, delete only the `spacing.screen` padding:
- five screen roots: delete the `.padding(spacing.screen),` line from the root modifier chain, leaving `modifier.fillMaxSize()` (or `.fillMaxSize()`);
- `OnboardingScreen.kt`: change `.padding(horizontal = MeotimeTokens.spacing.screen, vertical = MeotimeTokens.spacing.lg)` to `.padding(vertical = MeotimeTokens.spacing.lg)`;
- `OnboardingPages.kt`: same change on `OnboardingPageLayout`.

Keep every `spacing` local and `padding` import — each file uses them for other spacing.

- [ ] **Step 4: Document the sheet carve-out**

In `DateRangePickerSheet.kt`, add a one-line comment above `.padding(horizontal = spacing.screen)` stating that a `ModalBottomSheet` composes in a separate window and therefore does not inherit the gutter from `App`.

- [ ] **Step 5: Compile the shared module**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL, no unused-import warnings for the touched files.

- [ ] **Step 6: Re-run the structural check from Step 1**

Expected: exit 0, no FAIL lines.

- [ ] **Step 7: Commit**

```bash
git add \
  docs/superpowers/specs/2026-11-23-centralize-screen-padding-design.md \
  docs/superpowers/plans/2026-11-23-centralize-screen-padding.md \
  shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/App.kt \
  shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DashboardScreen.kt \
  shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/appsettings/AppSettingsScreen.kt \
  shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/addapp/AddAppScreen.kt \
  shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/limit/SetLimitScreen.kt \
  shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/limit/WeeklyLimitScreen.kt \
  shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingScreen.kt \
  shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingPages.kt \
  shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/dashboard/DateRangePickerSheet.kt
git commit -m "refactor: move screen gutter to app root"
```

Do not stage `TimePickerScreen.kt` or any `.opencode/index/*` file.
