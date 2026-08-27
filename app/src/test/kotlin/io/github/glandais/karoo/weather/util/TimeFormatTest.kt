package io.github.glandais.karoo.weather.util

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    private val utc = ZoneId.of("UTC")
    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun `clock formats 24 hour with leading zeros`() {
        // 2024-01-01T09:05:00Z
        assertEquals("09:05", TimeFormat.clock(1_704_099_900L, utc))
    }

    @Test
    fun `clock respects the zone`() {
        // Same instant, Paris is UTC+1 in January.
        assertEquals("10:05", TimeFormat.clock(1_704_099_900L, paris))
    }

    @Test
    fun `hour is the two digit hour only`() {
        assertEquals("09", TimeFormat.hour(1_704_099_900L, utc))
        assertEquals("00", TimeFormat.hour(1_704_067_200L, utc))
    }

    @Test
    fun `ago is sub minute below sixty seconds`() {
        assertEquals("<1 min", TimeFormat.ago(1_000L, 1_000L))
        assertEquals("<1 min", TimeFormat.ago(1_059L, 1_000L))
    }

    @Test
    fun `ago switches unit at every boundary`() {
        assertEquals("1 min", TimeFormat.ago(1_060L, 1_000L))
        assertEquals("59 min", TimeFormat.ago(1_000L + 3_599L, 1_000L))
        assertEquals("1 h", TimeFormat.ago(1_000L + 3_600L, 1_000L))
        assertEquals("23 h", TimeFormat.ago(1_000L + 86_399L, 1_000L))
        assertEquals("1 d", TimeFormat.ago(1_000L + 86_400L, 1_000L))
    }

    @Test
    fun `ago clamps a future timestamp instead of going negative`() {
        assertEquals("<1 min", TimeFormat.ago(1_000L, 9_999L))
    }

    @Test
    fun `minutesBetween truncates and never goes negative`() {
        assertEquals(0L, TimeFormat.minutesBetween(0L, 59L))
        assertEquals(1L, TimeFormat.minutesBetween(0L, 60L))
        assertEquals(1L, TimeFormat.minutesBetween(0L, 119L))
        assertEquals(0L, TimeFormat.minutesBetween(500L, 0L))
    }
}
