package com.septaalfauzan.wanci.apps

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * Pins the bound that keeps a decorative 24dp icon from becoming a crash.
 *
 * The bug this exists for: the icon was rasterised at `Drawable.getIntrinsicWidth()`, which for an `AdaptiveIconDrawable`
 * is the full size of its *unmasked layers*. On a real device that produced a ~7150×7150 bitmap — 204,501,524 bytes —
 * and the hardware canvas refused it at draw time with `Canvas: trying to draw too large bitmap`, killing the app on
 * launch. Nothing about that failure was catchable at the point it happened, so the only defence is that the bitmap is
 * never allocated at the drawable's own size.
 */
@RunWith(RobolectricTestRunner::class)
class AppIconProviderAndroidTest {

    /** A drawable reporting exactly the intrinsic size that crashed the app. */
    private val absurdDrawable = object : Drawable() {
        override fun getIntrinsicWidth() = 7150
        override fun getIntrinsicHeight() = 7150
        override fun draw(canvas: Canvas) = canvas.drawColor(Color.RED)
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Required by the pre-API-24 Drawable contract", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * The regression itself: the output is the requested size, never the drawable's. Asserting the bitmap's *own*
     * dimensions rather than that the call survived, because the original code returned a bitmap of the wrong size
     * perfectly happily — it was the canvas, several frames later, that refused it.
     */
    @Test
    fun anIconReportingAnAbsurdIntrinsicSizeIsRasterisedAtTheRequestedSize() {
        val bitmap = rasteriseAppIcon(absurdDrawable, size = 192)

        assertEquals(192, bitmap.width)
        assertEquals(192, bitmap.height)
    }

    /**
     * A drawable reporting no intrinsic size at all is the other end of the same contract, and the reason
     * `setBounds` is load-bearing rather than cosmetic: without bounds such a drawable draws nothing at all.
     */
    @Test
    fun anIconReportingNoIntrinsicSizeStillRasterisesAtTheRequestedSize() {
        val unsized = object : Drawable() {
            override fun getIntrinsicWidth() = -1
            override fun getIntrinsicHeight() = -1
            override fun draw(canvas: Canvas) = canvas.drawColor(Color.BLUE)
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit

            @Deprecated("Required by the pre-API-24 Drawable contract", ReplaceWith("PixelFormat.TRANSLUCENT"))
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }

        val bitmap = rasteriseAppIcon(unsized, size = 48)

        assertEquals(48, bitmap.width)
        assertEquals(48, bitmap.height)
    }
}
