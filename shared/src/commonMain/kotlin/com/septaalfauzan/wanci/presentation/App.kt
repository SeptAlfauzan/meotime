package com.septaalfauzan.wanci.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.septaalfauzan.wanci.data.local.TodayKey
import com.septaalfauzan.wanci.domain.AppIconProvider
import com.septaalfauzan.wanci.domain.InstalledAppCheck
import com.septaalfauzan.wanci.domain.InstalledAppsProvider
import com.septaalfauzan.wanci.domain.NotificationPermissionRequest
import com.septaalfauzan.wanci.domain.PermissionNavigator
import com.septaalfauzan.wanci.domain.PermissionStatusProvider
import com.septaalfauzan.wanci.domain.useCase.WanciUseCases
import com.septaalfauzan.wanci.presentation.navigation.AppNavHost
import com.septaalfauzan.wanci.presentation.theme.MonochromeTheme

/**
 * Everything the UI needs from the host, assembled by `androidApp`'s `AppContainer`
 * (`toDependencies`) and handed to [App]. Keeping it a plain data class is what makes the
 * DI manual and the whole graph buildable in one place: adding a screen in Tasks 10-13
 * means adding a field here, not a framework.
 *
 * [deepLink] is the deep-link seam. `OverlayManager` launches the app with the PACKAGE extra when
 * the user taps "Manage restriction" on the overlay, and the navigation host turns a non-null
 * value into the `appsettings/{packageName}` route. It is host state, not navigation state, so it
 * is passed in rather than read from a platform Intent inside common code.
 */
data class WanciAppDependencies(
    val useCases: WanciUseCases,
    val todayKey: TodayKey,
    val permissionStatusProvider: PermissionStatusProvider,
    val permissionNavigator: PermissionNavigator,
    /**
     * `POST_NOTIFICATIONS` is a runtime dialog on API 33+, so the host owns the launcher and common
     * code only sees the request shape. Answering it re-reads the status snapshot.
     */
    val notificationPermissionRequest: NotificationPermissionRequest,
    /** Starts the monitoring foreground service. Called once, when onboarding completes. */
    val startMonitoring: () -> Unit,
    val installedAppsProvider: InstalledAppsProvider,
    val installedAppCheck: InstalledAppCheck,
    val appIconProvider: AppIconProvider,
    val deepLink: DeepLinkRequest? = null,
)

/**
 * One deep-link delivery: the app to open, plus a per-delivery [nonce].
 *
 * The nonce exists because the Manage flow always targets the *same* package. A bare `String?`
 * would be compared structurally, so re-delivering `com.foo` over `com.foo` invalidates no
 * Compose state, nothing recomposes and the navigation effect never re-runs — the user taps
 * "Manage restriction" a second time and stays where they are. Every delivery gets a fresh nonce,
 * so [deepLink] is never equal to the previous one and the delivery is always observable. It is
 * monotonic per activity, not global: it only has to differ between two consecutive deliveries in
 * one process.
 */
data class DeepLinkRequest(
    val packageName: String,
    val nonce: Long,
)

val LocalDependencies = staticCompositionLocalOf<WanciAppDependencies> {
    error("WanciAppDependencies not provided")
}

/**
 * The single entry point every host composes: dependencies in, themed navigation out.
 *
 * [MonochromeTheme] is applied here rather than per screen so a screen can never be shown
 * without the design system, and its `spacing`/`controls` parameters stay the one seam a host
 * overrides (e.g. a settings screen offering a larger touch target) without re-theming
 * individual destinations.
 */
@Composable
fun App(dependencies: WanciAppDependencies) {
    CompositionLocalProvider(LocalDependencies provides dependencies) {
        MonochromeTheme {
            AppNavHost()
        }
    }
}
