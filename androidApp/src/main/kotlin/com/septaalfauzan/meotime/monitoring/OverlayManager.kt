package com.septaalfauzan.meotime.monitoring

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.septaalfauzan.meotime.EXTRA_REQUESTED_PACKAGE
import com.septaalfauzan.meotime.domain.AppIconProvider
import com.septaalfauzan.meotime.presentation.overlay.OverlayScreen
import com.septaalfauzan.meotime.presentation.overlay.SleepingCatOverlayAnimation
import com.septaalfauzan.meotime.presentation.overlay.UsageTrackerOverlayScreen
import com.septaalfauzan.meotime.presentation.theme.MonochromeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class OverlayManager(
    private val context: Context,
    private val appIconProvider: AppIconProvider,
    private val scope: CoroutineScope,
) {

    private class OverlayLifecycleOwner :
        LifecycleOwner,
        SavedStateRegistryOwner,
        ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        init {
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
            registry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
    }

    private val lifecycleOwner = OverlayLifecycleOwner()
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: ComposeView? = null

    /** Set by the container after construction; read on the main thread inside Compose's tap. */
    var onTrackerTap: () -> Unit = {}

    // Compose state, not plain fields: the tracker's ComposeView is created and given its content
    // once. Every subsequent tick only writes these values, so a 1s tick recomposes the pill
    // instead of rebuilding a window or a composition.
    private var trackerFrame by mutableStateOf<TrackerFrame?>(null)
    private var trackerIcon by mutableStateOf<ImageBitmap?>(null)
    private var trackerView: ComposeView? = null
    private var trackerParams: WindowManager.LayoutParams? = null
    /** Also records the size of the currently attached window, before the frame is published. */
    private var trackerExpanded: Boolean? = null
    /** The package whose icon is current; null while the tracker is hidden. */
    private var iconPackageName: String? = null
    private var iconJob: Job? = null

    fun show(decision: MonitoringCore.OverlayDecision) {
        // Hiding the pill is queued ahead of the blocker: the handler keeps post order, so the
        // handoff is the same on a main call and on a core coroutine posting in from another thread.
        renderTracker(null)
        runOnMain { showOnMain(decision) }
    }

    fun dismiss() {
        runOnMain { dismissOnMain() }
    }

    private fun showOnMain(decision: MonitoringCore.OverlayDecision) {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val view = ComposeView(context).apply {
            // No background here on purpose: this is a TRANSLUCENT overlay window, and OverlayScreen
            // paints colorScheme.background over the whole surface. Setting one here would be an
            // Android-side copy of the theme that follows the system on one platform only.
            isFocusableInTouchMode = true
            // BACK is an exit, not a dismissal. With both buttons leaving as well, no action
            // closes the overlay while leaving the user inside the restricted app, which is what
            // makes "one notice per entry" enforceable rather than advisory. Still consumed, so
            // the app underneath never sees it.
            setOnKeyListener { _, keyCode, _ ->
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    leaveApp()
                    true
                } else {
                    false
                }
            }
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent {
                MonochromeTheme {
                    OverlayScreen(
                        appName = decision.appName,
                        usageSeconds = decision.usageSeconds,
                        limitSeconds = decision.limitSeconds,
                        onLeave = { leaveApp() },
                        onManage = { openMeotime(decision.packageName) },
                        sleepingCatComposition = SleepingCatOverlayAnimation.composition,
                    )
                }
            }
        }
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // Usually SYSTEM_ALERT_WINDOW revoked in system settings after onboarding granted it.
            // Not retried: this runs on the main thread via a handler post, so a success signal
            // would need a two-way handshake back into the core, and a user who revoked the
            // permission has said what they want. Logged so it is at least answerable when
            // someone reports the app is not stopping them.
            Log.w(TAG, "Overlay not shown for ${decision.packageName}", e)
            return
        }
        view.requestFocus()
        overlayView = view
    }

    private fun dismissOnMain() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    fun renderTracker(frame: TrackerFrame?) {
        runOnMain { renderTrackerOnMain(frame) }
    }

    private fun renderTrackerOnMain(frame: TrackerFrame?) {
        if (frame == null) {
            removeTrackerOnMain()
            return
        }
        if (trackerView == null && !createTrackerViewOnMain(frame)) return
        trackerFrame = frame
        if (trackerExpanded != frame.expanded) {
            trackerExpanded = frame.expanded
            val view = trackerView
            val params = trackerParams
            if (view != null && params != null) {
                params.width = dpToPx(if (frame.expanded) 358f else 250f)
                params.height = dpToPx(if (frame.expanded) 110f else 48f)
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.w(TAG, "Tracker resize failed for ${frame.packageName}", e)
                }
            }
        }
        if (iconPackageName != frame.packageName) {
            iconJob?.cancel()
            iconPackageName = frame.packageName
            trackerIcon = null
            val packageName = frame.packageName
            iconJob = scope.launch {
                val icon = appIconProvider.getIcon(packageName)
                runOnMain {
                    // Only the package still on screen may publish its icon. A slow result from a
                    // previous package, or one arriving after the pill was hidden, is dropped.
                    if (trackerView != null && iconPackageName == packageName) {
                        trackerIcon = icon
                    }
                }
            }
        }
    }

    /** Adds the one small tracker window. Content is set once; ticks only write Compose state. */
    private fun createTrackerViewOnMain(frame: TrackerFrame): Boolean {
        trackerExpanded = frame.expanded
        val params = trackerLayoutParams(frame.expanded)
        trackerParams = params
        val view = ComposeView(context).apply {
            // The composable paints the pill; an Android-side background would paint over the
            // app behind it. No key listener: BACK stays with the app underneath.
            background = null
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent {
                val current = trackerFrame
                if (current != null) {
                    MonochromeTheme {
                        UsageTrackerOverlayScreen(
                            frame = current,
                            appIcon = trackerIcon,
                            onTap = { onTrackerTap() },
                        )
                    }
                }
            }
        }
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "Tracker not shown for ${frame.packageName}", e)
            trackerExpanded = null
            trackerParams = null
            return false
        }
        trackerView = view
        return true
    }

    private fun trackerLayoutParams(expanded: Boolean) = WindowManager.LayoutParams(
        dpToPx(if (expanded) 358f else 250f),
        dpToPx(if (expanded) 110f else 48f),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Not focusable keeps the app behind in charge of BACK; not touch-modal sends touches
        // outside this small window to that app. Touches inside it still reach the pill.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dpToPx(10f)
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun removeTrackerOnMain() {
        iconJob?.cancel()
        iconJob = null
        iconPackageName = null
        trackerFrame = null
        trackerIcon = null
        trackerParams = null
        trackerExpanded = null
        val view = trackerView ?: return
        trackerView = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun dpToPx(dp: Float): Int = (dp * context.resources.displayMetrics.density).roundToInt()

    private fun leaveApp() {
        runOnMain {
            dismissOnMain()
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(home)
        }
    }

    private fun openMeotime(packageName: String) {
        runOnMain {
            dismissOnMain()
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    setPackage(context.packageName)
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            intent.putExtra(EXTRA_REQUESTED_PACKAGE, packageName)
            context.startActivity(intent)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private companion object {
        const val TAG = "MeotimeOverlay"
    }
}
