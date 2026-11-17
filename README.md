# Meotime

Kotlin Multiplatform screen-time monitoring app for Android (and iOS shell). Tracks daily usage for selected apps, enforces per-app limits, and shows an overlay/notification when a limit is reached.

## Status

- **Android:** primary target; monitoring, permissions, overlay, and UI flows implemented.
- **iOS:** Compose Multiplatform entry shell (`MainViewController` / demo `App()` in shared) present; Android-specific monitoring/permission features are not implemented on iOS.

## Modules

- [`:shared`](./shared) — Kotlin Multiplatform code shared across targets.
  - `commonMain`: Compose UI, navigation, domain/use cases, repository, Room entities/DAOs/database, monitoring core logic.
  - `androidMain`: Android platform implementations (database builder, migrations/platform wiring).
  - `iosMain`: iOS platform implementations.
  - `commonTest`, `androidHostTest`: shared and Android-hosted tests.
- [`:androidApp`](./androidApp) — Android application host.
  - Activity/container wiring, permission handling, installed-app/icon providers.
  - Foreground monitoring service, usage-event parsing, notifications, overlay, boot receiver.
- [`:iosApp`](./iosApp) — iOS app entry point (Xcode project) hosting shared Compose UI.

## Key Features

- **Onboarding & permissions:** requests/checks usage access, overlay, and notifications (runtime `POST_NOTIFICATIONS` on API 33+ with Settings fallback when dialog exhausted).
- **Dashboard:** lists monitored apps with today’s usage, limit progress, loading/error/empty states.
- **Add app flow:** pick installed app and set daily limit.
- **App settings:** view/edit limit, enable/disable monitoring, remove app.
- **Monitoring core (`shared`):** tracks foreground session timing with monotonic clock, records usage on session close, emits limit-crossed decisions.
- **Android enforcement host:** foreground service (`specialUse`), overlay manager, notification controller, boot receiver to resume monitoring.

## Architecture Overview

- **UI:** Compose Multiplatform + Material 3, single `App(dependencies)` entry with `CompositionLocalProvider`.
- **Navigation:** Jetpack Navigation Compose routes — `onboarding`, `dashboard`, `addapp`, `limit/{packageName}`, `appsettings/{packageName}` (deep-link support for manage-from-overlay).
- **Data:** Room (`MonitoredApp`, `DailyUsage`, `SyncState`) via repository/use-case layer.
- **Platform abstractions:** installed apps, app icons/labels, permission status/navigation, notification permission request, monitoring start/clock hooks.
- **Android DI:** manual `AppContainer` assembled in `MeotimeApplication`.

## Tech Stack

- Kotlin `2.4.20`, AGP `9.1.1`, Compose Multiplatform `1.12.1`
- AndroidX Room `2.8.5` + KSP, SQLite bundled
- Kotlin Coroutines, Lifecycle ViewModel/Runtime Compose, Navigation Compose
- Testing: `kotlin.test`, coroutines-test, Robolectric (Android host tests), AndroidX test/JUnit

## Requirements

- JDK 11+ (project compiles with JVM target 11)
- Android SDK with compile SDK `37`, min SDK `28`, target SDK `37`
- Android Studio / IntelliJ with Kotlin Multiplatform support (recommended)
- Xcode for building/running the iOS app

## Build & Run

### Android

```bash
./gradlew :androidApp:assembleDebug
```

Install/run from IDE, or install the debug APK on a device/emulator.

### iOS

Open [`iosApp/`](./iosApp) in Xcode and run from there (framework produced by shared KMP module).

## Tests

```bash
# Shared/common + Android host tests
./gradlew :shared:testAndroidHostTest

# iOS simulator tests (Apple Silicon simulator target configured)
./gradlew :shared:iosSimulatorArm64Test

# Android app unit tests
./gradlew :androidApp:test
```

Tests can also be run from the IDE gutter/run configurations.

## Android Permissions & Behavior

Declared in [`androidApp/src/main/AndroidManifest.xml`](./androidApp/src/main/AndroidManifest.xml):

- `PACKAGE_USAGE_STATS` (protected) — detect foreground app/usage for monitoring
- `SYSTEM_ALERT_WINDOW` — show limit overlay
- `POST_NOTIFICATIONS` — foreground/monitoring notifications (runtime on API 33+)
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` — continuous monitoring service
- `RECEIVE_BOOT_COMPLETED` — restart monitoring after boot
- `QUERY_ALL_PACKAGES` — enumerate/select installed apps

`MainActivity` is `singleTask` to receive overlay deep links while running.

## Project Layout (high level)

```text
androidApp/   Android host app, services, receivers, permissions, UI wiring
iosApp/       iOS app entry (SwiftUI host for shared Compose)
shared/       KMP shared UI, domain, data, monitoring core, platform contracts
gradle/       Version catalog and wrapper
```

## Notes

- Usage is recorded when a monitored session closes (not continuously persisted mid-session), so an abrupt process death can lose in-progress session time.
- README reflects current repository structure/behavior; update as features evolve.
