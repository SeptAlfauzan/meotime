# Wanci — Accessibility to UsageStatsManager Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `AccessibilityService` foreground-detection source with a `UsageStatsManager` poll loop hosted by a foreground service, so banking apps (mBanking and equivalents) no longer refuse to run while Wanci is installed, without losing enforcement while the app is closed.

**Architecture:** `MonitoringCore` stays source-agnostic and keeps its single-session accounting; it gains one `onSessionChanged` callback so a new `NotificationController` can mirror session state into a persistent notification. A new `MonitoringForegroundService` polls `UsageStatsManager.queryEvents` once a second while the screen is on, delivers only the latest foreground package through the existing mutex-serialized `enqueueForegroundChange` path, and a `BootReceiver` restarts it after reboot. The accessibility service, its config XML, its manifest block, its permission check and its onboarding row are deleted outright.

**Tech Stack:** Kotlin 2.4.20, AGP 9.1.1, minSdk 28, targetSdk 37, compileSdk 37, JVM target 11, kotlinx-coroutines 1.10.2, Compose Multiplatform 1.12.1. **No new Gradle dependency** — every platform call this migration needs (`NotificationManager.areNotificationsEnabled`, `Activity.checkSelfPermission`, `Notification.Builder(context, channelId)`) is on API 23/26/24, all below the API 28 floor.

**Spec:** `docs/superpowers/specs/2026-09-28-usagestats-monitoring-design.md`

## Global Constraints

- Package root: `com.septaalfauzan.wanci`. Shared code: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/`. Android bindings: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/`.
- minSdk 28, targetSdk 37, compileSdk 37, JVM target 11. API 34+ `foregroundServiceType="specialUse"` with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property.
- `UsageEvents.Event` constants are compile-time `static final int`s, so referencing `ACTIVITY_RESUMED` (API 29+) from a `minSdk 28` module is safe — the value is inlined at compile time and the constant is never read at runtime on API 28.
- **`UsageEvents.Event.SCREEN_NON_INTERACTIVE` is `16`, the same value as `MOVE_TO_FOREGROUND`.** The two are indistinguishable by type, so `16` is read as **foreground** and must never be handled as a background signal. Screen-off is closed by the `SCREEN_OFF` broadcast instead, which is unambiguous. `ForegroundEventParserTest.typeSixteenIsTreatedAsForegroundAndUnwindsAPrecedingPause` pins this decision.
- Foreground event types handled: `MOVE_TO_FOREGROUND` (16), `ACTIVITY_RESUMED` (1). Background event types handled: `MOVE_TO_BACKGROUND` (17), `ACTIVITY_PAUSED` (2). Everything else is ignored.
- No new source of truth: session boundaries use the core's own `MonotonicClock` at processing time. UsageStats event timestamps are read for nothing.
- Usage is written exactly once per session, at close. No reconcile, no catch-up after service death.
- Poll cadence 1s while screen is on; zero polls while the screen is dark. Notification rollover cadence 60s, only while a session is open.
- Notification channel `IMPORTANCE_LOW`, `VISIBILITY_PRIVATE`, content intent opens the Wanci dashboard, id `2001`, channel id `wanci_monitoring`.
- Notification text literals (`"Monitoring active"`, `"Usage Access needed"`, `"Tap to grant it again."`) live in `NotificationController`, not in `strings.xml` — that class has no `Context`. Only `notification_channel_name` is a resource.
- Tests: `./gradlew :shared:testAndroidHostTest` and `./gradlew :androidApp:testDebugUnitTest`; Android build check `./gradlew :androidApp:assembleDebug`.
- Every task ends with a commit. Conventional Commits, subject ≤ 50 chars.
- No new comments except where the codebase's existing style demands reasoning that is not obvious from the code (KDoc on non-obvious invariants, platform quirks, and ordering constraints).

---

## Deviations from the spec (and why)

1. **`onSessionChanged` signature.** The spec's 5-argument function type has no way to express "the session closed". The plan uses `(SessionSnapshot?) -> Unit`, where `null` is the close. Same information, one signal instead of two.
2. **`parseForegroundPackage` lives in its own file**, not inside `MonitoringForegroundService.kt`. The spec's stated reason for keeping it near the service is that it must be host-testable without instantiating an Android service; a separate file achieves that more cleanly, and it keeps the service file about lifecycle rather than parsing.
3. **`NotificationController` is split into a pure state machine and an Android notifier.** The spec describes one class owning channel creation and cadence. The cadence/content half is pure and gets a fake-notifier host test as the spec requires; the channel/notify half is Android-only. `AndroidNotifier` is the concrete `Notifier`; `NotificationController` never imports `android.*`.
4. **Limit-crossing notification refresh is wired through `onOverlayDecision` in `AppContainer`, not through a new core callback.** The core already emits that signal; a second callback for the same event would be a duplicate source.

## File Structure Map

```
shared/src/commonMain/kotlin/com/septaalfauzan/wanci/
├── monitoring/
│   ├── SessionSnapshot.kt             # NEW  - snapshot data class with liveSeconds()
│   └── MonitoringCore.kt              # MOD  - onSessionChanged callback
├── domain/Permissions.kt              # MOD  - notification in status, drop accessibility
├── presentation/
│   ├── App.kt                         # MOD  - new deps fields
│   ├── navigation/AppNavHost.kt       # MOD  - pass new deps to OnboardingViewModel
│   ├── format/UsageFormat.kt          # MOD  - formatShortDuration()
│   └── onboarding/                    # MOD  - view model + screen
shared/src/commonTest/kotlin/com/septaalfauzan/wanci/
├── monitoring/MonitoringCoreTest.kt   # MOD  - onSessionChanged cases
├── presentation/format/UsageFormatTest.kt        # MOD  - formatShortDuration cases
└── presentation/onboarding/OnboardingViewModelTest.kt  # MOD  - notification row

androidApp/src/main/kotlin/com/septaalfauzan/wanci/
├── MainActivity.kt                    # MOD  - notification launcher + start-on-open
├── AppContainer.kt                    # MOD  - notifier/controller/startMonitoring
├── monitoring/
│   ├── ForegroundEventParser.kt       # NEW  - pure parseForegroundPackage
│   ├── NotificationController.kt      # NEW  - pure state machine
│   ├── AndroidNotifier.kt             # NEW  - NotificationManager + channel + intent
│   ├── MonitoringForegroundService.kt # NEW  - lifecycle + poll loop + receivers
│   ├── BootReceiver.kt                # NEW  - BOOT_COMPLETED
│   └── MonitoringAccessibilityService.kt  # DELETE
androidApp/src/test/kotlin/com/septaalfauzan/wanci/monitoring/   # NEW source set
├── ForegroundEventParserTest.kt
└── NotificationControllerTest.kt
androidApp/src/main/AndroidManifest.xml # MOD
androidApp/src/main/res/values/strings.xml  # MOD
androidApp/src/main/res/xml/accessibility_service_config.xml  # DELETE
androidApp/build.gradle.kts             # MOD
```

---

### Task 1: `SessionSnapshot` + `onSessionChanged` on `MonitoringCore`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/SessionSnapshot.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCore.kt`
- Test: `shared/src/commonTest/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCoreTest.kt`

**Interfaces:**
- Consumes: nothing (first task).
- Produces:
  - `data class SessionSnapshot(packageName: String, appName: String, startedAtMillis: Long, storedSeconds: Long, limitSeconds: Long)` with `fun liveSeconds(nowMillis: Long): Long`
  - `MonitoringCore(..., onSessionChanged: ((SessionSnapshot?) -> Unit)? = null)` — new last constructor parameter, defaulting to `null` so every existing construction site and test is untouched.

- [ ] **Step 1: Write the failing tests**

Append to `MonitoringCoreTest.kt`, inside `class MonitoringCoreTest`:

```kotlin
    @Test
    fun sessionOpenedFiresTheSnapshot() = runTest {
        val repo = repository()
        repo.recordUsageSeconds(INSTAGRAM, 600L, today.todayIso())
        val seen = mutableListOf<SessionSnapshot?>()
        val core = core(repo, onSessionChanged = { seen += it })

        core.onForegroundChange(INSTAGRAM)

        assertEquals(1, seen.size)
        val snapshot = seen[0]
        assertNotNull(snapshot)
        assertEquals(INSTAGRAM, snapshot.packageName)
        assertEquals("Instagram", snapshot.appName)
        assertEquals(600L, snapshot.storedSeconds)
        assertEquals(1_800L, snapshot.limitSeconds)
        // Zero elapsed, so the live total is exactly the stored total: liveSeconds is a running
        // total, not the amount this session has added.
        assertEquals(600L, snapshot.liveSeconds(currentTime))
    }

    @Test
    fun anOverLimitEntryStillFiresTheSnapshot() = runTest {
        val repo = repository()
        repo.recordUsageSeconds(INSTAGRAM, 30 * 60L, today.todayIso())
        val seen = mutableListOf<SessionSnapshot?>()
        val core = core(repo, onSessionChanged = { seen += it })

        core.onForegroundChange(INSTAGRAM)

        assertEquals(1, seen.size, "the immediate over-limit branch must not swallow the open")
        assertEquals(1_800L, seen[0]!!.storedSeconds)
    }

    @Test
    fun sessionClosedFiresNull() = runTest {
        val repo = repository()
        val seen = mutableListOf<SessionSnapshot?>()
        val core = core(repo, onSessionChanged = { seen += it })

        core.onForegroundChange(INSTAGRAM)
        advanceTimeBy(20_000L)
        core.onForegroundChange(LAUNCHER)

        assertEquals(listOf(true, false), seen.map { it != null })
    }

    @Test
    fun aCloseWithNoOpenSessionFiresNothing() = runTest {
        val repo = repository()
        val seen = mutableListOf<SessionSnapshot?>()
        val core = core(repo, onSessionChanged = { seen += it })

        core.onForegroundChange(LAUNCHER)
        advanceTimeBy(1_000L)
        core.onForegroundChange(LAUNCHER)

        assertTrue(seen.isEmpty(), "no session was open, so nothing changed")
    }

    @Test
    fun aSubSecondCloseStillFiresTheClose() = runTest {
        val repo = repository()
        val seen = mutableListOf<SessionSnapshot?>()
        val core = core(repo, onSessionChanged = { seen += it })

        core.onForegroundChange(INSTAGRAM)
        core.onForegroundChange(LAUNCHER)

        assertEquals(listOf(true, false), seen.map { it != null }, "zero elapsed is still a state change")
        assertTrue(repo.usage.value.isEmpty())
    }
```

