# Wanci — Realtime Screen-Time Tracking

Date: 2026-09-27
Status: Draft
Supersedes: `2026-09-26-timer-based-usage-tracking.md` (implemented, but the implementation drifted from its own spec — see Problem)

## Problem

`MonitoringCore` cannot detect a limit being crossed while the user is still inside the restricted app.

Usage is credited in exactly one place: the leave branch of `onForegroundChange`, which computes `eventTime - lastEventTime`, floors it to whole minutes, and discards anything under a minute. Nothing writes to `daily_usage` while an app is in the foreground, so the number the limit check reads is frozen for the whole session.

The 5-second ticker added in `9a69cd6` re-reads that frozen value every tick and therefore cannot observe a crossing. The original spec (`2026-09-26-timer-based-usage-tracking.md:36`) called for `roomUsage + counter >= limit` with a per-app in-memory counter; the implementation dropped the counter and kept only the re-read. The ticker is currently a no-op for the case it was written for.

`UsageStatsReader` cannot fill the gap either. `queryForegroundMinutes` only adds to its totals when it sees a closing event, so a package still foreground at the end of the query window is never counted. The next window starts past its `MOVE_TO_FOREGROUND` event, so when the close finally arrives `isForeground[pkg]` is null and the whole session is dropped permanently. Reconcile only ever counts completed sessions.

Two further consequences of the current design: `resetDay()` has no callers, and `triggeredByDate` is a per-day suppression set that no longer matches the intended "one notice per entry" behaviour.

## Goals

1. Crossing the limit while using a restricted app shows the overlay within a second, without waiting for the user to leave.
2. Entering a restricted app that is already over its limit shows the overlay immediately.
3. The recorded number is the time actually spent, to the second.

## Non-goals

- A live-updating dashboard. `MonitoredApp.todayUsageSeconds` stays a frozen reading taken from the database; the write still happens on leave. Making the dashboard tick live is a separate change.
- Healing usage lost to a service kill. See "Service death" below.
- Using `UsageStatsManager` as a second source of truth.
- Any change to the limits UI, the add-app flow, or the onboarding sequence.

## Decisions

| # | Decision | Rationale |
|---|---|---|
| 1 | One overlay per entry, and it is an exit rather than a notice: BACK and both buttons leave the app, so it cannot be dismissed and ignored. | Enforcement should not be a nag the user can wave away, and it should not be an unskippable modal loop either. The overlay appearing on every entry is the loop that matters; the user always ends up out of the app. |
| 2 | Usage is stored and compared in **seconds**, not minutes. | Whole-minute flooring silently drops every sub-minute session; a day of short sessions can undercount by enough to never trip a limit. |
| 3 | `UsageStatsManager` reconcile is removed entirely. The accessibility service is the only source of truth. | Two sources for one number drift. Reconcile is also the component with the open-session bug. |
| 4 | The `PACKAGE_USAGE_STATS` permission and its onboarding gate stay. | Nothing reads it now, but removing it means touching the onboarding flow and manifest to re-add later. |
| 5 | Usage keeps accruing for the rest of the session after the overlay fires. Only the nagging stops. | Recording "time until blocked" instead of "time used" would make the dashboard wrong. |
| 6 | A session is a **contiguous foreground run**: it ends only when a different package takes foreground or the screen turns off. | `TYPE_WINDOW_STATE_CHANGED` fires repeatedly for one app — activity changes, in-app dialogs, permission prompts. Overwriting the session clock on each event drops the time spent before the last dialog. |
| 7 | Hand-written `Migration(1, 2)` with a table rebuild. | `minSdk` is 28, so `ALTER TABLE ... DROP COLUMN` (SQLite 3.35 / API 30) is unavailable. Rebuilding preserves monitored apps, limits, and history. |
| 8 | Live count is **recomputed from a session start timestamp** on a 1s tick, not accumulated by counting ticks. | One source of truth, self-correcting if the stored total or the limit changes, and trivially testable with virtual time. |
| 9 | BACK on the overlay behaves exactly like "Leave app". | With BACK leaving and both buttons leaving, no action leaves the user inside the restricted app. |
| 10 | A service kill mid-session loses that session's unpersisted time. | Accepted trade for the write-on-leave model. Bounds the complexity of the accounting. |
| 11 | KDoc on the contract and the non-obvious invariants. No line-by-line narration. | |

