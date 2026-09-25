# Wanci — Screen Time Monitor: Design Spec

Date: 2026-09-25
Status: Approved (awaiting spec review)

## 1. Overview

Wanci is an Android screen-time intervention app. Users select installed apps, assign a
daily time limit, and Wanci enforces that limit with an overlay intervention when usage
exceeds it. The product prioritizes active intervention over passive analytics.

Platform: Android. Architecture: MVVM. Local storage: Room. UI: Jetpack Compose
Multiplatform (shared).

Working name: **Wanci**. Package: `com.septaalfauzan.wanci`. minSdk 28, target/compile 37.

## 2. Goals

Primary:
1. Select installed applications to monitor.
2. Configure a daily usage limit per app (1–1440 minutes).
3. Track daily foreground usage.
4. Detect when a configured limit is exceeded.
5. Display an overlay while a restricted app is in the foreground past its limit.
6. Let the user manage the restriction from the overlay (never force a hard lock).
7. Persist configuration locally with Room.
8. Operate without a backend or account.
9. Track usage and intervene **without the user opening Wanci** (accessibility-based).

Secondary (deferred):
- Usage history, notifications (separate plan doc), weekly stats, temporary bypass.

## 3. Non-Goals (MVP)

Cloud sync, accounts, social, AI recommendations, web dashboard, cross-device sync,
detailed analytics, subscription, iOS version, server backend, foreground service.

Notifications are explicitly deferred to a separate plan: see
`2026-09-25-wanci-notifications.md`.

## 4. Approved Architectural Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Module split | KMP: `shared` (UI + domain + data + monitoring core) + `androidApp` (Android bindings) | Future iOS UI; core logic host-testable. UI, Room, and MonitoringCore live in `shared`; accessibility service, UsageStats reader, overlay window host live in `androidApp`. |
| Foreground detection | AccessibilityService primary (real-time, survives app never being opened) | UsageStats-only poller dies with process; accessibility is sustained by the system and auto-reattaches on reboot. |
| Usage reconciliation | UsageStatsManager secondary safety net | Heals gaps if accessibility process was down; never overcounts (max-merge). |
| Monitoring core | Thin AccessibilityService + pure `MonitoringCore` | Service is a dumb adapter; logic unit-testable on host. |
| Overlay | SYSTEM_ALERT_WINDOW WindowManager host rendering the shared CMP `OverlayScreen` | Overlay composable reused from shared UI. |
| DI | Manual (no framework) | MVP simplicity. |
| Navigation | Navigation Compose (CMP) | Standard, supports the 5-screen flow. |
| Theme | Monochrome (black/white inverse), border-based, minimal | Matches approved design mockup; no gradients/shadows/purple. |
| Daily reset | Date-key query (`today = LocalDate.now()`), no midnight timer, no boot receiver | Resilient to reboot/service death. |

## 5. Module Layout

```
shared/ (Compose Multiplatform, commonMain)
├── data/
│   ├── local/                 # Room (KMP)
│   │   ├── AppDatabase
│   │   ├── MonitoredAppDao
│   │   ├── DailyUsageDao
│   │   └── entities/
│   └── repository/
│       └── ScreenTimeRepository
├── domain/
│   ├── model/                 # MonitoredApp, DailyUsage, SyncState
│   └── usecase/
│       ├── GetMonitoredApps
│       ├── AddMonitoredApp
│       ├── UpdateDailyLimit
│       ├── ToggleRestriction
│       ├── RemoveMonitoredApp
│       └── CheckUsageLimit
├── monitoring/
│   ├── MonitoringCore         # pure Kotlin, host-tested
│   └── LimitChecker
└── presentation/
    ├── AppNavHost
    ├── dashboard/             # DashboardScreen + ViewModel
    ├── addapp/                # AddAppScreen + ViewModel
    ├── limit/                 # SetLimitScreen + ViewModel
    ├── appsettings/           # AppSettingsScreen + ViewModel
    ├── onboarding/            # OnboardingScreen + ViewModel
    ├── overlay/               # OverlayScreen (reusable composable)
    └── theme/                 # MonochromeTheme

androidApp/
├── monitoring/
│   ├── accessibility/         # MonitoringAccessibilityService (thin adapter)
│   ├── usage/                 # UsageStatsReader
│   └── overlay/               # OverlayManager (WindowManager host)
├── WanciApp.kt                # Application: manual DI graph, Room DB instance
├── MainActivity.kt
└── permission/                # Permission helpers/validators
```

New dependencies: Room (KMP, KSP), navigation-compose (CMP), kotlinx-coroutines.

