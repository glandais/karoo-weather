package io.github.glandais.karoo.weather.extension

import io.github.glandais.karoo.weather.domain.PrecipBucket
import io.github.glandais.karoo.weather.extension.RainAlerter.Companion.COOLDOWN_SEC
import io.github.glandais.karoo.weather.extension.RainAlerter.Companion.rainStartingIn
import io.github.glandais.karoo.weather.extension.RainAlerter.Companion.shouldAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainAlerterTest {

    private val now = 1_700_000_000L

    private fun bucket(offsetSec: Long, mm: Double) =
        PrecipBucket(time = now + offsetSec, durationSec = 900, mm = mm)

    /** Bucket containing `now` (starts 180 s ago), then three future quarter-hours. */
    private fun series(vararg futureMm: Double, currentMm: Double = 0.0): List<PrecipBucket> =
        listOf(bucket(-180L, currentMm)) +
            futureMm.mapIndexed { index, mm -> bucket(720L + index * 900L, mm) }

    @Test
    fun `rain in 12 minutes is detected`() {
        assertEquals(12, rainStartingIn(series(0.5), now))
    }

    @Test
    fun `a dry series reports nothing`() {
        assertNull(rainStartingIn(series(0.0, 0.0, 0.1), now))
    }

    @Test
    fun `a trace below the wet threshold is not rain`() {
        assertNull(rainStartingIn(series(0.19), now))
        assertEquals(12, rainStartingIn(series(RainAlerter.WET_MM), now))
    }

    @Test
    fun `already raining is not a rain-starting event`() {
        assertNull(rainStartingIn(series(0.8, currentMm = 1.0), now))
    }

    @Test
    fun `rain beyond the lookahead is ignored`() {
        val beyond = listOf(bucket(-180L, 0.0), bucket(RainAlerter.LOOKAHEAD_SEC + 60L, 2.0))
        assertNull(rainStartingIn(beyond, now))

        val atEdge = listOf(bucket(-180L, 0.0), bucket(RainAlerter.LOOKAHEAD_SEC, 2.0))
        assertEquals(30, rainStartingIn(atEdge, now))
    }

    @Test
    fun `past buckets are skipped`() {
        val withPast =
            listOf(
                bucket(-5_400L, 5.0),
                bucket(-1_800L, 5.0),
                bucket(-180L, 0.0),
                bucket(600L, 1.0),
            )
        assertEquals(10, rainStartingIn(withPast, now))
    }

    @Test
    fun `an empty series reports nothing`() {
        assertNull(rainStartingIn(emptyList(), now))
    }

    @Test
    fun `unordered buckets are handled`() {
        val shuffled = listOf(bucket(1_620L, 0.0), bucket(720L, 0.9), bucket(-180L, 0.0))
        assertEquals(12, rainStartingIn(shuffled, now))
    }

    @Test
    fun `minutes are rounded up and never below one`() {
        assertEquals(1, rainStartingIn(listOf(bucket(-180L, 0.0), bucket(30L, 1.0)), now))
        assertEquals(2, rainStartingIn(listOf(bucket(-180L, 0.0), bucket(61L, 1.0)), now))
    }

    @Test
    fun `alert fires when enabled and recording with no previous alert`() {
        assertTrue(shouldAlert(12, null, now, enabled = true, recording = true))
    }

    @Test
    fun `no rain means no alert`() {
        assertFalse(shouldAlert(null, null, now, enabled = true, recording = true))
    }

    @Test
    fun `disabled blocks the alert`() {
        assertFalse(shouldAlert(12, null, now, enabled = false, recording = true))
    }

    @Test
    fun `not recording blocks the alert`() {
        assertFalse(shouldAlert(12, null, now, enabled = true, recording = false))
    }

    @Test
    fun `cooldown blocks a second alert`() {
        assertFalse(shouldAlert(12, now - COOLDOWN_SEC + 1, now, enabled = true, recording = true))
    }

    @Test
    fun `cooldown expires exactly at the boundary`() {
        assertTrue(shouldAlert(12, now - COOLDOWN_SEC, now, enabled = true, recording = true))
        assertTrue(shouldAlert(12, now - COOLDOWN_SEC - 1, now, enabled = true, recording = true))
    }
}