## Architecture

### One session holder, not a map

Only one app is foreground at a time, so `MonitoringCore` holds a single `currentSession` rather than `lastPackage` + `lastEventTime` + `activeTimers: MutableMap<String, Job>`.

That map was the shape of the previous bug: it allowed a timer to exist for a package that was no longer foreground, and it had no notion of when the session began — so the ticker had nothing to measure against.

```kotlin
private class Session(
    val packageName: String,
    val appName: String,
    val startedAtMillis: Long,
    val storedSeconds: Long,
    val limitSeconds: Long,
    var overlayShown: Boolean,
) {
    fun liveSeconds(nowMillis: Long): Long = storedSeconds + (nowMillis - startedAtMillis) / 1000

    fun toDecision(liveSeconds: Long) = OverlayDecision(
        packageName = packageName,
        appName = appName,
        usageSeconds = liveSeconds,
        limitSeconds = limitSeconds,
    )
}
```

`liveSeconds` is the only place elapsed time is derived.

### `MonotonicClock`

New `fun interface` in shared, one method:

```kotlin
fun interface MonotonicClock {
    /** Milliseconds since boot, excluding deep sleep. */
    fun uptimeMillis(): Long
}
```

Android binds it to `SystemClock.uptimeMillis()`.

`uptimeMillis` rather than `elapsedRealtime` is deliberate. It does not advance while the device is asleep, so a session spanning a doze records awake time only. The ticker's `delay()` is driven by `elapsedRealtime` and *will* stall under doze, but when it resumes it recomputes from `uptimeMillis`, so the stall self-corrects rather than over-counting. It is also immune to the user changing the wall clock or the timezone.

Tests bind it to `TestScope.currentTime`, which makes every timing assertion virtual.

### Signature change

`onForegroundChange(packageName: String?)` — the `eventTimeMillis` parameter is deleted. The session clock is ours, read when we process the event, so there is no longer a reason to mix `AccessibilityEvent.eventTime` into our own clock domain. `enqueueForegroundChange` and the `SCREEN_OFF` receiver both simplify accordingly.

### Removed

| Removed | Reason |
|---|---|
| `activeTimers` map | Replaced by the single session plus one `tickJob`. |
| `lastPackage`, `lastEventTime` | Folded into `Session.startedAtMillis`. |
| `triggeredByDate` | Per-day suppression replaced by per-entry `Session.overlayShown`. |
| `onOverlayDismissed` | Existed only to release a dedupe token. With BACK now leaving, there is no dismiss path and nothing to release. |
| `resetDay()` | Already dead; the map it pruned is gone. |
| `checkCurrentApp`, `startUsageTimer`, `stopUsageTimer` | Folded into the session lifecycle. |
| `UsageStatsReader` (whole file) | Reconcile dropped. |

`OverlayManager` loses its `core` and `coreMutex` constructor parameters and becomes `OverlayManager(context)` — a pure view host with no knowledge of the core. Its coroutine scope goes with them.

`SyncStateDao`, `getSyncValue`, `putSyncValue`, and `observeSyncValue` all stay: onboarding depends on them (`WanciUseCases.kt:16`). Only the `lastReconcileEpochMillis` key is abandoned.

### Comments

`MonitoringCore` carries KDoc on the class contract and on the four invariants a future reader cannot infer from the code:

- why `uptimeMillis` and not `elapsedRealtime` or `currentTimeMillis`
- why the ticker exiting on the crossing does not stop accrual
- why step 1 (close and persist) must run before step 2 (open the next session)
- why `overlayShown` is per-session and not per-day

## Data model and migration

`DailyUsageEntity.usageMinutes: Long` becomes `usageSeconds: Long`. That is the only column whose shape changes.

`MonitoredAppEntity.dailyLimitMinutes: Int` is **unchanged**. The limit is an authoring unit — users think in minutes, `SetLimitViewModel` and the limit input take minutes, the dashboard prints minutes. It is converted once, at the single comparison point in `MonitoringCore`:

```kotlin
val limitSeconds = app.dailyLimitMinutes * 60L
```

Keeping it avoids a second table migration and leaves every limit-related screen untouched.

