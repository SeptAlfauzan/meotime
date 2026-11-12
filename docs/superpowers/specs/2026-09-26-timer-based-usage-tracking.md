# Meotime — Timer-Based Continuous Usage Tracking

Date: 2026-09-26
Status: Draft

## Problem

Current `MonitoringCore.onForegroundChange` only triggers overlay when **leaving** a restricted app (checks `previousPackage`). It cannot:
1. Show overlay when **opening** a restricted app that already exceeded its limit
2. Track usage continuously while the app is actively in foreground

Root cause: `onForegroundChange` is event-driven (fires only on `TYPE_WINDOW_STATE_CHANGED`). While the app is in foreground, no events fire, so usage is not accumulated.

## Approach

Accessibility + internal timer. When a restricted app enters foreground, start a `delay()`-based coroutine that accumulates usage every 5 seconds. When the running total crosses the limit, show the overlay. On app leave or screen off, stop the timer and persist accumulated time to Room.

This avoids expensive `UsageStatsManager.queryEvents()` polling.

## Components

### MonitoringCore (shared)

**New state:**
```kotlin
private val activeTimers = mutableMapOf<String, Job>()
```

**`startUsageTimer(packageName: String)`**:
1. Call `checkCurrentApp(packageName)`:
   - Returns `OverlayDecision` → show overlay immediately, **do not start timer** (already exceeded)
   - Returns null because app is not monitored or not enabled → do nothing
   - Returns null but app IS monitored and enabled → start the timer
2. If starting: launch coroutine in `scope` that every 5s:
   - Increments a per-app counter by 5 seconds
   - Checks `(roomUsage + counter) >= limit` → if yes, emit `OverlayDecision`
3. Store `Job` in `activeTimers[packageName]`

**`stopUsageTimer(packageName: String)`**:
1. Cancel the `Job` from `activeTimers[packageName]`
2. Persist accumulated counter to Room via `repository.recordUsage(packageName, accumulatedSeconds / 60_000L, date)`
3. Remove from `activeTimers`

**`onForegroundChange`** — modified to manage timer lifecycle:
1. Stop timer for `previousPackage` (the app being left)
2. Set `lastPackage = packageName`
3. Start timer for `packageName` (the app entering, if restricted)
4. Existing delta recording and overlay logic unchanged

**`checkCurrentApp`** — already added in the reverted commit. Reads Room, checks limit, uses `triggeredByDate` dedup.

### MonitoringAccessibilityService (androidApp)

No changes needed. Timer lifecycle is fully managed inside `MonitoringCore.onForegroundChange`. The service continues to call `enqueueForegroundChange` as before.

**Screen off** (`screenReceiver`): calls `enqueueForegroundChange(null, uptimeMillis)`. With `packageName = null`, `onForegroundChange` will:
- `previousPackage = lastPackage` (the app being left)
- `stopUsageTimer(previousPackage)`
- `lastPackage = null`
- `startUsageTimer(null)` → returns early (null check)

### Screen Off Handling

Existing `screenReceiver` behavior is preserved. `enqueueForegroundChange(null, ...)` triggers `onForegroundChange` with null, which stops the active timer and persists accumulated time.

### Service Death Recovery

Existing `UsageStatsReader.reconcile()` (15-min interval) heals gaps. Timers are lost on death, but Room has persisted usage from `stopUsageTimer` calls. `reconcile` fills gaps between last stop and death.

### Overlay Dedup

`triggeredByDate` in `MonitoringCore` prevents duplicate overlays for the same app on the same day. `checkCurrentApp` and `startUsageTimer` both use this mechanism.

## Data Flow

```
User opens restricted App B (30min limit, 30min used today)

TYPE_WINDOW_STATE_CHANGED: event.packageName = B
  → onForegroundChange(B, eventTime)
    → previousPackage = A
    → stopUsageTimer(A)
    → lastPackage = B
    → startUsageTimer(B)
      → checkCurrentApp(B) → Room has 30min >= 30min limit
      → OverlayDecision! → overlay shows immediately
      → (timer not started, already exceeded)

User continues using B
  → No events fire while B is in foreground
  → Timer is running, ticks every 5s
  → After 5s: counter = 5s, roomUsage + counter < limit → no overlay
  → ... continues ticking ...
  → When roomUsage + counter >= 30min → overlay shows

User switches to App C
  → TYPE_WINDOW_STATE_CHANGED: event.packageName = C
    → onForegroundChange(C, eventTime)
      → previousPackage = B
      → stopUsageTimer(B) → persist counter to Room
      → lastPackage = C
      → startUsageTimer(C)
      → checkCurrentApp(C) → check limits
```

## Edge Cases

- **Service death during timer**: Timer lost. `reconcile` heals gap. `checkCurrentApp` on next enter reads Room (may be slightly behind).
- **Screen off**: `screenReceiver` → `enqueueForegroundChange(null, ...)` → `onForegroundChange(null, ...)` → stops timer, persists to Room.
- **App uninstalled**: `getMonitoredApp` returns null → timer not started. No-op.
- **Meotime itself**: `selfPackageName` check in `startUsageTimer`/`checkCurrentApp` prevents self-monitoring.
- **Split-screen**: `TYPE_WINDOW_STATE_CHANGED` fires with top-window package. Timer tracks the top app.

## Files Changed

| File | Change |
|---|---|
| `shared/.../monitoring/MonitoringCore.kt` | Add `activeTimers`, `startUsageTimer`, `stopUsageTimer`; modify `onForegroundChange` |
| `androidApp/.../monitoring/MonitoringAccessibilityService.kt` | No changes needed (timer logic moves into `onForegroundChange`) |

## Testing

- `MonitoringCore` JVM tests: timer starts/stops, accumulates correctly, shows overlay at limit, persists on stop
- Screen off test: timer stops, Room updated
- Already-exceeded at enter: `checkCurrentApp` returns `OverlayDecision` immediately
- Service death: `reconcile` fills gap
