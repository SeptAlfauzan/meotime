# Wanci — Accessibility to UsageStatsManager Migration

Date: 2026-09-28
Status: Draft
Related: `2026-09-27-realtime-screentime-tracking.md` (implements the accessibility path this spec replaces)

## Problem

Banking apps (mBanking and equivalents) refuse to run while **any** accessibility service is enabled — not only the one in question, any. With `MonitoringAccessibilityService` enabled, the user cannot open their bank app. Screen-time enforcement that locks the user out of banking is unusable.

The foreground-detection source must therefore change from `AccessibilityService` to `UsageStatsManager`, which no banking app treats as hostile.

## Goals

1. Zero accessibility footprint: no accessibility service declared, requested, or checked anywhere in the app.
2. Foreground detection and limit enforcement keep working with the app closed (same core promise as today).
3. Monitoring survives reboot without the user opening the app.
4. `MonitoringCore` and its test suite stay valid — the migration is a source swap, not an accounting rewrite.

## Non-goals

- Recovering usage lost to process/service death (still accepted — 2026-09-27 Decision 10, re-confirmed here).
- Reintroducing a UsageStats reconcile / second source of truth for the recorded number.
- Exact session boundaries: polled detection is seconds late (see Decision 6).
- iOS.
- Changing limits UI, add-app flow, dashboard, or overlay behavior.

## Decisions

| # | Decision | Rationale |
|---|---|---|
| 1 | Foreground service with persistent notification hosts the poll loop. | A screen-time limiter must run with the app closed. Android's price for an always-alive process is the notification. Alternatives (poll-while-open, WorkManager) either break enforcement or add hours of latency. |
| 2 | Poll `queryEvents` every 1s while screen is on; stop on `SCREEN_OFF`, resume on `SCREEN_ON`. | Matches the core's 1s tick so overlay latency stays at today's feel. No wasted polls while the screen is dark; screen-off already closes the session via broadcast. |
| 3 | Notification content: live `"App · 12m / 30m"` while a session runs, `"Monitoring active"` idle, `"Usage Access needed"` on revoke. Updated on session change, limit crossing, and a 60s rollover — not every tick. | Session-change events make content fresh exactly when it matters; the 60s cadence only refreshes the seconds counter. One `notify()`/min instead of 60. Lock-screen visibility is `VISIBILITY_PRIVATE`: shade shows full content unlocked, lock screen shows title only. Privacy tradeoff of live content accepted, leak surface bounded. |
| 4 | Onboarding gains a **Notification** permission row (Usage Access → Display over other apps → Notification); accessibility row deleted. | `POST_NOTIFICATIONS` is runtime-gated on API 33+; a hidden system background-alert is worse UX than the existing explicit permission flow. Request is a launcher callback from `MainActivity`, surfaced in shared code as a `() -> Unit` fun-interface (shared code cannot hold `ActivityResultLauncher`). |
| 5 | Accessibility path is **fully deleted**: service, config XML, manifest block, permission check, status field, navigator method, onboarding row, description string. | Keeping a disabled fallback buys nothing: mBanking trips on *other apps'* accessibility services too, so an in-app toggle could never help. Git history is the archive. |
| 6 | Session boundaries use the core's own clock at processing time; UsageStats event timestamps are ignored. | Polling delivers events 1–5s late, so each switch under-counts by that much — noise against minute-scale limits. Using event timestamps would reintroduce a second time source (`elapsedRealtime` vs `uptimeMillis`) that the 2026-09-27 spec deliberately removed. |
| 7 | No catch-up after service death: on start, poll only from "now" forward. | Same accepted loss as the accessibility path. Event replay would be *safe* here (unlike the deleted reconcile, it would deliver ordered open/close pairs, not aggregates), but it is an accuracy upgrade with its own test surface — not needed for this migration. |
| 8 | `BOOT_COMPLETED` receiver starts the service. | Foreground services do not survive reboot; without this the limiter silently dies until the user next opens Wanci, breaking the "works without opening the app" promise that accessibility got for free from the system. |
| 9 | Service starts on: onboarding completion (explicit `deps.startMonitoring()` from the onboarding finish path), `BOOT_COMPLETED`, and `MainActivity.onCreate` when permissions are already granted (idempotent, `START_STICKY`). | Covers first-run, reboot, and the force-stop case (system won't restart a force-stopped app; opening it is the only re-entry). One idempotent start path, no state machine. |
| 10 | `SecurityException` from `queryEvents` (usage access revoked mid-run) stops the poll loop; notification switches to `"Usage Access needed"`; service stays alive. | Revocation is rare; onboarding is the re-entry path. No auto-restart loop on re-grant. |
| 11 | Within one 1s poll window, only the latest foreground package is delivered to the core. | Core already drops same-package repeats. Quick A→B→A switches inside one window lose at most one second of B — accepted under Decision 6. Keeps delivery to one call/poll. |
| 12 | Poll results carry a generation counter; results from a cancelled generation are discarded. | Guards the race where `SCREEN_OFF` closes the session while a query is in flight — its late result would otherwise reopen a session while the screen is dark, accruing until the next event. |

## Architecture

### Component map (after)

```
shared/commonMain
  monitoring/MonitoringCore.kt        + onSessionChanged callback (no-op default)
  domain/Permissions.kt               PermissionStatus(usageAccess, overlay, notification);
                                       PermissionNavigator drops openAccessibility;
                                       NotificationPermissionRequest fun-interface (grant request)
  presentation/onboarding/            3 rows: usage, overlay, notification

androidApp
  monitoring/MonitoringForegroundService.kt   replaces MonitoringAccessibilityService.kt
  monitoring/NotificationController.kt        new
  monitoring/BootReceiver.kt                  new
  permission/PermissionChecks.kt              -hasAccessibility
  permission/PermissionActions.kt             -openAccessibility
  MainActivity.kt                             + notification permission launcher, passes request + refresh callback
  AppContainer.kt                             + notificationController, startMonitoring wiring
  AndroidManifest.xml                         service swap, FGS/boot/notification permissions
```

`MonitoringCore` remains source-agnostic: it still receives only `onForegroundChange(packageName)` through the same mutex-serialized `enqueueForegroundChange` path. The service adapter changes shape; the accounting does not.

### MonitoringForegroundService

Structure mirrors the deleted `MonitoringAccessibilityService` — one class owns lifecycle, receivers, and delivery:

- `onStartCommand` → `startForeground(...)`, `core.attach()`, register `SCREEN_OFF`/`SCREEN_ON` receivers, start poll loop. Returns `START_STICKY`.
- Poll loop (service-scoped coroutine): every 1s, `UsageStatsManager.queryEvents(lastWatermark..now, INTERVAL_REALTIME)`; parse foreground package (`ACTIVITY_RESUMED` on API 29+, legacy `MOVE_TO_FOREGROUND` also handled for minSdk 28); dedup; `enqueueForegroundChange(pkg)` for the latest only. Watermark = previous query's end time (ranges are inclusive; boundary double-delivery is absorbed by the core's same-package drop).
- `SCREEN_OFF` → `enqueueForegroundChange(null)` **then** cancel poll loop and bump generation.
- `SCREEN_ON` → restart poll loop.
- `onDestroy` → unregister receivers, `core.detach()` (synchronous — stops ticker before return, same order-invariant as today), `overlayManager.dismiss()`.