`MonitoredApp.todayUsageMinutes: Long` becomes `todayUsageSeconds: Long`, so the domain model carries one unambiguous value and formatting happens at the edges.

### `Migration(1, 2)`

`WanciDatabase` goes `version = 1 → 2` and `exportSchema = false → true`. `room.schemaLocation` is set as a KSP argument per source set in `shared/build.gradle.kts`, and the generated `schemas/` directory is committed so the next migration is generated rather than guessed.

The migration rebuilds `daily_usage`, because `DROP COLUMN` is unavailable at `minSdk` 28:

```sql
CREATE TABLE IF NOT EXISTS `daily_usage_new` (
  `packageName`  TEXT    NOT NULL,
  `date`         TEXT    NOT NULL,
  `usageSeconds` INTEGER NOT NULL,
  `updatedAt`    INTEGER NOT NULL,
  PRIMARY KEY(`packageName`, `date`)
);
INSERT OR REPLACE INTO `daily_usage_new`
  SELECT `packageName`, `date`, `usageMinutes` * 60, `updatedAt` FROM `daily_usage`;
DROP TABLE `daily_usage`;
ALTER TABLE `daily_usage_new` RENAME TO `daily_usage`;
```

Room wraps this in a transaction. `MIGRATION_1_2` lives in commonMain beside `WanciDatabase`, and **both** `PlatformDatabase.android.kt:20` and `PlatformDatabase.ios.kt:18` gain `.addMigrations(MIGRATION_1_2)`. Neither platform registers any migration today, and an unregistered migration means a silent destructive wipe on upgrade.

### Repository surface

| Removed | Added / changed |
|---|---|
| `reconcileDailyUsage` | `getTodayUsage` → `getTodayUsageSeconds` |
| `DailyUsageDao.raiseToAtLeast` | `recordUsage` → `recordUsageSeconds` |
| `queryForegroundMinutes` | `addDelta` → `addDeltaSeconds` |
| the `lastReconcileEpochMillis` key | `getTodayUsage` → `observeTodayUsageSeconds` |

`deleteUsageBefore` stays but loses its only caller. The 7-day prune moves to `WanciApplication.onCreate`: one indexed `DELETE` per cold start on a table holding at most a few dozen rows. The cutoff date is derived from `todayKey.todayIso()` parsed as a `LocalDate` and pushed back seven days, so it follows the same clock as everything else rather than introducing a second `LocalDate.now()` call site.

### `dashboardRow` must convert before it compares

`dashboardRow` (`DashboardViewModel.kt:61-77`) currently mixes `todayUsageMinutes` with `dailyLimitMinutes` in three places: the `progress` ratio, the `limitReached` comparison, and the `remainingMinutes` subtraction. Renaming the usage field to seconds while the limit stays in minutes makes all three wrong by a factor of 60, silently — the row still renders, it just shows `1.0` progress and `0` remaining for every app.

`dashboardRow` therefore converts once and does all of its arithmetic in seconds:

```kotlin
val limitSeconds = app.dailyLimitMinutes * 60L
val usage = app.todayUsageSeconds
```

`progress` and `limitReached` are computed from those, and `remainingMinutes` is derived at the end as `remainingSeconds / 60`, so `DashboardAppRow` keeps its minutes-typed field and `DashboardScreen` is unaffected. `AppSettingsViewModel` reuses `dashboardRow` at line 103, so its `usageMinutes` state field (line 43) and the value it feeds in at line 416 are renamed to seconds at the same time.

### Formatting

Three screens print usage — `DashboardScreen.kt:154`, `AppSettingsScreen.kt:188`, and the overlay. There is already a formatter for this, `formatUsageMinutes` at `AppSettingsViewModel.kt:131`, so it is **moved and repurposed** rather than replaced by a competing one. It keeps the app's established word style (`"5 min"`, `"1 hour"`, `"1 hour 5 min"`) rather than the abbreviated style this spec originally proposed, because the abbreviated form would be the only place in the UI not speaking in the same register as its neighbours.

Its input becomes seconds, and it gains the one case that second precision makes reachable — a reading under a minute, which the old minute-based input could not express:

