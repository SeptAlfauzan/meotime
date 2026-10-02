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
import androidx.savedstate.read
import com.septaalfauzan.wanci.presentation.LocalDependencies
import com.septaalfauzan.wanci.presentation.addapp.AddAppScreen
import com.septaalfauzan.wanci.presentation.addapp.AddAppViewModel
import com.septaalfauzan.wanci.presentation.appsettings.AppSettingsScreen
import com.septaalfauzan.wanci.presentation.appsettings.AppSettingsViewModel
import com.septaalfauzan.wanci.presentation.dashboard.DashboardScreen
import com.septaalfauzan.wanci.presentation.dashboard.DashboardViewModel
import com.septaalfauzan.wanci.presentation.limit.SetLimitScreen
import com.septaalfauzan.wanci.presentation.limit.SetLimitViewModel
import com.septaalfauzan.wanci.presentation.onboarding.OnboardingScreen
import com.septaalfauzan.wanci.presentation.onboarding.OnboardingViewModel
import kotlinx.coroutines.flow.catch

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
 * `deepLink` and becomes the start destination. A warm start (the app is already running, so
 * `MainActivity` receives the extra through `onNewIntent` because the activity is `singleTask`)
 * pushes the destination onto the existing back stack instead, so Back returns to the dashboard
 * rather than leaving the app. Both paths key on the whole request, nonce included, so a repeat
 * request for the *same* package re-fires instead of being written over an equal value.
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
) {
    val deps = LocalDependencies.current
    val deepLink = deps.deepLink
    val onboardingComplete by deps.useCases.observeOnboardingComplete()
        // A failed read must not leave the app permanently blank: `collectAsState` cancels its
        // collector when the upstream throws, so the flow has to be kept alive here. Falling back
        // to "not complete" shows onboarding, which is the recoverable branch.
        .catch { emit(false) }
        .collectAsState(initial = null)

    if (onboardingComplete == null) return

    val startDestination = remember {
        deepLink?.let { Routes.appSettings(it.packageName) }
            ?: if (onboardingComplete == true) Routes.DASHBOARD else Routes.ONBOARDING
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            val vm: OnboardingViewModel = viewModel {
                OnboardingViewModel(
                    useCases = deps.useCases,
                    permissionStatusProvider = deps.permissionStatusProvider,
                    permissionNavigator = deps.permissionNavigator,
                    notificationPermissionRequest = deps.notificationPermissionRequest,
                    startMonitoring = deps.startMonitoring,
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
                    appIconProvider = deps.appIconProvider,
                    installedAppCheck = deps.installedAppCheck,
                )
            }
            DashboardScreen(
                viewModel = vm,
                onAddApp = { navController.navigate(Routes.ADD_APP) },
                // `launchSingleTop` for the same reason as the limit hop below: this hop is
                // idempotent, and the destination is the one the overlay's deep link also pushes, so
                // a double tap must not leave two `appsettings/{pkg}` entries stacked.
                onOpenApp = { navController.navigate(Routes.appSettings(it)) { launchSingleTop = true } },
            )
        }
        composable(Routes.ADD_APP) {
            val vm: AddAppViewModel = viewModel {
AddAppViewModel(
                        installedAppsProvider = deps.installedAppsProvider,
                        appIconProvider = deps.appIconProvider,
                        useCases = deps.useCases,
                    )
            }
            AddAppScreen(
                viewModel = vm,
                onContinue = { packageName ->
                    // `launchSingleTop`: the hop is idempotent, so a double-tapped Continue must not
                    // stack a second `limit/com.foo` destination. Without it the user presses Back
                    // and lands on the step they just came from, with the limit screen still queued
                    // behind it.
                    navController.navigate(Routes.limit(packageName)) { launchSingleTop = true }
                },
            )
        }
        composable(
            route = Routes.LIMIT,
            arguments = listOf(navArgument(Routes.ARGUMENT_PACKAGE) { type = NavType.StringType }),
        ) { entry ->
            // The argument is already decoded by the navigation library
            // (NavDeepLink.getMatchingPathArguments -> NavUriUtils.decode): read it through
            // SavedState.read, the common-code accessor, and do NOT decode it again. A null here is
            // a real answer — the destination renders its own "couldn't open this app" state rather
            // than inventing a package to save.
            val requestedPackage = entry.arguments?.read { getStringOrNull(Routes.ARGUMENT_PACKAGE) }
            val vm: SetLimitViewModel = viewModel {
                // The label and the current limit are resolved inside the view model, not here: a
                // launcher that throws while the destination is being composed cannot become a
                // retryable state any other way.
                SetLimitViewModel(
                    requestedPackageName = requestedPackage,
                    installedAppsProvider = deps.installedAppsProvider,
                    useCases = deps.useCases,
                )
            }
            SetLimitScreen(
                viewModel = vm,
                onClose = {
                    // Leaving pops the step that sent us here, so the flow is still walkable. The
                    // fallback covers a stack with nothing below this destination, where a pop is a
                    // no-op and the screen's only action would be dead.
                    if (!navController.popBackStack()) navController.landOnDashboard()
                },
                // The conflict's button says "Back to my apps", so it goes to the apps: the
                // dashboard is where the row that won the conflict now lives. A pop would land on
                // addapp — the step that sent us here — so the label would promise a screen the
                // user does not arrive at. Same destination as a successful save, reached for the
                // same reason: the limit screen has nothing left to say about that app.
                onBackToApps = { navController.landOnDashboard() },
                onSaved = { navController.landOnDashboard() },
            )
        }
        composable(
            route = Routes.APP_SETTINGS,
            arguments = listOf(navArgument(Routes.ARGUMENT_PACKAGE) { type = NavType.StringType }),
        ) { entry ->
            // The argument is already decoded by the navigation library
            // (NavDeepLink.getMatchingPathArguments -> NavUriUtils.decode): read it through
            // SavedState.read, the common-code accessor, and do NOT decode it again. A null here is
            // a real answer — the destination renders its own "couldn't open this app" state rather
            // than inventing a package to write to.
            val requestedPackage = entry.arguments?.read { getStringOrNull(Routes.ARGUMENT_PACKAGE) }
            val vm: AppSettingsViewModel = viewModel {
                // The row, today's usage and the installed probe are resolved inside the view model:
                // a launcher that throws while the destination composes cannot become a retryable
                // state any other way.
                AppSettingsViewModel(
                    requestedPackageName = requestedPackage,
                    useCases = deps.useCases,
                    installedAppCheck = deps.installedAppCheck,
                )
            }
            AppSettingsScreen(
                viewModel = vm,
                // Leaving pops the destination that sent us here, so the flow stays walkable. The
                // fallback covers a deep-link cold start, where this destination *is* the start
                // destination: a pop is a no-op there and the screen's only action would be dead.
                onBack = {
                    if (!navController.popBackStack()) navController.landOnDashboard()
                },
                // The limit is edited on the set-limit screen, which already owns the stepper, the
                // presets and the honest update-then-re-read save. `launchSingleTop` because the hop
                // is idempotent and this screen stays on the stack under it — a double tap would
                // otherwise leave a second `limit/{pkg}` above it. Backing out of the editor returns
                // here, where the screen re-reads the row on resume, so the limit shown cannot be
                // the one the editor abandoned.
                onChangeLimit = { packageName ->
                    navController.navigate(Routes.limit(packageName)) { launchSingleTop = true }
                },
                // After a confirmed delete this screen has nothing left to say, and the dashboard is
                // where the absence is visible. Popping to it drops whatever is in between (a deep
                // link landed straight here, so the fallback navigate covers a stack with no
                // dashboard on it) and leaves exactly one dashboard entry.
                onRemoved = { navController.landOnDashboard() },
            )
        }
    }

    LaunchedEffect(deepLink) {
        val request = deepLink ?: return@LaunchedEffect
        val currentEntry = navController.currentBackStackEntry
        // The route pattern alone is not enough: it matches *any* appsettings destination, so a
        // deep link for com.bar would be dropped while the user sits on appsettings/com.foo. The
        // resolved argument - the entry's saved state, filled and already decoded by
        // NavDeepLink.getMatchingPathArguments - is what says whether this destination *is* this
        // request. It is read through SavedState.read, which is the common-code accessor; the
        // entry's `arguments` is a Bundle on Android and a SavedState on iOS.
        val currentPackage = currentEntry?.arguments?.read { getStringOrNull(Routes.ARGUMENT_PACKAGE) }
        val isSameAppSettings = currentEntry?.destination?.route == Routes.APP_SETTINGS &&
            currentPackage == request.packageName
        if (isSameAppSettings) return@LaunchedEffect
        navController.navigate(Routes.appSettings(request.packageName)) { launchSingleTop = true }
    }
}

