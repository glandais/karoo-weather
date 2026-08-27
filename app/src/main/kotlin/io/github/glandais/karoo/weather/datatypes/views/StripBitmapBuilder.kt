package io.github.glandais.karoo.weather.datatypes.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.domain.Units
import io.github.glandais.karoo.weather.ui.theme.Wx
import io.hammerhead.karooext.models.ViewConfig
import kotlin.math.roundToInt

/**
 * The `route-forecast` strip, rendered as **one** bitmap sized to the field's `viewSize`.
 *
 * Never assemble the strip from per-cell bitmaps: a `RemoteViews` is Parcelled across a Binder on
 * every `updateView`, and five arrows plus five WMO icons at ARGB_8888 blow the ~1 MB transaction
 * budget (DESIGN §3.0). Everything — icons, temperatures, arrows, labels, wet washes and dividers —
 * goes into one `Canvas`.
 */
object StripBitmapBuilder {

    /** Opacity of the wet-cell wash, DESIGN §3.4. */
    const val WET_WASH_ALPHA = 31 // 12 % of 255

    data class Column(
        @DrawableRes val icon: Int,
        val tempC: Double,
        val relativeWindAngle: Double,
        val headwindMs: Double,
        val label: String,
        val etaLabel: String?,
        val wet: Boolean,
        val beyondHorizon: Boolean,
    )

    data class Rows(
        val icon: Boolean,
        val temp: Boolean,
        val arrow: Boolean,
        val label: Boolean,
        val eta: Boolean,
    ) {
        val count: Int
            get() =
                (if (icon) 1 else 0) +
                    (if (temp) 1 else 0) +
                    (if (arrow) 1 else 0) +
                    (if (label) 1 else 0) +
                    (if (eta) 1 else 0)
    }

    fun render(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        columns: List<Column>,
        rows: Rows,
        night: Boolean,
        textSizeSp: Int,
        units: Units,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (columns.isEmpty() || rows.count == 0) return bitmap

        val density = context.resources.displayMetrics
        val sp = { value: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, density)
        }

        val fg = Wx.fg.pick(night)
        val muted = Wx.fgMuted.pick(night)
        val dividerColour = Wx.divider.pick(night)

        val cellWidth = width.toFloat() / columns.size
        val rowHeight = height.toFloat() / rows.count

        val tempPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = minOf(sp(textSizeSp * 0.55f), rowHeight * 0.8f)
            }
        val labelPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                textAlign = Paint.Align.CENTER
                textSize =
                    minOf(
                        sp(maxOf(textSizeSp * 0.36f, FieldChrome.MIN_LABEL_SP)),
                        rowHeight * 0.8f,
                    )
            }

        val wash = Paint().apply { color = withAlpha(Wx.rainMed.pick(night), WET_WASH_ALPHA) }

        val divider = Paint().apply { color = dividerColour }
        val arrowDrawable = ContextCompat.getDrawable(context, R.drawable.ic_wind_arrow)?.mutate()

        columns.forEachIndexed { index, column ->
            val left = index * cellWidth
            val centreX = left + cellWidth / 2f

            if (column.wet) {
                canvas.drawRect(left, 0f, left + cellWidth, height.toFloat(), wash)
            }
            if (index > 0) {
                canvas.drawRect(left, 0f, left + 1f, height.toFloat(), divider)
            }

            var row = 0

            if (rows.icon) {
                val box = minOf(cellWidth, rowHeight) * 0.86f
                val top = row * rowHeight + (rowHeight - box) / 2f
                ContextCompat.getDrawable(context, column.icon)?.mutate()?.let { icon ->
                    icon.setTint(fg)
                    icon.setBounds(
                        (centreX - box / 2f).roundToInt(),
                        top.roundToInt(),
                        (centreX + box / 2f).roundToInt(),
                        (top + box).roundToInt(),
                    )
                    icon.draw(canvas)
                }
                row++
            }

            if (rows.temp) {
                tempPaint.color =
                    if (column.beyondHorizon) muted else Wx.forTemp(column.tempC).pick(night)
                val text = formatTemp(column.tempC, units)
                canvas.drawText(text, centreX, baseline(row, rowHeight, tempPaint), tempPaint)
                row++
            }

            if (rows.arrow && arrowDrawable != null) {
                val box = minOf(cellWidth, rowHeight) * 0.7f
                val centreY = row * rowHeight + rowHeight / 2f
                arrowDrawable.setTint(Wx.forHeadwind(column.headwindMs).pick(night))
                arrowDrawable.setBounds(
                    (centreX - box / 2f).roundToInt(),
                    (centreY - box / 2f).roundToInt(),
                    (centreX + box / 2f).roundToInt(),
                    (centreY + box / 2f).roundToInt(),
                )
                canvas.save()
                canvas.rotate(
                    FieldChrome.arrowBucketDegrees(column.relativeWindAngle),
                    centreX,
                    centreY,
                )
                arrowDrawable.draw(canvas)
                canvas.restore()
                row++
            } else if (rows.arrow) {
                row++
            }

            if (rows.label) {
                labelPaint.color = if (column.beyondHorizon) muted else fg
                val text =
                    if (column.beyondHorizon) context.getString(R.string.horizon_beyond)
                    else column.label
                canvas.drawText(text, centreX, baseline(row, rowHeight, labelPaint), labelPaint)
                row++
            }

            if (rows.eta) {
                labelPaint.color = muted
                val text = column.etaLabel.orEmpty()
                if (text.isNotEmpty()) {
                    canvas.drawText(text, centreX, baseline(row, rowHeight, labelPaint), labelPaint)
                }
                row++
            }
        }

        return bitmap
    }

    /** Temperature as whole degrees in the rider's unit, e.g. `22°`. */
    fun formatTemp(celsius: Double, units: Units): String =
        "${units.temp(celsius).roundToInt()}°"

    private fun baseline(row: Int, rowHeight: Float, paint: Paint): Float {
        val centreY = row * rowHeight + rowHeight / 2f
        return centreY - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
    }

    private fun withAlpha(colour: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))
}

/**
 * The pure layout decisions behind the route strip: which rows exist at a given `gridSize`, and the
 * `maxColumns` ceiling `FieldChrome.columnsFor` then narrows using `viewSize` (DESIGN §3.0).
 *
 * It is a separate top-level object, not a companion of the data type, so it can be unit tested
 * without loading any `android.graphics` class.
 */
object RouteStripLayout {

    /**
     * The ETA row appears only at `(60,60)`: at `(60,30)` five stacked rows would put both bottom
     * labels at the 10 sp floor of DESIGN §1.3.
     */
    fun rowsFor(config: ViewConfig): StripBitmapBuilder.Rows {
        val wide = config.gridSize.first >= 60
        val height = config.gridSize.second
        return when {
            !wide || height <= 15 ->
                StripBitmapBuilder.Rows(
                    icon = true,
                    temp = true,
                    arrow = false,
                    label = true,
                    eta = false,
                )
            height < 60 ->
                StripBitmapBuilder.Rows(
                    icon = true,
                    temp = true,
                    arrow = true,
                    label = true,
                    eta = false,
                )
            else ->
                StripBitmapBuilder.Rows(
                    icon = true,
                    temp = true,
                    arrow = true,
                    label = true,
                    eta = true,
                )
        }
    }

    /** The ceiling on columns for this `gridSize`, before `viewSize` narrows it further. */
    fun maxColumnsFor(config: ViewConfig): Int =
        when {
            config.gridSize.first < 60 -> 1
            config.gridSize.second <= 15 -> 3
            config.gridSize.second < 60 -> 5
            else -> 6
        }
}
