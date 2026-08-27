package io.github.glandais.karoo.weather.datatypes

import io.github.glandais.karoo.weather.datatypes.views.BarChartBuilder
import io.github.glandais.karoo.weather.datatypes.views.FieldChrome
import io.github.glandais.karoo.weather.datatypes.views.RouteStripLayout
import io.github.glandais.karoo.weather.datatypes.views.StripBitmapBuilder
import io.github.glandais.karoo.weather.domain.PrecipBucket
import io.hammerhead.karooext.models.ViewConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `route-forecast` / `rain-next-hour` layout decisions, and the pure chart helpers. */
class StripLayoutTest {

    private fun config(gridSize: Pair<Int, Int>, viewSize: Pair<Int, Int>, textSize: Int = 32) =
        ViewConfig(gridSize = gridSize, viewSize = viewSize, textSize = textSize)

    // ---- rows ----------------------------------------------------------------------------------

    @Test
    fun `a narrow field is a single stacked column with no arrow row`() {
        val rows = RouteStripLayout.rowsFor(config(30 to 30, 240 to 400))
        assertEquals(StripBitmapBuilder.Rows(true, true, false, true, false), rows)
        assertEquals(3, rows.count)
        assertEquals(1, RouteStripLayout.maxColumnsFor(config(30 to 30, 240 to 400)))
        assertEquals(1, RouteStripLayout.maxColumnsFor(config(30 to 60, 240 to 800)))
    }

    @Test
    fun `the strip grid drops the arrow and caps at three columns`() {
        val cfg = config(60 to 15, 480 to 200)
        val rows = RouteStripLayout.rowsFor(cfg)
        assertFalse(rows.arrow)
        assertFalse(rows.eta)
        assertEquals(3, RouteStripLayout.maxColumnsFor(cfg))
        assertEquals(3, FieldChrome.columnsFor(cfg.viewSize, RouteStripLayout.maxColumnsFor(cfg)))
    }

    @Test
    fun `the wide grid adds the arrow row but not the ETA row`() {
        val cfg = config(60 to 30, 480 to 400)
        val rows = RouteStripLayout.rowsFor(cfg)
        assertTrue(rows.arrow)
        assertFalse("ETA at (60,30) would put both labels at the 10 sp floor", rows.eta)
        assertEquals(4, rows.count)
        assertEquals(5, RouteStripLayout.maxColumnsFor(cfg))
        assertEquals(5, FieldChrome.columnsFor(cfg.viewSize, RouteStripLayout.maxColumnsFor(cfg)))
    }

    @Test
    fun `the full page adds the ETA row and allows six columns before viewSize narrows it`() {
        val cfg = config(60 to 60, 480 to 800, textSize = 48)
        val rows = RouteStripLayout.rowsFor(cfg)
        assertTrue(rows.eta)
        assertEquals(5, rows.count)
        assertEquals(6, RouteStripLayout.maxColumnsFor(cfg))
        // 480 px only fits five, exactly as DESIGN §3.0 predicts.
        assertEquals(5, FieldChrome.columnsFor(cfg.viewSize, RouteStripLayout.maxColumnsFor(cfg)))
    }

    // ---- chart helpers -------------------------------------------------------------------------

    private fun bucket(mm: Double, durationSec: Int = 900, time: Long = 0L) =
        PrecipBucket(time = time, durationSec = durationSec, mm = mm, probability = null)

    @Test
    fun `hourly buckets are normalised onto the fifteen minute rain ramp`() {
        assertEquals(0.4, BarChartBuilder.mmPerQuarterHour(bucket(0.4)), 1e-9)
        assertEquals(0.4, BarChartBuilder.mmPerQuarterHour(bucket(1.6, durationSec = 3600)), 1e-9)
        // A zero duration must not divide by zero.
        assertEquals(0.4, BarChartBuilder.mmPerQuarterHour(bucket(0.4, durationSec = 0)), 1e-9)
    }

    @Test
    fun `totalMm sums to one decimal`() {
        assertEquals(0.0, BarChartBuilder.totalMm(emptyList()), 1e-9)
        assertEquals(
            1.4,
            BarChartBuilder.totalMm(listOf(bucket(0.35), bucket(0.7), bucket(0.36))),
            1e-9,
        )
    }

    @Test
    fun `firstWetTime finds the first bucket at or above the dry threshold`() {
        assertNull(BarChartBuilder.firstWetTime(emptyList()))
        assertNull(
            BarChartBuilder.firstWetTime(listOf(bucket(0.0, time = 10), bucket(0.05, time = 20)))
        )
        assertEquals(
            30L,
            BarChartBuilder.firstWetTime(
                    listOf(bucket(0.0, time = 10), bucket(0.09, time = 20), bucket(0.1, time = 30))
                )!!
                .toLong(),
        )
        // An hourly bucket must be judged on its normalised rate, not its raw millimetres.
        assertNull(BarChartBuilder.firstWetTime(listOf(bucket(0.3, durationSec = 3600, time = 40))))
    }
}