/**
 * Lands on the dashboard from anywhere, leaving exactly one dashboard entry behind.
 *
 * Popping back to the dashboard *without* clearing it is the whole point: the dashboard -> add-app
 * -> limit flow has two hop destinations above it, and popping drops both, so the user returns to
 * the entry that was already on the stack instead of a second copy of it. `launchSingleTop` on the
 * fallback covers the one stack with no dashboard on it — a deep-link cold start, where
 * `popBackStack` returns false — and there it replaces the start destination rather than growing
 * the stack.
 */
private fun NavHostController.landOnDashboard() {
    if (popBackStack(Routes.DASHBOARD, inclusive = false)) return
    navigate(Routes.DASHBOARD) {
        popUpTo(graph.startDestinationId) { inclusive = true }
        launchSingleTop = true
    }
}

private const val UNRESERVED = "-._~"
private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * Percent-encodes one path segment.
 *
 * Encoding is still required: a value that travels through [Routes.appSettings] must not be able
 * to carry a `/`, `?` or `#` and forge a second segment or truncate the route. Decoding is *not*
 * the counterpart of this function, because the navigation library already decodes path arguments
 * on read — `NavDeepLink.getMatchingPathArguments` runs the matched values through
 * `NavUriUtils.decode` — so a destination reads the plain value straight out of
 * `arguments.getString(...)` and must not decode it a second time. That also keeps the JVM's
 * URL-decoding helper out of common code, where it does not exist for the iOS target.
 *
 * This is a hand-rolled RFC 3986 unreserved-set encoder rather than `java.net.URLEncoder`, which
 * is not available in common code and encodes for query strings (space becomes `+`, which a path
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
