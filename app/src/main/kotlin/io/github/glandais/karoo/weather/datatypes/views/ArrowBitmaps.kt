package io.github.glandais.karoo.weather.datatypes.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * Rotated, pre-tinted arrow bitmaps.
 *
 * The tint is **baked in** — a bitmap drawn into a `Canvas` cannot be re-resolved by a Glance
 * `ColorProvider` (ARCHITECTURE §7.5) — so it is part of the cache key alongside the drawable, the
 * 10 degree bearing bucket and the pixel size.
 *
 * The cache is a bounded [LruCache] (~2 MB by default). An unbounded process-lifetime object would
 * retain 36 bearings x 4 tints x 2 sizes inside the extension service, which outlives every view.
 *
 * **One instance per `startView`**, never a property of the `DataTypeImpl`: `KarooExtension` calls
 * `startView` on one shared instance and the page editor opens several previews at once
 * (ARCHITECTURE §4.3). Callers MUST call [clear] from their `setCancellable`.
 */
class ArrowBitmaps(maxBytes: Int = DEFAULT_MAX_BYTES) {

    private val cache =
        object : LruCache<String, Bitmap>(if (maxBytes > 0) maxBytes else DEFAULT_MAX_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

    /**
     * A [sizePx] square bitmap of [res], tinted [tint] and rotated clockwise to the 10 degree
     * bucket containing [bearingDeg]. The drawable's tip points at 12 o'clock at 0 degrees.
     */
    fun rotated(
        context: Context,
        @DrawableRes res: Int,
        bearingDeg: Double,
        sizePx: Int,
        tint: Int,
    ): Bitmap {
        val side = sizePx.coerceIn(MIN_SIZE_PX, MAX_SIZE_PX)
        val bucket = FieldChrome.arrowBucket10(bearingDeg)
        val key = "$res:$bucket:$side:$tint"
        cache.get(key)?.let {
            return it
        }
        val bitmap = render(context, res, bucket * 10f, side, tint)
        cache.put(key, bitmap)
        return bitmap
    }

    /** Drops every cached bitmap. Call from `Emitter.setCancellable`. */
    fun clear() {
        cache.evictAll()
    }

    private fun render(
        context: Context,
        @DrawableRes res: Int,
        degrees: Float,
        sizePx: Int,
        tint: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: return bitmap
        drawable.setTint(tint)
        drawable.setBounds(0, 0, sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val centre = sizePx / 2f
        canvas.save()
        canvas.rotate(degrees, centre, centre)
        drawable.draw(canvas)
        canvas.restore()
        return bitmap
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024
        const val MIN_SIZE_PX = 8
        const val MAX_SIZE_PX = 256
    }
}
