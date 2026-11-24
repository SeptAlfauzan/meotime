# App screen-time tracker overlay — short design

## Intent

Show a small, live screen-time pill over a limited app while the user still has time left. It is
the state before today's full-screen blocker, not a replacement for it. When the limit is reached,
the tracker disappears and the existing `Time's up` blocker shows exactly as it does now.

The visual source is the supplied `MeoTime – Usage Overlay.html` reference. No new visual language.

## Goals / non-goals / anti-goals

- Goal: compact Dynamic-Island-style tracker on opening a limited, enabled app that is **under**
  its daily limit.
- Goal: tap pill → expand; tap again or 3s timer → compact. Ring turns orange in the last 10% of
  the limit.
- Goal: realtime usage from the same session clock the blocker uses, so the two never disagree.
- Non-goal: changing the blocker screen, its copy, its actions, or its trigger condition.
- Non-goal: showing the tracker on no-limit days or for already-over-limit entries.
- Anti-goal: a full-screen tracker that hides the app. The app stays usable; only the pill is
  tappable.

## Constraints

- `MonitoringCore` already fires `onSessionChanged` **before** its immediate over-limit check, so
  reusing that callback would flash a tracker before the blocker on an already-over-limit entry.
  Add a dedicated under-limit callback instead; leave `onSessionChanged` alone.
- Usage arithmetic must stay `SessionSnapshot.liveSeconds(clock.uptimeMillis())`, already used by
  the blocker decision and the notification. No second counter.
- `AppIconProvider` already returns `ImageBitmap`; reuse it for the pill icon. No new icon stack.
- Tracker window is small and top-centred, not full-screen. Touches outside the pill go to the
  underlying app; BACK is not consumed by the tracker.
- Exact reference palette is a dedicated overlay palette. It does not alter `MonochromeTheme`.

## Approaches

- **A — Reuse `onSessionChanged` for the tracker.** Rejected: over-limit entries emit a session
  open before the blocker decision, so the tracker would flash. Would need a delay hack.
- **B — Full-screen tracker composable, then blocker.** Rejected: blocks the app during tracking
  and abandons the reference's compact state.
- **C — Compact-first pill with dedicated under-limit callback (picked).** Core publishes a
  tracker decision only when it has decided not to block. A controller owns the 1s tick and the
  3s expand timer. Android renders a small `WindowManager` view. Clean separation, no race, no
  duplicate clock.

## Decisions

### Trigger and dismissal

- Add `onUsageTrackerDecision: ((SessionSnapshot) -> Unit)? = null` to `MonitoringCore`, defaulted
  like `onSessionChanged`.
- Fire it in `afterSessionOpened` only after `LimitChecker.exceeds(live, limitSeconds)` is false,
  immediately before `startTicking()`. Midnight rollover gets the same treatment through
  `afterSessionOpened(reopened, now)`.
- Do **not** fire it when `limitSeconds == null`, when the app is disabled/unmonitored, when the
  package is Meotime itself, or when the session is already over the limit.
- `onSessionChanged(null)` (session close) hides the tracker.
- `onOverlayDecision` hides the tracker before `OverlayManager.show(decision)` shows the blocker.
  One event, one hand-off.
- `detach()` hides the tracker through the same close path as the notification; no zombie pill.

### Controller (shared)

- New `UsageTrackerController` beside `NotificationController`, with the same dependencies:
  `MonotonicClock` and `CoroutineScope`.
- API: `onSessionOpened(snapshot)`, `onSessionClosed()`, `onLimitCrossed()`, `onPillTapped()`.
- Holds the latest snapshot; on a 1s cadence publishes a `TrackerFrame` to a `TrackerRenderer`.
- `TrackerFrame`: `packageName`, `appName`, `usageSeconds`, `limitSeconds`, `percent`,
  `nearLimit`, `expanded`. `nearLimit` is `remaining <= limitSeconds * 0.10`.
