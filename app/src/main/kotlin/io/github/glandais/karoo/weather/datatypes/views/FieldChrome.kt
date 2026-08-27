package io.github.glandais.karoo.weather.datatypes.views

import android.content.Context
import androidx.annotation.StringRes
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.domain.TempUnit
import io.github.glandais.karoo.weather.domain.WindUnit
import io.github.glandais.karoo.weather.ui.theme.ColorPair
import io.github.glandais.karoo.weather.ui.theme.isNightMode
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Chrome shared by every graphical field.
 *
 * ## Observed `ViewConfig` values (ARCHITECTURE spike S3)
 *
 * The geometry below is the *design assumption* derived from the portrait 480 x 800 ride page
 * (DESIGN preamble). **It has not yet been confirmed against a Karoo 2 / Karoo 3.** Nothing in this
 * file hard-codes a column count, so a device that reports a narrower `viewSize` degrades on its
 * own via [columnsFor]; the table is documentation, not behaviour.
 *
 * | gridSize | expected viewSize | expected textSize | columnsFor(maxColumns) |
 * |----------|-------------------|-------------------|------------------------|
 * | (30, 30) | ~240 x 400        | ~32 sp            | 1 (max 1)              |
 * | (60, 15) | ~480 x 200        | ~32 sp            | 3 (max 3)              |
 * | (60, 30) | ~480 x 400        | ~40 sp            | 5 (max 5)              |
 * | (60, 60) | ~480 x 800        | ~48 sp            | 5 (max 6)              |
 *
 * To fill this in on hardware, place each field at each grid size and read the single
 * `Log.i("karoo-weather", "startView …")` line every graphical field emits on entry to `startView`.
 */
object FieldChrome {

    /**
     * Narrowest column that still clears the 10 sp legibility floor of DESIGN §1.3 at the Karoo's
     * ~2x density.
     */
    const val MIN_CELL_PX = 88

    /** Logcat tag every field uses for its one-line `startView` geometry report. */
    const val LOG_TAG = "karoo-weather"

    /**
     * `gridSize` decides which rows exist; `viewSize` decides how many columns fit (DESIGN §3.0).
     *
     * Never returns fewer than 1, never more than [maxColumns]. A non-positive [maxColumns] is
     * treated as 1 rather than throwing: a bad caller must not take a data field down mid-ride.
     */
    fun columnsFor(viewSize: Pair<Int, Int>, maxColumns: Int): Int {
        val ceiling = if (maxColumns < 1) 1 else maxColumns
        return (viewSize.first / MIN_CELL_PX).coerceIn(1, ceiling)
    }

    /** Re-export of `«root».ui.theme.isNightMode` so view code has one import. */
    fun night(context: Context): Boolean = isNightMode(context)

    /**
     * The ONLY sanctioned way to build a [ShowCustomStreamState]: the SDK signature is `(message:
     * String?, @ColorInt color: Int?)` and takes neither a `@StringRes` nor a [ColorPair].
     */
    fun customState(
        context: Context,
        @StringRes message: Int?,
        pair: ColorPair,
        night: Boolean,
    ): ShowCustomStreamState =
        ShowCustomStreamState(message?.let(context::getString), pair.pick(night))

    /**
     * The cleared state a graphical field emits once at start, without which Karoo's own "no data"
     * overlay covers the custom view (ARCHITECTURE §4.3).
     *
     * It lives here so that `ShowCustomStreamState` is still constructed in exactly one place.
     */
    fun clearState(): ShowCustomStreamState = ShowCustomStreamState(null, null)

    @StringRes
    fun windUnitLabel(unit: WindUnit): Int =
        when (unit) {
            WindUnit.MS -> R.string.unit_ms
            WindUnit.KMH -> R.string.unit_kmh
            WindUnit.MPH -> R.string.unit_mph
            WindUnit.KNOTS -> R.string.unit_kn
            WindUnit.BEAUFORT -> R.string.unit_bft
        }

    @StringRes
    fun tempUnitLabel(unit: TempUnit): Int =
        when (unit) {
            TempUnit.CELSIUS -> R.string.unit_celsius
            TempUnit.FAHRENHEIT -> R.string.unit_fahrenheit
        }