Event parsing is a pure function in the same file (`parseForegroundPackage(events): String?`), so it can be host-tested without instantiating an Android service.

### NotificationController

Owns channel creation (`IMPORTANCE_LOW` — the overlay owns interruption), snapshot state, and update cadence:

- Inputs: `onSessionChanged` callback from core (package, app name, start timestamp, stored seconds, limit seconds), `MonotonicClock`, limit-crossing signal, permission-lost signal.
- Session opened/updated → format `"App · Xm / Ym"`, `notify()`; own 60s rollover ticker runs only while a session is active; session closed → `"Monitoring active"`; poll stopped on `SecurityException` → `"Usage Access needed"` with tap intent to `ACTION_USAGE_ACCESS_SETTINGS`.
- `VISIBILITY_PRIVATE`, content intent opens Wanci dashboard.

### MonitoringCore change

One addition, mirroring the existing `onOverlayDecision` pattern:

```kotlin
onSessionChanged: ((packageName: String, appName: String, startedAtMillis: Long,
                    storedSeconds: Long, limitSeconds: Long) -> Unit)? = null
```

Fired at session open (after the immediate over-limit check) and at close. Default no-op keeps every existing test untouched. The controller derives display seconds from `storedSeconds + (clock.uptimeMillis() - startedAtMillis) / 1000` on its 60s ticker — it never writes to the recorded total.

### Manifest

Removed: `MonitoringAccessibilityService` `<service>` block with `BIND_ACCESSIBILITY_SERVICE` + meta-data.

Added:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<service
    android:name=".monitoring.MonitoringForegroundService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Ongoing screen-time monitoring so app limits are enforced without the app open." />
</service>

<receiver
    android:name=".monitoring.BootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

`specialUse` (not `dataSync`): this is ongoing monitoring, not transfer work; API 34+ requires an accurate declared type. `PACKAGE_USAGE_STATS` stays (now actually read again). `QUERY_ALL_PACKAGES` and `SYSTEM_ALERT_WINDOW` unchanged.

