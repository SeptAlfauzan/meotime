package com.septaalfauzan.meotime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.septaalfauzan.meotime.permission.PermissionActions
import com.septaalfauzan.meotime.permission.PermissionChecks
import com.septaalfauzan.meotime.presentation.App
import com.septaalfauzan.meotime.presentation.DeepLinkRequest

/**
 * Intent extra carrying the app the user wants to manage. The overlay's "Manage restriction"
 * button sets it ([com.septaalfauzan.meotime.monitoring.OverlayManager.openMeotime]) and the
 * navigation host reads it as `appsettings/{packageName}`, so the string is declared once here
 * and imported by the producer instead of being repeated in both directions.
 */
internal const val EXTRA_REQUESTED_PACKAGE = "com.septaalfauzan.meotime.extra.PACKAGE"

private const val KEY_REQUESTED_PACKAGE = "meotime.requestedPackage"
private const val KEY_REQUESTED_NONCE = "meotime.requestedNonce"

class MainActivity : ComponentActivity() {

    /**
     * Compose state, not a plain field: the activity is `singleTask`, so a deep link that arrives
     * while Meotime is already running is delivered to [onNewIntent] instead of [onCreate]. A plain
     * field read once in `onCreate` would leave the running app on the previous screen and drop
     * the request.
     */
    private var deepLink by mutableStateOf<DeepLinkRequest?>(null)

    /**
     * Per-delivery counter feeding [DeepLinkRequest.nonce], kept strictly above every nonce this
     * activity instance has already restored or issued.
     *
     * A field starts at zero on every new instance, including the one the system creates for a
     * configuration change: rotation restores [DeepLinkRequest] with the nonce it already had
     * while the counter restarts at 0, so the next delivery for the same package would reuse that
     * nonce and produce a structurally equal request. [mutableStateOf] would not invalidate on
     * an equal value, `LaunchedEffect(deepLink)` would not re-key, and the request would be
     * dropped — exactly the collision the nonce exists to prevent. [onCreate] therefore advances
     * this past the restored nonce; a real process restart has no restored nonce to collide with,
     * so zero is correct there.
     */
    private var deliveredRequests = 0L

    /**
     * `POST_NOTIFICATIONS` is a runtime dialog on API 33+, and shared code cannot hold a launcher.
     * The pending callback is held here rather than captured by the launch lambda because the
     * launcher is registered at construction time while the shared graph is built later, in
     * `setContent` — the two cannot be wired together at registration time.
     */
    private var onNotificationAnswer: (() -> Unit)? = null