Change the `core()` helper to forward the callback (existing call sites keep working because of the default):

```kotlin
    private fun TestScope.core(
        repository: FakeRepository,
        onSessionChanged: ((SessionSnapshot?) -> Unit)? = null,
    ) = MonitoringCore(
        repository = repository,
        todayKey = today,
        selfPackageName = selfPkg,
        clock = MonotonicClock { currentTime },
        scope = this,
        onOverlayDecision = { decisions += it },
        onSessionChanged = onSessionChanged,
    ).apply {
        // The core is inert until a service attaches it, exactly as AppContainer builds it. A
        // test that skipped this would watch a core that ignores every event and pass vacuously.
        attach()
    }
```

Add the import `com.septaalfauzan.wanci.monitoring.SessionSnapshot` to the test file.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.septaalfauzan.wanci.MonitoringCoreTest"`

Expected: compilation failure — `Unresolved reference: SessionSnapshot` and `Too many arguments` / `No value passed for parameter` on `MonitoringCore`.

- [ ] **Step 3: Add `SessionSnapshot`**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/SessionSnapshot.kt`:

```kotlin
package com.septaalfauzan.wanci.monitoring

/**
 * A read-only picture of an open session, published by [MonitoringCore] so a consumer can show
 * what is happening without asking the core anything.
 *
 * [liveSeconds] is deliberately the same arithmetic the core uses internally, so a consumer that
 * renders a running total and the core that enforces the limit can never disagree by a second.
 * Nothing here writes to the repository: a snapshot is a reading, not an update.
 */
data class SessionSnapshot(
    val packageName: String,
    val appName: String,
    val startedAtMillis: Long,
    val storedSeconds: Long,
    val limitSeconds: Long,
) {
    fun liveSeconds(nowMillis: Long): Long = storedSeconds + (nowMillis - startedAtMillis) / 1000
}
```

- [ ] **Step 4: Add the callback to `MonitoringCore`**

In `MonitoringCore.kt`, add the constructor parameter after `onOverlayDecision`:

```kotlin
    private val onOverlayDecision: (OverlayDecision) -> Unit,
    /**
     * Published whenever the core's session changes: a non-null snapshot when a session opens, and
     * `null` when one closes. Defaulted to `null` so every existing construction site — and the
     * whole existing test suite — keeps compiling unchanged.
     *
     * Not fired by [detach]: the service is going away and its notification with it, so a close
     * there would be reported to a listener that is already being torn down.
     */
    private val onSessionChanged: ((SessionSnapshot?) -> Unit)? = null,
    private val tickIntervalMillis: Long = 1_000L,
```

Replace the private `Session` class with a version that converts to the public snapshot. Delete the nested `private class Session` and put this in its place:

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

        fun toDecision(liveSeconds: Long) =
            OverlayDecision(packageName, appName, liveSeconds, limitSeconds)

        fun toSnapshot() =
            SessionSnapshot(packageName, appName, startedAtMillis, storedSeconds, limitSeconds)
    }
```

In `onForegroundChange`, publish the open. Change:

```kotlin
        currentSession = session

        val live = session.liveSeconds(now)
```

to:

```kotlin
        currentSession = session
        // Published before the over-limit check, so the one-shot branch below cannot swallow the
        // open. A consumer that shows a running total has to hear about an over-limit entry too.
        onSessionChanged?.invoke(session.toSnapshot())

        val live = session.liveSeconds(now)
```

In `closeCurrentSession`, publish the close. Change:

```kotlin
        val session = currentSession ?: return
        currentSession = null
        val elapsed = (now - session.startedAtMillis) / 1000
        if (elapsed <= 0L) return
```

to:

```kotlin
        val session = currentSession ?: return
        currentSession = null
        // Fires even when nothing is written below: a session that closed after zero elapsed
        // seconds is still a state change, and a consumer mirroring "an app is open" has to hear
        // about it or it would show an app that is no longer in the foreground.
        onSessionChanged?.invoke(null)
        val elapsed = (now - session.startedAtMillis) / 1000
        if (elapsed <= 0L) return
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.septaalfauzan.wanci.MonitoringCoreTest"`

Expected: PASS — all tests in `MonitoringCoreTest` green, including the five new ones.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/SessionSnapshot.kt \
        shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCore.kt \
        shared/src/commonTest/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCoreTest.kt
