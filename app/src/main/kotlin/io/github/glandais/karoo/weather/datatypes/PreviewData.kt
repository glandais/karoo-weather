package io.github.glandais.karoo.weather.datatypes

import io.github.glandais.karoo.weather.domain.ForecastBundle
import io.github.glandais.karoo.weather.domain.GeoPoint
import io.github.glandais.karoo.weather.domain.LocationForecast
import io.github.glandais.karoo.weather.domain.PrecipBucket
import io.github.glandais.karoo.weather.domain.RouteForecast
import io.github.glandais.karoo.weather.domain.RoutePointForecast
import io.github.glandais.karoo.weather.domain.Units
import io.github.glandais.karoo.weather.domain.WeatherSample
import io.github.glandais.karoo.weather.domain.WeatherSnapshot
import io.github.glandais.karoo.weather.route.RelativeWind

/**
 * The fixed, plausible snapshot every field renders when `ViewConfig.preview` is true.
 *
 * The page editor may instantiate several previews at once, so this must be a pure constant: no
 * repository, no network, no clock. The values come from DESIGN §8 and are chosen so every visual
 * mechanism is exercised in the picker — a clear cell, a cloudy cell, two wet cells, a tailwind
 * arrow, a headwind arrow, and a temperature spread that crosses two ramp buckets.
 *
 * It must look like a good day's data, not like an error state: this is the field's advertisement.
 */
object PreviewData {

    /** 2026-06-21 12:00:00 UTC. Fixed so two previews of the same field render identically. */
    const val BASE_TIME = 1_782_043_200L

    private const val KMH = 1.0 / 3.6

    /** Current conditions at the rider: 22 °C, feels 24 °C, 14 km/h from NE, gusts 26 km/h. */
    val sample: WeatherSample =
        WeatherSample(
            time = BASE_TIME,
            temp = 22.0,
            apparentTemp = 24.0,
            windSpeed = 14.0 * KMH,
            windGusts = 26.0 * KMH,
            windDir = 45.0,
            precip = 0.4,
            precipProb = 40,
            wmoCode = 61,
            cloudCover = 60,
            isDay = true,
        )

    /** Twelve hours of hourly forecast, used by the `(60,60)` outlook strip of `weather-now`. */
    val hourly: List<WeatherSample> = buildHourly()

    /** Eight 15-minute nowcast buckets: a shower that builds, peaks and clears within 2 h. */
    val buckets: List<PrecipBucket> =
        listOf(
                0.0 to 10,
                0.1 to 30,
                0.4 to 55,
                1.1 to 80,
                0.6 to 70,
                0.2 to 45,
                0.0 to 20,
                0.0 to 10,
            )
            .mapIndexed { index, (mm, probability) ->
                PrecipBucket(
                    time = BASE_TIME + index * 900L,
                    durationSec = 900,
                    mm = mm,
                    probability = probability,
                )
            }

    val here: LocationForecast =
        LocationForecast(
            lat = 48.8566,
            lon = 2.3522,
            current = sample,
            hourly = hourly,
            minutely15 = buckets,
            elevation = 35.0,
        )

    /**
     * Five route points at 0/18/36/54/72 km. Angles and speeds are chosen first and the headwind
     * component is derived from them, so the arrow direction and its colour can never disagree.
     */
    val route: RouteForecast = buildRoute()

    val bundle: ForecastBundle =
        ForecastBundle(fetchedAt = BASE_TIME, here = here, route = route, provider = "open-meteo")

    val snapshot: WeatherSnapshot =
        WeatherSnapshot(
            bundle = bundle,
            units = Units(),
            position = GeoPoint(48.8566, 2.3522),
            bearing = 90.0,
            loading = false,
            error = null,
            lastSuccessAt = BASE_TIME,
            consentAccepted = true,
        )

    private fun buildHourly(): List<WeatherSample> {
        val temps = listOf(22.0, 23.0, 22.0, 20.0, 19.0, 18.0, 18.0, 17.0, 16.0, 15.0, 14.0, 13.0)
        val codes = listOf(0, 0, 2, 61, 61, 3, 3, 2, 2, 1, 0, 0)
        val precip = listOf(0.0, 0.0, 0.1, 1.2, 0.8, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        return temps.indices.map { i ->
            WeatherSample(
                time = BASE_TIME + i * 3600L,
                temp = temps[i],
                apparentTemp = temps[i] + 2.0,
                windSpeed = (12.0 + i) * KMH,
                windGusts = (24.0 + i) * KMH,
                windDir = 45.0 + i * 5.0,
                precip = precip[i],
                precipProb = (precip[i] * 60).toInt().coerceIn(0, 100),
                wmoCode = codes[i],
                cloudCover = 20 + i * 5,
                isDay = i < 9,
            )
        }
    }

    private fun buildRoute(): RouteForecast {
        // (distance m, minutes from now, wmo code, temp °C, wind m/s, relative wind angle °)
        val spec =
            listOf(
                Row(0.0, 0, 0, 22.0, 14.0 * KMH, 25.0),
                Row(18_000.0, 55, 2, 21.0, 15.0 * KMH, 70.0),
                Row(36_000.0, 108, 61, 18.0, 23.0 * KMH, 170.0),
                Row(54_000.0, 160, 61, 18.0, 21.5 * KMH, 155.0),
                Row(72_000.0, 212, 2, 20.0, 16.0 * KMH, 120.0),
            )
        val points = spec.map { row ->
            val eta = BASE_TIME + row.minutes * 60L
            val bearing = 90.0
            // windDir is meteorological (FROM); invert the relative angle to recover it.
            val windTo = (bearing + row.relativeAngle + 360.0) % 360.0
            val windFrom = (windTo + 180.0) % 360.0
            val precip = if (row.code >= 51) 1.1 else 0.0
            RoutePointForecast(
                point = GeoPoint(48.8566 + row.distance / 111_320.0, 2.3522),
                distanceAlong = row.distance,
                eta = eta,
                routeBearing = bearing,
                sample =
                    WeatherSample(
                        time = eta,
                        temp = row.temp,
                        apparentTemp = row.temp + 2.0,
                        windSpeed = row.windMs,
                        windGusts = row.windMs * 1.8,
                        windDir = windFrom,
                        precip = precip,
                        precipProb = if (precip > 0) 70 else 10,
                        wmoCode = row.code,
                        cloudCover = if (row.code >= 51) 90 else 30,
                        isDay = true,
                    ),
                relativeWindAngle = row.relativeAngle,
                headwindSpeed = RelativeWind.headwindComponent(row.relativeAngle, row.windMs),
                beyondHorizon = false,
            )
        }
        val wet = points.filter { it.sample.precip >= RouteForecast.WET_THRESHOLD_MM }
        return RouteForecast(
            routeName = "Morning loop",
            routeDistance = 72_000.0,
            progress = 0.0,
            computedAt = BASE_TIME,
            assumedSpeed = 22.0 * KMH,
            points = points,
            totalPrecipMm = points.sumOf { it.sample.precip },
            firstWetDistance = wet.firstOrNull()?.distanceAlong,
            firstWetEta = wet.firstOrNull()?.eta,
        )
    }

    private data class Row(
        val distance: Double,
        val minutes: Int,
        val code: Int,
        val temp: Double,
        val windMs: Double,
        val relativeAngle: Double,
    )
}
