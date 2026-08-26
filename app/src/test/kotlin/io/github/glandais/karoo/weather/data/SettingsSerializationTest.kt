package io.github.glandais.karoo.weather.data

import io.github.glandais.karoo.weather.domain.TempUnit
import io.github.glandais.karoo.weather.domain.WeatherSettings
import io.github.glandais.karoo.weather.domain.WindUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored record is one JSON string, so the codec is the whole migration story: an unknown key
 * must be ignored, a missing key must fall back to the data class default, and nothing may throw.
 */
class SettingsSerializationTest {

    @Test
    fun `round trip preserves every field`() {
        val settings =
            WeatherSettings(
                consentAccepted = true,
                tempUnit = TempUnit.FAHRENHEIT,
                windUnit = WindUnit.BEAUFORT,
                assumedSpeedKmh = 31,
                useMeasuredSpeed = false,
                refreshMinutes = 60,
                roundLocationKm = 5.0,
                mapLayerEnabled = false,
                rainAlertEnabled = true,
                viewRefreshMs = 4_000L,
                lastRefreshRequestedAt = 1_700_000_000_123L,
            )

        assertEquals(settings, SettingsCodec.decode(SettingsCodec.encode(settings)))
    }

    @Test
    fun `null unit overrides survive the round trip`() {
        val settings = WeatherSettings(tempUnit = null, windUnit = null)
        val decoded = SettingsCodec.decode(SettingsCodec.encode(settings))
        assertNull(decoded.tempUnit)
        assertNull(decoded.windUnit)
    }

    @Test
    fun `an added field is a free migration`() {
        // A record written by a build that did not know about mapLayerEnabled yet.
        val legacy = """{"consentAccepted":true,"refreshMinutes":15}"""
        val decoded = SettingsCodec.decode(legacy)

        assertTrue(decoded.consentAccepted)
        assertEquals(15, decoded.refreshMinutes)
        assertEquals(WeatherSettings().mapLayerEnabled, decoded.mapLayerEnabled)
        assertEquals(WeatherSettings().assumedSpeedKmh, decoded.assumedSpeedKmh)
    }

    @Test
    fun `a removed field is ignored rather than fatal`() {
        val future = """{"consentAccepted":true,"someFutureSetting":"yes"}"""
        assertTrue(SettingsCodec.decode(future).consentAccepted)
    }

    @Test
    fun `absent corrupt and blank records fall back to the defaults`() {
        assertEquals(WeatherSettings(), SettingsCodec.decode(null))
        assertEquals(WeatherSettings(), SettingsCodec.decode(""))
        assertEquals(WeatherSettings(), SettingsCodec.decode("   "))
        assertEquals(WeatherSettings(), SettingsCodec.decode("not json at all"))
        assertEquals(WeatherSettings(), SettingsCodec.decode("""{"refreshMinutes":"thirty"}"""))
    }

    @Test
    fun `the defaults constant decodes back to the defaults`() {
        assertEquals(WeatherSettings(), SettingsCodec.decode(WeatherSettings.DEFAULT_JSON))
    }

    @Test
    fun `assumed speed is clamped when converted to metres per second`() {
        assertEquals(22.0 / 3.6, WeatherSettings().assumedSpeedMs(), 1e-9)
        assertEquals(5.0 / 3.6, WeatherSettings(assumedSpeedKmh = 1).assumedSpeedMs(), 1e-9)
        assertEquals(60.0 / 3.6, WeatherSettings(assumedSpeedKmh = 900).assumedSpeedMs(), 1e-9)
    }
}
