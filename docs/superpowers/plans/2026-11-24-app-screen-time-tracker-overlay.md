# App Screen-Time Tracker Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a compact, live app screen-time pill that appears before the existing full-screen blocker and hands off to it when the limit is reached.

**Architecture:** `MonitoringCore` publishes a dedicated under-limit session decision so the tracker never flashes on already-over-limit entries. A shared `UsageTrackerController` owns the 1s usage tick and the 3s expand timer; Android's `OverlayManager` renders a small, top-centred `WindowManager` view whose pill is the only tappable area. The existing blocker stays untouched.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Kotlin coroutines, Android `WindowManager`, existing `AppIconProvider`.

**Spec:** `docs/superpowers/specs/2026-11-24-app-screen-time-tracker-overlay-design.md`

## Global Constraints

- Tracker shows only for monitored, enabled app with `limitSeconds != null` and `usageSeconds < limitSeconds`.
- Already-over-limit entry: no tracker, blocker only.
- Compact visual: 250×40dp, radius 20dp, top-centre y=10dp; hit area 250×48dp.
- Expanded: 358×110dp, radius 36dp, top-centre y=10dp.
- Tick interval: 1_000ms. Expand timeout: 3_000ms. Near-limit: `remaining <= limitSeconds * 0.10`.
- Palette: pill `#000`, primary `#FFF`, secondary `#B0B0B5`, camera `#1A1A1A`, track `White 18%`, safe `#30D158`, near `#FF9F0A`.
- Ring: stroke 5dp, 52×52 expanded with centred percent text, 28×28 compact with no text, starts at -90°.
- Expanded copy: safe `<usage> of <limit>`; near `<usage> <remaining> left`; secondary `<App> · screen time today`.
- Compact copy: `formatShortDuration` (e.g. `42m`). Overlay limit word is `hr`/`hrs`, not `hour`/`hours`.
- Blocker screen, copy, buttons, and trigger condition do not change.

## Review Focus

- Already-over-limit entry must not flash a tracker — Task 1 test `alreadyOverEntryFiresBlockerWithoutTrackerDecision`.
- No-limit day must never show a tracker — Task 1 test `noLimitDayFiresNoTrackerDecision`.
- Limit crossing while expanded must hide the pill before the blocker — Task 2 test `limitCrossedHidesAndStopsTicker`.
- Session close or service detach must not leave a zombie pill — Task 2 test `sessionClosedHidesAndStopsTicker`, Task 5 detach step.
- Touches outside the pill must reach the underlying app — Task 5 on-device verification step.

---

### Task 1: Core publishes an under-limit tracker decision

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/monitoring/MonitoringCore.kt`
- Test: `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/monitoring/MonitoringCoreTest.kt`

**Interfaces:**
- Consumes: `SessionSnapshot`, `LimitChecker.exceeds`, existing `onOverlayDecision`, `onSessionChanged`.
- Produces: `onUsageTrackerDecision: ((SessionSnapshot) -> Unit)? = null` constructor parameter, invoked once per under-limit session open.

- [ ] **Step 1: Extend the test helper and add failing tests**

In `MonitoringCoreTest`, add `private val trackerDecisions = mutableListOf<SessionSnapshot>()`, add `onUsageTrackerDecision: ((SessionSnapshot) -> Unit)? = null` to `TestScope.core(...)`, pass it into `MonitoringCore(...)`, and make the default lambda append to `trackerDecisions`. Add these tests:

```kotlin
@Test
fun trackerDecisionFiresForAnUnderLimitEntry() = runTest {
    val repo = repository()
    val core = core(repo)

    core.onForegroundChange(INSTAGRAM)

    val frame = trackerDecisions.single()
    assertEquals(INSTAGRAM, frame.packageName)
    assertEquals("Instagram", frame.appName)
    assertEquals(0L, frame.storedSeconds)
    assertEquals(1_800L, frame.limitSeconds)
    assertTrue(decisions.isEmpty(), "under the limit is not a blocker decision")
}

