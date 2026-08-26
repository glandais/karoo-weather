package io.github.glandais.karoo.weather.domain

import kotlinx.serialization.Serializable

/**
 * One point-in-time, point-in-space weather observation or forecast. Canonical units: temperature
 * °C, speeds m/s, precipitation mm (per interval), angles degrees true, time epoch seconds UTC.
 */
@Serializable
data class WeatherSample(
    /** Epoch seconds, UTC. */
    val time: Long,
    /** Air temperature at 2 m, °C. */
    val temp: Double,
    /** Apparent ("feels like") temperature, °C. Null when not requested. */
    val apparentTemp: Double? = null,
    /** Mean wind at 10 m, m/s. */
    val windSpeed: Double,
    /** Wind gusts at 10 m, m/s. */
    val windGusts: Double,
    /** Meteorological wind direction: the direction the wind blows FROM, degrees true (0 = N). */
    val windDir: Double,
    /** Precipitation in the sample interval, mm. */
    val precip: Double,
    /** Probability of precipitation, percent 0..100. Null for `current` and `minutely_15`. */
    val precipProb: Int? = null,
    /** WMO 4677 code. */
    val wmoCode: Int,
    /** Cloud cover, percent 0..100. */
    val cloudCover: Int? = null,
    /** True when the sun is up at this point/time. */
    val isDay: Boolean,
) {
    /** Direction the wind blows TOWARDS, degrees true. */
    val windToDir: Double
        get() = (windDir + 180.0) % 360.0
}

/** One bar of the short-term rain nowcast. */
@Serializable
data class PrecipBucket(
    /** Epoch seconds, UTC, start of the bucket. */
    val time: Long,
    /** Bucket length in seconds (900 for minutely_15, 3600 for hourly fallback). */
    val durationSec: Int,
    /** Precipitation in the bucket, mm. */
    val mm: Double,
    /** Probability 0..100, null when unavailable. */
    val probability: Int? = null,
)

/** Coarse icon/semantic bucket derived from [WeatherSample.wmoCode]. */
enum class WmoCategory {
    CLEAR,
    MOSTLY_CLEAR,
    PARTLY_CLOUDY,
    OVERCAST,
    FOG,
    DRIZZLE,
    RAIN,
    HEAVY_RAIN,
    SHOWERS,
    FREEZING,
    SNOW,
    HEAVY_SNOW,
    THUNDER,
    THUNDER_HAIL,
    UNKNOWN,
}
