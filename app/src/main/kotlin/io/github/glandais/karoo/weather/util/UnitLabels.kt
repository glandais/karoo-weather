package io.github.glandais.karoo.weather.util

import androidx.annotation.StringRes
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.domain.DistanceUnit
import io.github.glandais.karoo.weather.domain.TempUnit
import io.github.glandais.karoo.weather.domain.WindUnit

/**
 * The Compose twin of `datatypes/views/FieldChrome`'s label helpers.
 *
 * The enums carry no resource name on purpose: resolving one at runtime needs the deprecated
 * `Resources.getIdentifier()`, which the release build's R8 resource shrinking breaks (PLAN WP5).
 * A `when` over the enum keeps every id a compile-time constant and therefore reachable.
 */
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

/** `+%1$s km` / `+%1$s mi` — the distance-ahead label of a route row. */
@StringRes
fun distanceAheadLabel(unit: DistanceUnit): Int =
    when (unit) {
        DistanceUnit.KM -> R.string.dist_ahead_km
        DistanceUnit.MILES -> R.string.dist_ahead_mi
    }

/** [index] is `RelativeWind.compassIndex`, 0..15. Out-of-range input falls back to N. */
@StringRes
fun compassLabel(index: Int): Int =
    when (((index % 16) + 16) % 16) {
        0 -> R.string.compass_n
        1 -> R.string.compass_nne
        2 -> R.string.compass_ne
        3 -> R.string.compass_ene
        4 -> R.string.compass_e
        5 -> R.string.compass_ese
        6 -> R.string.compass_se
        7 -> R.string.compass_sse
        8 -> R.string.compass_s
        9 -> R.string.compass_ssw
        10 -> R.string.compass_sw
        11 -> R.string.compass_wsw
        12 -> R.string.compass_w
        13 -> R.string.compass_wnw
        14 -> R.string.compass_nw
        else -> R.string.compass_nnw
    }

/**
 * Short unit suffixes that PLAN WP0's `strings.xml` does not contain.
 *
 * WP5 may not add a string resource (PLAN rule 2), and a dropdown whose options render as bare
 * numbers is worse than an unlocalised suffix, so the three suffixes the settings dropdowns need
 * are collected here. They are ASCII unit abbreviations, identical in every locale this project
 * ships. The integrator moving them into `strings.xml` is a one-file change; see the WP5 report.
 */
object AppLiterals {
    /** Minutes, as in "Refresh every 30 min". */
    const val MINUTES = "min"

    /** Seconds, as in "Field repaint 2 s". */
    const val SECONDS = "s"

    /** Kilometres, as in "Location privacy 3 km". */
    const val KILOMETRES = "km"

    /** Separates the fragments of the summary lines ("Updated 3 min ago · Open-Meteo"). */
    const val SEPARATOR = " · "
}
