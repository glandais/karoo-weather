package io.github.glandais.karoo.weather.weather.openmeteo

import io.github.glandais.karoo.weather.domain.GeoPoint
import io.github.glandais.karoo.weather.weather.Fixtures
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoUrlTest {

    private val hourly =
        "temperature_2m,precipitation,precipitation_probability,weather_code," +
            "wind_speed_10m,wind_direction_10m,wind_gusts_10m,is_day"

    private val current =
        "temperature_2m,apparent_temperature,precipitation,weather_code,cloud_cover," +
            "wind_speed_10m,wind_direction_10m,wind_gusts_10m,is_day"

    private fun batchPoints(n: Int): List<GeoPoint> =
        (0 until n).map { GeoPoint(48.85 + it * 0.02, 2.35 + it * 0.03) }

    @Test
    fun `hourly and current variable lists match the architecture`() {
        assertEquals(8, OpenMeteoUrl.HOURLY_VARS.size)
        assertEquals(9, OpenMeteoUrl.CURRENT_VARS.size)
        assertEquals(hourly, OpenMeteoUrl.HOURLY_VARS.joinToString(","))
        assertEquals(current, OpenMeteoUrl.CURRENT_VARS.joinToString(","))
    }

    @Test
    fun `single point request A is exact`() {
        val expected =
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=48.8500&longitude=2.3500" +
                "&hourly=$hourly" +
                "&forecast_hours=12&past_hours=0" +
                OpenMeteoUrl.UNIT_PARAMS
        assertEquals(expected, OpenMeteoUrl.routeBatch(listOf(GeoPoint(48.85, 2.35))))
    }

    @Test
    fun `twenty five point request A is exact`() {
        val points = batchPoints(25)
        val lats = points.joinToString(",") { String.format(Locale.US, "%.4f", it.lat) }
        val lons = points.joinToString(",") { String.format(Locale.US, "%.4f", it.lon) }
        val expected =
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lats&longitude=$lons" +
                "&hourly=$hourly" +
                "&forecast_hours=12&past_hours=0" +
                OpenMeteoUrl.UNIT_PARAMS
        assertEquals(expected, OpenMeteoUrl.routeBatch(points))
    }

    @Test
    fun `request B is exact`() {
        val expected =
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=48.8500&longitude=2.3500" +
                "&current=$current" +
                "&minutely_15=precipitation,precipitation_probability" +
                "&forecast_minutely_15=8" +
                "&hourly=apparent_temperature" +
                "&forecast_hours=12" +
                OpenMeteoUrl.UNIT_PARAMS
        assertEquals(expected, OpenMeteoUrl.hereDetail(GeoPoint(48.85, 2.35)))
    }

    @Test
    fun `coordinates never use a locale decimal comma`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val url = OpenMeteoUrl.routeBatch(listOf(GeoPoint(48.85, 2.35)))
            assertTrue(url, url.contains("latitude=48.8500"))
            assertTrue(url, url.contains("longitude=2.3500"))
            assertFalse(url, url.contains("48,8500"))
            assertEquals(OpenMeteoUrl.hereDetail(GeoPoint(-3.5, -70.25)).contains("-3,5000"), false)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `southern and western coordinates keep their sign`() {
        val url = OpenMeteoUrl.routeBatch(listOf(GeoPoint(-33.8688, -151.2093)))
        assertTrue(url, url.contains("latitude=-33.8688"))
        assertTrue(url, url.contains("longitude=-151.2093"))
    }

    @Test
    fun `empty point list is rejected`() {
        try {
            OpenMeteoUrl.routeBatch(emptyList())
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // as designed
        }
    }

    @Test
    fun `estimate covers the committed 25 point fixture`() {
        val fixture = Fixtures.read("multi_point_25.json")
        assertTrue(
            "estimate must not be optimistic",
            OpenMeteoUrl.estimateResponseBytes(25, 8, 12) >= fixture.length,
        )
    }

    @Test
    fun `estimate covers the committed single point fixture`() {
        val fixture = Fixtures.read("single_point.json")
        assertTrue(OpenMeteoUrl.estimateResponseBytes(1, 8, 12) >= fixture.length)
    }

    @Test
    fun `estimate is monotonic in every argument`() {
        for (n in 1 until 40) {
            assertTrue(
                OpenMeteoUrl.estimateResponseBytes(n, 8, 12) <
                    OpenMeteoUrl.estimateResponseBytes(n + 1, 8, 12)
            )
        }
        for (v in 1 until 12) {
            assertTrue(
                OpenMeteoUrl.estimateResponseBytes(25, v, 12) <
                    OpenMeteoUrl.estimateResponseBytes(25, v + 1, 12)
            )
        }
        for (h in 1 until 24) {
            assertTrue(
                OpenMeteoUrl.estimateResponseBytes(25, 8, h) <
                    OpenMeteoUrl.estimateResponseBytes(25, 8, h + 1)
            )
        }
    }

    @Test
    fun `estimate formula matches the documented calibration`() {
        assertEquals(48_900, OpenMeteoUrl.estimateResponseBytes(25, 8, 12))
        assertEquals(450, OpenMeteoUrl.estimateResponseBytes(0, 8, 12))
    }

    @Test
    fun `maxPointsWithin is the largest fitting count`() {
        val max = OpenMeteoUrl.maxPointsWithin(OpenMeteoUrl.SIZE_BUDGET_BYTES, 8, 12)
        assertTrue(OpenMeteoUrl.estimateResponseBytes(max, 8, 12) <= OpenMeteoUrl.SIZE_BUDGET_BYTES)
        assertTrue(
            OpenMeteoUrl.estimateResponseBytes(max + 1, 8, 12) > OpenMeteoUrl.SIZE_BUDGET_BYTES
        )
        assertTrue("the 25-point budget must fit", max >= 25)
    }

    @Test
    fun `maxPointsWithin never drops below one`() {
        assertEquals(1, OpenMeteoUrl.maxPointsWithin(0, 8, 12))
        assertEquals(1, OpenMeteoUrl.maxPointsWithin(500, 8, 24))
    }
}
