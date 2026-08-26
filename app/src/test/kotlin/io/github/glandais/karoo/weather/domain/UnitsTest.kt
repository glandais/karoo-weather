package io.github.glandais.karoo.weather.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

    private val eps = 1e-9

    @Test
    fun celsiusToFahrenheit() {
        val f = Units(temp = TempUnit.FAHRENHEIT)
        assertEquals(32.0, f.temp(0.0), eps)
        assertEquals(212.0, f.temp(100.0), eps)
        assertEquals(-40.0, f.temp(-40.0), eps)
        assertEquals(71.6, f.temp(22.0), 1e-9)
    }

    @Test
    fun celsiusIsIdentityInCelsius() {
        assertEquals(22.0, Units(temp = TempUnit.CELSIUS).temp(22.0), eps)
    }

    @Test
    fun windConversions() {
        assertEquals(10.0, Units(wind = WindUnit.MS).wind(10.0), eps)
        assertEquals(36.0, Units(wind = WindUnit.KMH).wind(10.0), eps)
        assertEquals(22.36936, Units(wind = WindUnit.MPH).wind(10.0), 1e-6)
        assertEquals(19.43844, Units(wind = WindUnit.KNOTS).wind(10.0), 1e-6)
    }

    @Test
    fun distanceConversions() {
        assertEquals(1.0, Units(distance = DistanceUnit.KM).distance(1000.0), eps)
        assertEquals(0.621371, Units(distance = DistanceUnit.MILES).distance(1000.0), 1e-9)
    }

    @Test
    fun beaufortBoundaries() {
        val boundaries = listOf(0.3, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7)
        // Just below each boundary the force is the index; exactly at it the force steps up by one.
        boundaries.forEachIndexed { index, boundary ->
            assertEquals(
                "just below $boundary",
                index.toDouble(),
                Units.beaufort(boundary - 1e-6),
                eps,
            )
            assertEquals("at $boundary", (index + 1).toDouble(), Units.beaufort(boundary), eps)
        }
        assertEquals(0.0, Units.beaufort(0.0), eps)
        assertEquals(12.0, Units.beaufort(100.0), eps)
    }

    @Test
    fun beaufortGoesThroughTheWindAccessor() {
        assertEquals(5.0, Units(wind = WindUnit.BEAUFORT).wind(10.0), eps)
    }

    @Test
    fun settingsClampAssumedSpeed() {
        assertEquals(5 / 3.6, WeatherSettings(assumedSpeedKmh = 1).assumedSpeedMs(), eps)
        assertEquals(60 / 3.6, WeatherSettings(assumedSpeedKmh = 999).assumedSpeedMs(), eps)
        assertEquals(22 / 3.6, WeatherSettings().assumedSpeedMs(), eps)
    }

    @Test
    fun defaultSettingsSerialiseAndRoundTrip() {
        val json = WeatherSettings.DEFAULT_JSON
        val decoded =
            kotlinx.serialization.json
                .Json { ignoreUnknownKeys = true }
                .decodeFromString<WeatherSettings>(json)
        assertEquals(WeatherSettings(), decoded)
    }

    @Test
    fun windToDirIsTheOppositeOfWindDir() {
        val s =
            WeatherSample(
                time = 0,
                temp = 0.0,
                windSpeed = 0.0,
                windGusts = 0.0,
                windDir = 350.0,
                precip = 0.0,
                wmoCode = 0,
                isDay = true,
            )
        assertEquals(170.0, s.windToDir, eps)
        assertEquals(180.0, s.copy(windDir = 0.0).windToDir, eps)
    }

    @Test
    fun errorsThatReducePoints() {
        assertEquals(true, WeatherError.Oversize(1).reducePoints)
        assertEquals(true, WeatherError.EmptyBody.reducePoints)
        assertEquals(false, WeatherError.Timeout.reducePoints)
        assertEquals(false, WeatherError.Parse("x").reducePoints)
        assertEquals(false, WeatherError.Client(400).retryable)
        assertEquals(true, WeatherError.Server(500).retryable)
    }

    @Test
    fun dataTypeIds() {
        assertEquals(5, DataTypeIds.ALL.size)
        assertEquals(DataTypeIds.ALL.size, DataTypeIds.ALL.toSet().size)
        assertEquals("TYPE_EXT::karoo-weather::wind", DataTypeIds.full(DataTypeIds.WIND))
    }
}
