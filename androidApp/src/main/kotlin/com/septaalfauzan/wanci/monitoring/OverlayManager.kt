package com.septaalfauzan.wanci.monitoring

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.septaalfauzan.wanci.presentation.overlay.OverlayScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OverlayManager(
    private val context: Context,
    private val core: MonitoringCore,
    private val scope: CoroutineScope,
    private val coreMutex: Mutex = Mutex(),
) {

    private class OverlayLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        init {
            registry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = registry
    }

    private val lifecycleOwner = OverlayLifecycleOwner()
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: ComposeView? = null
    private var visiblePackage: String? = null

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
            setOnKeyListener { _, keyCode, _ ->
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    dismiss()
                    true
                } else {
                    false
                }
            }
            setViewTreeLifecycleOwner(lifecycleOwner)
            setContent {
                OverlayScreen(
                    appName = decision.appName,
                    usageMinutes = decision.usageMinutes,
                    limitMinutes = decision.limitMinutes,
                    onLeave = { leaveApp() },
                    onManage = { openWanci(decision.packageName) },
                )
            }
        }
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            return
        }
        view.requestFocus()
        overlayView = view
        visiblePackage = decision.packageName
    }

    private fun dismissOnMain() {
        val view = overlayView ?: return
        val packageName = visiblePackage
        visiblePackage = null
        overlayView = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        if (packageName != null) {
            scope.launch {
                coreMutex.withLock {
                    core.onOverlayDismissed(packageName)
                }
            }
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
            intent.putExtra("com.septaalfauzan.wanci.extra.PACKAGE", packageName)
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
}
