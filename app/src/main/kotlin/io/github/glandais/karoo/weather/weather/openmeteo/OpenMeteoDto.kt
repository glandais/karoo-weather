package io.github.glandais.karoo.weather.weather.openmeteo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of `https://api.open-meteo.com/v1/forecast`, parallel-array style exactly as the API
 * returns it. Every block and every array is nullable so a response that omits a variable (or has
 * gaps in it) still parses; the mapping into the domain model happens in [OpenMeteoParser].
 *
 * The top level is this object for a single-point request and a JSON **array** of it for a
 * multi-point request.
 */
@Serializable
data class OpenMeteoResponse(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val elevation: Double? = null,
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val current: OpenMeteoCurrent? = null,
    val hourly: OpenMeteoHourly? = null,
    @SerialName("minutely_15") val minutely15: OpenMeteoMinutely15? = null,
    /** Open-Meteo signals a rejected request with `{"error": true, "reason": "..."}`. */
    val error: Boolean = false,
    val reason: String? = null,
)

@Serializable
data class OpenMeteoCurrent(
    val time: Long = 0L,
    val interval: Int? = null,
    @SerialName("temperature_2m") val temperature2m: Double? = null,
    @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
    val precipitation: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("cloud_cover") val cloudCover: Int? = null,
    @SerialName("wind_speed_10m") val windSpeed10m: Double? = null,
    @SerialName("wind_direction_10m") val windDirection10m: Double? = null,
    @SerialName("wind_gusts_10m") val windGusts10m: Double? = null,
    @SerialName("is_day") val isDay: Int? = null,
)

@Serializable
data class OpenMeteoHourly(
    val time: List<Long> = emptyList(),
    @SerialName("temperature_2m") val temperature2m: List<Double?>? = null,
    @SerialName("apparent_temperature") val apparentTemperature: List<Double?>? = null,
    val precipitation: List<Double?>? = null,
    @SerialName("precipitation_probability") val precipitationProbability: List<Int?>? = null,
    @SerialName("weather_code") val weatherCode: List<Int?>? = null,
    @SerialName("cloud_cover") val cloudCover: List<Int?>? = null,
    @SerialName("wind_speed_10m") val windSpeed10m: List<Double?>? = null,
    @SerialName("wind_direction_10m") val windDirection10m: List<Double?>? = null,
    @SerialName("wind_gusts_10m") val windGusts10m: List<Double?>? = null,
    @SerialName("is_day") val isDay: List<Int?>? = null,
)

@Serializable
data class OpenMeteoMinutely15(
    val time: List<Long> = emptyList(),
    val precipitation: List<Double?>? = null,
    @SerialName("precipitation_probability") val precipitationProbability: List<Int?>? = null,
)