- `onPillTapped()` toggles `expanded`; expanding starts a 3s collapse timer, collapsing cancels it.
  Any close/limit event cancels timer + ticker and renders `null`.
- `percent` is `usageSeconds * 100 / limitSeconds`, floored at 0 and capped at 99. At 100 the
  blocker owns the screen.

### Android renderer

- Extend `OverlayManager` with `renderTracker(frame: TrackerFrame?)`; all work posted to main.
- One small `TYPE_APPLICATION_OVERLAY` `ComposeView`:
  - compact window: 250×48dp (40dp visual pill + 8dp transparent touch padding), top centre, y=10dp.
  - expanded window: 358×110dp, top centre, y=10dp.
  - `PixelFormat.TRANSLUCENT`, no background on the `ComposeView`, cutout mode `SHORT_EDGES`.
  - compact window is `WRAP_CONTENT`-sized to the pill; outside touches pass through. No BACK key
    listener. Pill click calls `trackerController.onPillTapped()`.
  - expanded change calls `WindowManager.updateViewLayout` with the new width/height.
- Load the icon once per `packageName` via `AppIconProvider.getIcon(packageName)`; pass the
  `ImageBitmap?` to the composable. No async work inside `setContent`.
- The existing full-screen blocker window stays unchanged. `show()` first calls `renderTracker(null)`.

### Composable and copy (exact reference)

- New `UsageTrackerOverlayScreen.kt` in `presentation/overlay`.
- Dedicated palette from the reference: pill `#000`, primary text `#FFF`, secondary `#B0B0B5`,
  camera dot `#1A1A1A`, progress track `White 18%`, safe green `#30D158`, near-limit orange
  `#FF9F0A`. No app-theme colours.
- Expanded: 358×110, radius 36, camera dot 22×22 at reference screen y=10 (0dp from the composable
  top), 44×44 icon tile, secondary line `<App> · screen time today`, headline 22/500. Safe headline
  `<usage> of <limit>`, e.g. `42 min of 1 hr`. Near-limit headline `<usage> <remaining> left`, e.g.
  `55 min 5 min left`.
- Compact: 250×40 visual pill, radius 20, 24×24 icon tile, `<short usage>` 14/500 (e.g. `42m`),
  28×28 ring. Camera dot is 22×22 at reference screen y=19; with the window at y=10, draw it 9dp
  from the composable top. The 8dp extra hit padding sits below the pill and stays transparent.
- Ring: custom `Canvas` arc, stroke 5, track `White 18%`, rounded cap, starts at -90°. Expanded
  has `52×52` ring with centred `13/500` percent text; compact has `28×28` and no text.
- Add an overlay-only duration formatter producing `1 hr` / `2 hrs` / `42 min` / `5 min` so the
  headline matches the board. Compact keeps `formatShortDuration` (`42m`).
- Accessibility: pill `contentDescription` includes app, usage, limit, percent, and tap action;
  ring has its own `percent% of daily limit used` semantic. Visual size stays reference-exact;
  the compact hit area is 48dp tall.

## Files touched

- `shared/.../monitoring/MonitoringCore.kt` — new under-limit callback.
- `shared/.../monitoring/UsageTrackerController.kt` — new controller + frame/state.
- `shared/.../presentation/overlay/UsageTrackerOverlayScreen.kt` — new composable.
- `shared/.../presentation/format/UsageFormat.kt` — overlay duration formatter.
- `androidApp/.../AppContainer.kt` — wire callback → controller → renderer.
- `androidApp/.../monitoring/OverlayManager.kt` — small tracker window.
- Tests: `MonitoringCoreTest`, new `UsageTrackerControllerTest`.

## Left to the builder

- No design changes beyond these decisions. Reference copy, sizes, and colours are fixed.
- Verify: over-limit open shows blocker only; under-limit open shows compact; tap expands;
  second tap or 3s collapses; 10% turns orange; limit crossing swaps to blocker; session close
  removes pill; `./gradlew test` passes.
