package io.github.glandais.karoo.weather.weather

import io.github.glandais.karoo.weather.domain.WmoCategory

/**
 * WMO 4677 present-weather codes as returned by Open-Meteo's `weather_code` field.
 *
 * The table is DESIGN §2 verbatim. Note that fog (45/48) maps to [WmoCategory.FOG] and is NOT wet:
 * treating it as rain is the karoo-headwind bug this mapping exists to avoid.
 */
object WmoCodes {

    /** WMO 4677 -> category. Fog 45/48 -> FOG (NOT RAIN). Unknown -> UNKNOWN. */
    fun category(code: Int): WmoCategory =
        when (code) {
            0 -> WmoCategory.CLEAR
            1 -> WmoCategory.MOSTLY_CLEAR
            2 -> WmoCategory.PARTLY_CLOUDY
            3 -> WmoCategory.OVERCAST
            45,
            48 -> WmoCategory.FOG
            51,
            53,
            55 -> WmoCategory.DRIZZLE
            56,
            57,
            66,
            67 -> WmoCategory.FREEZING
            61,
            63 -> WmoCategory.RAIN
            65 -> WmoCategory.HEAVY_RAIN
            71,
            73,
            77,
            85 -> WmoCategory.SNOW
            75,
            86 -> WmoCategory.HEAVY_SNOW
            80,
            81,
            82 -> WmoCategory.SHOWERS
            95 -> WmoCategory.THUNDER
            96,
            99 -> WmoCategory.THUNDER_HAIL
            else -> WmoCategory.UNKNOWN
        }

    /** True when the category implies liquid or frozen precipitation reaching the rider. */
    fun isWet(code: Int): Boolean = isWet(category(code))

    /** Category-level twin of [isWet]. Fog and every cloud category are dry. */
    fun isWet(category: WmoCategory): Boolean =
        when (category) {
            WmoCategory.DRIZZLE,
            WmoCategory.RAIN,
            WmoCategory.HEAVY_RAIN,
            WmoCategory.SHOWERS,
            WmoCategory.FREEZING,
            WmoCategory.SNOW,
            WmoCategory.HEAVY_SNOW,
            WmoCategory.THUNDER,
            WmoCategory.THUNDER_HAIL -> true
            WmoCategory.CLEAR,
            WmoCategory.MOSTLY_CLEAR,
            WmoCategory.PARTLY_CLOUDY,
            WmoCategory.OVERCAST,
            WmoCategory.FOG,
            WmoCategory.UNKNOWN -> false
        }
}
