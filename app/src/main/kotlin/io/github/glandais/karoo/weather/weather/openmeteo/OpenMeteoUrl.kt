package io.github.glandais.karoo.weather.weather.openmeteo

import io.github.glandais.karoo.weather.domain.GeoPoint
import java.util.Locale
import kotlin.math.ceil

/**
 * Builds the two Open-Meteo request URLs (ARCHITECTURE §5.4) and estimates their response size
 * before anything is sent.
 *
 * Coordinates are formatted `"%.4f"` under [Locale.US] — a locale-sensitive format would emit
 * decimal commas under e.g. `Locale.GERMANY` and silently corrupt the coordinate list.
 */
object OpenMeteoUrl {

    const val BASE = "https://api.open-meteo.com/v1/forecast"

    /** Estimates above this never leave the device; the point count is reduced instead. */
    const val SIZE_BUDGET_BYTES = 80_000

    /** Appended to BOTH requests so their field semantics can never diverge (ARCHITECTURE §5.4). */
    const val UNIT_PARAMS =
        "&timeformat=unixtime&wind_speed_unit=ms&temperature_unit=celsius&precipitation_unit=mm"

    /** Request A hourly variables, in the order they are sent. */
    val HOURLY_VARS: List<String> =
        listOf(
            "temperature_2m",
            "precipitation",
            "precipitation_probability",
            "weather_code",
            "wind_speed_10m",
            "wind_direction_10m",
            "wind_gusts_10m",
            "is_day",
        )

    /** Request B `current` variables, in the order they are sent. */
    val CURRENT_VARS: List<String> =
        listOf(
            "temperature_2m",
            "apparent_temperature",
            "precipitation",
            "weather_code",
            "cloud_cover",
            "wind_speed_10m",
            "wind_direction_10m",
            "wind_gusts_10m",
            "is_day",
        )

    /**
     * Request A — the route batch plus the rider's own point at index 0. Returns a JSON object when
     * [points] has one entry and a JSON array otherwise. `forecast_days` is deliberately not sent:
     * its interaction with `forecast_hours` is unverified against the live API.
     */
    fun routeBatch(points: List<GeoPoint>, forecastHours: Int = 12): String {
        require(points.isNotEmpty()) { "routeBatch needs at least one point" }
        val lats = points.joinToString(",") { coord(it.lat) }
        val lons = points.joinToString(",") { coord(it.lon) }
        return buildString {
            append(BASE)
            append("?latitude=").append(lats)
            append("&longitude=").append(lons)
            append("&hourly=").append(HOURLY_VARS.joinToString(","))
            append("&forecast_hours=").append(forecastHours)
            append("&past_hours=0")
            append(UNIT_PARAMS)
        }
    }

    /** Request B — current conditions plus the 15-minute nowcast at the rider's own point. */
    fun hereDetail(point: GeoPoint, forecastHours: Int = 12, nowcastSteps: Int = 8): String =
        buildString {
            append(BASE)
            append("?latitude=").append(coord(point.lat))
            append("&longitude=").append(coord(point.lon))
            append("&current=").append(CURRENT_VARS.joinToString(","))
            append("&minutely_15=precipitation,precipitation_probability")
            append("&forecast_minutely_15=").append(nowcastSteps)
            append("&hourly=apparent_temperature")
            append("&forecast_hours=").append(forecastHours)
            append(UNIT_PARAMS)
        }

    /**
     * Calibrated against `multi_point_25.json` with a 1.5x safety factor: `ceil((300 + points *
     * (140 + hourlyVars * hours * 12)) * 1.5)`.
     */
    fun estimateResponseBytes(points: Int, hourlyVars: Int, hours: Int): Int {
        val p = points.coerceAtLeast(0)
        val raw = 300.0 + p * (140.0 + hourlyVars.toDouble() * hours.toDouble() * 12.0)
        return ceil(raw * 1.5).toInt()
    }

    /** Largest point count whose estimate fits [budgetBytes], at least 1. */
    fun maxPointsWithin(budgetBytes: Int, hourlyVars: Int, hours: Int): Int {
        val perPoint = 140.0 + hourlyVars.toDouble() * hours.toDouble() * 12.0
        if (perPoint <= 0.0) return Int.MAX_VALUE
        var n = ((budgetBytes / 1.5 - 300.0) / perPoint).toInt()
        while (n > 1 && estimateResponseBytes(n, hourlyVars, hours) > budgetBytes) n--
        while (estimateResponseBytes(n + 1, hourlyVars, hours) <= budgetBytes) n++
        return n.coerceAtLeast(1)
    }

    private fun coord(value: Double): String = String.format(Locale.US, "%.4f", value)
}