```kotlin
fun formatUsage(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    if (total < 60L) return "$total sec"
    val minutes = total / 60
    val hours = minutes / 60
    val restMinutes = minutes % 60
    val hourLabel = if (hours == 1L) "hour" else "hours"
    return when {
        hours == 0L -> "$minutes min"
        restMinutes == 0L -> "$hours $hourLabel"
        else -> "$hours $hourLabel $restMinutes min"
    }
}
```

It lives in its own file, `presentation/format/UsageFormat.kt`, so the overlay — which is in the overlay package and has no business importing a view model to render a number — can use it. The existing clamping rationale in its KDoc carries over: deliberately not `formatLimitMinutes`, which clamps into `1..1440` because it formats a limit.

## Flow

`onForegroundChange(packageName: String?)` runs under `coreMutex`:

```
now = clock.uptimeMillis()

// 1. Close and persist whatever session was open.
tickJob?.cancel(); tickJob = null
currentSession?.let { s ->
    val elapsed = (now - s.startedAtMillis) / 1000
    if (elapsed > 0) {
        try { repository.recordUsageSeconds(s.packageName, elapsed, todayKey.todayIso()) }
        catch (e: Exception) { log(...) }   // lost seconds, not lost tracking
    }
}
currentSession = null

// 2. Nothing to track.
if (packageName == null || packageName == selfPackageName) return
val app = repository.getMonitoredApp(packageName) ?: return
if (!app.enabled) return

// 3. Open a session seeded with today's persisted total.
val s = Session(pkg, app.appName, startedAt = now,
                stored = repository.getTodayUsageSeconds(pkg, todayKey.todayIso()),
                limit = app.dailyLimitMinutes * 60L,
                overlayShown = false)
currentSession = s

// 4. Already over -> show now, never tick.
val live = s.liveSeconds(now)
if (LimitChecker.exceeds(live, s.limitSeconds)) {
    s.overlayShown = true
    onOverlayDecision(s.toDecision(live))
    return
}

// 5. Under the limit -> tick until it isn't.
tickJob = scope.launch {
    while (isActive) {
        delay(tickIntervalMillis)
        val current = currentSession ?: return@launch
        if (current.overlayShown) return@launch
        val live = current.liveSeconds(clock.uptimeMillis())
        if (!LimitChecker.exceeds(live, current.limitSeconds)) continue
        current.overlayShown = true
        tickJob = null
        onOverlayDecision(current.toDecision(live))
        return@launch
    }
}
```

`tickIntervalMillis` is a constructor parameter defaulting to `1_000L`, so production can be retuned and tests can pass a large value without touching logic.

The `elapsed > 0` guard in step 1 discards only sessions shorter than one second. That is the floor, and it exists to avoid a pointless `UPDATE` for an event that carried no time — it is not the old one-minute floor.

### One delivery path

Everything reaches the overlay through `onOverlayDecision`; `onForegroundChange` returns `Unit`. The current code has two delivery mechanisms — a return value the service checks, and a callback the ticker uses — and unifying them removes the service's `decision != null` branch.

This requires a synchronous teardown path on the core. `onDestroy` cannot wait for a coroutine, and `enqueueForegroundChange` launches on the IO scope, so reordering the two calls in `onDestroy` would only queue the session close and still race an in-flight tick. Instead the core gains two non-suspending methods:

```kotlin
fun attach() { detached = false }

fun detach() {
    tickJob?.cancel()
    tickJob = null
    currentSession = null
    detached = true
}
```

`detach()` cancels the ticker **before returning**, so once it returns no tick can fire and the caller may safely remove the overlay. `onDestroy` becomes `core.detach()` then `overlayManager.dismiss()`, both synchronous, order guaranteed by program order rather than by a coroutine boundary. The open session's unpersisted seconds are dropped, which is exactly the loss already accepted for a process death (Decision 10) — the two paths now fail identically instead of one of them racing.

`onForegroundChange` returns immediately while detached. The core is a process-wide singleton in `AppContainer`, and Android can reconnect the accessibility service to the same process, so `onServiceConnected` calls `attach()` to make `detach()` reversible rather than terminal.

### Why the ticker can stop while accrual continues

The ticker exiting on the crossing is not where accrual stops. Accrual is a property of `startedAtMillis` being read at leave time, entirely independent of whether a ticker is running. That separation is what lets the ticker be cheap without under-counting the minutes a user spends on a blocked app after dismissing the overlay.

