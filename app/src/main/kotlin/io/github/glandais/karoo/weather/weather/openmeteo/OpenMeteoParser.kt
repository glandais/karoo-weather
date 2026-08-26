package io.github.glandais.karoo.weather.weather.openmeteo

import io.github.glandais.karoo.weather.domain.LocationForecast
import io.github.glandais.karoo.weather.domain.PrecipBucket
import io.github.glandais.karoo.weather.domain.WeatherError
import io.github.glandais.karoo.weather.domain.WeatherSample
import kotlinx.serialization.json.Json

/** Thrown inside `Result.failure`. */
class WeatherErrorException(val error: WeatherError) : Exception(error.message)

/**
 * Turns Open-Meteo wire JSON into the domain model.
 *
 * Two rules that are easy to get wrong and expensive to debug:
 * 1. The top level is an **object** for a one-point request and an **array** for a multi-point one,
 *    so the branch is on `expectedPoints == 1`, never on sniffing the first character.
 * 2. Points are re-zipped **positionally**. Open-Meteo snaps coordinates to its model grid (a
 *    requested `2.3500` comes back as `2.3599997`), so matching by lat/lon would never work.
 *
 * Every failure is raised as [WeatherErrorException] carrying [WeatherError.Parse].
 */
object OpenMeteoParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Sentinel WMO code for "the response carried no code"; maps to `WmoCategory.UNKNOWN`. */
    private const val UNKNOWN_WMO = -1

    /** Request A. Branches object-vs-array on [expectedPoints]. */
    fun parseBatch(body: String, expectedPoints: Int): List<LocationForecast> {
        val responses =
            if (expectedPoints == 1) {
                listOf(decode<OpenMeteoResponse>(body))
            } else {
                decode<List<OpenMeteoResponse>>(body)
            }
        responses.forEach(::failOnApiError)
        if (responses.size != expectedPoints) {
            throw parseError("expected $expectedPoints locations, got ${responses.size}")
        }
        return responses.map { it.toForecast() }
    }

    /** Request B. Always a single-point response. */
    fun parseDetail(body: String): LocationForecast {
        val response = decode<OpenMeteoResponse>(body)
        failOnApiError(response)
        return response.toForecast()
    }

    /**
     * Merges request B into request A's index 0 **by time (epoch seconds), never by index**: the
     * two responses are anchored to "now" independently and their hourly arrays can be an hour
     * apart.
     *
     * `current` and `minutely15` come from B verbatim; hours of A that B does not cover simply keep
     * whatever apparent temperature they already had (normally none).
     */
    fun mergeDetailInto(
        routePoint0: LocationForecast,
        detail: LocationForecast,
    ): LocationForecast {
        val apparentByTime =
            detail.hourly
                .asSequence()
                .filter { it.apparentTemp != null }
                .associate {
                    it.time to it.apparentTemp
                }
        val hourly =
            routePoint0.hourly.map { sample ->
                val apparent = apparentByTime[sample.time] ?: sample.apparentTemp
                if (apparent == sample.apparentTemp) sample
                else sample.copy(apparentTemp = apparent)
            }
        return routePoint0.copy(
            hourly = hourly,
            current = detail.current ?: routePoint0.current,
            minutely15 = detail.minutely15.ifEmpty { routePoint0.minutely15 },
            elevation = routePoint0.elevation ?: detail.elevation,
        )
    }

    private inline fun <reified T> decode(body: String): T =
        try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw parseError(e.message ?: e::class.java.simpleName)
        }

    private fun failOnApiError(response: OpenMeteoResponse) {
        if (response.error) {
            throw parseError("api_error: ${response.reason ?: "unspecified"}")
        }
    }

    private fun parseError(detail: String): WeatherErrorException =
        WeatherErrorException(WeatherError.Parse(detail.take(200)))

    private fun OpenMeteoResponse.toForecast(): LocationForecast =
        LocationForecast(
            lat = latitude,
            lon = longitude,
            current = current?.toSample(),
            hourly = hourly?.toSamples() ?: emptyList(),
            minutely15 = minutely15?.toBuckets() ?: emptyList(),
            elevation = elevation,
        )

    private fun OpenMeteoCurrent.toSample(): WeatherSample =
        WeatherSample(
            time = time,
            temp = temperature2m ?: 0.0,
            apparentTemp = apparentTemperature,
            windSpeed = windSpeed10m ?: 0.0,
            windGusts = windGusts10m ?: (windSpeed10m ?: 0.0),
            windDir = windDirection10m ?: 0.0,
            precip = precipitation ?: 0.0,
            precipProb = null,
            wmoCode = weatherCode ?: UNKNOWN_WMO,
            cloudCover = cloudCover,
            isDay = (isDay ?: 1) != 0,
        )

    private fun OpenMeteoHourly.toSamples(): List<WeatherSample> =
        time.indices.map { i ->
            val speed = windSpeed10m.at(i) ?: 0.0
            WeatherSample(
                time = time[i],
                temp = temperature2m.at(i) ?: 0.0,
                apparentTemp = apparentTemperature.at(i),
                windSpeed = speed,
                windGusts = windGusts10m.at(i) ?: speed,
                windDir = windDirection10m.at(i) ?: 0.0,
                precip = precipitation.at(i) ?: 0.0,
                precipProb = precipitationProbability.at(i),
                wmoCode = weatherCode.at(i) ?: UNKNOWN_WMO,
                cloudCover = cloudCover.at(i),
                isDay = (isDay.at(i) ?: 1) != 0,
            )
        }

    private fun OpenMeteoMinutely15.toBuckets(): List<PrecipBucket> =
        time.indices.map { i ->
            PrecipBucket(
                time = time[i],
                durationSec = 900,
                mm = precipitation.at(i) ?: 0.0,
                probability = precipitationProbability.at(i),
            )
        }

    private fun <T> List<T?>?.at(index: Int): T? = this?.getOrNull(index)
}