git commit -m "feat: publish session changes from MonitoringCore"
```

---

### Task 2: `formatShortDuration`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/format/UsageFormat.kt`
- Test: `shared/src/commonTest/kotlin/com/septaalfauzan/wanci/presentation/format/UsageFormatTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `fun formatShortDuration(seconds: Long): String` — compact, for a notification that has ~40 characters of room. Task 6 uses it.

- [ ] **Step 1: Write the failing test**

Append to `UsageFormatTest.kt` — `formatShortDuration` is in
`com.septaalfauzan.wanci.presentation.format` while the test file's own package is the root
`com.septaalfauzan.wanci`, so it needs an import alongside the existing `formatUsage` one:

```kotlin
    @Test
    fun shortDurationStaysCompactForANotification() {
        assertEquals("0s", formatShortDuration(0L))
        assertEquals("45s", formatShortDuration(45L))
        assertEquals("1m", formatShortDuration(60L))
        assertEquals("12m", formatShortDuration(12 * 60L))
        assertEquals("59m", formatShortDuration(59 * 60L + 59L))
        assertEquals("1h", formatShortDuration(60 * 60L))
        assertEquals("1h 5m", formatShortDuration(65 * 60L))
        assertEquals("0s", formatShortDuration(-5L), "a negative reading floors at zero")
        // No 24h clamp, unlike formatUsage: a day can overrun its limit by more than a day, and
        // "25h" is the honest reading. The clamp formatUsage applies is for limits, not readings.
        assertEquals("25h 1m", formatShortDuration(25 * 60 * 60L + 60L))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.septaalfauzan.wanci.UsageFormatTest"`

Expected: compilation failure — `Unresolved reference: formatShortDuration`.

- [ ] **Step 3: Implement it**

Append to `UsageFormat.kt`:

```kotlin
/**
 * A duration small enough to sit in a persistent notification, where a full sentence-width label
 * would be truncated by the shade.
 *
 * Deliberately not [formatUsage]: that renders "5 min" and "1 hour 5 min" for a dashboard row, and
 * neither shape fits next to a limit. This one drops every word except the unit that is doing the
 * work. Seconds still appear below a minute, because "0m" next to a limit reads as a bug rather
 * than as a fresh session.
 */
fun formatShortDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    if (total < 60L) return "${total}s"
    val minutes = total / 60
    val hours = minutes / 60
    val restMinutes = minutes % 60
    return when {
        hours == 0L -> "${minutes}m"
        restMinutes == 0L -> "${hours}h"
        else -> "${hours}h ${restMinutes}m"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.septaalfauzan.wanci.UsageFormatTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/format/UsageFormat.kt \
        shared/src/commonTest/kotlin/com/septaalfauzan/wanci/presentation/format/UsageFormatTest.kt
git commit -m "feat: add a compact duration format for notifications"
```

---

### Task 3: Permission domain swap

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/domain/Permissions.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/onboarding/OnboardingViewModel.kt`
- Test: `shared/src/commonTest/kotlin/com/septaalfauzan/wanci/presentation/onboarding/OnboardingViewModelTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class PermissionStatus(usageAccess: Boolean, overlay: Boolean, notification: Boolean)` with `val allGranted: Boolean`
  - `interface PermissionNavigator { fun openUsageAccess(); fun openOverlay() }`
  - `fun interface NotificationPermissionRequest { fun request(onResult: () -> Unit) }`
  - `OnboardingViewModel(useCases, permissionStatusProvider, permissionNavigator, notificationPermissionRequest, startMonitoring)` where `startMonitoring: () -> Unit = {}`
  - `OnboardingViewModel.requestNotification()`

- [ ] **Step 1: Write the failing test**

In `OnboardingViewModelTest.kt`, replace `RecordingPermissionNavigator`'s `openAccessibility` override by removing it, add a fake request, update `newViewModel`, and replace the affected test bodies. The whole file becomes:

```kotlin
package com.septaalfauzan.wanci.presentation.onboarding

import com.septaalfauzan.wanci.FakeRepository
import com.septaalfauzan.wanci.FakeTodayKey
import com.septaalfauzan.wanci.domain.NotificationPermissionRequest
import com.septaalfauzan.wanci.domain.PermissionNavigator
import com.septaalfauzan.wanci.domain.PermissionStatus
import com.septaalfauzan.wanci.domain.PermissionStatusProvider
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakePermissionStatusProvider(
    var current: PermissionStatus = PermissionStatus(false, false, false),
) : PermissionStatusProvider {
    var calls = 0
    override fun status(): PermissionStatus {
        calls++
        return current
    }
}

private class RecordingPermissionNavigator : PermissionNavigator {
    val opened = mutableListOf<String>()

    override fun openUsageAccess() {
        opened += "usage"
    }

    override fun openOverlay() {
        opened += "overlay"
    }
}

private class FakeNotificationPermissionRequest : NotificationPermissionRequest {
    var requests = 0

    /** When true the fake answers immediately, the way a granted permission would. */
    var grantImmediately: Boolean = false

    override fun request(onResult: () -> Unit) {
        requests++
        if (grantImmediately) onResult()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val repository = FakeRepository()
    private val permissions = FakePermissionStatusProvider()
    private val navigator = RecordingPermissionNavigator()
    private val notificationRequest = FakeNotificationPermissionRequest()
    private val started = mutableListOf<String>()
    private lateinit var viewModel: OnboardingViewModel

    private fun newViewModel(): OnboardingViewModel = OnboardingViewModel(
        useCases = WanciUseCases(repository, FakeTodayKey("2026-09-25")),
        permissionStatusProvider = permissions,
        permissionNavigator = navigator,
        notificationPermissionRequest = notificationRequest,
        startMonitoring = { started += "start" },
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = newViewModel()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsWithNoPermissionsAndIncompleteOnboarding() {
        val state = viewModel.state.value
        assertFalse(state.onboardingComplete)
        assertFalse(state.permissionStatus.allGranted)
        assertFalse(state.saving)
    }

    @Test
    fun readsPersistedOnboardingFlagOnInit() {
        repository.syncValues["onboardingComplete"] = "true"

        assertTrue(newViewModel().state.value.onboardingComplete)
    }

    @Test
    fun refreshPublishesLatestPermissionSnapshot() {
        permissions.current = PermissionStatus(usageAccess = true, overlay = true, notification = true)

        viewModel.refresh()

        assertEquals(1, permissions.calls)
        assertTrue(viewModel.state.value.permissionStatus.allGranted)
    }

    @Test
    fun partialPermissionsDoNotCompleteOnboarding() {
        permissions.current = PermissionStatus(usageAccess = true, overlay = false, notification = true)

        viewModel.refresh()

        val status = viewModel.state.value.permissionStatus
        assertTrue(status.usageAccess)
        assertFalse(status.overlay)
        assertTrue(status.notification)
        assertFalse(status.allGranted)
    }

    @Test
    fun completeOnboardingPersistsFlagNotifiesAndStartsMonitoring() {
        var done = false

        viewModel.completeOnboarding { done = true }

        assertEquals("true", repository.syncValues["onboardingComplete"])
        assertTrue(viewModel.state.value.saving)
        assertTrue(done)
        assertEquals(listOf("start"), started, "monitoring must be live the moment onboarding ends")
    }

    @Test
    fun requestMethodsDelegateToNavigatorAndRequest() {
        viewModel.requestUsageAccess()
        viewModel.requestOverlay()
        viewModel.requestNotification()

        assertEquals(listOf("usage", "overlay"), navigator.opened)
        assertEquals(1, notificationRequest.requests)
    }

    @Test
    fun aNotificationAnswerRefreshesThePermissionSnapshot() {
        permissions.current = PermissionStatus(usageAccess = true, overlay = true, notification = true)
        notificationRequest.grantImmediately = true

        viewModel.requestNotification()

        assertEquals(1, permissions.calls, "the grant is only visible after a re-read")
        assertTrue(viewModel.state.value.permissionStatus.notification)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.septaalfauzan.wanci.presentation.onboarding.OnboardingViewModelTest"`

Expected: compilation failure — `Unresolved reference: NotificationPermissionRequest`, `PermissionStatus` named-argument mismatch, `Unresolved reference: requestNotification`.

- [ ] **Step 3: Rewrite `Permissions.kt`**

```kotlin
package com.septaalfauzan.wanci.domain

data class PermissionStatus(
    val usageAccess: Boolean,
    val overlay: Boolean,
    val notification: Boolean,
) {
    val allGranted: Boolean get() = usageAccess && overlay && notification
}

fun interface PermissionStatusProvider {
    fun status(): PermissionStatus
}

interface PermissionNavigator {
    fun openUsageAccess()
    fun openOverlay()
}

/**
 * Asks the platform for `POST_NOTIFICATIONS`, reporting the answer through [onResult].
 *
 * A fun-interface taking a callback rather than a bare `request()` because the grant is a
 * runtime dialog on API 33+: the answer arrives asynchronously, and a view model holding only a
 * one-shot lambda would never learn it. Shared code cannot hold an `ActivityResultLauncher`, so
 * the host supplies the implementation and common code only ever sees this shape.
 */
fun interface NotificationPermissionRequest {
    fun request(onResult: () -> Unit)
}
```

- [ ] **Step 4: Rewrite `OnboardingViewModel.kt`**

```kotlin
package com.septaalfauzan.wanci.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.septaalfauzan.wanci.domain.NotificationPermissionRequest
import com.septaalfauzan.wanci.domain.PermissionNavigator
import com.septaalfauzan.wanci.domain.PermissionStatus
import com.septaalfauzan.wanci.domain.PermissionStatusProvider
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Onboarding state machine:
 *
 * - [onboardingComplete] mirrors the persisted `onboardingComplete` sync value and is observed from
 *   the repository, so it survives process death and decides whether onboarding is shown at all.
 * - [permissionStatus] is a snapshot: the platform cannot push changes while the app is backgrounded
 *   on a Settings page, so it only advances on [OnboardingViewModel.refresh].
 * - [saving] is true for the duration of the persist in [OnboardingViewModel.completeOnboarding].
 */
data class OnboardingUiState(
    val onboardingComplete: Boolean = false,
    val permissionStatus: PermissionStatus = PermissionStatus(false, false, false),
    val saving: Boolean = false,
)

class OnboardingViewModel(
    private val useCases: WanciUseCases,
    private val permissionStatusProvider: PermissionStatusProvider,
    private val permissionNavigator: PermissionNavigator,
    private val notificationPermissionRequest: NotificationPermissionRequest,
    private val startMonitoring: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            useCases.observeOnboardingComplete().collect { complete ->
                _state.value = _state.value.copy(onboardingComplete = complete)
            }
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(permissionStatus = permissionStatusProvider.status())
    }

    fun requestUsageAccess() = permissionNavigator.openUsageAccess()

    fun requestOverlay() = permissionNavigator.openOverlay()

    fun requestNotification() = notificationPermissionRequest.request { refresh() }

    fun completeOnboarding(onDone: () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true)
            useCases.putSyncValue("onboardingComplete", "true")
            // Started here, not from the dashboard: onboarding is the one moment the three
            // permissions are known to be granted, and the service has to be alive before the user
            // leaves the flow. The persisted flag above is what makes it idempotent for the
            // `MainActivity` start path.
            startMonitoring()
            onDone()
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.septaalfauzan.wanci.presentation.onboarding.OnboardingViewModelTest"`

Expected: PASS — all 7 tests green.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/wanci/domain/Permissions.kt \
        shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/onboarding/OnboardingViewModel.kt \
        shared/src/commonTest/kotlin/com/septaalfauzan/wanci/presentation/onboarding/OnboardingViewModelTest.kt
git commit -m "feat: swap accessibility permission for notification"
```

---

### Task 4: Onboarding screen, app deps, navigation wiring

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/App.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/navigation/AppNavHost.kt`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/onboarding/OnboardingScreen.kt`

**Interfaces:**
- Consumes: `NotificationPermissionRequest`, `OnboardingViewModel(... notificationPermissionRequest, startMonitoring)` from Task 3.
- Produces: `WanciAppDependencies.notificationPermissionRequest: NotificationPermissionRequest` and `WanciAppDependencies.startMonitoring: () -> Unit`. Task 7 supplies both from `AppContainer`.

- [ ] **Step 1: Add the dependency fields**

In `presentation/App.kt`, add the import and the two fields:

```kotlin
import com.septaalfauzan.wanci.domain.NotificationPermissionRequest
```

```kotlin
data class WanciAppDependencies(
    val useCases: WanciUseCases,
    val todayKey: TodayKey,
    val permissionStatusProvider: PermissionStatusProvider,
    val permissionNavigator: PermissionNavigator,
    val installedAppsProvider: InstalledAppsProvider,
    val installedAppCheck: InstalledAppCheck,
    /**
     * `POST_NOTIFICATIONS` is a runtime dialog on API 33+, so the host owns the launcher and common
     * code only sees the request shape. Answering it re-reads the status snapshot.
     */
    val notificationPermissionRequest: NotificationPermissionRequest,
    /** Starts the monitoring foreground service. Called once, when onboarding completes. */
    val startMonitoring: () -> Unit,
    val deepLink: DeepLinkRequest? = null,
)
```

- [ ] **Step 2: Pass them to the view model**

In `navigation/AppNavHost.kt`, inside `composable(Routes.ONBOARDING)`, replace the `OnboardingViewModel(...)` construction with:

```kotlin
                OnboardingViewModel(
                    useCases = deps.useCases,
                    permissionStatusProvider = deps.permissionStatusProvider,
                    permissionNavigator = deps.permissionNavigator,
                    notificationPermissionRequest = deps.notificationPermissionRequest,
                    startMonitoring = deps.startMonitoring,
                )
```

- [ ] **Step 3: Replace the onboarding row**

In `onboarding/OnboardingScreen.kt`, replace the accessibility `PermissionRow` block with:

```kotlin
        PermissionRow(
            title = "Notifications",
            detail = "Show the reminder while Wanci is not open.",
            granted = state.permissionStatus.notification,
            onClick = viewModel::requestNotification,
        )
```

- [ ] **Step 3b: Surface a failed onboarding save**

`OnboardingUiState.saveFailed` is set when the `onboardingComplete` write throws, but nothing
renders it. The write is the only record that onboarding finished, so swallowing it means the next
launch shows onboarding again and nothing is ever monitored — an infinite silent tap. The retry is
safe (idempotent upsert of one key), so the user just has to be able to see that they should.

In `onboarding/OnboardingScreen.kt`, below the permission rows and above the continue button,
render the error. Match the existing text/error styling already used elsewhere in this screen or
its siblings — do not invent a new style:

```kotlin
        if (state.saveFailed) {
            Text(
                text = "Could not save. Check your storage and try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
```

Note `state.saving` has no reader either. Bind it to the continue button's `enabled` only if
`WanciButton` already accepts an `enabled` parameter; if it does not, leave `saving` alone rather
than widening this task into a component change.

- [ ] **Step 4: Compile the shared module**

Run: `./gradlew :shared:compileKotlinAndroid`

Expected: FAIL — `androidApp` is not compiled here, but `shared` itself must compile. If it fails on anything other than a `WanciAppDependencies` construction site outside `androidApp`, fix that site. `androidApp`'s `toDependencies` cannot be checked until Task 7, so the next gate for the whole graph is Task 7's `assembleDebug`.

- [ ] **Step 5: Run the shared tests as a regression gate**

Run: `./gradlew :shared:testAndroidHostTest`

Expected: FAIL is *not* acceptable for existing tests; only the onboarding suite should be affected, and it was already made green in Task 3. All suites PASS.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/App.kt \
        shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/navigation/AppNavHost.kt \
        shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/onboarding/OnboardingScreen.kt
git commit -m "feat: show the notification permission in onboarding"
```

---

### Task 5: `parseForegroundPackage`

**Files:**
- Create: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/ForegroundEventParser.kt`
- Modify: `androidApp/build.gradle.kts`
- Create: `androidApp/src/test/kotlin/com/septaalfauzan/wanci/monitoring/ForegroundEventParserTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `internal data class RawUsageEvent(val eventType: Int, val packageName: String?)`
  - `internal fun parseForegroundPackage(events: List<RawUsageEvent>): String?` — the package in the foreground at the end of the window, or `null` if the window ends with the foreground app going away.
  - `internal fun hasForegroundSignal(events: List<RawUsageEvent>): Boolean` — whether the window contained any event that says anything about the foreground at all. **Required**, because `parseForegroundPackage` returns `null` both for "the app went away" and for "nothing happened", and the poll loop must not read the first as the second.

- [ ] **Step 1: Enable the androidApp unit-test source set**

In `androidApp/build.gradle.kts`, add to `dependencies`:

```kotlin
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
```

`kotlin-test-junit` rather than `kotlin-test` because the Android application plugin's unit-test runner is JUnit 4.

- [ ] **Step 2: Write the failing test**

Create `androidApp/src/test/kotlin/com/septaalfauzan/wanci/monitoring/ForegroundEventParserTest.kt`:

```kotlin
package com.septaalfauzan.wanci.monitoring

import android.app.usage.UsageEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun resumed(pkg: String) =
    RawUsageEvent(UsageEvents.Event.ACTIVITY_RESUMED, pkg)

private fun paused(pkg: String) =
    RawUsageEvent(UsageEvents.Event.ACTIVITY_PAUSED, pkg)

private fun legacyForeground(pkg: String) =
    RawUsageEvent(UsageEvents.Event.MOVE_TO_FOREGROUND, pkg)

private fun legacyBackground(pkg: String) =
    RawUsageEvent(UsageEvents.Event.MOVE_TO_BACKGROUND, pkg)

private fun noise(pkg: String) =
    RawUsageEvent(UsageEvents.Event.SCREEN_INTERACTIVE, pkg)

class ForegroundEventParserTest {

    @Test
    fun anEmptyWindowResolvesToNoPackage() {
        assertNull(parseForegroundPackage(emptyList()))
    }

    @Test
    fun aResumedEventIsAForegroundSwitch() {
        assertEquals("com.foo", parseForegroundPackage(listOf(resumed("com.foo"))))
    }

    @Test
    fun theLegacyForegroundEventIsHandledForMinSdk28() {
        assertEquals("com.foo", parseForegroundPackage(listOf(legacyForeground("com.foo"))))
    }

    @Test
    fun theLastEventInAWindowWins() {
        val events = listOf(resumed("com.a"), resumed("com.b"), paused("com.a"))

        assertEquals("com.b", parseForegroundPackage(events))
    }

    @Test
    fun endingOnAPauseMeansTheForegroundIsEmpty() {
        assertNull(parseForegroundPackage(listOf(resumed("com.a"), paused("com.a"))))
    }

    @Test
    fun endingOnTheLegacyBackgroundEventMeansTheForegroundIsEmpty() {
        assertNull(parseForegroundPackage(listOf(legacyForeground("com.a"), legacyBackground("com.a"))))
    }

    @Test
    fun anActivitySwitchInsideOneAppResolvesToThatApp() {
        // A dialog or a permission prompt pauses and resumes the *same* package. The pause must not
        // be read as "left the foreground", which is why only the last event is consulted.
        val events = listOf(resumed("com.a"), paused("com.a"), resumed("com.a"))

        assertEquals("com.a", parseForegroundPackage(events))
    }

    @Test
    fun irrelevantEventTypesAreIgnored() {
        val events = listOf(noise("com.a"), resumed("com.b"), noise("com.b"))

        assertEquals("com.b", parseForegroundPackage(events))
    }
    @Test
    fun typeSixteenIsTreatedAsForegroundAndUnwindsAPrecedingPause() {
        // SCREEN_NON_INTERACTIVE and MOVE_TO_FOREGROUND are both 16 and the type check cannot tell
        // them apart. This test pins the actual decision rather than the hoped-for one: 16 is read
        // as foreground, so a screen-off event carrying a null package unwinds the pause that
        // preceded it. Screen-off is closed by the broadcast, not by this event, so the ambiguity
        // costs nothing.
        val events = listOf(
            resumed("com.a"),
            paused("com.a"),
            RawUsageEvent(UsageEvents.Event.SCREEN_NON_INTERACTIVE, null),
        )

        assertNull(parseForegroundPackage(events))
    }

    @Test
    fun anEventWithNoPackageIsNotAForegroundSwitch() {
        val events = listOf(resumed("com.a"), RawUsageEvent(UsageEvents.Event.ACTIVITY_RESUMED, null))

        assertNull(parseForegroundPackage(events))
    }

    @Test
    fun aWindowWithNoForegroundEventsCarriesNoSignal() {
        // The distinction the poll loop lives on: an empty window and a window where the app left
        // both parse to null, but only the second one means anything. Without this, a quiet second
        // would close the session of whatever the user is using.
        assertFalse(hasForegroundSignal(emptyList()))
        assertFalse(hasForegroundSignal(listOf(noise("com.a"))))
    }

    @Test
    fun aWindowWhereTheAppLeftCarriesSignal() {
        assertTrue(hasForegroundSignal(listOf(resumed("com.a"), paused("com.a"))))
        assertTrue(hasForegroundSignal(listOf(legacyForeground("com.a"))))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.septaalfauzan.wanci.monitoring.ForegroundEventParserTest"`

Expected: compilation failure — `Unresolved reference: RawUsageEvent`, `Unresolved reference: parseForegroundPackage`.

- [ ] **Step 4: Implement the parser**

Create `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/ForegroundEventParser.kt`:

```kotlin
package com.septaalfauzan.wanci.monitoring

import android.app.usage.UsageEvents

/**
 * One `UsageEvents.Event`, reduced to the two fields the parser reads. A local type rather than
 * the platform class so the parser can be host-tested without a `UsageStatsManager` or Robolectric
 * — [MonitoringForegroundService] does the reduction in one place.
 */
internal data class RawUsageEvent(
    val eventType: Int,
    val packageName: String?,
)

/**
 * The package in the foreground at the **end** of the window, or `null` if the window ends with
 * the foreground app going away.
 *
 * Only the last relevant event is consulted, which is what makes a pause-and-resume of the same
 * app — a dialog, an in-app activity, a permission prompt — resolve to that app instead of to
 * "nobody". Reading every event in order would drop the session on each of those.
 *
 * `ACTIVITY_PAUSED` counts as background, which is the only way a leave-home is visible: the
 * launcher emits no `ACTIVITY_RESUMED`, so without the pause the previous app would look like it
 * was still in the foreground until the user came back.
 *
 * `ACTIVITY_RESUMED` is API 29+ and `MOVE_TO_FOREGROUND` is its API 28 ancestor; both are handled
 * because `minSdk` is 28. They are compile-time constants, so the newer name inlines to a literal
 * on an API 28 device rather than failing to link.
 *
 * `SCREEN_NON_INTERACTIVE` is deliberately absent from the background branch. It shares the value
 * 16 with `MOVE_TO_FOREGROUND`, so the type check cannot tell them apart and reading it as a
 * background signal would let it cancel the foreground event it collides with. It is therefore
 * read as foreground, which for a real screen-off event means it resolves to whatever package that
 * event carries — harmless, because the poll loop is gated on the screen being on and the
 * `SCREEN_OFF` broadcast closes the session through a channel that cannot be ambiguous.
 */
internal fun parseForegroundPackage(events: List<RawUsageEvent>): String? {
    var foreground: String? = null
    for (event in events) {
        when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND,
            UsageEvents.Event.ACTIVITY_RESUMED,
            -> foreground = event.packageName

            UsageEvents.Event.MOVE_TO_BACKGROUND,
            UsageEvents.Event.ACTIVITY_PAUSED,
            -> foreground = null

            else -> Unit
        }
    }
    return foreground
}

/**
 * Whether the window said anything about the foreground at all.
 *
 * [parseForegroundPackage] returns `null` for two different situations — "the app in the
 * foreground went away" and "nothing relevant happened" — and the poll loop has to tell them
 * apart. Reading the first as the second would close the user's session on every quiet second, and
 * the accrual would restart from zero each time.
 *
 * `SCREEN_NON_INTERACTIVE` (16) counts as a signal here, because it *is* the same value as
 * `MOVE_TO_FOREGROUND` and the type check cannot tell them apart. That is exactly why the parser
 * must not read it as a background event: doing so would let it cancel the foreground event it
 * collides with. Screen-off is handled by a broadcast instead, which is unambiguous.
 */
internal fun hasForegroundSignal(events: List<RawUsageEvent>): Boolean =
    events.any { event ->
        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
            event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
            event.eventType == UsageEvents.Event.ACTIVITY_PAUSED
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.septaalfauzan.wanci.monitoring.ForegroundEventParserTest"`

Expected: PASS — all 12 tests green.

- [ ] **Step 6: Commit**

```bash
git add androidApp/build.gradle.kts \
        androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/ForegroundEventParser.kt \
        androidApp/src/test/kotlin/com/septaalfauzan/wanci/monitoring/ForegroundEventParserTest.kt
git commit -m "feat: parse the foreground package from usage events"
```

---

### Task 6: `NotificationController` + `AndroidNotifier`

**Files:**
- Create: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/NotificationController.kt`
- Create: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/AndroidNotifier.kt`
- Modify: `androidApp/src/main/res/values/strings.xml`
- Test: `androidApp/src/test/kotlin/com/septaalfauzan/wanci/monitoring/NotificationControllerTest.kt`

**Interfaces:**
- Consumes: `SessionSnapshot` from Task 1, `formatShortDuration` from Task 2.
- Produces:
  - `sealed interface NotificationState { data object Idle; data class Session(val snapshot: SessionSnapshot); data object UsageAccessNeeded }` in package `com.septaalfauzan.wanci.monitoring`
  - `fun interface Notifier { fun update(title: String, text: String) }`
  - `NotificationController(notifier: Notifier, clock: MonotonicClock, scope: CoroutineScope, rolloverIntervalMillis: Long = 60_000L)` with `val state: NotificationState`, and methods `start()`, `onSessionChanged(SessionSnapshot?)`, `onLimitCrossed()`, `onUsageAccessRevoked()`
  - `AndroidNotifier(context: Context) : Notifier` with `fun latest(): Notification`

- [ ] **Step 1: Write the failing test**

Create `androidApp/src/test/kotlin/com/septaalfauzan/wanci/monitoring/NotificationControllerTest.kt`:

```kotlin
package com.septaalfauzan.wanci.monitoring

import com.septaalfauzan.wanci.monitoring.MonotonicClock
import com.septaalfauzan.wanci.monitoring.NotificationController
import com.septaalfauzan.wanci.monitoring.NotificationState
import com.septaalfauzan.wanci.monitoring.SessionSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeNotifier : Notifier {
    val updates = mutableListOf<Pair<String, String>>()

    override fun update(title: String, text: String) {
        updates += title to text
    }
}

private fun snapshot(
    stored: Long = 600L,
    limit: Long = 1_800L,
    startedAt: Long = 0L,
) = SessionSnapshot(
    packageName = "com.foo",
    appName = "Foo",
    startedAtMillis = startedAt,
    storedSeconds = stored,
    limitSeconds = limit,
)

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationControllerTest {

    private val notifier = FakeNotifier()

    private fun TestScope.controller(rollover: Long = 60_000L) = NotificationController(
        notifier = notifier,
        clock = MonotonicClock { currentTime },
        scope = this,
        rolloverIntervalMillis = rollover,
    )

    @Test
    fun startPublishesTheIdleState() = runTest {
        val controller = controller()

        controller.start()

        assertEquals(NotificationState.Idle, controller.state)
        assertEquals("Monitoring active", notifier.updates.single().first)
    }

    @Test
    fun anOpenSessionRendersAppUsageOverLimit() = runTest {
        val controller = controller()

        controller.onSessionChanged(snapshot(stored = 720L, limit = 1_800L))

        val (title, text) = notifier.updates.last()
        assertEquals("Foo", title)
        assertEquals("Foo \u00B7 12m / 30m", text)
    }

    @Test
    fun theRenderedTotalFollowsTheSnapshotStartNotAPublishCount() = runTest {
        val controller = controller()
        controller.start()
        // Republished three times inside one second: nothing may move except the clock.
        controller.onSessionChanged(snapshot(stored = 0L, limit = 1_800L))
        controller.onLimitCrossed()
        controller.onLimitCrossed()

        assertTrue(notifier.updates.last().second.endsWith("/ 30m"))
        assertTrue(notifier.updates.last().second.contains("0s"), "no time passed: ${notifier.updates.last()}")
    }

    @Test
    fun aClosedSessionRevertsToIdle() = runTest {
        val controller = controller()
        controller.start()
        controller.onSessionChanged(snapshot())

        controller.onSessionChanged(null)

        assertEquals(NotificationState.Idle, controller.state)
        assertEquals("Monitoring active", notifier.updates.last().first)
    }

    @Test
    fun theRolloverRefreshesTheRunningTotalWhileASessionIsOpen() = runTest {
        val controller = controller()
        controller.start()
        controller.onSessionChanged(snapshot(stored = 0L, limit = 1_800L))
        val afterOpen = notifier.updates.size

        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(afterOpen + 1, notifier.updates.size, "one rollover refresh per minute")
        assertTrue(notifier.updates.last().second.startsWith("1m"), "a minute has passed: ${notifier.updates.last()}")
    }

    @Test
    fun theRolloverStopsWithTheSession() = runTest {
        val controller = controller()
        controller.start()
        controller.onSessionChanged(snapshot())
        controller.onSessionChanged(null)
        val afterClose = notifier.updates.size

        advanceTimeBy(600_000L)
        runCurrent()

        assertEquals(afterClose, notifier.updates.size, "an idle controller must not wake up every minute")
    }

    @Test
    fun aLimitCrossingRepublishesImmediately() = runTest {
        val controller = controller()
        controller.start()
        controller.onSessionChanged(snapshot(stored = 1_790L, limit = 1_800L))
        val before = notifier.updates.size

        controller.onLimitCrossed()

        assertEquals(before + 1, notifier.updates.size)
        assertTrue(notifier.updates.last().second.contains("/ 30m"))
    }

    @Test
    fun aRevokedUsageAccessPublishesTheReentryStateAndStopsTheRollover() = runTest {
        val controller = controller()
        controller.start()
        controller.onSessionChanged(snapshot())

        controller.onUsageAccessRevoked()

        assertIs<NotificationState.UsageAccessNeeded>(controller.state)
        assertEquals("Usage Access needed", notifier.updates.last().first)
        val published = notifier.updates.size
        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals(published, notifier.updates.size, "no session to count, so no rollover")
    }

    @Test
    fun aSecondStartRepublishesTheIdleState() = runTest {
        // startForegroundService can be delivered more than once — onboarding, the boot broadcast
        // and MainActivity.onCreate all call it. Republishing is correct: a startForeground with a
        // notification the controller has not seen would build from a stale snapshot.
        val controller = controller()

        controller.start()
        controller.start()

        assertEquals(2, notifier.updates.size)
        assertEquals(NotificationState.Idle, controller.state)
    }

    @Test
    fun aSessionOpeningOverTheIdleStateLeavesNoRolloverFromAPreviousSession() = runTest {
        val controller = controller()
        controller.start()
        controller.onSessionChanged(snapshot(stored = 0L, limit = 1_800L))
        controller.onSessionChanged(null)
        controller.onSessionChanged(snapshot(stored = 0L, limit = 1_800L))
        val afterReopen = notifier.updates.size

        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(afterReopen + 1, notifier.updates.size, "exactly one rollover for the open session")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.septaalfauzan.wanci.monitoring.NotificationControllerTest"`

Expected: compilation failure — `Unresolved reference: NotificationController`, `NotificationState`, `Notifier`, `SessionSnapshot` (the last one is in `shared`, which is on the unit-test classpath via `implementation(project(":shared"))`).

- [ ] **Step 3: Add the notification channel name**

Replace `androidApp/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">Wanci</string>
    <string name="notification_channel_name">Screen-time monitoring</string>
</resources>
```

The notification's own text is deliberately **not** a string resource: it is rendered by
`NotificationController`, which has no `Context` and therefore no access to resources. That is the
price of being a pure host-testable class, and it is worth paying for a row of text that has one
place to be changed. `notification_channel_name` stays a resource because only `AndroidNotifier`
reads it.

- [ ] **Step 4: Implement the state machine**

Create `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/NotificationController.kt`:

```kotlin
package com.septaalfauzan.wanci.monitoring

import com.septaalfauzan.wanci.monitoring.MonotonicClock
import com.septaalfauzan.wanci.presentation.format.formatShortDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the persistent notification is currently saying.
 *
 * A closed set of three, so a caller cannot put the notification into a state nobody thought about.
 * [Session] carries the whole snapshot because the content is derived from the session's own start
 * timestamp, never from a counter this class keeps.
 */
sealed interface NotificationState {
    /** No monitored app is in the foreground. Monitoring itself is running. */
    data object Idle : NotificationState

    /** A monitored app is in the foreground and accruing. */
    data class Session(val snapshot: SessionSnapshot) : NotificationState

    /** Usage access was revoked mid-run. The poll loop is stopped; onboarding is the way back. */
    data object UsageAccessNeeded : NotificationState
}

/** Where the rendered text goes. The Android implementation posts it; a test records it. */
fun interface Notifier {
    fun update(title: String, text: String)
}

/**
 * Mirrors [MonitoringCore]'s session into a notification, on its own slow cadence.
 *
 * The cadence is the point. The core ticks every second; posting every tick would be 60
 * notifications a minute, which the shade would treat as noise. This republishes only when the
 * *state* changes — a session opens, a session closes, a limit is crossed — and then once a
 * minute while a session is open, purely so the elapsed figure in the shade does not go stale.
 *
 * The elapsed figure is read from the snapshot's own start timestamp, so it agrees with what the
 * core enforces, and this class never writes to the repository.
 */
class NotificationController(
    private val notifier: Notifier,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    private val rolloverIntervalMillis: Long = 60_000L,
) {

    var state: NotificationState = NotificationState.Idle
        private set

    private var rolloverJob: Job? = null

    /** Called by the service before `startForeground`, so the very first frame is not blank. */
    fun start() {
        rolloverJob?.cancel()
        rolloverJob = null
        publish(NotificationState.Idle)
    }

    fun onSessionChanged(snapshot: SessionSnapshot?) {
        if (snapshot == null) {
            stopRollover()
            publish(NotificationState.Idle)
            return
        }
        publish(NotificationState.Session(snapshot))
        startRollover()
    }

    /**
     * The core decided the limit is reached. Republishing immediately rather than waiting out the
     * rollover means the shade shows the crossing as it happens, which is the one moment the user
     * is looking at it.
     */
    fun onLimitCrossed() {
        if (state is NotificationState.Session) publish(state)
    }

    fun onUsageAccessRevoked() {
        stopRollover()
        publish(NotificationState.UsageAccessNeeded)
    }

    private fun startRollover() {
        if (rolloverJob != null) return
        rolloverJob = scope.launch {
            while (isActive) {
                delay(rolloverIntervalMillis)
                if (state !is NotificationState.Session) return@launch
                publish(state)
            }
        }
    }

    private fun stopRollover() {
        rolloverJob?.cancel()
        rolloverJob = null
    }

    private fun publish(next: NotificationState) {
        state = next
        notifier.update(next.title(), next.text())
    }

    private fun NotificationState.title(): String = when (this) {
        NotificationState.Idle -> "Monitoring active"
        NotificationState.UsageAccessNeeded -> "Usage Access needed"
        is NotificationState.Session -> snapshot.appName
    }

    private fun NotificationState.text(): String = when (this) {
        NotificationState.Idle -> "Monitoring active"
        NotificationState.UsageAccessNeeded -> "Tap to grant it again."
        is NotificationState.Session -> buildString {
            // Mid-dot, not a hyphen or a colon: a hyphen is ambiguous against the negative-looking
            // difference between usage and limit, a colon wraps on narrow shade rows.
            append(snapshot.appName)
            append(" \u00B7 ")
            append(formatShortDuration(snapshot.liveSeconds(clock.uptimeMillis())))
            append(" / ")
            append(formatShortDuration(snapshot.limitSeconds))
        }
    }
}
```

- [ ] **Step 5: Implement the Android notifier**

Create `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/AndroidNotifier.kt`:

```kotlin
package com.septaalfauzan.wanci.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.septaalfauzan.wanci.R

/**
 * Posts the monitoring notification and owns its channel.
 *
 * [latest] exists because `Service.startForeground` needs a `Notification` object, not a title and
 * a body — and it must be called within seconds of the service starting or the system kills the
 * process. [NotificationController.start] publishes the idle state first, so by the time the
 * service asks for the notification, one has been built and cached.
 *
 * Channel importance is `LOW`: the overlay already interrupts when a limit is crossed, and a
 * channel that could make sound would have this notification buzzing over another app.
 */
class AndroidNotifier(private val context: Context) : Notifier {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var cached: Notification? = null

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    override fun update(title: String, text: String) {
        val notification = build(title, text)
        cached = notification
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * The most recently built notification. Throws when nothing has been published yet, which is a
     * programming error rather than a runtime state: a service that calls this before
     * [NotificationController.start] has no notification to show the system.
     */
    fun latest(): Notification = checkNotNull(cached) { "no notification published yet" }

    private fun build(title: String, text: String): Notification {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setPackage(context.packageName)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            // PRIVATE, not SECRET: the shade shows the running figure, but the lock screen shows the
            // title only. Live content on the lock screen would name the app the user is on.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "wanci_monitoring"
        const val NOTIFICATION_ID = 2001
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.septaalfauzan.wanci.monitoring.NotificationControllerTest"`

Expected: PASS — all 10 tests green.

- [ ] **Step 7: Commit**

```bash
git add androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/NotificationController.kt \
        androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/AndroidNotifier.kt \
        androidApp/src/main/res/values/strings.xml \
        androidApp/src/test/kotlin/com/septaalfauzan/wanci/monitoring/NotificationControllerTest.kt
git commit -m "feat: mirror the session into a persistent notification"
```

---

### Task 7: `MonitoringForegroundService`, `BootReceiver`, host wiring

**Files:**
- Create: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringForegroundService.kt`
- Create: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/BootReceiver.kt`
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/AppContainer.kt`
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/MainActivity.kt`
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/permission/PermissionChecks.kt`
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/permission/PermissionActions.kt`

**Interfaces:**
- Consumes: `NotificationController`, `AndroidNotifier` (Task 6), `parseForegroundPackage` (Task 5), `WanciAppDependencies.notificationPermissionRequest` / `.startMonitoring` (Task 4), `PermissionStatus.notification` (Task 3).
- Produces: `MonitoringForegroundService.start(context: Context)`, `AppContainer.startMonitoring()`, `PermissionActions : PermissionNavigator` (accessibility method gone), `PermissionChecks.status(context)`.

- [ ] **Step 1: Write the service**

Create `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringForegroundService.kt`:

```kotlin
package com.septaalfauzan.wanci.monitoring

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.septaalfauzan.wanci.AppContainer
import com.septaalfauzan.wanci.WanciApplication
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Hosts the poll loop that makes Wanci work with the app closed.
 *
 * The trade this makes is explicit: Android's price for an always-alive process is a permanent
 * notification. A screen-time limiter that only ran while Wanci was open would enforce nothing, and
 * a periodic worker would add hours of latency to a limit measured in minutes.
 *
 * **Why the event timestamp is ignored.** Polling delivers events one to five seconds late, and
 * every switch would under-count by that much. Using each event's own timestamp would mean a second
 * time source — wall clock against the core's monotonic uptime — which the core was deliberately
 * built to have exactly one of. A second per switch is noise against a minute-scale limit.
 *
 * Every change funnels through [enqueueForegroundChange] and takes the core mutex, so the core's
 * single session is only ever advanced by one coroutine at a time, exactly as it was when an
 * accessibility service fed it.
 */
class MonitoringForegroundService : Service() {

    private val generation = AtomicLong(0L)
    private var container: AppContainer? = null
    private var pollJob: Job? = null
    private var screenReceiverRegistered = false
    private var screenOn = true
    private var watermarkMillis = 0L
    private var lastForeground: String? = null

    /**
     * Screen-off is not a usage event, so without this the session of whatever was in the
     * foreground would stay open — and keep accruing — for as long as the screen was dark. A null
     * package is how the core is told to close it.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    enqueueForegroundChange(null)
                    stopPolling()
                }

                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    startPolling()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as? WanciApplication)?.container
        // Read once at create time rather than defaulting to on: a service started by
        // BOOT_COMPLETED routinely runs while the screen is still dark, and polling then would
        // keep the wake lock alive with nothing to detect. The broadcast only fires on a
        // transition, so a later screen-on is still what starts the loop.
        screenOn = isScreenOn(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appContainer = container ?: return START_NOT_STICKY
        appContainer.notificationController.start()
        startForeground(
            AndroidNotifier.NOTIFICATION_ID,
            appContainer.notifier.latest(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        appContainer.core.attach()
        registerScreenReceiver()
        if (screenOn) startPolling()
        // STICKY: the process is killed under memory pressure and the limiter must come back on its
        // own. The gap is not recovered — see the spec's Decision 7 — but a dead service is worse
        // than a few lost seconds, and the notification tells the user monitoring is running.
        return START_STICKY
    }

    override fun onDestroy() {
        stopPolling()
        unregisterScreenReceiver()
        // Order matters, and both calls are synchronous. detach() stops the ticker before it
        // returns, so no tick can fire between here and the view being removed. Closing the
        // session instead would queue a coroutine and leave that race open.
        container?.core?.detach()
        container?.overlayManager?.dismiss()
        container = null
        super.onDestroy()
    }

    private fun startPolling() {
        if (pollJob != null) return
        val appContainer = container ?: return
        // Only ever polls forward from now. Replaying older events would be safe in a way the
        // deleted reconcile was not — it would deliver ordered open/close pairs rather than
        // aggregates — but it is an accuracy upgrade with its own test surface, and a service that
        // restarts has not lost anything the user would notice at minute scale.
        watermarkMillis = System.currentTimeMillis()
        lastForeground = null
        val myGeneration = generation.incrementAndGet()
        pollJob = appContainer.scope.launch {
            while (isActive && myGeneration == generation.get()) {
                delay(POLL_INTERVAL_MILLIS)
                val now = System.currentTimeMillis()
                val packageName = try {
                    queryForegroundPackage(watermarkMillis, now)
                } catch (_: SecurityException) {
                    // Usage access was revoked from Settings while Wanci ran. The poll loop cannot
                    // recover on its own — there is nothing to poll with — and retrying would spin.
                    // The notification says what happened; onboarding is the way back.
                    appContainer.notificationController.onUsageAccessRevoked()
                    return@launch
                }
                // The screen may have gone dark, or the service may have been destroyed, while the
                // query was in flight. Its result describes a world that no longer exists: acting
                // on it would open a session the core then accrues until the next event.
                if (myGeneration != generation.get()) return@launch
                watermarkMillis = now
                if (packageName === NO_SIGNAL) return@launch
                val next = packageName as String?
                if (next == lastForeground) return@launch
                lastForeground = next
                enqueueForegroundChange(next)
            }
        }
    }

    private fun stopPolling() {
        generation.incrementAndGet()
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * The package in the foreground at the end of the window, `null` if the foreground app went
     * away, or [NO_SIGNAL] if the window contained no foreground event at all.
     */
    private fun queryForegroundPackage(from: Long, to: Long): Any? {
        val stats = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = stats
            .queryEvents(from, to, UsageStatsManager.INTERVAL_REALTIME)
            .map { RawUsageEvent(it.eventType, it.packageName) }
        // Two nulls, one meaning. `parseForegroundPackage` cannot distinguish "the app left" from
        // "nothing happened", so the signal check decides which of the two this window was. Without
        // it, every quiet second would close the session the user is in and the running total would
        // restart from zero — the same bug in a different disguise.
        if (!hasForegroundSignal(events)) return NO_SIGNAL
        return parseForegroundPackage(events)
    }

    private fun enqueueForegroundChange(packageName: String?) {
        val appContainer = container ?: return
        appContainer.scope.launch {
            appContainer.coreMutex.withLock {
                appContainer.core.onForegroundChange(packageName)
            }
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        unregisterReceiver(screenReceiver)
        screenReceiverRegistered = false
    }

    companion object {
        private const val POLL_INTERVAL_MILLIS = 1_000L

        /**
         * "This window said nothing about the foreground" — not a package, and not `null`, which
         * means "the foreground app went away". A private object so it can be compared by identity
         * and can never collide with a real package name.
         */
        private val NO_SIGNAL = object {}

        fun start(context: Context) {
            // minSdk is 28, so the branch is only here to keep the call honest if the floor moves.
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(
                    Intent(context, MonitoringForegroundService::class.java),
                )
            } else {
                context.startService(Intent(context, MonitoringForegroundService::class.java))
            }
        }

        /**
         * Whether the screen is on right now, read from the sticky broadcast rather than assumed.
         * A null-returning receiver is a query, not a subscription: nothing is registered.
         */
        @Suppress("DEPRECATION")
        private fun isScreenOn(context: Context): Boolean =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_SCREEN_ON)) != null
    }
}
```

Imports required, and no others: `android.app.Service`, `android.app.usage.UsageStatsManager`,
`android.content.BroadcastReceiver`, `android.content.Context`, `android.content.Intent`,
`android.content.IntentFilter`, `android.content.pm.ServiceInfo`, `android.os.Build`,
`android.os.IBinder`, `com.septaalfauzan.wanci.AppContainer`, `com.septaalfauzan.wanci.WanciApplication`,
`java.util.concurrent.atomic.AtomicLong`, `kotlinx.coroutines.Job`, `kotlinx.coroutines.delay`,
`kotlinx.coroutines.isActive`, `kotlinx.coroutines.launch`, `kotlinx.coroutines.sync.withLock`.

`android.app.usage.UsageEvents` is deliberately absent: the service reduces events to
`RawUsageEvent`, and the `UsageEvents` constants are only read inside `parseForegroundPackage`,
which lives in its own file.

- [ ] **Step 2: Write the boot receiver**

Create `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/BootReceiver.kt`:

```kotlin
package com.septaalfauzan.wanci.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts monitoring after a reboot.
 *
 * A foreground service does not survive one, and without this the limiter is silently dead until
 * the user happens to open Wanci — which breaks the promise the accessibility service used to get
 * from the system for free. It does not check permissions: starting the service is idempotent and
 * it degrades to the "Usage Access needed" notification if usage access was not restored.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        MonitoringForegroundService.start(context.applicationContext)
    }
}
```

- [ ] **Step 3: Wire `AppContainer`**

Add the import `com.septaalfauzan.wanci.domain.NotificationPermissionRequest`.

Add this interface at file scope, after the imports and before `class AppContainer`:

```kotlin
/**
 * Implemented by the Activity that owns the `POST_NOTIFICATIONS` launcher.
 *
 * An interface rather than a `NotificationPermissionRequest` lambda stored on the container so the
 * container never holds an Activity reference. `MainActivity` registers itself on create and
 * unregisters on destroy, and [AppContainer.toDependencies] reads the registration at the moment it
 * is called — so a request made while no Activity is alive is a no-op instead of a leak.
 */
interface NotificationPermissionHost {
    fun requestNotificationPermission(onResult: () -> Unit)
}
```

In `AppContainer`, replace the whole `core` / `coreMutex` / `overlayManager` / `init` region with
the following, **in this order** — `clock` is a `val` that the `core` initializer reads, so it has
to be declared above it:

```kotlin
    /**
     * The one clock every piece of session arithmetic reads, hoisted so the core and the
     * notification controller cannot end up on two different notions of "now". Uptime, not
     * elapsedRealtime: a session must not bill the user for time the device spent asleep. See
     * MonotonicClock for the rest of the reasoning.
     */
    val clock = MonotonicClock { SystemClock.uptimeMillis() }

    @Suppress("UnnecessaryLateinit")
    internal lateinit var overlayManager: OverlayManager
        private set

    @Suppress("UnnecessaryLateinit")
    internal lateinit var notifier: AndroidNotifier
        private set

    @Suppress("UnnecessaryLateinit")
    internal lateinit var notificationController: NotificationController
        private set

    /**
     * Set by `MainActivity` for as long as an Activity is alive. Read only inside
     * [toDependencies], so a request made with no Activity alive drops the callback instead of
     * crashing — which is the correct outcome, because there is no visible surface to answer on.
     */
    @Volatile
    internal var notificationPermissionHost: NotificationPermissionHost? = null

    val coreMutex = Mutex()

    val core = MonitoringCore(
        repository = repository,
        todayKey = todayKey,
        selfPackageName = context.packageName,
        clock = clock,
        scope = scope,
        // No extra launch hop: OverlayManager.show posts to the main thread itself, so wrapping
        // this in scope.launch would only add a queue between the decision and the screen. The
        // lambdas read the lateinit properties when they run, which is after init.
        onOverlayDecision = { decision ->
            overlayManager.show(decision)
            // The core already emits the crossing, so the notification hears about it here rather
            // than through a second callback for the same event — one signal, one place.
            notificationController.onLimitCrossed()
        },
        onSessionChanged = { snapshot -> notificationController.onSessionChanged(snapshot) },
    )

    init {
        overlayManager = OverlayManager(context)
        notifier = AndroidNotifier(context)
        notificationController = NotificationController(
            notifier = notifier,
            clock = clock,
            scope = scope,
        )
    }

    /**
     * Starts (or re-starts) the monitoring foreground service. Idempotent by construction:
     * `onStartCommand` re-registers receivers — guarded by a flag — and `startPolling` returns
     * early when a poll job is already running, so calling this from onboarding, from a boot
     * broadcast and from `MainActivity.onCreate` cannot double-poll.
     */
    fun startMonitoring() = MonitoringForegroundService.start(context)
```

In `toDependencies`, add two fields to the `WanciAppDependencies(...)` call:

```kotlin
            // The launcher lives in the Activity, so the shared graph only ever sees the request
            // shape. Null host is a dropped request, not a crash: there is no visible surface to
            // show the system dialog on.
            notificationPermissionRequest = NotificationPermissionRequest { onResult ->
                notificationPermissionHost?.requestNotificationPermission(onResult)
            },
            startMonitoring = { startMonitoring() },
```

Add these imports: `com.septaalfauzan.wanci.monitoring.AndroidNotifier`,
`com.septaalfauzan.wanci.monitoring.MonitoringForegroundService`,
`com.septaalfauzan.wanci.monitoring.NotificationController`.

- [ ] **Step 4: Update `MainActivity`**

Add these imports: `android.Manifest`, `android.content.pm.PackageManager`, `android.os.Build`,
`androidx.activity.result.contract.ActivityResultContracts`,
`com.septaalfauzan.wanci.permission.PermissionChecks`.

`NotificationPermissionHost` is in `com.septaalfauzan.wanci`, the same package as `MainActivity`,
so it needs no import. `WanciApplication` likewise.

Add these members inside the class:

```kotlin
    /**
     * `POST_NOTIFICATIONS` is a runtime dialog on API 33+, and shared code cannot hold a launcher.
     * The pending callback is held here rather than captured by the launch lambda because the
     * launcher is registered at construction time while the shared graph is built later, in
     * `setContent` — the two cannot be wired together at registration time.
     */
    private var onNotificationAnswer: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onNotificationAnswer?.also { onNotificationAnswer = null }?.invoke() }

    private val permissionHost = object : NotificationPermissionHost {
        override fun requestNotificationPermission(onResult: () -> Unit) {
            val alreadyGranted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (alreadyGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // Nothing to ask: below API 33 the permission does not exist, and an already-granted
                // permission is a fact the caller wants immediately rather than after a round trip.
                onResult()
                return
            }
            onNotificationAnswer = onResult
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
```

`Context.checkSelfPermission` is API 23 and the floor is 28, so no `ContextCompat` and no
`androidx.core` dependency is needed. `registerForActivityResult` is called on a field initializer,
which runs during construction — legal because `ComponentActivity` supports registration before
`onCreate`.

Insert into `onCreate`, immediately after `super.onCreate(savedInstanceState)`:

```kotlin
        val container = application as WanciApplication
        container.notificationPermissionHost = permissionHost
        // The one re-entry for a force-stop: the system will not restart anything the user stopped
        // by hand, and opening the app is the only signal that they want it running again. Guarded
        // on usage access, because a service with nothing to poll would publish the "Usage Access
        // needed" state on the first tick and never recover on its own.
        if (PermissionChecks.status(this).usageAccess) container.startMonitoring()
```

Leave the existing `enableEdgeToEdge()` / `super.onCreate(...)` pair above it untouched, and change
the existing `setContent` block to reuse the new local instead of re-casting — replace

```kotlin
            val deps = remember(deepLink) {
                (application as WanciApplication).container.toDependencies(deepLink)
            }
```

with

```kotlin
            val deps = remember(deepLink) { container.toDependencies(deepLink) }
```

Add `onDestroy`, which releases the host so the container cannot outlive the Activity holding the
launcher:

```kotlin
    override fun onDestroy() {
        val container = application as? WanciApplication
        if (container?.notificationPermissionHost === permissionHost) {
            container.notificationPermissionHost = null
        }
        super.onDestroy()
    }
```

`MainActivity` is not recreated on a configuration change (it has no `configChanges`), so `onDestroy`
runs on a rotation too — the new instance re-registers in its own `onCreate`, and the identity check
above keeps the teardown from clearing a host a newer instance already installed.

- [ ] **Step 5: Update the permission checks and actions**

Replace `PermissionChecks.kt`:

```kotlin
package com.septaalfauzan.wanci.permission

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import com.septaalfauzan.wanci.domain.PermissionStatus

object PermissionChecks {
    fun status(context: Context): PermissionStatus = PermissionStatus(
        usageAccess = hasUsageAccess(context),
        overlay = Settings.canDrawOverlays(context),
        notification = hasNotificationPermission(context),
    )

    private fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Whether notifications are allowed, which is the question the onboarding row actually asks.
     * `areNotificationsEnabled` is the answer the system gives when the permission is granted *or*
     * when the user turned notifications off in Settings afterwards — and a user who did that has
     * the same problem as a user who never granted: no notification, no visible enforcement.
     */
    private fun hasNotificationPermission(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled()
    }
}
```

`areNotificationsEnabled` is API 24+ and the floor is 28, so no guard is needed.

Replace `PermissionActions.kt`:

```kotlin
package com.septaalfauzan.wanci.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.septaalfauzan.wanci.domain.PermissionNavigator

class PermissionActions(private val context: Context) : PermissionNavigator {
    override fun openUsageAccess() {
        try {
            start(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
        }
    }

    override fun openOverlay() {
        val uri = Uri.parse("package:${context.packageName}")
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
        if (intent.resolveActivity(context.packageManager) != null) {
            start(intent)
        }
    }

    private fun start(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
```

- [ ] **Step 6: Build the app**

Run: `./gradlew :androidApp:assembleDebug`

Expected: BUILD SUCCESSFUL. Every failure mode at this point is a wiring mistake from the steps
above, and each has one fix:

| Compiler error | Cause | Fix |
|---|---|---|
| `WanciAppDependencies` missing arguments | Task 4 added the fields; Step 3 must pass them | Check Step 3's `toDependencies` block |
| `Unresolved reference: SessionSnapshot` in `androidApp` | `implementation(project(":shared"))` is already in place | Verify the import path is `com.septaalfauzan.wanci.monitoring.SessionSnapshot` |
| `Unresolved reference: androidNotifier` | `internal` visibility across packages | `notificationController` and `notifier` are read from the `monitoring` package — `internal` is module-wide in Kotlin, so this compiles; if it does not, make them `public` |
| Unused-import warnings in the service | — | The import list in Step 1 is exact |

- [ ] **Step 7: Run both test suites**

Run: `./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest`

Expected: all suites PASS. The parser and controller suites from Tasks 5 and 6 must still pass after the wiring compiles.

- [ ] **Step 8: Commit**

```bash
git add androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringForegroundService.kt \
        androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/BootReceiver.kt \
        androidApp/src/main/kotlin/com/septaalfauzan/wanci/AppContainer.kt \
        androidApp/src/main/kotlin/com/septaalfauzan/wanci/MainActivity.kt \
        androidApp/src/main/kotlin/com/septaalfauzan/wanci/permission/PermissionChecks.kt \
        androidApp/src/main/kotlin/com/septaalfauzan/wanci/permission/PermissionActions.kt \
        androidApp/build.gradle.kts
git commit -m "feat: run monitoring from a foreground service"
```

---

### Task 8: Manifest swap and accessibility deletion

**Files:**
- Modify: `androidApp/src/main/AndroidManifest.xml`
- Delete: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringAccessibilityService.kt`
- Delete: `androidApp/src/main/res/xml/accessibility_service_config.xml`
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCore.kt` (KDoc only)
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/dashboard/DashboardScreen.kt` (KDoc only)
- Modify: `androidApp/src/main/kotlin/com/septaalfauzan/wanci/apps/InstalledAppsProviderAndroid.kt` (KDoc only)

**Interfaces:**
- Consumes: `MonitoringForegroundService`, `BootReceiver` (Task 7).
- Produces: a manifest with zero accessibility footprint.

- [ ] **Step 1: Rewrite the manifest**

Replace `androidApp/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission
        android:name="android.permission.QUERY_ALL_PACKAGES"
        tools:ignore="QueryAllPackagesPermission" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission
        android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <queries>
        <intent>
            <action android:name="android.settings.action.MANAGE_OVERLAY_PERMISSION" />
        </intent>
    </queries>

    <application
        android:name=".WanciApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:exported="true"
            android:name=".MainActivity"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!--
            specialUse, not dataSync: this is continuous monitoring, not a transfer job, and API 34+
            requires the declared type to be accurate. The subtype property below is the answer to
            a Play review question about why the type was chosen.
        -->
        <service
            android:exported="false"
            android:name=".monitoring.MonitoringForegroundService"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Ongoing screen-time monitoring so app limits are enforced without the app open." />
        </service>

        <receiver
            android:exported="true"
            android:name=".monitoring.BootReceiver">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
    </application>

</manifest>
```

- [ ] **Step 2: Delete the accessibility service and its config**

```bash
git rm androidApp/src/main/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringAccessibilityService.kt \
       androidApp/src/main/res/xml/accessibility_service_config.xml
```

The `res/xml` directory becomes empty; git removes it. Confirm with `ls androidApp/src/main/res/` that no `xml` directory remains.

- [ ] **Step 3: Update the stale KDoc**

In `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/monitoring/MonitoringCore.kt`, the class KDoc still says `UsageStatsManager` is the thing being replaced. Change:

```
 * Usage is written exactly once, when the session closes. Nothing is written during a session, so
 * an open session is unpersisted and a process death loses it. That is a deliberate trade: the
 * `UsageStatsManager` reconcile this replaces was the previous answer to that loss and it could not
 * cover it, because it only ever totalled packages it saw *close*.
```

to:

```
 * Usage is written exactly once, when the session closes. Nothing is written during a session, so
 * an open session is unpersisted and a process death loses it. That is a deliberate trade, and
 * `UsageStatsManager` is read again since the 2026-09-28 migration — for foreground detection only.
 * It is not a second account of usage: the core still receives only `onForegroundChange` and still
 * writes once, at close.
```

Change `attach()`'s KDoc:

```
    /**
     * Begins accepting foreground changes. The core is a process-wide singleton and Android
     * reconnects the accessibility service to the same process, so [detach] has to be reversible
     * rather than terminal. Called from `onServiceConnected`.
     */
```

to:

```
    /**
     * Begins accepting foreground changes. The core is a process-wide singleton and Android
     * restarts the foreground service in the same process, so [detach] has to be reversible
     * rather than terminal. Called from `onStartCommand`.
     */
```

Change `detach()`'s KDoc opening line:

```
     * Stops everything immediately and synchronously, for `AccessibilityService.onDestroy`,
```

to:

```
     * Stops everything immediately and synchronously, for `Service.onDestroy`,
```

Change the comment in `onForegroundChange`:

```
        // The accessibility service delivers a window-state change for activity changes, in-app
        // dialogs and permission prompts on the same package. Those are not switches, so they must
        // not reach the close path at all: closing would persist a partial session and reopening
        // would restart the clock, and the run would become two writes instead of one.
```

to:

```
        // Every foreground source — a window-state change, a usage event, an activity change, an
        // in-app dialog, a permission prompt — repeats the same package for what is not a switch,
        // so those must not reach the close path at all: closing would persist a partial session
        // and reopening would restart the clock, and the run would become two writes instead of one.
```

In `shared/src/commonMain/kotlin/com/septaalfauzan/wanci/presentation/dashboard/DashboardScreen.kt`, change:

```
 * of the observed app list, so a minute recorded by the accessibility service shows up here while
```

to:

```
 * of the observed app list, so a minute recorded by the monitoring service shows up here while
```

In `androidApp/src/main/kotlin/com/septaalfauzan/wanci/apps/InstalledAppsProviderAndroid.kt`, change:

```
 * the accessibility service can only ever see an app that was started somehow, and a
```

to:

```
 * the monitoring service can only ever see an app that was started somehow, and a
```

- [ ] **Step 4: Verify no accessibility footprint remains**

Run:

```bash
grep -rEil "accessibility" --include="*.kt" --include="*.xml" . | grep -v "/build/"
```

Expected: no output. Any hit is a leftover and must be edited or deleted before the commit.

- [ ] **Step 5: Build and test**

Run: `./gradlew :androidApp:assembleDebug :shared:testAndroidHostTest :androidApp:testDebugUnitTest`

Expected: BUILD SUCCESSFUL, all suites PASS.

- [ ] **Step 6: Sanity-check the merged manifest**

Run:

```bash
./gradlew :androidApp:processDebugMainManifest > /dev/null && \
  grep -c "BIND_ACCESSIBILITY_SERVICE" androidApp/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
```

Expected: `0`.

- [ ] **Step 7: Commit**

```bash
git add -A androidApp/src/main androidApp/build.gradle.kts shared/src/commonMain
git commit -m "refactor: drop the accessibility service entirely"
```

---

### Task 9: Manual acceptance on a device

**Files:** none. No `androidTest` source set exists in this project and none is introduced.

- [ ] **Step 1: Install and confirm the app starts**

Run: `./gradlew :androidApp:installDebug`

Expected: launch succeeds and the notification permission row appears in onboarding instead of an accessibility row.

- [ ] **Step 2: The acceptance criterion for the whole migration**

Open a banking app (mBanking or any equivalent that blocks on *any* enabled accessibility service) with Wanci installed, monitoring running and Wanci's own accessibility setting irrelevant.

Expected: the banking app opens normally. This is the reason the spec exists; if it fails, the migration has not achieved its goal regardless of what else works.

- [ ] **Step 3: Foreground detection latency**

With a monitored app that has a limit comfortably in the future, switch between it and the launcher.

Expected: the notification title changes to the app name within about one second plus the OEM's event lag. No session split: today's usage in the app's settings row increases continuously.

- [ ] **Step 4: Overlay**

Set a monitored app's limit to 1 minute, open it, and wait.

Expected: the overlay appears at the limit, and it reappears on every re-entry.

- [ ] **Step 5: Reboot**

Reboot the device. Do not open Wanci. Open a monitored app that is already over its limit.

Expected: the overlay appears, which can only happen if the boot receiver started the service.

- [ ] **Step 6: Revoke usage access**

Revoke Usage Access for Wanci in system Settings while monitoring is running.

Expected: the notification flips to "Usage Access needed" within about a second, the service does not crash, and re-opening Wanci's onboarding offers the permission again. No auto-restart loop.

- [ ] **Step 7: Screen-off accrual**

Open a monitored app, wait a few seconds, turn the screen off, wait a minute, then close the app.

Expected: usage accrues only for the awake seconds, and the notification reads "Monitoring active" while dark.

- [ ] **Step 8: Force-stop recovery**

Force-stop Wanci from the app switcher, then open Wanci from the launcher without granting anything new.

Expected: monitoring resumes, because `MainActivity.onCreate` is the re-entry for a force-stop.
