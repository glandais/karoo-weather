package io.github.glandais.karoo.weather.datatypes.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import io.github.glandais.karoo.weather.domain.PrecipBucket
import io.github.glandais.karoo.weather.ui.theme.Wx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The `rain-next-hour` chart, rendered as **one** bitmap sized to the field's `viewSize`.
 *
 * Glance cannot draw, and a `RemoteViews` is Parcelled across a Binder on every `updateView`, so
 * the whole chart — bars, baseline, axis, probability polyline and summary — goes into a single
 * `Canvas` (DESIGN §3.0). `night` picks the [io.github.glandais.karoo.weather.ui.theme.ColorPair]
 * side because a Canvas cannot resolve a Glance `ColorProvider`.
 */
object BarChartBuilder {

    /** Bucket gap as a fraction of bar width (DESIGN §1.4). */
    const val GAP_RATIO = 0.25f

    /** Below this a bucket is "no rain" and is drawn in `fgMuted` at the minimum stub height. */
    const val DRY_MM_PER_QUARTER = 0.1

    /** Full-scale of the bar axis, mm per 15 min. Light drizzle must not fill the field. */
    const val FULL_SCALE_MM = 2.5

    /**
     * @param buckets ascending in time; may be 15 min (`durationSec == 900`) or hourly buckets.
     * @param showLabels draw the time axis and the summary line. Callers pass false when the row
     *   would fall under the 10 sp floor of DESIGN §1.3.
     * @param showProbability draw the probability polyline. Callers pass `gridSize.second >= 30`.
     * @param dryLabel the word shown when nothing is forecast (`R.string.state_dry`).
     * @param summary the pre-formatted "Rain at 14:20 · 1.4 mm next 2 h" line, or null.
     */
    fun render(
        widthPx: Int,
        heightPx: Int,
        buckets: List<PrecipBucket>,
        night: Boolean,
        showLabels: Boolean,
        showProbability: Boolean,
        dryLabel: String = "",
        summary: String? = null,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fg = Wx.fg.pick(night)
        val muted = Wx.fgMuted.pick(night)

        val pad = (height * 0.06f).coerceIn(2f, 10f)
        val labelPx = (height * 0.15f).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)

        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                textSize = labelPx
            }

        // Vertical budget: summary line at the bottom, then the axis, then the bars.
        val summaryHeight = if (showLabels && summary != null) labelPx * 1.4f else 0f
        val axisHeight = if (showLabels) labelPx * 1.3f else 0f
        val chartTop = pad
        val chartBottom = height - pad - summaryHeight - axisHeight
        val chartHeight = chartBottom - chartTop

        if (chartHeight <= 2f || buckets.isEmpty()) {
            drawCentred(canvas, text, dryLabel, muted, width / 2f, height / 2f)
            return bitmap
        }

        val n = buckets.size
        val barWidth = width - 2 * pad
        val unit = barWidth / (n + GAP_RATIO * (n - 1))
        val gap = unit * GAP_RATIO

        val perQuarter = buckets.map { mmPerQuarterHour(it) }
        val peak = max(perQuarter.maxOrNull() ?: 0.0, FULL_SCALE_MM)
        val wet = perQuarter.any { it >= DRY_MM_PER_QUARTER }

        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        buckets.indices.forEach { i ->
            val left = pad + i * (unit + gap)
            val value = perQuarter[i]
            val fraction = (value / peak).coerceIn(0.0, 1.0)
            val barHeight = max((chartHeight * fraction).toFloat(), MIN_BAR_PX)
            bar.color = Wx.forRain(value).pick(night)
            canvas.drawRect(left, chartBottom - barHeight, left + unit, chartBottom, bar)
        }

        // Baseline, 1 px.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted }
        canvas.drawRect(pad, chartBottom, width - pad, chartBottom + 1f, line)

        if (showProbability) {
            drawProbability(canvas, buckets, pad, unit, gap, chartTop, chartBottom, muted)
        }

        if (!wet) {
            text.color = muted
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(dryLabel, width / 2f, chartTop + chartHeight / 2f + labelPx / 3f, text)
        }

        if (showLabels) {
            val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
            text.color = muted
            text.textAlign = Paint.Align.LEFT
            val axisBaseline = chartBottom + axisHeight - labelPx * 0.25f
            // Label every other bucket so the ticks never collide.
            val step = if (n > 4) 2 else 1
            var i = 0
            while (i < n) {
                val left = pad + i * (unit + gap)
                canvas.drawText(
                    clock.format(Date(buckets[i].time * 1000L)),
                    left,
                    axisBaseline,
                    text,
                )
                i += step
            }
            if (summary != null) {
                text.color = fg
                canvas.drawText(summary, pad, height - pad, text)
            }
        }

        return bitmap
    }

    /**
     * Normalises a bucket to the "mm per 15 min" scale the rain ramp of DESIGN §1.1 is defined on,
     * so an hourly fallback bucket is not four times as dark as the nowcast it replaces.
     */
    fun mmPerQuarterHour(bucket: PrecipBucket): Double {
        val duration = if (bucket.durationSec > 0) bucket.durationSec else QUARTER_HOUR_SEC
        return bucket.mm * QUARTER_HOUR_SEC / duration
    }

    private fun drawProbability(
        canvas: Canvas,
        buckets: List<PrecipBucket>,
        pad: Float,
        unit: Float,
        gap: Float,
        top: Float,
        bottom: Float,
        colour: Int,
    ) {
        val points = buckets.mapIndexedNotNull { i, bucket ->
            bucket.probability?.let { probability ->
                val x = pad + i * (unit + gap) + unit / 2f
                val y = bottom - (bottom - top) * (probability.coerceIn(0, 100) / 100f)
                x to y
            }
        }
        if (points.size < 2) return
        val path = Path()
        path.moveTo(points[0].first, points[0].second)
        points.drop(1).forEach { path.lineTo(it.first, it.second) }
        val stroke =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colour
                style = Paint.Style.STROKE
                strokeWidth = PROBABILITY_STROKE_PX
            }
        canvas.drawPath(path, stroke)
    }

    private fun drawCentred(
        canvas: Canvas,
        paint: Paint,
        label: String,
        colour: Int,
        cx: Float,
        cy: Float,
    ) {
        if (label.isEmpty()) return
        paint.color = colour
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, cx, cy + paint.textSize / 3f, paint)
    }

    /** Total precipitation across [buckets], mm, rounded to one decimal. */
    fun totalMm(buckets: List<PrecipBucket>): Double =
        (buckets.sumOf { it.mm } * 10.0).roundToInt() / 10.0

    /** Start time of the first bucket at or above the wet threshold, epoch seconds, or null. */
    fun firstWetTime(buckets: List<PrecipBucket>): Long? =
        buckets.firstOrNull { mmPerQuarterHour(it) >= DRY_MM_PER_QUARTER }?.time

    private const val QUARTER_HOUR_SEC = 900
    private const val MIN_BAR_PX = 2f
    private const val MIN_TEXT_PX = 20f
    private const val MAX_TEXT_PX = 34f
    private const val PROBABILITY_STROKE_PX = 2f
}