### Both target behaviours

**Entering an app that is already over.** Stored `32m`, limit `30m` → step 4 fires on entry, `overlayShown = true`, no ticker. The user exits; on the next foreground event step 1 writes the exact elapsed seconds. Re-entering reads `≥ 30m` and fires again at step 4.

**Crossing the limit while using the app.** Stored `0`, limit `30m` → step 5 ticks. At `t+1800s` the recomputed total is `1800 >= 1800` → overlay, ticker exits. `startedAtMillis` is untouched, so leaving at `t+4200s` writes `4200` seconds, not `1800`. The next entry fires at step 4 because the stored total is now `70m`.

## Overlay

`OverlayManager(context)`. Unchanged, because they already work: the single-instance guard (`if (overlayView != null) return`), the full-screen `TYPE_APPLICATION_OVERLAY` window with an opaque white background, `isFocusableInTouchMode` + `requestFocus()` for BACK interception, the synthetic `OverlayLifecycleOwner` supplying lifecycle / saved-state / ViewModelStore to the Compose view tree, and the "Manage restriction" action relaunching Wanci with `EXTRA_REQUESTED_PACKAGE`.

BACK routes to `leaveApp()` — dismiss plus `ACTION_MAIN` / `CATEGORY_HOME` — and still returns `true` so the underlying app never receives it:

```kotlin
setOnKeyListener { _, keyCode, _ ->
    if (keyCode == KeyEvent.KEYCODE_BACK) {
        leaveApp()
        true
    } else {
        false
    }
}
```

`dismiss()` remains as an internal teardown path used only by `onDestroy`. It is no longer reachable from user input.

| Exit | Result |
|---|---|
| BACK or "Leave app" | Overlay removed, home launcher launched, session closes on the next event, exact elapsed seconds written. |
| "Manage restriction" | Overlay removed, Wanci launched with `EXTRA_REQUESTED_PACKAGE`, deep-links to that app's settings. Session closes and is written. On return a fresh session reads the new stored total and the new limit: raising the limit above the total means no overlay, lowering it means the overlay is up immediately. |

Text uses `formatUsage`, so an over-limit app reads `32m 14s / 30m` rather than a rounded `32 / 30`. The number shown is **frozen at the crossing** — it is not re-ticked while the overlay is up, because a counter climbing behind a dialog you are about to dismiss is noise, and the session accrues regardless.

The `addView` failure path stops being silent: it logs a warning naming the package. It does not retry — `showOnMain` runs on the main thread via a handler post, so a success signal would require a two-way asynchronous handshake, which is a lot of machinery for a state the onboarding overlay gate normally prevents.

## Failure handling

### Handled by design

| Case | Behaviour |
|---|---|
| Screen off | `ACTION_SCREEN_OFF` → `onForegroundChange(null)` → step 1 closes the session and writes elapsed. Deliberately **splits** the session, so pre-screen-off and post-unlock time are two records. |
| Doze mid-session | The ticker's `delay()` stalls on `elapsedRealtime`; the recomputation reads `uptimeMillis`, which did not advance during sleep. The tick resumes and computes the correct awake-time total. Self-correcting, no compensation code. |
| Wall-clock or timezone change | `uptimeMillis` is monotonic, so a clock change cannot corrupt a running session. Only `todayKey.todayIso()` is wall-clock-derived, so a timezone change while a session is open sends its write to the new date. |
| Session spans midnight | The write lands entirely on the new day's row. Accepted; splitting at the boundary needs a hook for a case affecting one row by at most one session. |
| Limit changed while a session is open | Not reachable. The limit is only editable from Wanci, and opening Wanci ends the session, so the captured `limitSeconds` is never stale. |
| Monitoring toggled off | Same reasoning. `enabled` is read at entry only, which is sufficient. |
| Monitored app uninstalled | Its row survives by design; it can never enter foreground, so no session opens. The dashboard already labels it as not installed. |
| Leave-write throws | Caught and logged inside step 1. Session state is already consistent at that point, so the cost is lost seconds, not lost tracking. Without this, one DB error on app A's leave would abort before step 2 and leave app B untracked for the rest of the foreground period. |

### Service death

