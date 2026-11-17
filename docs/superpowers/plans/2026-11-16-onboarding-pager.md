# Three-Page Onboarding Pager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current single-screen onboarding with a three-page horizontal pager (Welcome → Permissions → All set), with supplied illustrations and page 3 gated on all permissions being granted.

**Architecture:** Keep the existing `ONBOARDING` route and `OnboardingViewModel`. Add a pure navigation policy, a pages file with the three page composables, and rebuild `OnboardingScreen` around `HorizontalPager`. The pager freezes swiping while the permission page is ungranted, while explicit Back and Continue buttons remain available.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform 1.12.1, Material 3, `androidx.lifecycle.runtimeCompose` 2.11.0, Compose Resources PNG drawables, `kotlin.test`.

**Spec:** `docs/superpowers/specs/2026-11-16-onboarding-pager-design.md`

## Global Constraints

- Do not add dependencies. `androidx.lifecycle.runtimeCompose` and Compose Foundation pager are already present.
- Do not change `OnboardingViewModel`, its state model, its tests, or the persisted `onboardingComplete` key.
- Do not change `AppNavHost` routes or permission implementations.
- Keep the public signature `OnboardingScreen(viewModel: OnboardingViewModel, onFinished: () -> Unit, modifier: Modifier = Modifier)`.
- Brand text on every page is `MeoTime`.
- Page 3 (`Ready`) must be unreachable unless `PermissionStatus.allGranted` is true.
- Use `MeotimeTokens.spacing` and the existing `MeotimeButton` / `MeotimeOutlinedButton` / `MeotimeTextButton`. The only raw dp values allowed are the approved illustration max height `260.dp` and content max width `480.dp`.
- Illustrations are decorative: `contentDescription = null`.
- Resource names: `onboarding_welcome.png`, `onboarding_permissions.png`, `onboarding_ready.png`.
- Verification commands:
  - `./gradlew :shared:testAndroidHostTest`
  - `./gradlew :shared:iosSimulatorArm64Test`
  - `./gradlew :androidApp:assembleDebug --no-configuration-cache`

## File Structure

| File | Responsibility |
|---|---|
| `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingNavigation.kt` | Page indices and the pure `canNavigateToPage` gate. |
| `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingNavigationTest.kt` | Policy tests. |
| `shared/src/commonMain/composeResources/drawable/onboarding_welcome.png` | Welcome illustration. |
| `shared/src/commonMain/composeResources/drawable/onboarding_permissions.png` | Permission illustration. |
| `shared/src/commonMain/composeResources/drawable/onboarding_ready.png` | Ready illustration. |
| `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingPages.kt` | Illustration atom, page layout, three page composables, permission row, notice, page indicator. |
| `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingScreen.kt` | Pager scaffold, gating, resume refresh, bottom controls. |

---

### Task 1: Add the onboarding navigation policy