## Error handling

| Failure | Behavior |
|---|---|
| `SecurityException` on `queryEvents` (usage revoked) | Poll loop stops (generation bump), notification → "Usage Access needed", service alive. Re-grant detected only on next app open / onboarding refresh. |
| Empty query window | No events, no core calls. Idle is not an error. |
| Overlapping query boundaries | Inclusive ranges + core same-package drop absorb duplicates. |
| Notification permission denied | `notify()` no-ops system-side; service and enforcement unaffected; onboarding row reflects ungranted on refresh. |
| OEM kills the service | `START_STICKY` restart; resumes from now (Decision 7 — gap not recovered). |
| Force-stop by user | Android won't restart anything until the user opens the app; `MainActivity.onCreate` start path covers it. |
| In-flight query completes after `SCREEN_OFF` | Generation counter discards it (Decision 12). |
| Screen off (session accrual) | `SCREEN_OFF` broadcast → null package → session closed, same as accessibility path. |

Battery: a 1s `queryEvents` over a ~2–5s window is a single indexed query — negligible. Screen-gated: zero polls while dark.

## Testing

- **`MonitoringCoreTest`** — unchanged expectations; new cases for `onSessionChanged` (fires at open/close with the snapshot, no-op default keeps old tests green).
- **`parseForegroundPackage`** (new, host) — dedup, legacy + modern event types, empty list, interleaved packages within a window.
- **`NotificationController`** (new, host, fake notifier) — content per state, 60s ticker active only during a session, revoke swap, session-close revert.
- **`OnboardingViewModelTest`** — fake navigator order becomes `usage → overlay → notification`.
- **Manifest/lint** — `manifestDebug` output sanity: no accessibility service, FGS type declared, boot receiver present.
- **Manual (no `androidTest` source set):** mBanking opens with Wanci running (the acceptance criterion for this whole spec); app switch updates notification within ~1s + event lag; overlay fires past limit; reboot resumes monitoring; revoke usage access → notification flips, no crash; screen-off accrual stops.

## File changes

| File | Change |
|---|---|
| `androidApp/.../monitoring/MonitoringAccessibilityService.kt` | **Deleted** |
| `androidApp/src/main/res/xml/accessibility_service_config.xml` | **Deleted** |
| `androidApp/.../monitoring/MonitoringForegroundService.kt` | **New** — lifecycle, receivers, poll loop, `parseForegroundPackage` |
| `androidApp/.../monitoring/NotificationController.kt` | **New** — channel, snapshot, cadence |
| `androidApp/.../monitoring/BootReceiver.kt` | **New** |
| `androidApp/src/main/AndroidManifest.xml` | Swap service block; add 4 permissions, boot receiver |
| `androidApp/.../permission/PermissionChecks.kt` | Drop `hasAccessibility` + accessibility imports |
| `androidApp/.../permission/PermissionActions.kt` | Drop `openAccessibility` |
| `androidApp/.../MainActivity.kt` | Notification permission launcher; wire request + refresh |
| `androidApp/.../AppContainer.kt` | `NotificationController`, `startMonitoring` wiring |
| `androidApp/.../res/values/strings.xml` | Drop `accessibility_service_description` |
| `shared/.../domain/Permissions.kt` | `PermissionStatus(usageAccess, overlay, notification)`; drop `openAccessibility`; add notification-request fun-interface |
| `shared/.../monitoring/MonitoringCore.kt` | Add `onSessionChanged` callback (default no-op) |
| `shared/.../presentation/onboarding/OnboardingScreen.kt` | Replace accessibility row with notification row |
| `shared/.../presentation/onboarding/OnboardingViewModel.kt` | Drop `requestAccessibility`, add `requestNotification` |
| `shared/.../presentation/onboarding/OnboardingViewModelTest.kt` | Navigator order `usage → overlay → notification` |
| `shared/.../presentation/App.kt` | Pass notification request/refresh + `startMonitoring` deps |
| `docs/superpowers/specs/2026-09-27-realtime-screentime-tracking.md` | No edit — superseded decisions noted here (its Decision 4 "keep usage permission doing nothing" now resolves: it is read again) |

## Risks

- **Event lag > 1s on some OEMs** — overlay may fire seconds late. Acceptable against minute limits; not fixable without accessibility.
- **`specialUse` review scrutiny** — Play may ask for justification on submission; the declared property value is the answer. Sideloaded/dev builds unaffected.
- **Notification content on shade** — accepted (Decision 3) with `VISIBILITY_PRIVATE` bounding lock-screen exposure.
- **Quick in-window switches under-count** — Decision 11, ≤1s per switch.
