package io.github.glandais.karoo.weather.domain

/** What one fetch cycle asks for. */
data class WeatherRequest(
    /** Rider position first, then route sample points, in order. Max [MAX_POINTS]. */
    val points: List<GeoPoint>,
    /** Hours of hourly forecast to request (1..24). */
    val forecastHours: Int = 12,
    /** Request the 15-min nowcast + `current` for points[0]. */
    val includeNowcast: Boolean = true,
) {
    init {
        require(points.isNotEmpty() && points.size <= MAX_POINTS)
    }

    companion object {
        const val MAX_POINTS = 25
    }
}

sealed class WeatherError(val message: String, val retryable: Boolean) {
    data object NoConnection : WeatherError("no_connection", true)

    data object Timeout : WeatherError("timeout", true)

    data class RateLimited(val retryAfterSec: Long) : WeatherError("rate_limited", true)

    data class Server(val status: Int) : WeatherError("server_$status", true)

    data class Client(val status: Int) : WeatherError("client_$status", false)

    /** Response body exceeded the Karoo transport ceiling. Retry with fewer points. */
    data class Oversize(val bytes: Int) : WeatherError("oversize", true)

    /** Transport reported success with no body. Retry with fewer points. */
    data object EmptyBody : WeatherError("empty_body", true)

    /** Body was present but is not the JSON we expect. Permanent; do not retry. */
    data class Parse(val detail: String) : WeatherError("parse", false)

    /** True for the two errors whose remedy is to ask for a smaller response. */
    val reducePoints: Boolean
        get() = this is Oversize || this is EmptyBody
}

/**
 * Source of forecast data. One implementation in v1 (Open-Meteo, direct from device); a thin
 * backend or MET Norway would implement the same interface.
 */
interface WeatherProvider {
    val id: String

    /** Returns one [LocationForecast] per requested point, in request order. */
    suspend fun fetch(request: WeatherRequest): Result<List<LocationForecast>>
}
