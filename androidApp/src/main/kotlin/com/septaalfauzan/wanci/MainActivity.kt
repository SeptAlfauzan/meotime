package com.septaalfauzan.wanci

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.septaalfauzan.wanci.presentation.App
import com.septaalfauzan.wanci.presentation.WanciAppDependencies

/**
 * Intent extra carrying the app the user wants to manage. The overlay's "Manage restriction"
 * button sets it ([com.septaalfauzan.wanci.monitoring.OverlayManager.openWanci]) and the
 * navigation host reads it as `appsettings/{packageName}`, so the string is declared once here
 * and imported by the producer instead of being repeated in both directions.
 */
internal const val EXTRA_REQUESTED_PACKAGE = "com.septaalfauzan.wanci.extra.PACKAGE"

class MainActivity : ComponentActivity() {

    /**
     * Compose state, not a plain field: the activity is `singleTask`, so a deep link that arrives
     * while Wanci is already running is delivered to [onNewIntent] instead of [onCreate]. A plain
     * field read once in `onCreate` would leave the running app on the previous screen and drop
     * the request.
     */
    private var requestedPackage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestedPackage = consumeRequestedPackage(intent)

        setContent {
            App(dependencies())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep the latest intent so a configuration change re-reads this deep link, not the one
        // the activity was originally launched with.
        setIntent(intent)
        requestedPackage = consumeRequestedPackage(intent)
    }

    private fun dependencies(): WanciAppDependencies =
        (application as WanciApplication).container.toDependencies(requestedPackage)

    /**
     * Reads the extra once and clears it, so the request is a one-shot navigation instead of a
     * destination the app keeps re-entering on every recomposition or rotation.
     */
    private fun consumeRequestedPackage(intent: Intent?): String? =
        intent?.takeIf { it.hasExtra(EXTRA_REQUESTED_PACKAGE) }?.let { launch ->
            launch.getStringExtra(EXTRA_REQUESTED_PACKAGE).also {
                launch.removeExtra(EXTRA_REQUESTED_PACKAGE)
            }
        }
}
