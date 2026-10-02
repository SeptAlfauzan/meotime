package com.septaalfauzan.wanci.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.septaalfauzan.wanci.domain.AppIconProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * App icons, rasterised at the size the add-app row actually draws them.
 *
 * **An icon is never rasterised at its intrinsic size.** An adaptive icon reports the full size of its
 * *unmasked layers* from `intrinsicWidth`/`intrinsicHeight`, which is thousands of pixels on some devices, and an app
 * that ships an oversized `BitmapDrawable` reports its oversized bitmap just as plainly. Rasterising either one
 * produces a bitmap the hardware canvas refuses to draw (`Canvas: trying to draw too large bitmap`) — a crash on the
 * draw pass, not a wrong-looking icon, and one that takes the whole app down over a decorative 24dp square. So the
 * bound in [iconSizePx] is load-bearing rather than a memory optimisation, and the drawable is always drawn into a
 * bitmap allocated here at that bound: a `Drawable`'s own bitmap is never returned as-is, because "the size the
 * platform felt like shipping" is exactly the thing that cannot be trusted.
 *
 * [ApplicationInfo.loadIcon] returns an un-sized `AdaptiveIconDrawable` on API 26+, which is the right thing to get
 * and the wrong thing to measure. It renders correctly through [rasteriseAppIcon] because the bounds it is given are
 * the bounds it draws into, so no masking is reproduced here.
 */
class AppIconProviderAndroid(private val context: Context) : AppIconProvider {

    override suspend fun getIcon(packageName: String): ImageBitmap? = withContext(Dispatchers.Default) {
        val packageManager = context.packageManager
        try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            val drawable = applicationInfo.loadIcon(packageManager)
            rasteriseAppIcon(drawable, iconSizePx(context.resources.displayMetrics.density))
        } catch (_: PackageManager.NameNotFoundException) {
            // A normal state rather than a failure, and it is the same one `InstalledAppsProviderAndroid` already
            // treats as normal: an app can be uninstalled while the list is being read, and a row with no icon is
            // still a usable, selectable row.
            null
        }
    }

    /**
     * The output's pixel size: the add-app row's icon slot in device pixels, so the bitmap is sized to the one thing
     * it is drawn into rather than to whatever the platform felt like shipping.
     *
     * Floored and capped because neither end of the density range is trustworthy either — a floor keeps a
     * low-density device from storing an icon too small to stay smooth if the window is scaled up, and the ceiling
     * bounds a wildly reported density to a bitmap no canvas can refuse.
     */
    private fun iconSizePx(density: Float): Int =
        (ICON_SLOT_DP * density).roundToInt().coerceIn(MIN_ICON_PX, MAX_ICON_PX)

    private companion object {
        /**
         * Matches the slot `AddAppRow` renders into. Duplicated as a comment rather than a shared constant because the
         * row is common code and this is Android code: plumbing one number across the module boundary for a value that
         * is wrong by a factor of two only in sharpness, not in correctness.
         */
        const val ICON_SLOT_DP = 24
        const val MIN_ICON_PX = 48
        const val MAX_ICON_PX = 192
    }
}

/**
 * Draws [drawable] into a `size`×`size` bitmap and hands back a Compose [ImageBitmap].
 *
 * [Drawable.setBounds] is what does the scaling, and it is not optional: a drawable reporting no intrinsic size
 * (a plain `VectorDrawable`) draws nothing at all without it, and one reporting a huge intrinsic size would draw at
 * that size. A non-positive intrinsic size is therefore the case that most needs the bounds rather than a reason to
 * reject the drawable.
 *
 * [Bitmap.Config.ARGB_8888] rather than a config derived from the drawable, because the icon is composited over a
 * transparent square by the row's own background — anything that dropped the alpha channel would render the masking
 * areas black.
 *
 * Top-level and `internal` so the bound can be pinned by a test against a drawable reporting an absurd intrinsic
 * size. That drawable is the whole reason this function exists, and it is not reachable through `getIcon` without
 * standing up a `PackageManager` — which is exactly the part that could regress unnoticed. Stateless, so it has no
 * business being a member.
 */
internal fun rasteriseAppIcon(drawable: Drawable, size: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}
