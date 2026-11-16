# Time’s Up Screen & Global Warm Theme Redesign

**Date:** 2026-11-16  
**Status:** Approved (brainstorm)  
**Scope:** `shared` UI theme + Time-up overlay screen (Compose Multiplatform / Android overlay host)  
**Reference:** `Time’s Up – Break Time Screen.html` (root) — visual/color/layout source of truth

## Goal

Redesign the time-up/break-time screen to closely match the provided HTML reference (colors, layout, hierarchy, controls) and update app theme colors globally to the same warm palette in **both light and dark modes**.

## Decisions (confirmed)

- **Theme scope:** Global, light + dark (warm palette replaces current monochrome schemes in both modes).
- **Content:** Keep dynamic app data (app name, usage/limit values, existing callbacks/behavior). **No new illustration asset** added now.
- **Typography:** No new font bundled now (do not add Nunito). Match intent via existing Material typography/weights/sizes.

## Reference palette & elements (from HTML)

- Background: `#FDF3E3`
- Title text: `#3B2620` (“Time’s up”, 36sp/800 feel)
- Body/subtitle text: `#6B4A3D` (17sp/600, centered, max ~300dp)
- Progress track: `#F6DDBE` (pill, ~220dp × 10dp)
- Progress fill/accent: `#FF8F45` (full/ratio)
- Usage label: `#3B2620` (16sp/800, `usage / limit`)
- Primary button: bg `#3B2620`, fg `#FFF8F0`, 56dp, radius 20dp, 17sp/800, “Leave app”
- Outlined button: border 2dp `#8A6A5A`, transparent bg, fg `#4A3128`, 56dp, radius 20dp, 17sp/700, “Manage restriction”
- Layout: 390×844 ref canvas, centered column, ~24dp horizontal padding, vertical rhythm/gaps matching reference; no hero illustration in implementation.

## A. Global theme & color tokens

**Primary files:** `shared/src/commonMain/kotlin/.../presentation/theme/Theme.kt` (and any token files as needed)

1. **Light color scheme (replace `MonochromeLight`)**
   - Warm cream backgrounds/surfaces/containers (`#FDF3E3` base with stepped warm neutrals for container hierarchy).
   - On-colors: deep brown `#3B2620` for primary text; `#6B4A3D` for secondary/variant text.
   - Primary/onPrimary: `#3B2620` / `#FFF8F0` to align main CTA.
   - Secondary/tertiary & containers: warm brown/peach neutrals consistent with reference.
   - Outline/outlineVariant: `#8A6A5A` family (borders/hairlines), tuned for >=3:1 where used as borders.
   - Accent `#FF8F45` mapped to an appropriate scheme role and/or theme-accessible token for progress/highlights, used consistently.
   - Error roles remain semantic and readable, harmonized to warm palette where feasible.
   - **All 48 Material3 ColorScheme roles authored explicitly** (no default purple fallbacks). Maintain text/surface contrast >=4.5:1 and border contrast targets per existing theme standards.

2. **Dark color scheme (replace `MonochromeDark`, warm-dark)**
   - Deep espresso/warm-brown backgrounds and surfaces.
   - Cream/light-peach on-colors for readable contrast.
   - Same orange accent family (`#FF8F45`-adjacent) for progress/CTA emphasis.
   - Adjusted warm outlines for visibility; all 48 roles explicitly authored with contrast preserved.

3. **Application:** Theme remains applied at app root and overlay host; existing screens inherit via `MaterialTheme`/tokens (global visual update).

## B. Time-up screen redesign

**Primary file:** `shared/src/commonMain/kotlin/.../presentation/overlay/OverlayScreen.kt`

1. **Structure & background**
   - `Box/Column` fills size, paints `MaterialTheme.colorScheme.background` (keeps overlay readable over arbitrary underlying app).
   - Centered content, ~24dp horizontal padding, spacing tuned to reference rhythm (without illustration block).

2. **Header/copy (dynamic)**
   - “Time’s up” as prominent headline in deep brown (`headline/title` style mapped to ~36sp, heavy weight).
   - Subtitle: `You've reached your daily limit for $appName. Take a little break — you’ve earned it.` centered, max width ~300dp, secondary warm brown, ~17sp medium/semibold feel, comfortable line height.

3. **Progress block**
   - Centered column: track (≈220dp × 10dp, pill, `#F6DDBE`) with fill (`#FF8F45`, fraction = `usageSeconds / limitSeconds` clamped 0..1).
   - Label below: `${formatUsage(usageSeconds)} / ${formatUsage(limitSeconds)}` in deep brown, bold.
   - Colors driven by theme roles/tokens from section A for light/dark correctness.

4. **Actions**
   - Full-width buttons, 12dp gap, 56dp height, ~20dp corners.
   - Primary: “Leave app” → `onLeave`, filled dark-brown/cream styling per reference.
   - Outlined: “Manage restriction” → `onManage`, 2dp warm border, transparent/warm bg, warm-brown text.
   - Use/extend `MeotimeButton`/`MeotimeOutlinedButton` (or local styled variants) while preserving accessibility (>=48dp touch target) and theme consistency.

5. **Behavior preserved:** Same parameters and callbacks; no navigation/logic changes.

## C. Shared components & dark-mode behavior

**Primary files:** `shared/src/commonMain/kotlin/.../presentation/theme/Theme.kt` (buttons/tokens), consumers unchanged unless required.

- **Buttons:** Ensure primary/outlined components can express 56dp/20dp/2dp outlined styling needed by time-up (via parameters, defaults, or call-site modifiers) without regressing other screens. Colors follow updated scheme (`primary/onPrimary`, `outline`).
- **Typography:** No new font; map to existing `MeotimeTypography`/Material roles for close visual weight.
- **Dark mode:** Warm-dark surfaces/text/outline as above; progress accent remains clearly visible; overlay and all themed text maintain sufficient contrast.

## Out of scope

- Adding/importing cat illustration or other new image assets.
- Bundling Nunito font files.
- Changing monitoring logic, overlay trigger conditions, or navigation flows.
- Non-visual refactors unrelated to this redesign.

## Risks / notes

- Global palette shift affects all screens — review for contrast/legibility regressions in both modes.
- Explicitly author all scheme roles to avoid accidental Material default hues.
- Progress bar introduces a small new UI element in `OverlayScreen`; keep implementation simple and theme-driven.
