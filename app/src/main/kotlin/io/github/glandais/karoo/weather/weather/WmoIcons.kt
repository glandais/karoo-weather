package io.github.glandais.karoo.weather.weather

import androidx.annotation.DrawableRes
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.domain.WmoCategory

/**
 * Pure `(WmoCategory, isDay) -> @DrawableRes Int` map.
 *
 * It lives in `weather/` rather than `datatypes/views/` on purpose: it carries no Glance
 * dependency, and both the data fields (WP4) and the companion app (WP5) need it, so keeping it
 * here avoids a WP5 -> WP4 dependency.
 *
 * Day/night variants exist only where a sun or moon is drawn, i.e. CLEAR and the two
 * mostly-clear/partly-cloudy categories (DESIGN §2).
 */
object WmoIcons {

    /** In-field / in-app drawable. */
    @DrawableRes
    fun field(category: WmoCategory, isDay: Boolean): Int =
        when (category) {
            WmoCategory.CLEAR ->
                if (isDay) R.drawable.ic_wmo_clear_day else R.drawable.ic_wmo_clear_night
            WmoCategory.MOSTLY_CLEAR,
            WmoCategory.PARTLY_CLOUDY ->
                if (isDay) R.drawable.ic_wmo_partly_day else R.drawable.ic_wmo_partly_night
            WmoCategory.OVERCAST -> R.drawable.ic_wmo_cloudy
            WmoCategory.FOG -> R.drawable.ic_wmo_fog
            WmoCategory.DRIZZLE -> R.drawable.ic_wmo_drizzle
            WmoCategory.RAIN -> R.drawable.ic_wmo_rain
            WmoCategory.HEAVY_RAIN -> R.drawable.ic_wmo_rain_heavy
            WmoCategory.SHOWERS -> R.drawable.ic_wmo_showers
            WmoCategory.FREEZING -> R.drawable.ic_wmo_freezing
            WmoCategory.SNOW -> R.drawable.ic_wmo_snow
            WmoCategory.HEAVY_SNOW -> R.drawable.ic_wmo_snow_heavy
            WmoCategory.THUNDER -> R.drawable.ic_wmo_thunder
            WmoCategory.THUNDER_HAIL -> R.drawable.ic_wmo_thunder_hail
            WmoCategory.UNKNOWN -> R.drawable.ic_wmo_unknown
        }

    /**
     * Map-symbol drawable. The icon set defined by DESIGN §2 has exactly one map-specific asset
     * (`ic_map_wind_arrow`, used by the wind symbol, not by a condition symbol), so every condition
     * category falls back to [field] here. The function exists so callers on the map path never
     * have to know that, and so a later map-specific condition asset is a one-line change.
     */
    @DrawableRes fun map(category: WmoCategory, isDay: Boolean): Int = field(category, isDay)

    /** Convenience for the common `(wmoCode, isDay)` call site. */
    @DrawableRes
    fun fieldForCode(code: Int, isDay: Boolean): Int = field(WmoCodes.category(code), isDay)
}
