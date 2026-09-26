package com.septaalfauzan.wanci.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.septaalfauzan.wanci.data.local.TodayKey
import com.septaalfauzan.wanci.domain.InstalledAppCheck
import com.septaalfauzan.wanci.domain.InstalledAppsProvider
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
 * [requestedPackage] is the deep-link seam. `OverlayManager` launches the app with
 * `com.septaalfauzan.wanci.extra.PACKAGE` when the user taps "Manage restriction" on the
 * overlay, and the navigation host turns a non-null value into the `appsettings/{packageName}`
 * route. It is host state, not navigation state, so it is passed in rather than read from a
 * platform Intent inside common code.
 */
data class WanciAppDependencies(
    val useCases: WanciUseCases,
    val todayKey: TodayKey,
    val permissionStatusProvider: PermissionStatusProvider,
    val permissionNavigator: PermissionNavigator,
    val installedAppsProvider: InstalledAppsProvider,
    val installedAppCheck: InstalledAppCheck,
    val requestedPackage: String? = null,
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
