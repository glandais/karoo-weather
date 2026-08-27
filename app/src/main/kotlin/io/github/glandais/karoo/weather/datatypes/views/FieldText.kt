package io.github.glandais.karoo.weather.datatypes.views

import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.ceil

/**
 * Measures a text run against the face the data fields actually render with.
 *
 * [FieldChrome] decides whether a row fits; this decides how wide the row *is*. The two are
 * separate because the arithmetic is pure and testable while the measurement is not: it has to ask
 * the platform, since the monospace advance is a property of whatever face the device resolves.
 * Estimating it from a character count and a nominal em is not good enough — the Karoo's monospace
 * advances roughly 0.45 em where a stock Droid Sans Mono advances 0.6, and a third of a row of
 * phantom width is the difference between showing the gust and dropping it.
 *
 * The [TextPaint] is per-thread because Glance composes on whichever dispatcher the field's scope
 * is running on, and a `Paint` is not safe to share across threads.
 */
object FieldText {

    private val regular = ThreadLocal.withInitial { TextPaint().apply { typeface = MONO } }
    private val bold = ThreadLocal.withInitial { TextPaint().apply { typeface = MONO_BOLD } }

    /**
     * Width in px of [text] rendered at [sp] on a [density] display.
     *
     * @param bold true for the primary value, which every field draws bold.
     */
    fun widthPx(text: String, sp: Float, density: Float, bold: Boolean = false): Int {
        if (text.isEmpty() || sp <= 0f) return 0
        val safeDensity = if (density > 0f) density else 1f
        val paint = (if (bold) this.bold else regular).get() ?: return 0
        paint.textSize = sp * safeDensity
        return ceil(paint.measureText(text)).toInt()
    }

    private val MONO: Typeface = Typeface.MONOSPACE
    private val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
}
