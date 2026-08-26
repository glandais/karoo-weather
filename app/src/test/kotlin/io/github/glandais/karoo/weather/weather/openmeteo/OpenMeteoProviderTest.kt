package io.github.glandais.karoo.weather.weather.openmeteo

import io.github.glandais.karoo.weather.domain.GeoPoint
import io.github.glandais.karoo.weather.domain.HttpGateway
import io.github.glandais.karoo.weather.domain.HttpResult
import io.github.glandais.karoo.weather.domain.WeatherError
import io.github.glandais.karoo.weather.domain.WeatherRequest
import io.github.glandais.karoo.weather.weather.Fixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoProviderTest {

    private val singlePoint = Fixtures.read("single_point.json")
    private val multiPoint = Fixtures.read("multi_point_25.json")
    private val minutely15 = Fixtures.read("minutely15.json")

    /** Replies with [batch] to request A and [detail] to request B, recording every URL. */
    private class FakeGateway(
        private val batch: HttpResult,
        private val detail: HttpResult = HttpResult.Ok(200, "{}"),
    ) : HttpGateway {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()

        override suspend fun get(url: String, headers: Map<String, String>): HttpResult {
            urls += url
            this.headers += headers
            return if (url.contains("&current=")) detail else batch
        }
    }

    private fun provider(gateway: HttpGateway) = OpenMeteoProvider(gateway, USER_AGENT)

    private fun requestOf(vararg points: GeoPoint, nowcast: Boolean = true) =
        WeatherRequest(points = points.toList(), includeNowcast = nowcast)

    private fun errorOf(result: Result<*>): WeatherError {
        val cause = result.exceptionOrNull()
        assertTrue("expected a WeatherErrorException, got $cause", cause is WeatherErrorException)
        return (cause as WeatherErrorException).error
    }

    @Test
    fun `a successful cycle merges request B into index 0`() = runTest {
        val gateway = FakeGateway(HttpResult.Ok(200, singlePoint), HttpResult.Ok(200, minutely15))
        val result = provider(gateway).fetch(requestOf(HERE))

        val forecasts = result.getOrThrow()
        assertEquals(1, forecasts.size)
        assertNotNull(forecasts[0].current)
        assertEquals(8, forecasts[0].minutely15.size)
        assertEquals(25.6, forecasts[0].hourly.first().apparentTemp!!, 1e-9)

        assertEquals(2, gateway.urls.size)
        assertTrue(gateway.urls[0].startsWith(OpenMeteoUrl.BASE))
        assertTrue(gateway.urls[0].contains("&hourly=temperature_2m,"))
        assertTrue(gateway.urls[1].contains("&minutely_15="))
        gateway.headers.forEach { assertEquals(USER_AGENT, it["User-Agent"]) }
    }

    @Test
    fun `a twenty five point batch is returned in request order`() = runTest {
        val gateway = FakeGateway(HttpResult.Ok(200, multiPoint), HttpResult.Ok(200, minutely15))
        val points = (0 until 25).map { GeoPoint(48.85 + it * 0.02, 2.35 + it * 0.03) }
        val forecasts = provider(gateway).fetch(WeatherRequest(points = points)).getOrThrow()
        assertEquals(25, forecasts.size)
        assertNotNull("only index 0 is enriched", forecasts[0].current)
        assertNull(forecasts[1].current)
    }

    @Test
    fun `the nowcast request is skipped when not asked for`() = runTest {
        val gateway = FakeGateway(HttpResult.Ok(200, singlePoint))
        val forecasts = provider(gateway).fetch(requestOf(HERE, nowcast = false)).getOrThrow()
        assertEquals(1, gateway.urls.size)
        assertNull(forecasts[0].current)
    }

    @Test
    fun `429 becomes RateLimited`() = runTest {
        val result = provider(FakeGateway(HttpResult.Ok(429, "slow down"))).fetch(requestOf(HERE))
        val error = errorOf(result)
        assertTrue(error is WeatherError.RateLimited)
        assertTrue(error.retryable)
    }

    @Test
    fun `5xx becomes Server and is retryable`() = runTest {
        val result = provider(FakeGateway(HttpResult.Ok(503, "nope"))).fetch(requestOf(HERE))
        val error = errorOf(result)
        assertEquals(WeatherError.Server(503), error)
        assertTrue(error.retryable)
    }

    @Test
    fun `4xx becomes Client and is not retryable`() = runTest {
        val result = provider(FakeGateway(HttpResult.Ok(400, "bad"))).fetch(requestOf(HERE))
        val error = errorOf(result)
        assertEquals(WeatherError.Client(400), error)
        assertEquals(false, error.retryable)
        assertEquals(false, error.reducePoints)
    }

    @Test
    fun `a malformed body becomes Parse`() = runTest {
        val result = provider(FakeGateway(HttpResult.Ok(200, "<html>oops"))).fetch(requestOf(HERE))
        assertTrue(errorOf(result) is WeatherError.Parse)
    }

    @Test
    fun `an empty body becomes EmptyBody and asks for fewer points`() = runTest {
        val result = provider(FakeGateway(HttpResult.Ok(200, "   "))).fetch(requestOf(HERE))
        val error = errorOf(result)
        assertEquals(WeatherError.EmptyBody, error)
        assertTrue(error.reducePoints)
    }

    @Test
    fun `an oversize body becomes Oversize and asks for fewer points`() = runTest {
        val huge = "{" + " ".repeat(OpenMeteoProvider.MAX_BODY_BYTES) + "}"
        val result = provider(FakeGateway(HttpResult.Ok(200, huge))).fetch(requestOf(HERE))
        val error = errorOf(result)
        assertTrue(error is WeatherError.Oversize)
        assertEquals(huge.length, (error as WeatherError.Oversize).bytes)
        assertTrue(error.reducePoints)
    }

    @Test
    fun `a transport failure is passed through untouched`() = runTest {
        val result =
            provider(FakeGateway(HttpResult.Fail(WeatherError.NoConnection))).fetch(requestOf(HERE))
        assertEquals(WeatherError.NoConnection, errorOf(result))
    }

    @Test
    fun `a request B transport failure is non-fatal`() = runTest {
        val gateway =
            FakeGateway(HttpResult.Ok(200, singlePoint), HttpResult.Fail(WeatherError.Timeout))
        val forecasts = provider(gateway).fetch(requestOf(HERE)).getOrThrow()
        assertEquals(1, forecasts.size)
        assertEquals(12, forecasts[0].hourly.size)
        assertNull(forecasts[0].current)
        assertTrue(forecasts[0].minutely15.isEmpty())
        assertNull(forecasts[0].hourly.first().apparentTemp)
    }

    @Test
    fun `a request B parse failure is non-fatal`() = runTest {
        val gateway = FakeGateway(HttpResult.Ok(200, singlePoint), HttpResult.Ok(200, "not json"))
        val forecasts = provider(gateway).fetch(requestOf(HERE)).getOrThrow()
        assertEquals(1, forecasts.size)
        assertNull(forecasts[0].current)
    }

    @Test
    fun `a request B server error is non-fatal`() = runTest {
        val gateway = FakeGateway(HttpResult.Ok(200, singlePoint), HttpResult.Ok(500, "boom"))
        val forecasts = provider(gateway).fetch(requestOf(HERE)).getOrThrow()
        assertNull(forecasts[0].current)
    }

    @Test
    fun `the merge joins on time when A and B are one hour apart`() = runTest {
        val t = 1_700_000_000L
        val batchBody =
            """
            {"latitude":1.0,"longitude":2.0,
             "hourly":{"time":[$t,${t + 3600},${t + 7200}],
                       "temperature_2m":[10.0,11.0,12.0],
                       "wind_speed_10m":[1.0,1.0,1.0],
                       "weather_code":[0,0,0]}}
            """
                .trimIndent()
        val detailBody =
            """
            {"latitude":1.0,"longitude":2.0,
             "current":{"time":$t,"temperature_2m":9.0,"weather_code":0,"is_day":1},
             "hourly":{"time":[${t + 3600},${t + 7200},${t + 10800}],
                       "apparent_temperature":[101.0,102.0,103.0]}}
            """
                .trimIndent()
        val gateway = FakeGateway(HttpResult.Ok(200, batchBody), HttpResult.Ok(200, detailBody))
        val here = provider(gateway).fetch(requestOf(GeoPoint(1.0, 2.0))).getOrThrow().single()

        assertEquals(3, here.hourly.size)
        assertNull(here.hourly[0].apparentTemp)
        assertEquals(101.0, here.hourly[1].apparentTemp!!, 1e-9)
        assertEquals(102.0, here.hourly[2].apparentTemp!!, 1e-9)
        assertEquals(11.0, here.hourly[1].temp, 1e-9)
        assertEquals(9.0, here.current!!.temp, 1e-9)
    }

    @Test
    fun `the provider identifies itself as open-meteo`() {
        assertEquals("open-meteo", provider(FakeGateway(HttpResult.Ok(200, "{}"))).id)
    }

    private companion object {
        val HERE = GeoPoint(48.85, 2.35)
        const val USER_AGENT = "karoo-weather/1.0 (+https://github.com/glandais/karoo-weather)"
    }
}