## 6. Data Layer

### 6.1 Entities

`MonitoredAppEntity` — `tableName = "monitored_apps"`, unique index on `packageName`:

- `id: Long` (PK, autoGenerate)
- `packageName: String` (stable Android identity)
- `appName: String` (display name, refreshable from PackageManager)
- `dailyLimitMinutes: Int`
- `enabled: Boolean` (default true; disabling ≠ deleting)
- `createdAt: Long`, `updatedAt: Long`

`DailyUsageEntity` — `tableName = "daily_usage"`, PK `["packageName", "date"]`:

- `packageName: String`
- `date: String` (ISO `LocalDate` string, e.g. `2026-09-25`)
- `usageMinutes: Long`
- `updatedAt: Long`

`SyncStateEntity` — small k/v table:

- `key: String` (PK), `value: String` — stores `lastReconcileEpochMillis`.

### 6.2 DAOs

`MonitoredAppDao`: insert, update limit, enable/disable, delete, observe all, get by package.

`DailyUsageDao`: get today's usage, upsert today's usage, usage history, delete old rows.

`SyncStateDao`: get/put.

### 6.3 Repository

`ScreenTimeRepository` is the single source of truth. Room table lives in `shared`; the
actual DB instance is built in `androidApp` and injected, keeping `shared` free of
`Context`.

### 6.4 Daily reset

Usage is keyed by device-local date. Queries use `today = LocalDate.now()`. A new day
naturally yields zero usage. No midnight timer or boot receiver.

### 6.5 Reconcile (UsageStatsReader safety net)

Runs when Wanci is opened and periodically from accessibility idle:

1. Read `lastReconcileEpochMillis` from `SyncStateEntity`.
2. `UsageStatsManager.queryEvents(lastEpoch..now)` → per-package foreground deltas.
3. Append deltas to Room, then take `max(roomTotal, statsSum)` per package-day — never
   overcount against the live accessibility counter.
4. Persist new `lastReconcileEpochMillis`.

The live counter from MonitoringCore only persists increments as foreground changes
occur; reconcile heals gaps after process death.

## 7. Monitoring

### 7.1 MonitoringAccessibilityService (androidApp, thin)

- Listens for `TYPE_WINDOW_STATE_CHANGED`, extracts the top package.
- Dedupes consecutive same-package events.
- Forwards `onForeground(package, eventTime)` to `MonitoringCore`.
- No business logic. Survives without Wanci being opened; system reattaches it on reboot.
- Ignores keyguard / screen-off events (no usage while locked).

### 7.2 MonitoringCore (shared, pure Kotlin)

- Tracks `lastForegroundPkg` and `lastForegroundTimestamp`.
- On foreground change: `delta = now - lastTs`; if previous package is monitored and
  enabled (and not Wanci itself), persist the increment.
- After persisting, `LimitChecker.isExceeded(usage, limit)`.
- If exceeded and restriction enabled → emit overlay command.
- Maintains `overlayVisible` per package — never re-shows for the same package; only on a
  different restricted foreground package or after dismissal.

### 7.3 LimitChecker

```kotlin
fun isExceeded(usageMinutes: Long, limitMinutes: Int): Boolean =
    usageMinutes >= limitMinutes
```

## 8. Overlay

`OverlayManager` (androidApp):

- Requires `SYSTEM_ALERT_WINDOW`. Adds a `WindowManager` overlay window containing a
  `ComposeView` that renders the shared `OverlayScreen`.
- **Leave app** → fire `ACTION_MAIN` Home intent (launcher), dismiss window.
- **Manage restriction** → launch Wanci `MainActivity` deep-linked to `appsettings/{package}`, dismiss window.
- No forced hard lock: user always has an escape path (overlay → manage → disable).

Overlay content: "Time's up" + "You've reached your daily limit for {app}." + today's
usage figure (e.g. "42 / 30 minutes") + primary Leave app + secondary Manage restriction.

## 9. UI

Screens (shared CMP):

- **Onboarding**: 3 permission steps with rationale + "You're ready → Add your first app".
- **Dashboard**: monitored-app cards (icon, name, `usage / limit`, progress bar,
  remaining time, status "Limit reached"), `+ Add app`, permission status row, settings
  entry.
- **AddApp**: searchable installed-app list, single select, Continue.
- **SetLimit**: stepper −/+, quick-select chips (15/30/45 min, 1 h, 2 h), validation
  1–1440 minutes, Save.
- **AppSettings**: today's usage / daily limit / status rows; Change limit; Monitoring
  toggle (off ≠ delete — configuration retained for re-enable); Remove monitoring.
