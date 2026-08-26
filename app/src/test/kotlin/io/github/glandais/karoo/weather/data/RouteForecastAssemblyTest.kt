package io.github.glandais.karoo.weather.data

import io.github.glandais.karoo.weather.domain.GeoPoint
import io.github.glandais.karoo.weather.domain.LocationForecast
import io.github.glandais.karoo.weather.domain.RouteForecast
import io.github.glandais.karoo.weather.domain.WeatherSample
import io.github.glandais.karoo.weather.route.Geo
import io.github.glandais.karoo.weather.route.RoutePath
import io.github.glandais.karoo.weather.route.RouteSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WeatherRepository.buildRouteForecast` is the one place that prepends the rider's own point, so
 * everything about indices, ETAs, wind signs and the horizon marker is pinned here.
 */
class RouteForecastAssemblyTest {

    private val now = 1_700_000_000L
    private val start = GeoPoint(45.0, 5.0)

    /** Due east, four vertices 10 km apart: route bearing is ~90 degrees everywhere. */
    private val path =
        RoutePath(
            listOf(
                start,
                Geo.destination(start, 10_000.0, 90.0),
                Geo.destination(start, 20_000.0, 90.0),
                Geo.destination(start, 30_000.0, 90.0),
            )
        )

    private val progress = 5_000.0
    private val rider = GeoPoint(45.0, 5.06)

    @Test
    fun `index zero is the rider at the current progress`() {
        val samples = samples(10_000.0, 20_000.0, 30_000.0)
        val forecast = build(samples, forecasts = forecasts(4))

        assertEquals(4, forecast.points.size)
        val first = forecast.points.first()
        assertEquals(progress, first.distanceAlong, 1e-9)
        assertEquals(rider, first.point)
        assertEquals(now, first.eta)
        assertFalse(first.beyondHorizon)
    }

    @Test
    fun `points stay in ascending distance order with increasing etas`() {
        val samples = samples(10_000.0, 20_000.0, 30_000.0)
        val forecast = build(samples, forecasts = forecasts(4))

        val distances = forecast.points.map { it.distanceAlong }
        assertEquals(distances.sorted(), distances)
        val etas = forecast.points.map { it.eta }
        assertEquals(etas.sorted(), etas)
        // 5 km ahead at 10 m/s.
        assertEquals(now + 500L, forecast.points[1].eta)
    }

    @Test
    fun `head and tail wind signs follow the travel direction`() {
        val samples = samples(10_000.0, 20_000.0)
        val forecast =
            build(
                samples,
                forecasts =
                    listOf(
                        forecast(sample(windDir = 90.0)), // rider: wind from the east = headwind
                        forecast(sample(windDir = 90.0)),
                        forecast(sample(windDir = 270.0)), // wind from the west = tailwind
                    ),
            )

        val head = forecast.points[1]
        assertEquals(180.0, head.relativeWindAngle, 1.0)
        assertEquals(8.0, head.headwindSpeed, 0.05)

        val tail = forecast.points[2]
        assertEquals(0.0, tail.relativeWindAngle, 1.0)
        assertEquals(-8.0, tail.headwindSpeed, 0.05)
    }

    @Test
    fun `first wet point is reported by distance and eta`() {
        val samples = samples(10_000.0, 20_000.0, 30_000.0)
        val forecast =
            build(
                samples,
                forecasts =
                    listOf(
                        forecast(sample(precip = 0.0)),
                        forecast(sample(precip = 0.0)),
                        forecast(sample(precip = 0.6)),
                        forecast(sample(precip = 1.4)),
                    ),
            )

        assertEquals(20_000.0, forecast.firstWetDistance!!, 1e-9)
        assertEquals(forecast.points[2].eta, forecast.firstWetEta)
        assertEquals(2.0, forecast.totalPrecipMm, 1e-9)
    }

    @Test
    fun `a high probability counts as wet even without accumulation`() {
        val samples = samples(10_000.0)
        val forecast =
            build(
                samples,
                forecasts =
                    listOf(
                        forecast(sample(precip = 0.0, precipProb = 10)),
                        forecast(sample(precip = 0.0, precipProb = 60)),
                    ),
            )

        assertEquals(10_000.0, forecast.firstWetDistance!!, 1e-9)
    }

    @Test
    fun `a dry route reports no wet point`() {
        val forecast = build(samples(10_000.0, 20_000.0), forecasts = forecasts(3))
        assertNull(forecast.firstWetDistance)
        assertNull(forecast.firstWetEta)
        assertEquals(0.0, forecast.totalPrecipMm, 1e-9)
    }

    @Test
    fun `the horizon marker lands on the sample the sampler flagged`() {
        val samples = samples(10_000.0, 20_000.0, 30_000.0)
        val forecast = build(samples, forecasts = forecasts(4), horizonMarkerIndex = 2)

        // markerIndex is an index into `samples`, so it is index + 1 in the assembled list.
        assertTrue(forecast.points[3].beyondHorizon)
        assertFalse(forecast.points[0].beyondHorizon)
        assertFalse(forecast.points[1].beyondHorizon)
        assertFalse(forecast.points[2].beyondHorizon)
    }

    @Test
    fun `a sample with no forecast is skipped rather than mispaired`() {
        val samples = samples(10_000.0, 20_000.0, 30_000.0)
        // Only the rider and the first two samples came back.
        val forecast = build(samples, forecasts = forecasts(3))

        assertEquals(3, forecast.points.size)
        assertEquals(listOf(progress, 10_000.0, 20_000.0), forecast.points.map { it.distanceAlong })
    }

    @Test
    fun `metadata carries the route identity and the speed used`() {
        val forecast = build(samples(10_000.0), forecasts = forecasts(2))
        assertEquals("Test route", forecast.routeName)
        assertEquals(30_000.0, forecast.routeDistance, 1e-9)
        assertEquals(progress, forecast.progress, 1e-9)
        assertEquals(now, forecast.computedAt)
        assertEquals(SPEED_MS, forecast.assumedSpeed, 1e-9)
    }

    // ---- fixtures --------------------------------------------------------------------------

    private fun build(
        samples: List<RouteSample>,
        forecasts: List<LocationForecast>,
        horizonMarkerIndex: Int? = null,
    ): RouteForecast =
        WeatherRepository.buildRouteForecast(
            routeName = "Test route",
            path = path,
            routeDistance = 30_000.0,
            progress = progress,
            riderPoint = rider,
            samples = samples,
            horizonMarkerIndex = horizonMarkerIndex,
            forecasts = forecasts,
            eta = { d -> now + ((d - progress) / SPEED_MS).toLong() },
            nowSec = now,
            assumedSpeedMs = SPEED_MS,
        )

    private fun samples(vararg distances: Double): List<RouteSample> = distances.map {
        RouteSample(path.pointAt(it), it, path.bearingAt(it))
    }

    private fun forecasts(count: Int): List<LocationForecast> = List(count) { forecast(sample()) }

    private fun forecast(sample: WeatherSample) =
        LocationForecast(lat = 0.0, lon = 0.0, hourly = listOf(sample))

    private fun sample(
        windDir: Double = 0.0,
        precip: Double = 0.0,
        precipProb: Int? = 0,
        temp: Double = 12.0,
    ) =
        WeatherSample(
            time = now,
            temp = temp,
            windSpeed = 8.0,
            windGusts = 12.0,
            windDir = windDir,
            precip = precip,
            precipProb = precipProb,
            wmoCode = 0,
            isDay = true,
        )

    private companion object {
        const val SPEED_MS = 10.0
    }
}
