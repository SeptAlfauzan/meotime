# Wanci Scribble UI Revamp — Design (approved)

Date: 2026-10-08
Status: approved by product owner; supersedes the monochrome palette rules in
`2026-09-25-wanci-design.md` for visual style only. Behaviour, data, permission,
navigation and logic contracts from all earlier specs remain in force.

Visual/interaction restyle of the whole app, with full spec detail on three screens:
Home (dashboard), Add an app, Set daily limit. Every other screen picks up the new
design system mechanically (components and background) without layout or copy changes.

## 1. Source PRD

The original PRD ("Wanci Scribble UI Revamp rev 2") was delivered in chat. Its key
contents are recorded below; where this document and the chat PRD disagree, the chat
PRD wins and this file must be corrected.

### Palette tokens (light)

| Token | Value | Use |
|---|---|---|
| `paper` | `#FFFFFF` | page + card background |
| `rule` | `#D9D9D9` | ruled-paper line, hairline borders |
| `margin` | `#FF7EB6` | notebook margin line |
| `ink` | `#111111` | primary text, strokes, wobble borders |
| `inkSoft` | `#555555` | secondary text (≥4.5:1 on paper) |
| `grey` | `#EDEDED` | chips, progress track |
| `cyan` | `#38D5EE` | primary accent: buttons, progress, underline |
| `cyanSoft` | `#BFF3FA` | selected chip fill |
| `pink` | `#FF7EB6` | secondary accent: near-limit, stamps |
| `pinkSoft` | `#FFD3E6` | stamp/tint fills |
| `disabled` | `#D4D4D4` | disabled fills/strokes |

### Dark notebook mode

Dark mode stays supported (system-following), re-skinned as a charcoal notebook.
Author's note: ask product — answered: build it. Working values, to be verified at
≥4.5:1 before ship and corrected if they fail:

| Token | Value |
|---|---|
| `paper` | `#1C1C1E` |
| `rule` | `#3A3A3C` |
| `margin` | `#D96BA4` |
| `ink` | `#F2F2F2` |
| `inkSoft` | `#A3A39D` |
| `grey` | `#2A2A2C` |
| `cyan` | `#5FE3F5` |
| `cyanSoft` | `#123B44` |
| `pink` | `#FF9AC7` |
| `pinkSoft` | `#4A2437` |
| `disabled` | `#4A4A4C` |

### Typography

Fonts bundled in the repo (OFL, no runtime network): **Patrick Hand** for body,
**Caveat Brush** for titles and big numbers.

| Role | Size | Font | Notes |
|---|---|---|---|
| title | 36sp | Caveat Brush | screen title |
| card title | 22sp | Caveat Brush | |
| body | 17sp | Patrick Hand | |
| caption | 14sp | Patrick Hand | |
| eyebrow | 13sp | Patrick Hand | ALL CAPS, +1.5sp tracking |
| big number | 72sp | Caveat Brush | limit stepper, hero figures |
| button label | 18sp | Patrick Hand | ALL CAPS, +1sp tracking |

Support system font scaling up to 1.3×: everything in sp, scroll rather than clip.

### Ruled-paper background

- Horizontal rule lines every 32dp, `rule` colour, hairline.
- Pink `margin` line 1.5dp, 14dp from the left edge.
- Procedurally drawn (Canvas), not a bitmap. Preferred, fixed — does not scroll with content.

### Wobble language

- Ink strokes 2.5dp.
- Corner radius 16–24dp, ±2dp per corner; edge jitter ±1.5dp.
- Deterministic seed per object (e.g. package name) — identical every render,
  no per-frame randomness.
- Paths cached by (size, seed).

### Components

| Component | Spec |
|---|---|
| ScribbleButton (primary) | cyan fill, 2.5dp ink wobble border, hard ink shadow offset 3dp/4dp; press = translate 3dp/4dp (80ms) which swallows the shadow; disabled = dashed border + `disabled` fill + disabled ink |
| ScribbleButton (secondary) | transparent fill, dashed ink border 6dp/4dp |
| ScribbleCard | paper fill, 2.5dp ink wobble border, radius ~20dp |
| Blob | 8–10 point seeded polygon; sizes 56dp / 88dp |
| Chip | 44dp high, ≥72dp wide, grey fill, wobble border; selected = `cyanSoft` fill |
| Tag/Stamp | `pinkSoft` fill, tilt −3°..+3° deterministic |
| Scribble progress bar | track `grey`, fill by threshold: <70% `cyan`, 70–90% `pinkSoft`, >90% `pink` + diagonal hatch; dot at both ends; 300ms ease-out; paused/disabled = `disabled` |
| Scribble radio | 24dp wobble circle; selected inner blob scales in over 150ms |
| Underline title | title text with curved cyan taper stroke under it, 60–70% of text width |