- **OverlayScreen**: reusable composable hosted both in the overlay window and available
  for previews.

Navigation routes: `onboarding → dashboard → addapp → limit/{package} →
appsettings/{package}`.

Theme: `MonochromeTheme`, black-on-white with white-on-black inverse, border-based
cards, high contrast, minimal styling matching the approved mockup.

ViewModels: `UiState` + `StateFlow` per screen; ViewModels observe the repository;
screens never query Room directly.

## 10. Permissions & Onboarding

Required permissions:

1. **Usage Access** — `Settings.ACTION_USAGE_ACCESS_SETTINGS`; validate via AppOps.
2. **Overlay** — `ACTION_MANAGE_OVERLAY_PERMISSION`; validate `Settings.canDrawOverlays`.
3. **Accessibility** — `ACTION_ACCESSIBILITY_SETTINGS`; validate enabled-service list.

Onboarding walks each permission with an explicit privacy explanation. Permission status
re-checked on resume; dashboard shows a missing-permission banner. Battery-optimization
exemption is **not** requested (accessibility is self-sustaining).

Privacy: all data stays on-device. No backend, account, cloud, or analytics. The app
explains that usage-access data is stored locally.

## 11. Edge Cases

- **App uninstalled**: configuration retained (do not silently delete). Dashboard flags
  "App no longer installed"; Remove still offered.
- **App renamed**: identity is `packageName`; display name refreshed from PackageManager.
- **System date changed**: usage follows the device's local date.
- **Device reboot**: system reattaches the accessibility service; no code path depends on
  a running timer.
- **Wanci itself**: excluded from monitoring by package filter.
- **Overlay already visible**: `overlayVisible` state prevents duplicate windows; new
  overlay only when restricted foreground app changes or previous dismissed.
- **Split-screen / multi-window**: top-window semantics per accessibility events.

## 12. Testing

- `shared` JVM host tests: MonitoringCore accumulation math, LimitChecker, daily reset
  with injected clock, repository reconcile max-merge, DAO CRUD.
- `androidApp`: permission validators and accessibility wiring, minimal.
- Manual device pass: the MVP success scenario (below).

## 13. Tech Spike First

Before building UI: a minimal on-device spike proving
`accessibility foreground detection → usage accumulation → overlay trigger`. If that
mechanism is unreliable on target devices/versions, the rest of the architecture is
irrelevant. Plan ordering must start here.

### Spike Gate Result (2026-09-25, PASS)

Verified on Pixel_Fold_API_36 emulator (API 36, debug build, headless):

1. Accessibility `TYPE_WINDOW_STATE_CHANGED` events arrive with external package names
   (`com.android.settings`, launcher) and uptime timestamps.
2. Leaving an app after a 69s session accumulates exactly `1` minute into
   `daily_usage` (`com.android.settings|2026-09-25|1`).
3. Limit-0 monitored app triggers `OverlayDecision` on leave; full-screen
   `TYPE_APPLICATION_OVERLAY` Compose window is added and renders.
4. Overlay dismiss (back/continue) works and re-arms the same-day trigger; a
   subsequent leave re-shows the overlay.
5. Screen-off uses the uptime clock consistently with accessibility event times
   (wall/uptime mismatch fixed during implementation review).

Defect found and fixed during the spike: a `ComposeView` attached outside an
Activity via `WindowManager` crashes with
`"Composed into the View which doesn't propagate ViewTreeSavedStateRegistryOwner"`.
`OverlayManager` now provides a full owner chain (LifecycleOwner +
SavedStateRegistryOwner via `SavedStateRegistryController.performRestore` +
ViewModelStoreOwner) before `setContent`.

Harness notes (not product behavior): accessibility must be re-enabled after
re-install or force-stop; the seed row was inserted via a temporary debug hook,
since no app-management UI exists until Tasks 7–13.

## 14. MVP Success Criteria

1. Install Wanci.
2. Grant the three permissions.
3. Select Instagram.
4. Set limit = 30 minutes.
5. Use Instagram normally.
6. Wanci accumulates usage live (without being opened).
7. Usage reaches 30 minutes.
8. Continue using Instagram.
9. Wanci displays the intervention overlay.
10. Tap "Manage restriction".
11. Wanci opens.
12. Disable restriction.
13. Return to Instagram.
14. Next day usage starts at 0.

## 15. Deliverables

- This spec: main design.
- `2026-09-25-wanci-notifications.md`: deferred notifications plan (separate doc,
  requested by user).
- Next step: implementation plan via writing-plans.