package io.github.glandais.karoo.weather.datatypes.views

import android.graphics.Bitmap

/**
 * The single mutable bitmap one view repaints into.
 *
 * A full-field ARGB_8888 bitmap is ~1.5 MB at (60,60) on the 480x800 panel, and the field repaints
 * every `viewRefreshMs` for the whole ride: allocating a fresh one each time churns megabytes a
 * minute on a device that already skips frames. Reuse is safe because `ViewEmitter.updateView` is a
 * SYNCHRONOUS Binder call that Parcels (copies) the pixels before it returns, so the surface is
 * only ever erased once the host holds its own copy.
 *
 * ONE INSTANCE PER `startView` CALL — never a property of the shared `DataTypeImpl`, since the page
 * editor composes several previews at once.
 */
class BitmapSurface {

    private var bitmap: Bitmap? = null

    /** The bitmap to draw into at this size, or null when a new one must be created. */
    fun get(width: Int, height: Int): Bitmap? {
        val current = bitmap ?: return null
        if (current.isRecycled || current.width != width || current.height != height) return null
        return current
    }

    fun keep(bitmap: Bitmap) {
        this.bitmap = bitmap
    }

    /**
     * Drops the reference on `stopView`. Deliberately NOT `recycle()`: within one process the last
     * `RemoteViews` handed to the host can still reference these pixels, and drawing a recycled
     * bitmap crashes the host, not us. Letting the GC take it is the safe release.
     */
    fun release() {
        bitmap = null
    }
}
