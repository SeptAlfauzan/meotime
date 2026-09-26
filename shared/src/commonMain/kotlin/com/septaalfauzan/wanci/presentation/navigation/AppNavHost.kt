package com.septaalfauzan.wanci.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.septaalfauzan.wanci.presentation.LocalDependencies
import com.septaalfauzan.wanci.presentation.dashboard.DashboardScreen
import com.septaalfauzan.wanci.presentation.dashboard.DashboardViewModel
import com.septaalfauzan.wanci.presentation.onboarding.OnboardingScreen
import com.septaalfauzan.wanci.presentation.onboarding.OnboardingViewModel

/**
 * Route strings. The `{packageName}` templates are matched by the navigation library; the
 * helpers build the concrete paths and are the only place that encodes them, so a package name
 * can never be read as a second path segment.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val ADD_APP = "addapp"
    const val LIMIT = "limit/{packageName}"
    const val APP_SETTINGS = "appsettings/{packageName}"

    /**
     * Name of the path argument, shared by the `{packageName}` templates and the `navArgument`
     * declarations. A destination that declared a different name would resolve the argument to
     * null with no error, so Tasks 12/13 read this instead of repeating the literal.
     */
    const val ARGUMENT_PACKAGE = "packageName"

    fun limit(packageName: String): String = "limit/" + encodePathSegment(packageName)

    fun appSettings(packageName: String): String = "appsettings/" + encodePathSegment(packageName)
}

/**
 * The app's navigation graph.
 *
 * **Onboarding gating.** The persisted `onboardingComplete` flag is *observed*, not read once:
 * `collectAsState` starts at `null` ("not read yet"), and nothing is composed until the
 * repository answers. Deciding earlier with a `false` default would flash onboarding at a
 * user who already finished it. Once answered, the start destination is frozen for the life of
 * this graph because [NavHost] rebuilds - and drops the back stack - whenever `startDestination`
 * changes; the onboarding -> dashboard move is therefore an explicit navigation, not a graph
 * reset.
 *
 * **Deep links.** A cold start from the overlay's "Manage restriction" action arrives as
 * `requestedPackage` and becomes the start destination. A warm start (the app is already
 * running, so `MainActivity` receives the extra through `onNewIntent` because the activity is
 * `singleTask`) pushes the destination onto the existing back stack instead, so Back returns to
 * the dashboard rather than leaving the app.
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
) {
    val deps = LocalDependencies.current
    val requestedPackage = deps.requestedPackage
    val onboardingComplete by deps.useCases.observeOnboardingComplete().collectAsState(initial = null)

    if (onboardingComplete == null) return

    val startDestination = remember {
        requestedPackage?.let(Routes::appSettings)
            ?: if (onboardingComplete == true) Routes.DASHBOARD else Routes.ONBOARDING
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            val vm: OnboardingViewModel = viewModel {
                OnboardingViewModel(
                    useCases = deps.useCases,
                    permissionStatusProvider = deps.permissionStatusProvider,
                    permissionNavigator = deps.permissionNavigator,
                )
            }
            OnboardingScreen(
                viewModel = vm,
                onFinished = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.DASHBOARD) {
            val vm: DashboardViewModel = viewModel {
                DashboardViewModel(
                    useCases = deps.useCases,
                    installedAppCheck = deps.installedAppCheck,
                )
            }
            DashboardScreen(
                viewModel = vm,
                onAddApp = { navController.navigate(Routes.ADD_APP) },
                onOpenApp = { navController.navigate(Routes.appSettings(it)) },
            )
        }
        composable(Routes.ADD_APP) {
            // AddAppViewModel + AddAppScreen land in Task 11.
        }
        composable(
            route = Routes.LIMIT,
            arguments = listOf(navArgument(Routes.ARGUMENT_PACKAGE) { type = NavType.StringType }),
        ) {
            // SetLimitViewModel + SetLimitScreen land in Task 12; they read and decode
            // entry.arguments.getString(Routes.ARGUMENT_PACKAGE).
        }
        composable(
            route = Routes.APP_SETTINGS,
            arguments = listOf(navArgument(Routes.ARGUMENT_PACKAGE) { type = NavType.StringType }),
        ) {
            // AppSettingsViewModel + AppSettingsScreen land in Task 13; they read and decode
            // entry.arguments.getString(Routes.ARGUMENT_PACKAGE).
        }
    }

    LaunchedEffect(requestedPackage) {
        if (requestedPackage == null) return@LaunchedEffect
        if (navController.currentDestination?.route == Routes.APP_SETTINGS) return@LaunchedEffect
        navController.navigate(Routes.appSettings(requestedPackage)) { launchSingleTop = true }
    }
}

private const val UNRESERVED = "-._~"
private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * Percent-encodes one path segment.
 *
 * The navigation library does not decode route arguments, so a value that travels through
 * [Routes.appSettings] has to be encoded here and decoded by the screen that reads it. This is a
 * hand-rolled RFC 3986 unreserved-set encoder rather than `java.net.URLEncoder`, which is not
 * available in common code and encodes for query strings (space becomes `+`, which a path
 * segment never means). Package names are `[A-Za-z0-9_.]` in practice, so the escaping path is
 * defensive: it guarantees a value containing `/` cannot forge an extra segment.
 */
internal fun encodePathSegment(value: String): String {
    val encoded = StringBuilder(value.length)
    value.encodeToByteArray().forEach { byte ->
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (code < 0x80 && (char.isLetterOrDigit() || UNRESERVED.contains(char))) {
            encoded.append(char)
        } else {
            encoded.append('%')
                .append(HEX_DIGITS[code shr 4])
                .append(HEX_DIGITS[code and 0x0F])
        }
    }
    return encoded.toString()
}