State is never colour alone: progress has status text, radio has the existing
"Selected" label, limit-reached has words.

### Motion

- Screen content enter: fade + 12dp upward slide, 220ms, 40ms stagger, max 6 elements.
- Remove-animated setting must disable all of it.

### Accessibility

- ≥48dp touch targets (existing `WanciControls` tokens stay).
- inkSoft text ≥4.5:1; strokes ≥3:1 where they carry meaning.
- Decorative shapes hidden from screen readers.
- Existing semantics (roles, selectableGroup, clearAndSetSemantics, content
  descriptions) preserved verbatim.

### Performance

- 60fps on Samsung SM-A336E.
- Lazy lists stay lazy; wobble paths cached; deterministic seeds only.

### Known open items from the PRD

- Dark mode: answered — dark notebook mode (above).
- Back arrow on Add an app / Set limit: default no (current nav unchanged).
- Stepper ± step size: whatever the code uses today (1 minute, `SetLimitViewModel`).

## 2. Architecture decision

**Approach A — extend the theme in place.**

- `Theme.kt` rewritten: both schemes re-authored with scribble tokens, typography
  rebuilt on the bundled fonts, a `ScribbleColors` token object exposed through
  `WanciTokens.colors` (same pattern as `WanciTokens.spacing`).
- `MonochromeTheme` renamed `ScribbleTheme` (all call sites + docs).
- New package `com.septaalfauzan.wanci.presentation.scribble` holds the primitives:
  `WobbleShape`, `RuledPaperBackground`, `ScribbleButton`, `ScribbleCard`,
  `ScribbleProgress`, `ScribbleRadio`, `Chip`, `Blob`, `UnderlineTitle`, `Stamp`.
- Screens swap primitives; ViewModels, repositories, navigation, permissions and
  strings are untouched.

Alternatives rejected: a parallel scribble theme (two live design systems) and a
full atomic directory restructure (churn beyond a visual restyle).

## 3. Screen specs

### Home (DashboardScreen)

- Root: ruled-paper background (app-wide, drawn once at the root).
- Header: `UnderlineTitle` "Wanci"; eyebrow "TODAY · <date>" above it.
- App rows become `ScribbleCard`s: icon, name, usage/limit caption,
  `ScribbleProgress`, status text — semantics identical to today.
- Primary action: `ScribbleButton` "Add an app".
- Skeleton/empty/error restyled with the same primitives; copy unchanged.

### Add an app (AddAppScreen)

- `UnderlineTitle` + body copy unchanged.
- Search field: Material `OutlinedTextField` with wobble shape and ink border.
- Rows: keep `selectable` / `Role.RadioButton` / `selectableGroup`; radio becomes
  `ScribbleRadio`; selected row carries the "Selected" stamp.
- Continue: primary `ScribbleButton`, disabled state dashed per PRD.

### Set daily limit (SetLimitScreen)

- `UnderlineTitle` (dynamic "Set daily limit" / "Change daily limit").
- Big number: 72sp Caveat Brush, existing `clearAndSetSemantics` kept.
- −/+ : small wobble buttons, existing content descriptions kept.
- Format line, "Preset: …"/"Custom limit" labels unchanged.
- Presets become `Chip`s — `LIMIT_PRESETS` (15, 30, 45, 60, 120) untouched.
- Notices become stamp/note cards; label text kept (never colour alone).
- Save: primary `ScribbleButton`, "Saving…" in-flight label kept.
- Existing vertical scroll wrapper kept (1.3× font scale).

### Other screens (mechanical only)

Onboarding, app settings, permissions, limit overlay: buttons → scribble buttons,
surfaces → `ScribbleCard`, background automatic via the root. No layout changes,
no copy changes.

## 4. Non-negotiables

1. Zero behaviour change — only composables and theme resources.
2. All existing tests green.
3. Accessibility semantics preserved; state never carried by colour alone.
4. Deterministic, cached rendering — no per-frame randomness.
5. Both schemes' contrast pairs measured and recorded before ship.