**Files:**
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingNavigation.kt`
- Create: `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingNavigationTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `internal enum class OnboardingPage { Welcome, Permissions, Ready }`
  - `internal val OnboardingPageCount: Int`
  - `internal fun canNavigateToPage(target: Int, allGranted: Boolean): Boolean`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingNavigationTest.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.onboarding

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingNavigationTest {

    @Test
    fun welcomeAndPermissionsAreReachableWithoutPermissions() {
        assertTrue(canNavigateToPage(OnboardingPage.Welcome.ordinal, allGranted = false))
        assertTrue(canNavigateToPage(OnboardingPage.Permissions.ordinal, allGranted = false))
    }

    @Test
    fun readyIsBlockedUntilAllPermissionsAreGranted() {
        assertFalse(canNavigateToPage(OnboardingPage.Ready.ordinal, allGranted = false))
        assertTrue(canNavigateToPage(OnboardingPage.Ready.ordinal, allGranted = true))
    }

    @Test
    fun outOfRangeTargetsAreRejected() {
        assertFalse(canNavigateToPage(-1, allGranted = true))
        assertFalse(canNavigateToPage(OnboardingPageCount, allGranted = true))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew :shared:testAndroidHostTest --tests "com.septaalfauzan.meotime.presentation.onboarding.OnboardingNavigationTest"
```

Expected: compilation fails because `canNavigateToPage`, `OnboardingPage`, and `OnboardingPageCount` do not exist.

- [ ] **Step 3: Write the minimal implementation**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingNavigation.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.onboarding

internal enum class OnboardingPage {
    Welcome,
    Permissions,
    Ready,
}

internal val OnboardingPageCount: Int = OnboardingPage.entries.size

/**
 * Page 0 and page 1 are always walkable. Page 2 is the "All set" page and is only
 * reachable once every onboarding permission is granted; an out-of-range index is
 * never reachable.
 */
internal fun canNavigateToPage(target: Int, allGranted: Boolean): Boolean {
    if (target !in OnboardingPage.entries.indices) return false
    return OnboardingPage.entries[target] != OnboardingPage.Ready || allGranted
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew :shared:testAndroidHostTest --tests "com.septaalfauzan.meotime.presentation.onboarding.OnboardingNavigationTest"
```

Expected: `BUILD SUCCESSFUL`; the three tests pass.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingNavigation.kt \
  shared/src/commonTest/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingNavigationTest.kt
git commit -m "feat: gate onboarding ready page on granted permissions"
```

---

### Task 2: Add onboarding illustrations and page composables

**Files:**
- Create: `shared/src/commonMain/composeResources/drawable/onboarding_welcome.png` (copy of `image_1.png`)
- Create: `shared/src/commonMain/composeResources/drawable/onboarding_permissions.png` (copy of `image_2.png`)
- Create: `shared/src/commonMain/composeResources/drawable/onboarding_ready.png` (copy of `image_3.png`)
- Create: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingPages.kt`

**Interfaces:**
- Consumes: `OnboardingUiState` and `PermissionStatus` from the onboarding package.
- Produces:
  - `@Composable internal fun WelcomePage(modifier: Modifier = Modifier)`
  - `@Composable internal fun PermissionsPage(state: OnboardingUiState, onRequestUsageAccess: () -> Unit, onRequestOverlay: () -> Unit, onRequestNotification: () -> Unit, onCheckPermissions: () -> Unit, modifier: Modifier = Modifier)`
  - `@Composable internal fun ReadyPage(state: OnboardingUiState, modifier: Modifier = Modifier)`
  - `@Composable internal fun OnboardingPageIndicator(currentPage: Int, pageCount: Int, modifier: Modifier = Modifier)`

- [ ] **Step 1: Copy the supplied illustrations into Compose Resources**

Run from the repository root:

```bash
mkdir -p shared/src/commonMain/composeResources/drawable
cp image_1.png shared/src/commonMain/composeResources/drawable/onboarding_welcome.png
cp image_2.png shared/src/commonMain/composeResources/drawable/onboarding_permissions.png
cp image_3.png shared/src/commonMain/composeResources/drawable/onboarding_ready.png
ls -lh shared/src/commonMain/composeResources/drawable/onboarding_*.png
```

Expected: three files listed, each about 230–260 KB.

- [ ] **Step 2: Create the page composables**

Create `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingPages.kt`:

```kotlin
package com.septaalfauzan.meotime.presentation.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.septaalfauzan.meotime.presentation.theme.MeotimeOutlinedButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTextButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens
import meotime.shared.generated.resources.Res
import meotime.shared.generated.resources.onboarding_permissions
import meotime.shared.generated.resources.onboarding_ready
import meotime.shared.generated.resources.onboarding_welcome
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun WelcomePage(modifier: Modifier = Modifier) {
    OnboardingPageLayout(
        illustration = Res.drawable.onboarding_welcome,
        title = "MeoTime",
        body = "Manage your screen time for your better life.",
        modifier = modifier,
    )
}

@Composable
internal fun PermissionsPage(
    state: OnboardingUiState,
    onRequestUsageAccess: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestNotification: () -> Unit,
    onCheckPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingPageLayout(
        illustration = Res.drawable.onboarding_permissions,
        title = "Permission is required",
        body = "We need some permissions to track your screen time.",
        modifier = modifier,
    ) {
        Spacer(Modifier.height(MeotimeTokens.spacing.lg))
        PermissionRow(
            title = "Usage Access",
            detail = "Measure how long you use apps.",
            granted = state.permissionStatus.usageAccess,
            onClick = onRequestUsageAccess,
        )
        PermissionRow(
            title = "Display over other apps",
            detail = "Show the reminder when you reach a limit.",
            granted = state.permissionStatus.overlay,
            onClick = onRequestOverlay,
        )
        PermissionRow(
            title = "Notifications",
            detail = "Show the reminder while Meotime is not open.",
            granted = state.permissionStatus.notification,
            onClick = onRequestNotification,
        )
        Spacer(Modifier.height(MeotimeTokens.spacing.md))
        MeotimeTextButton(
            onClick = onCheckPermissions,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Check permissions")
        }
        if (!state.permissionStatus.allGranted) {
            Spacer(Modifier.height(MeotimeTokens.spacing.sm))
            Text(
                text = "Grant all permissions to continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun ReadyPage(
    state: OnboardingUiState,
    modifier: Modifier = Modifier,
) {
    OnboardingPageLayout(
        illustration = Res.drawable.onboarding_ready,
        title = "All set",
        body = "Permission is granted. Let's make the most of MeoTime.",
        modifier = modifier,
    ) {
        if (state.saveFailed) {
            Spacer(Modifier.height(MeotimeTokens.spacing.lg))
            OnboardingNotice(
                label = "Not saved",
                message = "Meotime couldn't finish setting up. Nothing was changed — try again.",
            )
        }
    }
}

@Composable
private fun OnboardingPageLayout(
    illustration: DrawableResource,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = MeotimeTokens.spacing.screen,
                    vertical = MeotimeTokens.spacing.lg,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OnboardingIllustration(illustration)
            Spacer(Modifier.height(MeotimeTokens.spacing.md))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(MeotimeTokens.spacing.sm))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            content()
        }
    }
}

@Composable
private fun OnboardingIllustration(illustration: DrawableResource) {
    Image(
        painter = painterResource(illustration),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp),
    )
}

@Composable
internal fun OnboardingPageIndicator(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics {
            contentDescription = "Page ${currentPage + 1} of $pageCount"
        },
        horizontalArrangement = Arrangement.spacedBy(MeotimeTokens.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                modifier = Modifier
                    .height(MeotimeTokens.spacing.xs)
                    .width(if (selected) MeotimeTokens.spacing.lg else MeotimeTokens.spacing.xs)
                    .background(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun OnboardingNotice(label: String, message: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(MeotimeTokens.spacing.xxs))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MeotimeTokens.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(MeotimeTokens.spacing.sm))
        if (granted) {
            Text(
                text = "\u2713 Granted",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics {
                    contentDescription = "$title granted"
                },
            )
        } else {
            MeotimeOutlinedButton(
                onClick = onClick,
                modifier = Modifier.semantics {
                    contentDescription = "Grant $title"
                },
            ) {
                Text("Grant")
            }
        }
    }
}
```

- [ ] **Step 3: Compile to generate the resource accessors and check the new file**

Run:

```bash
./gradlew :androidApp:compileDebugKotlin --no-configuration-cache
```

Expected: `BUILD SUCCESSFUL`. If `Res.drawable.onboarding_welcome` cannot be resolved, run:

```bash
./gradlew :shared:generateComposeResClass
```

then re-run the compile command. Expected: accessors resolve.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/composeResources/drawable/onboarding_welcome.png \
  shared/src/commonMain/composeResources/drawable/onboarding_permissions.png \
  shared/src/commonMain/composeResources/drawable/onboarding_ready.png \
  shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingPages.kt
git commit -m "feat: add onboarding page composables and illustrations"
```

---

### Task 3: Replace the onboarding screen with the gated pager

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingScreen.kt`
- Test: no new test file; the policy test from Task 1 covers the gate.

**Interfaces:**
- Consumes:
  - `OnboardingPage`, `OnboardingPageCount`, `canNavigateToPage` from Task 1.
  - `WelcomePage`, `PermissionsPage`, `ReadyPage`, `OnboardingPageIndicator` from Task 2.
  - `OnboardingViewModel.state`, `refresh()`, `requestUsageAccess()`, `requestOverlay()`, `requestNotification()`, `completeOnboarding(onDone)`.
- Produces: unchanged public `OnboardingScreen(viewModel, onFinished, modifier)`.

- [ ] **Step 1: Replace `OnboardingScreen.kt`**

Write the full file:

```kotlin
package com.septaalfauzan.meotime.presentation.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.septaalfauzan.meotime.presentation.theme.MeotimeButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTextButton
import com.septaalfauzan.meotime.presentation.theme.MeotimeTokens
import kotlinx.coroutines.launch

/**
 * Three-page onboarding:
 *
 * 1. Welcome.
 * 2. Permission request.
 * 3. All set.
 *
 * Page 3 is unreachable until every permission is granted. While the user is on the
 * ungranted permission page, the pager's own swipe is frozen (with an explicit Back
 * button as the way back), and a revocation after reaching page 3 snaps the pager
 * back to the permission page.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    // Permissions are granted in another app (Settings) or in a runtime dialog, so the
    // snapshot has to be re-read when the app resumes; the manual "Check permissions"
    // button remains for a user who does not leave the app.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val pagerState = rememberPagerState(
        initialPage = OnboardingPage.Welcome.ordinal,
        pageCount = { OnboardingPageCount },
    )
    val scope = rememberCoroutineScope()
    val allGranted = state.permissionStatus.allGranted

    // Second line of defence: even if a future change lets the pager settle on the
    // Ready page, a snapshot without all permissions returns the user to page 2.
    LaunchedEffect(pagerState.currentPage, allGranted) {
        if (pagerState.currentPage == OnboardingPage.Ready.ordinal && !allGranted) {
            pagerState.animateScrollToPage(OnboardingPage.Permissions.ordinal)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            // Freeze swiping while the permission page is ungranted. This also freezes
            // back-swipe there, which is why the screen exposes an explicit Back button.
            userScrollEnabled = allGranted ||
                pagerState.settledPage == OnboardingPage.Welcome.ordinal,
        ) { page ->
            when (OnboardingPage.entries[page]) {
                OnboardingPage.Welcome -> WelcomePage()
                OnboardingPage.Permissions -> PermissionsPage(
                    state = state,
                    onRequestUsageAccess = viewModel::requestUsageAccess,
                    onRequestOverlay = viewModel::requestOverlay,
                    onRequestNotification = viewModel::requestNotification,
                    onCheckPermissions = viewModel::refresh,
                )
                OnboardingPage.Ready -> ReadyPage(state = state)
            }
        }

        OnboardingControls(
            currentPage = pagerState.currentPage,
            allGranted = allGranted,
            saving = state.saving,
            onBack = {
                scope.launch {
                    pagerState.animateScrollToPage(
                        (pagerState.currentPage - 1).coerceAtLeast(0),
                    )
                }
            },
            onWelcomeContinue = {
                scope.launch {
                    pagerState.animateScrollToPage(OnboardingPage.Permissions.ordinal)
                }
            },
            onPermissionsContinue = {
                if (canNavigateToPage(OnboardingPage.Ready.ordinal, allGranted)) {
                    scope.launch {
                        pagerState.animateScrollToPage(OnboardingPage.Ready.ordinal)
                    }
                }
            },
            onStart = { viewModel.completeOnboarding(onFinished) },
        )
    }
}

@Composable
private fun OnboardingControls(
    currentPage: Int,
    allGranted: Boolean,
    saving: Boolean,
    onBack: () -> Unit,
    onWelcomeContinue: () -> Unit,
    onPermissionsContinue: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .padding(
                horizontal = MeotimeTokens.spacing.screen,
                vertical = MeotimeTokens.spacing.lg,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OnboardingPageIndicator(
            currentPage = currentPage,
            pageCount = OnboardingPageCount,
        )
        Spacer(Modifier.height(MeotimeTokens.spacing.md))

        when (OnboardingPage.entries.getOrNull(currentPage)) {
            OnboardingPage.Welcome -> {
                MeotimeButton(
                    onClick = onWelcomeContinue,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue")
                }
            }

            OnboardingPage.Permissions -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MeotimeTextButton(onClick = onBack) {
                        Text("Back")
                    }
                    Spacer(Modifier.width(MeotimeTokens.spacing.sm))
                    MeotimeButton(
                        onClick = onPermissionsContinue,
                        enabled = allGranted,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Continue")
                    }
                }
            }

            OnboardingPage.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MeotimeTextButton(onClick = onBack) {
                        Text("Back")
                    }
                    Spacer(Modifier.width(MeotimeTokens.spacing.sm))
                    MeotimeButton(
                        onClick = onStart,
                        enabled = allGranted && !saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (saving) "Saving…" else "Start MeoTime")
                    }
                }
            }

            null -> Unit
        }
    }
}
```

- [ ] **Step 2: Compile the app**

Run:

```bash
./gradlew :androidApp:compileDebugKotlin --no-configuration-cache
```

Expected: `BUILD SUCCESSFUL` with no unresolved references.

- [ ] **Step 3: Run the unit tests**

Run:

```bash
./gradlew :shared:testAndroidHostTest
```

Expected: `BUILD SUCCESSFUL`; `OnboardingNavigationTest` passes and existing `OnboardingViewModelTest` cases remain green.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/septaalfauzan/meotime/presentation/onboarding/OnboardingScreen.kt
git commit -m "feat: rebuild onboarding as a gated three-page pager"
```

---

### Task 4: Full verification and manual QA

**Files:**
- No production file expected to change unless a verification step reveals a defect.
- If a defect is found, fix it in the owning file and add or update a test before re-running this task.

**Interfaces:**
- Consumes: all prior tasks.
- Produces: verified build and manual QA evidence.

- [ ] **Step 1: Run the Android host tests**

Run:

```bash
./gradlew :shared:testAndroidHostTest
```

Expected: `BUILD SUCCESSFUL`; all tests pass.

- [ ] **Step 2: Run the iOS simulator tests**

Run:

```bash
./gradlew :shared:iosSimulatorArm64Test
```

Expected: `BUILD SUCCESSFUL`; all tests pass. If the local machine cannot run the iOS simulator target, record the exact failure and stop; do not claim completion.

- [ ] **Step 3: Build the Android debug APK**

Run:

```bash
./gradlew :androidApp:assembleDebug --no-configuration-cache
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Install and run a fresh-install manual check**

Run:

```bash
./gradlew :androidApp:installDebug
```

Then verify on device/emulator:

- Fresh install (or clear app storage) starts on page 1: `MeoTime` title, `Continue` button, illustration 1.
- Tap `Continue`; page 2 shows the permission rows and illustration 2.
- Try to swipe from page 2 to page 3 with permissions ungranted: the pager does not move.
- Tap each `Grant`; the corresponding row changes to `✓ Granted`.
- Return from Android Settings: the rows update without tapping `Check permissions`.
- `Continue` on page 2 is disabled and `Grant all permissions to continue.` is visible until all three permissions are granted.
- After all three are granted, `Continue` is enabled; tapping it shows page 3 with illustration 3.
- Page 3 shows `Start MeoTime`; tapping it lands on the dashboard.
- Relaunch the app: onboarding does not reappear.
- Back button on page 2 returns to page 1.
- Large font scale: every page scrolls and the bottom controls remain reachable.
- Dark mode: text remains legible against the warm palette.

- [ ] **Step 5: Commit verification fixes only if any were needed**

If no files changed, no commit is made. If a fix was required:

```bash
git add <changed-files>
git commit -m "fix: correct onboarding pager verification issue"
```

## Self-Review

- Spec coverage: welcome/permission/ready pages (Tasks 2-3); gating (Tasks 1, 3); asset copying (Task 2); resume refresh (Task 3); accessibility labels, headings, decorative images, indicator semantics (Task 2); unchanged ViewModel and route (Global Constraints); tests and build/manual QA (Tasks 1, 4).
- Placeholder scan: no TBD/TODO placeholders; every code step contains the full code.
- Type consistency: `OnboardingPage`, `OnboardingPageCount`, `canNavigateToPage`, `WelcomePage`, `PermissionsPage`, `ReadyPage`, and `OnboardingPageIndicator` are defined once and referenced with matching signatures in later tasks.