@Test
fun alreadyOverEntryFiresBlockerWithoutTrackerDecision() = runTest {
    val repo = repository()
    repo.recordUsageSeconds(INSTAGRAM, 30 * 60L, today.todayIso())
    val core = core(repo)

    core.onForegroundChange(INSTAGRAM)

    assertEquals(1, decisions.size)
    assertTrue(trackerDecisions.isEmpty(), "an already-over entry must not show the tracker first")
}

@Test
fun noLimitDayFiresNoTrackerDecision() = runTest {
    val repo = FakeRepository().apply {
        addMonitoredAppWithWeeklyLimitsBlocking(INSTAGRAM, "Instagram", WeeklyLimits())
    }
    val core = core(repo)

    core.onForegroundChange(INSTAGRAM)

    assertTrue(trackerDecisions.isEmpty())
    assertNull(core.activeTick)
}

@Test
fun midnightRolloverFiresTrackerUnderNewDay() = runTest {
    val key = MutableFakeTodayKey("2026-09-28")
    val repo = FakeRepository().apply {
        addMonitoredAppWithWeeklyLimitsBlocking(INSTAGRAM, "Instagram", WeeklyLimits(monday = 30, tuesday = 30))
    }
    val core = core(repo, todayKey = key)

    core.onForegroundChange(INSTAGRAM)
    advanceTimeBy(60_000L)
    key.date = "2026-09-29"
    advanceTimeBy(1_000L)
    runCurrent()

    assertEquals(2, trackerDecisions.size, "one for Monday, one for the reopened Tuesday session")
    assertTrue(decisions.isEmpty())
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests '*MonitoringCoreTest'`
Expected: FAIL — `onUsageTrackerDecision` unresolved / tracker list empty.

- [ ] **Step 3: Add the callback and invoke it only under the limit**

In `MonitoringCore`, add the constructor parameter after `onSessionChanged`. In `afterSessionOpened`, after the existing `LimitChecker.exceeds` early return and immediately before `startTicking()`, call `onUsageTrackerDecision?.invoke(session.toSnapshot())`. No other call sites.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:testAndroidHostTest --tests '*MonitoringCoreTest'`
Expected: PASS, including all pre-existing core tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/meotime/monitoring/MonitoringCore.kt shared/src/commonTest/kotlin/com/septaalfauzan/meotime/monitoring/MonitoringCoreTest.kt
git commit -m "feat: publish under-limit tracker decision from monitoring core"
```

### Task 2: Shared tracker controller

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/monitoring/UsageTrackerController.kt`
- Test: `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/monitoring/UsageTrackerControllerTest.kt`

**Interfaces:**
- Consumes: `SessionSnapshot`, `MonotonicClock`, `CoroutineScope` from Task 1.
- Produces:

```kotlin
data class TrackerFrame(
    val packageName: String,
    val appName: String,
    val usageSeconds: Long,
    val limitSeconds: Long,
    val percent: Int,
    val nearLimit: Boolean,
    val expanded: Boolean,
)

fun interface TrackerRenderer { fun render(frame: TrackerFrame?) }

class UsageTrackerController(
    private val renderer: TrackerRenderer,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    private val tickIntervalMillis: Long = 1_000L,
    private val expandTimeoutMillis: Long = 3_000L,
) {
    fun onSessionOpened(snapshot: SessionSnapshot)
    fun onSessionClosed()
    fun onLimitCrossed()
    fun onPillTapped()
}
```

- [ ] **Step 1: Write the failing controller tests**

```kotlin
@Test
fun openPublishesCompactFrame() = runTest {
    val controller = controller()
    controller.onSessionOpened(snapshot(stored = 600L))

    val frame = renderer.frames.last()!!
    assertEquals(600L, frame.usageSeconds)
    assertEquals(1_800L, frame.limitSeconds)
    assertEquals(33, frame.percent)
    assertFalse(frame.nearLimit)
    assertFalse(frame.expanded)
}

@Test
fun tenPercentRemainingMarksNearLimit() = runTest {
    val controller = controller()
    controller.onSessionOpened(snapshot(stored = 1_620L))
    assertTrue(renderer.frames.last()!!.nearLimit)

    controller.onSessionOpened(snapshot(stored = 1_601L))
    assertFalse(renderer.frames.last()!!.nearLimit)
}

@Test
fun tapExpandsThenAutoCollapses() = runTest {
    val controller = controller()
    controller.onSessionOpened(snapshot())
    controller.onPillTapped()
    assertTrue(renderer.frames.last()!!.expanded)

    advanceTimeBy(3_000L)
    runCurrent()
    assertFalse(renderer.frames.last()!!.expanded)
}

@Test
fun secondTapCollapsesImmediatelyAndCancelsTimer() = runTest {
    val controller = controller()
    controller.onSessionOpened(snapshot())
    controller.onPillTapped()
    controller.onPillTapped()
    assertFalse(renderer.frames.last()!!.expanded)

    val count = renderer.frames.size
    advanceTimeBy(3_000L)
    runCurrent()
    assertEquals(count, renderer.frames.size)
}

@Test
fun limitCrossedHidesAndStopsTicker() = runTest {
    val controller = controller()
    controller.onSessionOpened(snapshot())
    controller.onPillTapped()

    controller.onLimitCrossed()
    assertNull(renderer.frames.last())

    advanceTimeBy(2_000L)
    runCurrent()
    assertEquals(1, renderer.frames.count { it == null })
}

@Test
fun sessionClosedHidesAndStopsTicker() = runTest {
    val controller = controller()
    controller.onSessionOpened(snapshot())

    controller.onSessionClosed()
    assertNull(renderer.frames.last())

    advanceTimeBy(2_000L)
    runCurrent()
    assertEquals(1, renderer.frames.count { it == null })
}
```

Use `FakeRenderer : TrackerRenderer` with `val frames = mutableListOf<TrackerFrame?>()`, `snapshot(stored: Long = 600L, limit: Long = 1_800L, startedAt: Long = 0L)`, and `TestScope.controller() = UsageTrackerController(renderer, MonotonicClock { currentTime }, backgroundScope)`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests '*UsageTrackerControllerTest'`
Expected: FAIL — class unresolved.

- [ ] **Step 3: Implement the controller**

Hold one nullable `SessionSnapshot`, an `expanded` flag, a 1s ticker `Job`, and a 3s collapse `Job`. `onSessionOpened` stores the snapshot, resets `expanded = false`, cancels the collapse job, starts the ticker if absent, and publishes. `publish()` calls `renderer.render(null)` with no snapshot; otherwise derives `usageSeconds = snapshot.liveSeconds(clock.uptimeMillis())`, `percent = (usageSeconds * 100 / limitSeconds).coerceIn(0, 99)`, and `nearLimit = limitSeconds - usageSeconds <= limitSeconds * 0.10`. `onPillTapped()` toggles `expanded`, cancels the old collapse job, and schedules a new 3s collapse only when expanding. `onSessionClosed()` and `onLimitCrossed()` clear the snapshot, cancel both jobs, reset `expanded`, and render `null`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:testAndroidHostTest --tests '*UsageTrackerControllerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/meotime/monitoring/UsageTrackerController.kt shared/src/commonTest/kotlin/com/septaalfauzan/meotime/monitoring/UsageTrackerControllerTest.kt
git commit -m "feat: add realtime screen-time tracker controller"
```

### Task 3: Overlay duration formatter

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/format/UsageFormat.kt`
- Test: `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/format/UsageFormatTest.kt`

**Interfaces:**
- Consumes: none.
- Produces: `fun formatOverlayDuration(seconds: Long): String`, same minute/hour rules as `formatUsage` but `hr`/`hrs` instead of `hour`/`hours`; seconds below a minute stay `<n> sec`.

- [ ] **Step 1: Write the failing formatter test**

```kotlin
@Test
fun overlayDurationUsesReferenceHourWords() {
    assertEquals("45 sec", formatOverlayDuration(45L))
    assertEquals("5 min", formatOverlayDuration(300L))
    assertEquals("42 min", formatOverlayDuration(2_520L))
    assertEquals("1 hr", formatOverlayDuration(3_600L))
    assertEquals("1 hr 1 min", formatOverlayDuration(3_660L))
    assertEquals("2 hrs 30 min", formatOverlayDuration(9_000L))
    assertEquals("0 sec", formatOverlayDuration(-5L))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests '*UsageFormatTest'`
Expected: FAIL — unresolved reference.

- [ ] **Step 3: Implement `formatOverlayDuration`**

Use the same floored/no-clamp arithmetic as `formatUsage`; change only the whole-hour labels to `hr` and `hrs`. Do not modify existing formatter output.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:testAndroidHostTest --tests '*UsageFormatTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/format/UsageFormat.kt shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/format/UsageFormatTest.kt
git commit -m "feat: add reference overlay duration formatter"
```

### Task 4: Tracker composable

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/overlay/UsageTrackerOverlayScreen.kt`

**Interfaces:**
- Consumes: `TrackerFrame` from Task 2, `formatShortDuration` from existing code, `formatOverlayDuration` from Task 3, `ImageBitmap`.
- Produces:

```kotlin
@Composable
fun UsageTrackerOverlayScreen(
    frame: TrackerFrame,
    appIcon: ImageBitmap?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 1: Implement the composable in reference exact values**

Root is a transparent `Box` sized `if (frame.expanded) 358.dp to 110.dp else 250.dp to 48.dp`, clickable via `onTap`. Draw the visual pill inside at full size when expanded and `250.dp to 40.dp` top-aligned when compact; clip it to radius `36.dp`/`20.dp` and paint `#000`. The extra 8dp compact hit padding is below the pill and stays transparent. Draw camera dot `22.dp` at top-centre: `top = 0.dp` when expanded, `top = 9.dp` when compact, colour `#1A1A1A`.

Expanded content row: 44dp icon tile, secondary line `"${frame.appName} · screen time today"` 12sp `#B0B0B5`, headline 22sp/500 `#FFF` — safe `formatOverlayDuration(usageSeconds) + " of " + formatOverlayDuration(limitSeconds)`, near `formatOverlayDuration(usageSeconds) + " " + formatOverlayDuration(limitSeconds - usageSeconds) + " left"`. Compact content row: 24dp icon tile, `formatShortDuration(frame.usageSeconds)` 14sp/500 `#FFF`.

Icon is `Image(bitmap = appIcon)` when non-null; otherwise a white rounded tile with an 8dp `#1A73E8` top border. Ring is a private `TrackerRing(percent, nearLimit, expanded, modifier)` composable using `Canvas`: track `Color.White.copy(alpha = 0.18f)`, arc `#30D158` or `#FF9F0A`, stroke 5dp, `strokeCap = RoundCap`, start angle -90°, size 52dp expanded / 28dp compact. Expanded ring draws centred `"${frame.percent}%"` 13sp/500 `#FFF`; compact draws no text.

Set pill `contentDescription` to `"${frame.appName}, ${frame.usageSeconds} seconds of ${frame.limitSeconds}, ${frame.percent}% of daily limit used. ${if (frame.expanded) "Tap to collapse." else "Tap to expand."}"` and ring semantics to `"${frame.percent}% of daily limit used"`.

- [ ] **Step 2: Verify compile**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL; no new compile warnings from the composable.

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/overlay/UsageTrackerOverlayScreen.kt
git commit -m "feat: add reference screen-time tracker pill UI"
```

### Task 5: Android tracker window and wiring

**Files:**
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/meotime/monitoring/OverlayManager.kt`
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/meotime/AppContainer.kt`
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/meotime/monitoring/MonitoringForegroundService.kt`

**Interfaces:**
- Consumes: `TrackerFrame`, `TrackerRenderer`, `UsageTrackerController`, `UsageTrackerOverlayScreen`, `AppIconProvider`.
- Produces: `OverlayManager.renderTracker(frame: TrackerFrame?)`, `OverlayManager.onTrackerTap: () -> Unit`, `AppContainer.usageTrackerController`.

- [ ] **Step 1: Add the tracker window to `OverlayManager`**

Change constructor to `OverlayManager(context: Context, appIconProvider: AppIconProvider, scope: CoroutineScope)`. Add `var onTrackerTap: () -> Unit = {}`. Add `fun renderTracker(frame: TrackerFrame?)` posting to main. On non-null frame: create a small `ComposeView` once with `TYPE_APPLICATION_OVERLAY`, `PixelFormat.TRANSLUCENT`, `Gravity.TOP or Gravity.CENTER_HORIZONTAL`, `y = 10dp`, cutout mode `SHORT_EDGES`, no view background, and `setContent { UsageTrackerOverlayScreen(frame, icon, onTap = { onTrackerTap() }) }`. Use `WindowManager.LayoutParams.WRAP_CONTENT` width/height and call `updateViewLayout` when `frame.expanded` changes between 250×48 and 358×110. Load icon via `appIconProvider.getIcon(frame.packageName)` in the injected `scope` only when the package changes; pass `null` while loading and post the result back with `runOnMain` before updating `setContent` state. On `null`, `removeView` and clear state. Do not add a BACK key listener and do not make the window full-screen. `show(decision)` must call `renderTracker(null)` before adding the blocker view.

- [ ] **Step 2: Wire the controller in `AppContainer`**

Add `private val appIconProvider = AppIconProviderAndroid(context)` and `internal lateinit var usageTrackerController: UsageTrackerController`. In the `MonitoringCore` constructor: `onUsageTrackerDecision = { usageTrackerController.onSessionOpened(it) }`; in `onOverlayDecision`, call `usageTrackerController.onLimitCrossed()` **before** `overlayManager.show(decision)`; in `onSessionChanged`, call `usageTrackerController.onSessionClosed()` when the snapshot is `null`, then the existing notification call. In `init`, construct `overlayManager = OverlayManager(context, appIconProvider, scope)`, then `usageTrackerController = UsageTrackerController(renderer = TrackerRenderer { overlayManager.renderTracker(it) }, clock = clock, scope = scope)`, then set `overlayManager.onTrackerTap = { usageTrackerController.onPillTapped() }`. Replace the `AppIconProviderAndroid(context)` in `toDependencies()` with the new field.

- [ ] **Step 3: Hide tracker on service teardown**

In `MonitoringForegroundService.onDestroy()`, after `container?.core?.detach()` and before `container?.overlayManager?.dismiss()`, call `container?.usageTrackerController?.onSessionClosed()`.

- [ ] **Step 4: Verify full suite and on-device touch behaviour**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

On device: open a limited app under its limit → compact pill appears; tap pill → expanded; tap again or wait 3s → compact; tap calendar row under/around pill → underlying app receives touch; reach limit → pill disappears and existing blocker shows; an already-over app shows blocker only; close app/screen off → pill disappears.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/com/septaalfauzan/meotime/monitoring/OverlayManager.kt androidApp/src/main/kotlin/com/septaalfauzan/meotime/AppContainer.kt androidApp/src/main/kotlin/com/septaalfauzan/meotime/monitoring/MonitoringForegroundService.kt
git commit -m "feat: show tracker pill over limited apps before blocker"
```

## Self-review

- Spec coverage: under-limit trigger (T1), blocker handoff (T1/T5), compact/tap/3s (T2/T4/T5), 10% near-limit (T2/T4), exact reference visuals/copy (T3/T4), real icon (T5), pass-through (T5), no-limit and midnight (T1), teardown (T2/T5).
- Type consistency: `SessionSnapshot` → `TrackerFrame` → `OverlayManager.renderTracker` → `UsageTrackerOverlayScreen`; names match across tasks.
- No task writes code already determined by its tests; bodies appear only where exact values must be copied from the spec.