    /** [index] is `RelativeWind.compassIndex`, 0..15 for N, NNE, NE, ... Out of range wraps. */
    @StringRes fun compassLabel(index: Int): Int = COMPASS_LABELS[((index % 16) + 16) % 16]

    /** 4 dp when the user asked for field boundaries, else 2 dp (DESIGN §1.4). */
    fun paddingDp(config: ViewConfig): Int = if (config.boundariesEnabled) 4 else 2

    /**
     * 48 px, or 56 px on a tall field (DESIGN §1.4). Never the 128 px a 293 ppi panel cannot show.
     */
    fun arrowSizePx(config: ViewConfig): Int = if (config.viewSize.second > 300) 56 else 48

    /**
     * Bearing quantised to 10 degrees, as a bucket index in 0..35.
     *
     * Pure and separate from [ArrowBitmaps] so it can be unit tested without touching
     * `android.graphics`. At most 36 rotations per (size, tint) can therefore exist in the cache.
     */
    fun arrowBucket10(bearingDeg: Double): Int {
        if (!bearingDeg.isFinite()) return 0
        val normalised = ((bearingDeg % 360.0) + 360.0) % 360.0
        return (normalised / 10.0).roundToInt() % 36
    }

    /** The bucket index of [arrowBucket10] expressed back in degrees, 0..350. */
    fun arrowBucketDegrees(bearingDeg: Double): Float = arrowBucket10(bearingDeg) * 10f

    // ---- derived type sizes (DESIGN §1.3) ------------------------------------------------------

    /** Primary value: `1.00 x textSize`, bold. */
    fun primarySp(config: ViewConfig): Float = config.textSize.toFloat()

    /** Secondary value (gust, second metric): `0.55 x`. */
    fun secondarySp(config: ViewConfig): Float = config.textSize * 0.55f

    /** Unit suffix: `0.40 x`. */
    fun unitSp(config: ViewConfig): Float = config.textSize * 0.40f

    /** Column label (distance / clock): `0.36 x`, floor 10 sp. */
    fun labelSp(config: ViewConfig): Float = maxOf(config.textSize * 0.36f, MIN_LABEL_SP)

    /** Micro annotation (axis ticks): `0.30 x`, floor 9 sp. */
    fun microSp(config: ViewConfig): Float = maxOf(config.textSize * 0.30f, MIN_MICRO_SP)

    /**
     * True when a `0.36 x` column label would still clear the 10 sp floor without being enlarged.
     *
     * DESIGN §1.3: "drop the element rather than shrink it". A renderer that would have to inflate
     * the label past its share of the row should omit the row instead.
     */
    fun labelFits(config: ViewConfig): Boolean = config.textSize * 0.36f >= MIN_LABEL_SP

    /** Icon box: `0.42 x` the shorter field dimension, clamped to 24..56 dp (DESIGN §1.4). */
    fun iconBoxDp(config: ViewConfig, density: Float): Int {
        val shorterPx = minOf(config.viewSize.first, config.viewSize.second)
        val safeDensity = if (density > 0f) density else 1f
        val dp = floor(shorterPx * 0.42f / safeDensity).toInt()
        return dp.coerceIn(MIN_ICON_DP, MAX_ICON_DP)
    }

    const val MIN_LABEL_SP = 10f
    const val MIN_MICRO_SP = 9f
    const val MIN_ICON_DP = 24
    const val MAX_ICON_DP = 56

    private val COMPASS_LABELS =
        intArrayOf(
            R.string.compass_n,
            R.string.compass_nne,
            R.string.compass_ne,
            R.string.compass_ene,
            R.string.compass_e,
            R.string.compass_ese,
            R.string.compass_se,
            R.string.compass_sse,
            R.string.compass_s,
            R.string.compass_ssw,
            R.string.compass_sw,
            R.string.compass_wsw,
            R.string.compass_w,
            R.string.compass_wnw,
            R.string.compass_nw,
            R.string.compass_nnw,
        )
}