A killed service loses the in-memory session, the ticker, and everything since the last write. This is accepted (Decision 10) rather than mitigated with periodic checkpointing, which would add a second accounting path and a drift risk to bound a loss that `UsageStatsManager` reconcile was previously — and incorrectly — trying to cover.

### Must be verified on a device

Whether the overlay window itself produces a `TYPE_WINDOW_STATE_CHANGED` event attributed to Wanci's package. If it does, showing the overlay feeds `onForegroundChange("com.septaalfauzan.wanci")` back into the core, which closes the session and writes the time. The outcome is still correct — the user is over limit and gets the overlay on re-entry — but the session bookkeeping churns. Verify on real hardware before this is considered done; it cannot be settled from the code.

## Testing

`MonotonicClock` bound to `TestScope.currentTime` makes every timing assertion virtual and instant.

### `MonitoringCoreTest` (commonTest, rewritten)

1. overlay fires on the tick where the total reaches the limit exactly — stored 29m, limit 30m, advance 60s → `usageSeconds == 1800`
2. entering an already-over app fires immediately and starts no ticker
3. usage keeps accruing after the overlay fires — cross at 1800s, leave at 4200s → 4200 recorded
4. one overlay per entry — cross, advance 10 min, no second decision; leave, re-enter → second decision
5. exact seconds with no one-minute floor — a 30s session records 30 (replaces `usageDeltasUnderOneMinuteAreDropped`, which asserted the old lossy behaviour)
6. an in-app dialog does not split the session — same package twice, full elapsed recorded once
7. screen off ends the session and records elapsed
8. deep-sleep time is not recorded — the virtual clock simply does not advance across it
9. unmonitored and disabled apps open no session
10. the self package closes the previous session and opens none
11. a failing leave-write still lets the next app open a session
12. a limit raised before re-entry is honoured — new limit above stored, no overlay

### Supporting changes

- `FakeRepository` gains the seconds API plus a `failOnRecord` toggle for test 11.
- `LimitCheckerTest` moves to the seconds boundary: `exceeds(1799, 1800)` false, `exceeds(1800, 1800)` true.
- `ScreenTimeRepositoryImplTest` renames to seconds; its two `reconcileDailyUsage` tests are deleted.
- `formatUsage` gets a commonTest table test: `0 → "0 sec"`, `45 → "45 sec"`, `60 → "1 min"`, `720 → "12 min"`, `3660 → "1 hour 1 min"`, `3840 → "1 hour 4 min"`.
- `DashboardViewModelTest`'s existing `app(usageMinutes, limitMinutes)` helper converts to seconds on the way into the model, which keeps its ~16 call sites and their assertions unchanged. One new case bypasses the helper with `todayUsageSeconds = 1_770` against a 30-minute limit: every existing fixture is a whole number of minutes and would pass even with the seconds conversion missing, so that one case is the only thing standing between a silent 60× error and a row that reads "limit reached" at 29m30s.
- `OverlayScreen` gets **no** automated test. `commonTest` depends only on `kotlin.test` and `kotlinx-coroutines-test`, there is no Compose UI test artifact in the version catalog, and no composable in this project has ever been rendered in a test. Adding a test dependency for two static strings is not worth it; the argument wiring is verified by hand instead. The logic that formatting *does* have is covered by the `formatUsage` table test.


### Migration test

The test that matters most, because Decision 7 exists specifically to avoid data loss. Robolectric is already wired up and `buildTestDatabase` (`TestBuilders.kt:10`) already accepts a full path. Create a file with the **v1** schema and statements via raw `SQLiteDatabase`, insert a `daily_usage` row at `usageMinutes = 42` and a `monitored_apps` row, open it with `createWanciDatabase(samePath)`, then assert the usage row reads back `2520` seconds and that the monitored app and its limit survived.

No `androidTest` source set is configured, so there is no instrumentation coverage of `MonitoringAccessibilityService` or `OverlayManager`. The on-device overlay-event question above is therefore a manual check.

## Implementation order

The change is one unit but has a hard dependency chain, because the repository cannot speak seconds until the schema can store them and the migration is registered on both platforms. The order the implementation plan should follow:

