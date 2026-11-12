package com.septaalfauzan.meotime.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.septaalfauzan.meotime.data.local.TodayKey
import com.septaalfauzan.meotime.domain.AppIconProvider
import com.septaalfauzan.meotime.domain.InstalledAppCheck
import com.septaalfauzan.meotime.domain.InstalledAppsProvider
import com.septaalfauzan.meotime.domain.NotificationPermissionRequest
import com.septaalfauzan.meotime.domain.PermissionNavigator
import com.septaalfauzan.meotime.domain.PermissionStatusProvider
import com.septaalfauzan.meotime.domain.useCase.MeotimeUseCases
import com.septaalfauzan.meotime.presentation.navigation.AppNavHost
import com.septaalfauzan.meotime.presentation.theme.MonochromeTheme

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
data class MeotimeAppDependencies(
    val useCases: MeotimeUseCases,
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

val LocalDependencies = staticCompositionLocalOf<MeotimeAppDependencies> {
    error("MeotimeAppDependencies not provided")
}

/**
 * The single entry point every host composes: dependencies in, themed navigation out.
 *
 * [MonochromeTheme] is applied here rather than per screen so a screen can never be shown
 * without the design system, and its `spacing`/`controls` parameters stay the one seam a host
 * overrides (e.g. a settings screen offering a larger touch target) without re-theming
 * individual destinations.
 *
 * The root also paints `colorScheme.background`. Every screen below it is a bare `Column` or
 * `LazyColumn` that deliberately paints nothing of its own, so without this fill the whole app is
 * transparent and the *platform window* is what the user sees — which stays light on a light
 * Android theme no matter what the Compose scheme says, i.e. near-white text on a white window in
 * dark mode. `safeContentPadding` insets the content only; the fill deliberately covers the insets
 * too, so a gesture bar never exposes the window underneath.
 */
@Composable
fun App(dependencies: MeotimeAppDependencies) {
    CompositionLocalProvider(LocalDependencies provides dependencies) {
        MonochromeTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .safeContentPadding(),
            ) {
                AppNavHost()
            }
        }
    }
}
