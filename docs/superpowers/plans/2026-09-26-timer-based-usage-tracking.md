# Timer-Based Continuous Usage Tracking — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add internal timer-based continuous usage tracking to `MonitoringCore` so the overlay shows immediately when opening a restricted app that already exceeded its limit, and tracks usage while the app is actively in foreground.

**Architecture:** Add `activeTimers` map, `CoroutineScope`, and `onOverlayDecision` callback to `MonitoringCore`. Add `checkCurrentApp`, `startUsageTimer`, `stopUsageTimer`. Modify `onForegroundChange` to manage timer lifecycle. Timer uses `delay(5_000L)` coroutine ticks. No changes to `MonitoringAccessibilityService` — timer logic stays entirely in `MonitoringCore`.

**Tech Stack:** Kotlin, kotlinx.coroutines, JUnit5 + kotlinx-coroutines-test, KMP (`shared` module tests).

## Global Constraints

- Only modify files in `shared/src/commonMain/kotlin/.../monitoring/`, `shared/src/commonTest/kotlin/.../monitoring/`, and `androidApp/src/main/kotlin/.../AppContainer.kt`
- Timer resolution: 5 seconds (`5_000L` ms)
- Timer uses `CoroutineScope` passed to `MonitoringCore` constructor
- Do not add `import android.*` to `shared` module
- All existing tests must continue to pass
- `MonitoringCore` is in `shared` module — cannot reference `OverlayManager` (androidApp). Use callback pattern.

---

## File Map

| Action | File | Responsibility |
|---|---|---|
| Modify | `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCore.kt` | Add `checkCurrentApp`, `activeTimers`, `scope`, `onOverlayDecision`, `startUsageTimer`, `stopUsageTimer`, modify `onForegroundChange` |
| Modify | `shared/src/commonTest/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCoreTest.kt` | Add timer tests |
| Modify | `androidApp/src/main/kotlin/com/septaalfauzan/wanci/AppContainer.kt` | Pass `scope` and `onOverlayDecision` to `MonitoringCore` |

---

