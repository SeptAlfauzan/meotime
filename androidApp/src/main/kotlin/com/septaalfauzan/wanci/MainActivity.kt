package com.septaalfauzan.wanci

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.septaalfauzan.wanci.presentation.App
import com.septaalfauzan.wanci.presentation.DeepLinkRequest

/**
 * Intent extra carrying the app the user wants to manage. The overlay's "Manage restriction"
 * button sets it ([com.septaalfauzan.wanci.monitoring.OverlayManager.openWanci]) and the
 * navigation host reads it as `appsettings/{packageName}`, so the string is declared once here
 * and imported by the producer instead of being repeated in both directions.
 */
internal const val EXTRA_REQUESTED_PACKAGE = "com.septaalfauzan.wanci.extra.PACKAGE"

private const val KEY_REQUESTED_PACKAGE = "wanci.requestedPackage"
private const val KEY_REQUESTED_NONCE = "wanci.requestedNonce"

class MainActivity : ComponentActivity() {

    /**
     * Compose state, not a plain field: the activity is `singleTask`, so a deep link that arrives
     * while Wanci is already running is delivered to [onNewIntent] instead of [onCreate]. A plain
     * field read once in `onCreate` would leave the running app on the previous screen and drop
     * the request.
     */
    private var deepLink by mutableStateOf<DeepLinkRequest?>(null)

    /**
     * Per-delivery counter feeding [DeepLinkRequest.nonce]. Reset to zero on every process start,
     * which is enough: a restored activity state is always paired with this same process, so two
     * consecutive deliveries can never collide.
     */
    private var deliveredRequests = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A rotation must not lose the destination: without the restored value the start
        // destination is recomputed to the dashboard while the remembered graph restores itself
        // at appsettings/{package}, and re-setting the graph replaces the back stack, so the user
        // lands on a blank dashboard. Reading the extra is skipped when state was restored, so a
        // rotation cannot deliver the same request twice.
        deepLink = restoredDeepLink(savedInstanceState) ?: consumeDeepLink(intent)

        setContent {
            // Keyed on the request so the graph is rebuilt only when a deep link actually
            // arrives, not on every recomposition.
            val deps = remember(deepLink) {
                (application as WanciApplication).container.toDependencies(deepLink)
            }
            App(deps)
        }
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
