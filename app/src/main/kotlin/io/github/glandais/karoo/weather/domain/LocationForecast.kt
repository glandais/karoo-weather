package io.github.glandais.karoo.weather.domain

import kotlinx.serialization.Serializable

/** Forecast series for one geographic point, as returned by a provider. */
@Serializable
data class LocationForecast(
    val lat: Double,
    val lon: Double,
    /** Provider "now" observation. Null in route-batch responses. */
    val current: WeatherSample? = null,
    /** Hourly series, ascending time, typically 12 entries. */
    val hourly: List<WeatherSample> = emptyList(),
    /** 15-minute precipitation nowcast, ascending time. Empty when unavailable. */
    val minutely15: List<PrecipBucket> = emptyList(),
    /** Model elevation, m. Informational. */
    val elevation: Double? = null,
)

/** Everything one fetch cycle produced. Persisted verbatim to DataStore. */
@Serializable
data class ForecastBundle(
    /** Epoch seconds when the fetch completed. */
    val fetchedAt: Long,
    /** Forecast at (or near) the rider's own position. */
    val here: LocationForecast,
    /** Forecast along the loaded route; null when no route is loaded. */
    val route: RouteForecast? = null,
    /** Provider identifier, for the About screen. */
    val provider: String = "open-meteo",
)