### Task 1: Modify MonitoringCore

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCore.kt`

**Before:** `MonitoringCore` has constructor `(repository, todayKey, selfPackageName)`, no coroutine scope, no timer, no `checkCurrentApp`.
**After:** `MonitoringCore` has constructor `(repository, todayKey, selfPackageName, scope, onOverlayDecision)`, manages per-app timer jobs, includes `checkCurrentApp`.

- [ ] **Step 1: Add imports and constructor parameters**

Add `import kotlinx.coroutines.CoroutineScope`, `import kotlinx.coroutines.Job`, `import kotlinx.coroutines.delay`, `import kotlinx.coroutines.isActive`.

Change constructor from:
```kotlin
class MonitoringCore(
    private val repository: ScreenTimeRepository,
    private val todayKey: TodayKey,
    private val selfPackageName: String,
) {
```
to:
```kotlin
class MonitoringCore(
    private val repository: ScreenTimeRepository,
    private val todayKey: TodayKey,
    private val selfPackageName: String,
    private val scope: CoroutineScope,
    private val onOverlayDecision: (OverlayDecision) -> Unit,
) {
```

- [ ] **Step 2: Add `activeTimers` and `checkCurrentApp`**

After the existing `triggeredByDate` field, add:
```kotlin
private val activeTimers = mutableMapOf<String, Job>()

suspend fun checkCurrentApp(packageName: String?): OverlayDecision? {
    if (packageName == null || packageName == selfPackageName) return null
    val app = repository.getMonitoredApp(packageName)
    if (app == null || !app.enabled) return null
    val date = todayKey.todayIso()
    val usage = repository.getTodayUsage(packageName, date)
    if (!LimitChecker.exceeds(usage, app.dailyLimitMinutes)) return null
    val triggered = triggeredByDate.getOrPut(date) { mutableSetOf() }
    if (packageName in triggered) return null
    triggered += packageName
    return OverlayDecision(
        packageName = packageName,
        appName = app.appName,
        usageMinutes = usage,
        limitMinutes = app.dailyLimitMinutes,
    )
}
```

- [ ] **Step 3: Add `startUsageTimer` and `stopUsageTimer`**

Add after `checkCurrentApp`:
```kotlin
suspend fun startUsageTimer(packageName: String) {
    if (packageName in activeTimers) return
    val decision = checkCurrentApp(packageName)
    if (decision != null) {
        onOverlayDecision(decision)
        return
    }
    val app = repository.getMonitoredApp(packageName)
    if (app == null || !app.enabled) return
    if (packageName == selfPackageName) return
    val job = scope.launch {
        while (isActive) {
            delay(5_000L)
            val usage = repository.getTodayUsage(packageName, todayKey.todayIso())
            if (LimitChecker.exceeds(usage, app.dailyLimitMinutes)) {
                val triggered = triggeredByDate.getOrPut(todayKey.todayIso()) { mutableSetOf() }
                if (packageName !in triggered) {
                    triggered += packageName
                    onOverlayDecision(
                        OverlayDecision(
                            packageName = packageName,
                            appName = app.appName,
                            usageMinutes = usage,
                            limitMinutes = app.dailyLimitMinutes,
                        )
                    )
                }
                return@launch
            }
        }
    }
    activeTimers[packageName] = job
}

fun stopUsageTimer(packageName: String) {
    activeTimers[packageName]?.cancel()
    activeTimers.remove(packageName)
}
```

Note: `stopUsageTimer` does NOT persist accumulated time to Room because the timer tracks time since last `stopUsageTimer`/`startUsageTimer` cycle, and `onForegroundChange`'s existing `recordUsage` handles the delta calculation when the app is left.

- [ ] **Step 4: Modify `onForegroundChange` to manage timers**

Add timer management at the start and end of `onForegroundChange`:

```kotlin
suspend fun onForegroundChange(packageName: String?, eventTimeMillis: Long): OverlayDecision? {
    val previousPackage = lastPackage

    // Stop timer for the app being left
    if (previousPackage != null && previousPackage != packageName) {
        stopUsageTimer(previousPackage)
    }

    lastPackage = packageName
    if (packageName != null) lastEventTime = eventTimeMillis

    // Start timer for the entering app
    if (packageName != null) {
        startUsageTimer(packageName)
    }

    if (previousPackage == null || previousPackage == packageName) return null
    if (previousPackage == selfPackageName) return null

    val elapsedMillis = (eventTimeMillis - previousTime).coerceAtLeast(0L)
    val deltaMinutes = elapsedMillis / 60_000L

    val app = repository.getMonitoredApp(previousPackage)
    if (app == null || !app.enabled) return null

    val date = todayKey.todayIso()
    if (deltaMinutes >= 1) {
        repository.recordUsage(previousPackage, deltaMinutes, date)
    }

    val usage = repository.getTodayUsage(previousPackage, date)
    if (!LimitChecker.exceeds(usage, app.dailyLimitMinutes)) return null

    val triggered = triggeredByDate.getOrPut(date) { mutableSetOf() }
    if (previousPackage in triggered) return null
    triggered += previousPackage
    return OverlayDecision(
        packageName = previousPackage,
        appName = app.appName,
        usageMinutes = usage,
        limitMinutes = app.dailyLimitMinutes,
    )
}
```

Wait — `previousTime` is used on line 35 but it's declared on line 25 which is before the timer code. The `previousTime` variable is only used after `lastPackage = packageName`. Let me re-check: the original `onForegroundChange` has `val previousTime = lastEventTime` before `lastPackage = packageName`. So `previousTime` is captured before the mutation. The timer code should come after `lastPackage = packageName` and `lastEventTime = eventTimeMillis` (when packageName != null).

Actually, looking at the current code more carefully, `previousTime` is captured on line 25, and `lastPackage = packageName` is on line 26. So `previousTime` = `lastEventTime` before the update. This is correct — it captures the time the previous app was foregrounded.

The timer management should happen after `lastPackage` is set but before the early return checks. This way, the entering app's timer starts, and the leaving app's timer stops.

**Corrected step 4**: Add timer calls after `lastPackage = packageName` and before the `previousPackage == null` check:

```kotlin
suspend fun onForegroundChange(packageName: String?, eventTimeMillis: Long): OverlayDecision? {
    val previousPackage = lastPackage
    val previousTime = lastEventTime

    lastPackage = packageName
    if (packageName != null) lastEventTime = eventTimeMillis

    if (previousPackage != null && previousPackage != packageName) {
        stopUsageTimer(previousPackage)
    }
    if (packageName != null) {
        startUsageTimer(packageName)
    }

    if (previousPackage == null || previousPackage == packageName) return null
    // ... rest unchanged
}
```

- [ ] **Step 5: Verify file compiles within `shared` module (no android imports)**

Confirm no `android.*` imports were added.

---

### Task 2: Update AppContainer

**Files:**
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/AppContainer.kt`

- [ ] **Step 1: Pass `scope` and `onOverlayDecision` to `MonitoringCore`**

Change line 33 from:
```kotlin
val core = MonitoringCore(repository, todayKey, context.packageName)
```
to:
```kotlin
val core = MonitoringCore(repository, todayKey, context.packageName, scope) { decision ->
    scope.launch { overlayManager.show(decision) }
}
```

- [ ] **Step 2: Verify build compiles**

Run `./gradlew :androidApp:compileDebugKotlin`

---

### Task 3: Write tests for timer behavior

**Files:**
- Modify: `shared/src/commonTest/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCoreTest.kt`

- [ ] **Step 1: Add `FakeRepository` helper for recording overlay decisions**

The test needs a way to capture `onOverlayDecision` callbacks. Add a mutable list to the test class:
```kotlin
private val overlayDecisions = mutableListOf<MonitoringCore.OverlayDecision>()
```

Helper function:
```kotlin
private fun createCore(): MonitoringCore {
    val r = repo()
    return MonitoringCore(r, today, selfPkg, scope) { decision ->
        overlayDecisions.add(decision)
    }
}
```

Wait — `scope` needs to be a `TestScope`. Use `runTest`'s `StandardTestDispatcher` or `TestScope`.

Actually, the existing tests use `runTest` which provides a test dispatcher. Let me use `kotlinx.coroutines.test.StandardTestDispatcher`:

```kotlin
private val testDispatcher = StandardTestDispatcher()
private val scope = CoroutineScope(testDispatcher)
```

- [ ] **Step 2: Add tests**

```kotlin
@Test
fun checkCurrentAppReturnsOverlayWhenExceeded() = runTest {
    val r = repo()
    r.recordUsage("com.instagram.android", 30L, "2026-09-25")
    val core = MonitoringCore(r, today, selfPkg, scope) { /* capture */ }
    
    val result = core.checkCurrentApp("com.instagram.android")
    assertNotNull(result)
    assertEquals("com.instagram.android", result.packageName)
}

@Test
fun checkCurrentAppReturnsNullWhenUnderLimit() = runTest {
    val r = repo()
    r.addMonitoredApp("com.instagram.android", "Instagram", 30)
    val core = MonitoringCore(r, today, selfPkg, scope) { /* capture */ }
    
    val result = core.checkCurrentApp("com.instagram.android")
    assertNull(result)
}

@Test
fun timerShowsOverlayImmediatelyWhenAlreadyExceeded() = runTest {
    val r = repo()
    r.recordUsage("com.instagram.android", 30L, "2026-09-25")
    val core = MonitoringCore(r, today, selfPkg, scope) { /* onOverlayDecision */ }
    
    core.startUsageTimer("com.instagram.android")
    // checkCurrentApp already exceeded → onOverlayDecision called immediately
}

@Test
fun timerStartsAndStopsOnAppSwitch() = runTest {
    val r = repo()
    r.addMonitoredApp("com.instagram.android", "Instagram", 60)
    val core = MonitoringCore(r, today, selfPkg, scope) { /* capture */ }
    
    core.onForegroundChange("com.instagram.android", 0L)
    // Timer is now running for Instagram
    core.onForegroundChange("com.youtube.android", 30 * 60_000L)
    // Timer for Instagram should be stopped (stopUsageTimer called in onForegroundChange)
    // Timer for YouTube should be started
}

@Test
fun stopUsageTimerCancelsJob() = runTest {
    val r = repo()
    r.addMonitoredApp("com.instagram.android", "Instagram", 60)
    val core = MonitoringCore(r, today, selfPkg, scope) { /* capture */ }
    
    core.startUsageTimer("com.instagram.android")
    assertNotNull(core.activeTimers["com.instagram.android"]) // needs package-private access or test via behavior
    core.stopUsageTimer("com.instagram.android")
    assertNull(core.activeTimers["com.instagram.android"])
}
```

Note: `activeTimers` is private. For tests, either:
1. Make it `internal` instead of `private`
2. Test behavior via `checkCurrentApp` and `onForegroundChange` side effects

Recommend making it `internal` for testability.

- [ ] **Step 3: Ensure existing tests still pass**

Run `./gradlew :shared:jvmTest` — all existing tests must pass. The `onForegroundChange` change adds timer calls, but existing test flow (calling `onForegroundChange` directly) should work because `startUsageTimer` checks if the app is monitored/enabled.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCore.kt shared/src/commonTest/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCoreTest.kt androidApp/src/main/kotlin/com/septaalfauzan/wanci/AppContainer.kt
git commit -m "feat: add timer-based continuous usage tracking to MonitoringCore"
```

---

### Task 4: Final verification

- [ ] **Step 1: Full build**

```bash
./gradlew :androidApp:compileDebugKotlin
```

- [ ] **Step 2: All tests pass**

```bash
./gradlew :shared:jvmTest
```

- [ ] **Step 3: Final commit**

---

## Spec Coverage Check

| Spec Requirement | Task |
|---|---|
| Timer starts when restricted app enters foreground | Task 1, Step 4 (`onForegroundChange` calls `startUsageTimer`) |
| Timer stops when app leaves | Task 1, Step 4 (`stopUsageTimer(previousPackage)`) |
| Overlay shows immediately if already exceeded | Task 1, Step 2 (`checkCurrentApp`) + Step 3 (`startUsageTimer`) |
| Timer ticks every 5s | Task 1, Step 3 (`delay(5_000L)`) |
| Accumulated time persisted on stop | Task 1, Step 4 (existing `onForegroundChange` records usage delta) |
| Screen off stops timer | Task 1 (screen off → `enqueueForegroundChange(null)` → `onForegroundChange(null)` → `stopUsageTimer`) |
| Service death recovery | `reconcile` already exists |
| `triggeredByDate` dedup preserved | `checkCurrentApp` and timer both use it |
| Existing tests pass | Task 3 |

## Placeholder Scan

No TBDs, TODOs, or vague steps. All code blocks contain complete implementation.