1. `MonotonicClock`, `formatUsage`, `MIGRATION_1_2`, the entity and DAO renames, and `addMigrations` on both platforms — with the migration test. Nothing else compiles until this lands.
2. `ScreenTimeRepository` / `ScreenTimeRepositoryImpl` in seconds, dropping `reconcileDailyUsage`.
3. `LimitChecker` in seconds, then the `MonitoringCore` rewrite.
4. `AppContainer`, `WanciApplication`, `MonitoringAccessibilityService`, `OverlayManager`, and the deletion of `UsageStatsReader`.
5. Domain and presentation: `MonitoredApp`, `WanciUseCases`, `dashboardRow`, and the three screens.
6. Test updates, replacing the assertions that encoded the old lossy behaviour.

Steps 1 and 2 are where a compile error is expected and harmless. A step 5 regression is the one that ships a 60× wrong number to users, so the `dashboardRow` case belongs with that step rather than the test cleanup in step 6.

## Files changed

| File | Change |
|---|---|
| `shared/.../monitoring/MonitoringCore.kt` | Rewrite around `Session`; delete the map, dedupe set, and timer API; add `attach` / `detach` |
| `shared/.../monitoring/MonotonicClock.kt` | **New** |
| `shared/.../monitoring/LimitChecker.kt` | Seconds boundary |
| `shared/.../data/local/WanciDatabase.kt` | `version = 2`, `exportSchema = true` |
| `shared/.../data/local/WanciMigrations.kt` | **New** — `MIGRATION_1_2` |
| `shared/.../data/local/DailyUsageDao.kt` | Seconds naming; delete `raiseToAtLeast` |
| `shared/.../data/local/entity/DailyUsageEntity.kt` | `usageSeconds` |
| `shared/.../data/repository/ScreenTimeRepository.kt` | Delete `reconcileDailyUsage`; rename to seconds |
| `shared/.../data/repository/ScreenTimeRepositoryImpl.kt` | Match the interface; domain mapping in seconds |
| `shared/.../domain/model/MonitoredApp.kt` | `todayUsageSeconds` |
| `shared/.../domain/useCase/WanciUseCases.kt` | Rename the usage accessor |
| `shared/.../presentation/dashboard/DashboardViewModel.kt` | `dashboardRow` converts to seconds before comparing |
| `shared/.../presentation/appsettings/AppSettingsViewModel.kt` | `formatUsageMinutes` moves out; seconds state field feeding `dashboardRow` |
| `shared/.../presentation/format/UsageFormat.kt` | **New** — `formatUsage` |
| `shared/.../presentation/overlay/OverlayScreen.kt` | `formatUsage` text |
| `shared/.../presentation/dashboard/DashboardScreen.kt` | `formatUsage` text |
| `shared/.../presentation/appsettings/AppSettingsScreen.kt` | `formatUsage` text |
| `shared/src/androidMain/.../PlatformDatabase.android.kt` | `.addMigrations(MIGRATION_1_2)` |
| `shared/src/iosMain/.../PlatformDatabase.ios.kt` | `.addMigrations(MIGRATION_1_2)` |
| `shared/build.gradle.kts` | `room.schemaLocation` KSP arg; commit `schemas/` |
| `androidApp/.../AppContainer.kt` | Bind `MonotonicClock`; drop `usageStatsReader`; run the prune |
| `androidApp/.../WanciApplication.kt` | 7-day prune on cold start |
| `androidApp/.../monitoring/MonitoringAccessibilityService.kt` | Delete the reconcile loop; drop the `eventTime` parameter; `attach()` / `detach()` around the service lifetime |
| `androidApp/.../monitoring/OverlayManager.kt` | Drop core/mutex/scope; BACK → `leaveApp()`; log `addView` failure |
| `androidApp/.../monitoring/UsageStatsReader.kt` | **Deleted** |
| `shared/src/commonTest/.../FakeRepository.kt` | Seconds API, `failOnRecord` |
| `shared/src/commonTest/.../MonitoringCoreTest.kt` | Rewrite, 12 cases |
| `shared/src/commonTest/.../LimitCheckerTest.kt` | Seconds boundary |
| `shared/src/androidHostTest/.../WanciDatabaseTest.kt` | Add the v1 → v2 migration test |
| `shared/src/androidHostTest/.../ScreenTimeRepositoryImplTest.kt` | Seconds; drop reconcile cases |

`AndroidManifest.xml` and the onboarding flow are deliberately untouched (Decision 4).
