package io.github.glandais.karoo.weather.data

import io.github.glandais.karoo.weather.domain.TempUnit
import io.github.glandais.karoo.weather.domain.WeatherError
import io.github.glandais.karoo.weather.domain.WeatherRequest
import io.github.glandais.karoo.weather.domain.WeatherSettings
import io.github.glandais.karoo.weather.domain.WindUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPolicyTest {

    @Test
    fun `backoff ladder is 30 60 120 300 then flat`() {
        assertEquals(30L, RefreshPolicy.backoffSec(1, recording = false))
        assertEquals(60L, RefreshPolicy.backoffSec(2, recording = false))
        assertEquals(120L, RefreshPolicy.backoffSec(3, recording = false))
        assertEquals(300L, RefreshPolicy.backoffSec(4, recording = false))
        assertEquals(900L, RefreshPolicy.backoffSec(5, recording = false))
        assertEquals(900L, RefreshPolicy.backoffSec(40, recording = false))
    }

    @Test
    fun `backoff stays at 300 while recording`() {
        assertEquals(300L, RefreshPolicy.backoffSec(5, recording = true))
        assertEquals(300L, RefreshPolicy.backoffSec(99, recording = true))
        // The ladder itself is identical while recording.
        assertEquals(30L, RefreshPolicy.backoffSec(1, recording = true))
    }

    @Test
    fun `attempt zero is treated as the first rung`() {
        assertEquals(30L, RefreshPolicy.backoffSec(0, recording = false))
        assertEquals(30L, RefreshPolicy.backoffSec(-3, recording = false))
    }

    @Test
    fun `min gap coalesces a burst of triggers into one request`() {
        assertTrue(RefreshPolicy.shouldFetch(nowSec = 1_000L, lastFetchSec = null))
        assertFalse(RefreshPolicy.shouldFetch(nowSec = 1_000L, lastFetchSec = 1_000L))
        assertFalse(RefreshPolicy.shouldFetch(nowSec = 1_059L, lastFetchSec = 1_000L))
        assertTrue(RefreshPolicy.shouldFetch(nowSec = 1_060L, lastFetchSec = 1_000L))
        assertEquals(60L, RefreshPolicy.MIN_GAP_SEC)
    }

    @Test
    fun `interval halves while recording with a 900 second floor`() {
        val thirty = WeatherSettings(refreshMinutes = 30)
        assertEquals(1_800L, RefreshPolicy.intervalSec(thirty, recording = false))
        assertEquals(900L, RefreshPolicy.intervalSec(thirty, recording = true))

        val sixty = WeatherSettings(refreshMinutes = 60)
        assertEquals(3_600L, RefreshPolicy.intervalSec(sixty, recording = false))
        assertEquals(1_800L, RefreshPolicy.intervalSec(sixty, recording = true))

        val fifteen = WeatherSettings(refreshMinutes = 15)
        assertEquals(900L, RefreshPolicy.intervalSec(fifteen, recording = false))
        assertEquals(900L, RefreshPolicy.intervalSec(fifteen, recording = true))
    }

    @Test
    fun `progress bucket advances every third of the spacing`() {
        val spacing = 2_000.0
        assertEquals(0, RefreshPolicy.progressBucket(0.0, spacing))
        assertEquals(0, RefreshPolicy.progressBucket(5_999.0, spacing))
        assertEquals(1, RefreshPolicy.progressBucket(6_000.0, spacing))
        assertEquals(1, RefreshPolicy.progressBucket(11_999.0, spacing))
        assertEquals(2, RefreshPolicy.progressBucket(12_000.0, spacing))
        assertEquals(3, RefreshPolicy.progressBucket(20_000.0, spacing))
    }

    @Test
    fun `progress bucket is total on degenerate input`() {
        assertEquals(0, RefreshPolicy.progressBucket(1_000.0, 0.0))
        assertEquals(0, RefreshPolicy.progressBucket(Double.NaN, 1_000.0))
        assertEquals(0, RefreshPolicy.progressBucket(1_000.0, Double.NaN))
    }

    @Test
    fun `point budget halves on oversize and empty body only`() {
        assertEquals(12, RefreshPolicy.nextPointBudget(25, WeatherError.Oversize(120_000)))
        assertEquals(6, RefreshPolicy.nextPointBudget(12, WeatherError.EmptyBody))
        assertEquals(3, RefreshPolicy.nextPointBudget(6, WeatherError.EmptyBody))
        assertEquals(2, RefreshPolicy.nextPointBudget(3, WeatherError.EmptyBody))
        assertEquals(2, RefreshPolicy.nextPointBudget(2, WeatherError.EmptyBody))
    }

    @Test
    fun `point budget resets on success and on any other error`() {
        assertEquals(WeatherRequest.MAX_POINTS, RefreshPolicy.nextPointBudget(2, null))
        assertEquals(
            WeatherRequest.MAX_POINTS,
            RefreshPolicy.nextPointBudget(2, WeatherError.Server(503)),
        )
        assertEquals(
            WeatherRequest.MAX_POINTS,
            RefreshPolicy.nextPointBudget(2, WeatherError.Parse("bad")),
        )
    }

    @Test
    fun `refresh key ignores settings that do not change the request`() {
        val base = key()
        assertEquals(base, key())

        // View-only preferences must never cost an HTTP round trip.
        val settings =
            WeatherSettings(
                mapLayerEnabled = false,
                rainAlertEnabled = true,
                viewRefreshMs = 5_000L,
                tempUnit = TempUnit.FAHRENHEIT,
                windUnit = WindUnit.MPH,
            )
        assertEquals(base, key(settings))
    }

    @Test
    fun `refresh key changes when the request changes`() {
        val base = key()
        assertNotEquals(base, key(lat = 45.1))
        assertNotEquals(base, key(routeKey = "other"))
        assertNotEquals(base, key(progressBucket = 1))
        assertNotEquals(base, key(WeatherSettings(refreshMinutes = 60)))
        assertNotEquals(base, key(WeatherSettings(roundLocationKm = 1.0)))
        assertNotEquals(base, key(WeatherSettings(lastRefreshRequestedAt = 42L)))
    }

    private fun key(
        settings: WeatherSettings = WeatherSettings(),
        lat: Double? = 45.0,
        lon: Double? = 5.0,
        routeKey: String? = "route-1",
        progressBucket: Int = 0,
    ) =
        RefreshKey(
            consentAccepted = settings.consentAccepted,
            roundLocationKm = settings.roundLocationKm,
            refreshMinutes = settings.refreshMinutes,
            assumedSpeedKmh = settings.assumedSpeedKmh,
            useMeasuredSpeed = settings.useMeasuredSpeed,
            lastRefreshRequestedAt = settings.lastRefreshRequestedAt,
            lat = lat,
            lon = lon,
            routeKey = routeKey,
            progressBucket = progressBucket,
        )
}
