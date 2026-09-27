package com.septaalfauzan.wanci.monitoring

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
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
import com.septaalfauzan.wanci.EXTRA_REQUESTED_PACKAGE
import com.septaalfauzan.wanci.presentation.overlay.OverlayScreen

class OverlayManager(private val context: Context) {

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

    fun show(decision: MonitoringCore.OverlayDecision) {
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
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        val view = ComposeView(context).apply {
            setBackgroundColor(Color.WHITE)
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
                OverlayScreen(
                    appName = decision.appName,
                    usageSeconds = decision.usageSeconds,
                    limitSeconds = decision.limitSeconds,
                    onLeave = { leaveApp() },
                    onManage = { openWanci(decision.packageName) },
                )
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

    private fun openWanci(packageName: String) {
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
        const val TAG = "WanciOverlay"
    }
}
