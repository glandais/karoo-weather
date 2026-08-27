package io.github.glandais.karoo.weather.util

import io.github.glandais.karoo.weather.domain.DistanceUnit
import io.github.glandais.karoo.weather.domain.Units
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Distance conversion and formatting for the companion app.
 *
 * SI in, display units out: every caller passes metres and gets a string. Nothing above this
 * function ever holds a converted number (PLAN rule 6).
 */
object Distance {

    /** Metres to the display unit. */
    fun convert(metres: Double, unit: DistanceUnit): Double = metres * unit.perMetre

    /**
     * `18` / `3.4`. One decimal below 10 display units, none above, so a route strip never jitters
     * between widths as the rider closes on a point.
     */
    fun format(
        metres: Double,
        unit: DistanceUnit,
        locale: Locale = Locale.getDefault(),
    ): String {
        val value = convert(metres, unit)
        return if (abs(value) < 10.0) String.format(locale, "%.1f", value)
        else String.format(locale, "%.0f", value)
    }
}

/**
 * The remaining display-number formatters. They live beside [Distance] rather than in a fourth file
 * because PLAN WP5 fixes the `util/` file list at three entries.
 */
object Numbers {

    /** `22` — temperature is always a whole degree in the app; the fields carry the precision. */
    fun temp(celsius: Double, units: Units, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%d", units.temp(celsius).roundToInt())

    /** `14` — wind is a whole display unit, Beaufort included. */
    fun wind(ms: Double, units: Units, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%d", units.wind(ms).roundToInt())

    /**
     * `2.1` — precipitation always carries one decimal; `0.0` is meaningfully different from `0.4`.
     */
    fun mm(millimetres: Double, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f", millimetres)

    /** `40` — a probability percent, clamped to 0..100. */
    fun percent(value: Int): String = value.coerceIn(0, 100).toString()

    /**
     * Signed headwind in display units, `+22` / `-6`, for the route rows.
     *
     * The magnitude is converted, then the sign is prepended. Converting the signed value directly
     * would be wrong for Beaufort, whose scale is defined on wind *speed* and maps every negative
     * argument to 0.
     */
    fun signedWind(ms: Double, units: Units, locale: Locale = Locale.getDefault()): String {
        val magnitude = units.wind(abs(ms)).roundToInt()
        val sign = if (ms < 0.0) "-" else "+"
        return sign + String.format(locale, "%d", magnitude)
    }
}