    /**
     * Whether this Activity has already seen the platform answer a notification request.
     *
     * The platform offers no way to ask "has this permission ever been requested" — only
     * [shouldShowRequestPermissionRationale], which is `false` both for a permission nobody has
     * asked about yet and for one the user has used up the dialog on. This is the disambiguator,
     * and it is what keeps a fresh install's first tap on the real dialog: with the flag clear,
     * only the never-asked case can be the reason the rationale is `false`, and the dialog is
     * still available there.
     *
     * Per-Activity rather than persisted, and deliberately so. Persisting it would buy nothing: the
     * most a lost flag can cost is one tap the system silently ignores after a process death, which
     * a field cannot survive either. One wasted tap is a better price than a new on-disk store for
     * one bit.
     */
    private var hasAskedForNotifications = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Set on every answer, not only on the denials: the point of the flag is to record that
        // the system has spoken once, and a silent answer speaks just as loudly as a denial.
        hasAskedForNotifications = true
        onNotificationAnswer?.also { onNotificationAnswer = null }?.invoke()
    }

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
            // After two denials the system stops showing the dialog and answers a launch
            // immediately and silently, so asking again is a no-op: `allGranted` never becomes
            // true and onboarding can never be completed at all. `shouldShowRequestPermissionRationale`
            // is true only while the system would still show the dialog, so `false` — together with
            // proof that it has already answered us once — is the signal that this request has to
            // become a trip to Settings instead of another launch.
            //
            // The platform method rather than `ActivityCompat.shouldShowRequestPermissionRationale`:
            // it is API 23 (checked against the SDK api-versions.xml) and minSdk is 28, so the
            // compat wrapper would be a new dependency for a method this Activity already has.
            if (hasAskedForNotifications &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                // Routed straight to the navigator, not through the launcher, and deliberately
                // without calling `onResult()`: the answer is a Settings page the user has not
                // visited yet, so refreshing now would only re-render a grant that has not
                // happened. Onboarding re-reads the status when the user comes back, via its own
                // "Check permissions" action.
                PermissionActions(this@MainActivity).openNotificationSettings()
                return
            }
            onNotificationAnswer = onResult
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MeotimeApplication).container
        container.notificationPermissionHost = permissionHost
        // The one re-entry for a force-stop: the system will not restart anything the user stopped
        // by hand, and opening the app is the only signal that they want it running again. Guarded
        // on usage access, because a service with nothing to poll would publish the "Usage Access
        // needed" state on the first tick and never recover on its own.
        if (PermissionChecks.status(this).usageAccess) container.startMonitoring()
        // A rotation must not lose the destination: without the restored value the start
        // destination is recomputed to the dashboard while the remembered graph restores itself
        // at appsettings/{package}, and re-setting the graph replaces the back stack, so the user
        // lands on a blank dashboard. Reading the extra is skipped when state was restored, so a
        // rotation cannot deliver the same request twice.
        deepLink = restoredDeepLink(savedInstanceState) ?: consumeDeepLink(intent)
        // The counter is a plain field, so the instance the system builds for a configuration
        // change starts at zero while the restored request still carries its old nonce. Resume
        // above it, otherwise the next delivery for the same package reuses that nonce, the new
        // request compares equal to the restored one, and the deep link is silently dropped.
        deliveredRequests = maxOf(deliveredRequests, deepLink?.nonce ?: 0L)

        setContent {
            // Keyed on the request so the graph is rebuilt only when a deep link actually
            // arrives, not on every recomposition.
            val deps = remember(deepLink) { container.toDependencies(deepLink) }
            App(deps)
        }
    }

    override fun onDestroy() {
        val container = (application as? MeotimeApplication)?.container
        if (container?.notificationPermissionHost === permissionHost) {
            container.notificationPermissionHost = null
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep the latest intent so a configuration change re-reads this deep link, not the one
        // the activity was originally launched with.
        setIntent(intent)
        // Only a delivery that actually carries the extra updates state: an unrelated intent must
        // not clear a request that has not been consumed yet.
        consumeDeepLink(intent)?.let { deepLink = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_REQUESTED_PACKAGE, deepLink?.packageName)
        outState.putLong(KEY_REQUESTED_NONCE, deepLink?.nonce ?: 0L)
    }

    /**
     * Reads the extra once and clears it, so the request is a one-shot navigation instead of a
     * destination the app keeps re-entering on every recomposition or rotation. The nonce is
     * bumped per delivery, which is what makes a repeated request for the same package
     * observable at all.
     */
    private fun consumeDeepLink(intent: Intent?): DeepLinkRequest? {
        val packageName = intent
            ?.takeIf { it.hasExtra(EXTRA_REQUESTED_PACKAGE) }
            ?.let { launch ->
                launch.getStringExtra(EXTRA_REQUESTED_PACKAGE).also {
                    launch.removeExtra(EXTRA_REQUESTED_PACKAGE)
                }
            }
            ?: return null
        deliveredRequests += 1
        return DeepLinkRequest(packageName, deliveredRequests)
    }

    private fun restoredDeepLink(savedInstanceState: Bundle?): DeepLinkRequest? {
        val packageName = savedInstanceState?.getString(KEY_REQUESTED_PACKAGE) ?: return null
        return DeepLinkRequest(packageName, savedInstanceState.getLong(KEY_REQUESTED_NONCE))
    }
}
