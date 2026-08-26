package io.github.glandais.karoo.weather.weather.openmeteo

import io.github.glandais.karoo.weather.domain.LocationForecast
import io.github.glandais.karoo.weather.domain.WeatherError
import io.github.glandais.karoo.weather.domain.WeatherSample
import io.github.glandais.karoo.weather.weather.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoParseTest {

    private val singlePoint = Fixtures.read("single_point.json")
    private val multiPoint = Fixtures.read("multi_point_25.json")
    private val minutely15 = Fixtures.read("minutely15.json")
    private val error400 = Fixtures.read("error_400.json")

    private fun parseFailure(block: () -> Unit): WeatherError {
        try {
            block()
        } catch (e: WeatherErrorException) {
            return e.error
        }
        throw AssertionError("expected a WeatherErrorException")
    }

    @Test
    fun `one point parses the object branch`() {
        val forecasts = OpenMeteoParser.parseBatch(singlePoint, expectedPoints = 1)
        assertEquals(1, forecasts.size)
        val here = forecasts.single()
        assertEquals(48.84, here.lat, 1e-6)
        assertEquals(2.3599997, here.lon, 1e-6)
        assertEquals(46.0, here.elevation!!, 1e-6)
        assertEquals(12, here.hourly.size)
        assertNull("request A carries no current block", here.current)
        assertTrue(here.minutely15.isEmpty())

        val first = here.hourly.first()
        assertEquals(1_787_778_000L, first.time)
        assertEquals(25.6, first.temp, 1e-9)
        assertEquals(3.42, first.windSpeed, 1e-9)
        assertEquals(6.90, first.windGusts, 1e-9)
        assertEquals(83.0, first.windDir, 1e-9)
        assertEquals(0.0, first.precip, 1e-9)
        assertEquals(0, first.precipProb)
        assertEquals(3, first.wmoCode)
        assertEquals(false, first.isDay)
        assertNull(first.apparentTemp)
    }

    @Test
    fun `the hourly series is ascending and carries the wet hour`() {
        val here = OpenMeteoParser.parseBatch(singlePoint, 1).single()
        val times = here.hourly.map { it.time }
        assertEquals(times.sorted(), times)
        val wettest = here.hourly.maxByOrNull { it.precip }!!
        assertEquals(6.90, wettest.precip, 1e-9)
        assertEquals(95, wettest.wmoCode)
        assertEquals(65, wettest.precipProb)
    }

    @Test
    fun `twenty five points parse the array branch and zip positionally`() {
        val forecasts = OpenMeteoParser.parseBatch(multiPoint, expectedPoints = 25)
        assertEquals(25, forecasts.size)
        forecasts.forEach { assertEquals(12, it.hourly.size) }
        // Latitudes were requested ascending; the response preserves request order.
        val lats = forecasts.map { it.lat }
        assertEquals(lats.sorted(), lats)
        assertTrue(forecasts.first().lat < forecasts.last().lat)
    }

    @Test
    fun `an array body with the wrong length is a parse error`() {
        val error = parseFailure { OpenMeteoParser.parseBatch(multiPoint, expectedPoints = 24) }
        assertTrue(error is WeatherError.Parse)
        assertTrue((error as WeatherError.Parse).detail.contains("expected 24"))
        assertEquals(false, error.retryable)
    }

    @Test
    fun `an object body parsed as an array is a parse error`() {
        val error = parseFailure { OpenMeteoParser.parseBatch(singlePoint, expectedPoints = 25) }
        assertTrue(error is WeatherError.Parse)
    }

    @Test
    fun `malformed json is a parse error`() {
        assertTrue(parseFailure { OpenMeteoParser.parseBatch("not json", 1) } is WeatherError.Parse)
        assertTrue(
            parseFailure { OpenMeteoParser.parseDetail("{\"hourly\":") } is WeatherError.Parse
        )
    }

    @Test
    fun `an api error body is a parse error carrying the reason`() {
        val error = parseFailure { OpenMeteoParser.parseDetail(error400) }
        assertTrue(error is WeatherError.Parse)
        assertTrue((error as WeatherError.Parse).detail.contains("api_error"))
    }

    @Test
    fun `request B parses current, nowcast and the apparent temperature series`() {
        val detail = OpenMeteoParser.parseDetail(minutely15)
        assertNotNull(detail.current)
        val current = detail.current!!
        assertEquals(1_787_779_800L, current.time)
        assertEquals(25.2, current.temp, 1e-9)
        assertEquals(25.1, current.apparentTemp!!, 1e-9)
        assertEquals(3.45, current.windSpeed, 1e-9)
        assertEquals(7.10, current.windGusts, 1e-9)
        assertEquals(80.0, current.windDir, 1e-9)
        assertEquals(3, current.wmoCode)
        assertEquals(100, current.cloudCover)
        assertEquals(false, current.isDay)
        assertNull("current carries no probability", current.precipProb)

        assertEquals(8, detail.minutely15.size)
        assertEquals(1_787_779_800L, detail.minutely15.first().time)
        assertEquals(900, detail.minutely15.first().durationSec)
        assertEquals(0.0, detail.minutely15.first().mm, 1e-9)
        assertEquals(1, detail.minutely15.last().probability)

        assertEquals(12, detail.hourly.size)
        assertEquals(25.6, detail.hourly.first().apparentTemp!!, 1e-9)
    }

    @Test
    fun `merge joins on time when the two responses are aligned`() {
        val batch = OpenMeteoParser.parseBatch(singlePoint, 1).single()
        val detail = OpenMeteoParser.parseDetail(minutely15)
        val merged = OpenMeteoParser.mergeDetailInto(batch, detail)

        assertEquals(batch.hourly.size, merged.hourly.size)
        assertEquals(25.6, merged.hourly.first().apparentTemp!!, 1e-9)
        // The hourly values from request A survive untouched.
        assertEquals(batch.hourly.first().temp, merged.hourly.first().temp, 1e-9)
        assertEquals(detail.current, merged.current)
        assertEquals(detail.minutely15, merged.minutely15)
    }

    @Test
    fun `merge shifts nothing when the two responses are one hour apart`() {
        val t = 1_700_000_000L
        val hourly =
            (0 until 3).map { i ->
                WeatherSample(
                    time = t + i * 3_600L,
                    temp = 10.0 + i,
                    windSpeed = 1.0,
                    windGusts = 2.0,
                    windDir = 0.0,
                    precip = 0.0,
                    wmoCode = 0,
                    isDay = true,
                )
            }
        val batch = LocationForecast(lat = 1.0, lon = 2.0, hourly = hourly)
        val detailHourly =
            (1 until 4).map { i ->
                WeatherSample(
                    time = t + i * 3_600L,
                    temp = 0.0,
                    apparentTemp = 100.0 + i,
                    windSpeed = 0.0,
                    windGusts = 0.0,
                    windDir = 0.0,
                    precip = 0.0,
                    wmoCode = 0,
                    isDay = true,
                )
            }
        val detail = LocationForecast(lat = 1.0, lon = 2.0, hourly = detailHourly)

        val merged = OpenMeteoParser.mergeDetailInto(batch, detail)
        assertEquals(3, merged.hourly.size)
        assertNull("A's first hour has no B counterpart", merged.hourly[0].apparentTemp)
        assertEquals(101.0, merged.hourly[1].apparentTemp!!, 1e-9)
        assertEquals(102.0, merged.hourly[2].apparentTemp!!, 1e-9)
        // An index merge would have produced 101.0 at index 0 — the bug this guards against.
        assertEquals(11.0, merged.hourly[1].temp, 1e-9)
    }

    @Test
    fun `merge keeps A's data when B carries nothing`() {
        val batch = OpenMeteoParser.parseBatch(singlePoint, 1).single()
        val empty = LocationForecast(lat = 0.0, lon = 0.0)
        val merged = OpenMeteoParser.mergeDetailInto(batch, empty)
        assertEquals(batch.hourly, merged.hourly)
        assertNull(merged.current)
        assertTrue(merged.minutely15.isEmpty())
    }

    @Test
    fun `unknown keys and missing arrays are tolerated`() {
        val body =
            """
            {"latitude":1.0,"longitude":2.0,"something_new":{"a":1},
             "hourly":{"time":[100,200],"temperature_2m":[1.0,2.0]}}
            """
                .trimIndent()
        val forecast = OpenMeteoParser.parseBatch(body, 1).single()
        assertEquals(2, forecast.hourly.size)
        assertEquals(1.0, forecast.hourly[0].temp, 1e-9)
        assertEquals(0.0, forecast.hourly[0].windSpeed, 1e-9)
        assertEquals(0.0, forecast.hourly[0].windGusts, 1e-9)
        assertEquals(-1, forecast.hourly[0].wmoCode)
        assertEquals(true, forecast.hourly[0].isDay)
        assertNull(forecast.hourly[0].precipProb)
    }

    @Test
    fun `null entries inside an array are tolerated`() {
        val body =
            """
            {"latitude":1.0,"longitude":2.0,
             "hourly":{"time":[100,200],"temperature_2m":[1.0,null],
                       "wind_speed_10m":[3.0,4.0],"weather_code":[61,null]}}
            """
                .trimIndent()
        val forecast = OpenMeteoParser.parseBatch(body, 1).single()
        assertEquals(0.0, forecast.hourly[1].temp, 1e-9)
        assertEquals(61, forecast.hourly[0].wmoCode)
        assertEquals(-1, forecast.hourly[1].wmoCode)
        // No gust series: gusts fall back to the mean wind rather than to zero.
        assertEquals(3.0, forecast.hourly[0].windGusts, 1e-9)
    }
}
