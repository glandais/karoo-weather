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
        assertNull(BarChartBuilder.firstWetTime(emptyList(), nowSec = 0L))
        assertNull(
            BarChartBuilder.firstWetTime(
                listOf(bucket(0.0, time = 900), bucket(0.05, time = 1_800)),
                nowSec = 0L,
            )
        )
        assertEquals(
            BarChartBuilder.WetStart.At(2_700L),
            BarChartBuilder.firstWetTime(
                listOf(
                    bucket(0.0, time = 900),
                    bucket(0.09, time = 1_800),
                    bucket(0.1, time = 2_700),
                ),
                nowSec = 0L,
            ),
        )
        // An hourly bucket must be judged on its normalised rate, not its raw millimetres.
        assertNull(
            BarChartBuilder.firstWetTime(
                listOf(bucket(0.3, durationSec = 3600, time = 900)),
                nowSec = 0L,
            )
        )
    }

    @Test
    fun `a wet bucket the rider is already inside is not a start time in the past`() {
        // 13:45-14:00 is wet and it is 13:52: reporting "Rain at 13:45" would name the past.
        val buckets = listOf(bucket(0.4, time = 0L), bucket(0.4, time = 900L))
        assertEquals(BarChartBuilder.WetStart.Now, BarChartBuilder.firstWetTime(buckets, 420L))
        // A bucket that has fully elapsed is ignored entirely.
        assertEquals(
            BarChartBuilder.WetStart.At(900L),
            BarChartBuilder.firstWetTime(
                listOf(bucket(0.0, time = 0L), bucket(0.4, time = 900L)),
                420L,
            ),
        )
    }

    @Test
    fun `the summary window follows the buckets actually drawn`() {
        assertEquals(
            2 * 3600L,
            BarChartBuilder.windowSeconds(List(8) { bucket(0.0, time = it * 900L) }),
        )
        assertEquals(
            8 * 3600L,
            BarChartBuilder.windowSeconds(
                List(8) { bucket(0.0, durationSec = 3600, time = it * 3600L) }
            ),
        )
    }

    // ---- column selection ----------------------------------------------------------------------

    @Test
    fun `columns spread across the whole route, not just its first points`() {
        // 25 sampled points into 5 columns: rider, then evenly to the far end.
        assertEquals(listOf(0, 6, 12, 18, 24), RouteStripLayout.columnIndices(25, 5))
        assertEquals(listOf(0, 24), RouteStripLayout.columnIndices(25, 2))
        assertEquals(listOf(0), RouteStripLayout.columnIndices(25, 1))
    }

    @Test
    fun `column selection keeps the rider first and the far end last`() {
        for (size in 1..25) {
            for (count in 1..6) {
                val indices = RouteStripLayout.columnIndices(size, count)
                assertEquals("first column is the rider", 0, indices.first())
                assertEquals("ascending", indices.sorted(), indices)
                assertEquals("no duplicate points", indices.distinct(), indices)
                assertTrue("never more than asked", indices.size <= count)
                assertTrue("in range", indices.all { it in 0 until size })
                if (count > 1 && size > 1) {
                    assertEquals("last column is the far end", size - 1, indices.last())
                }
            }
        }
    }

    @Test
    fun `fewer points than columns keeps every point`() {
        assertEquals(listOf(0, 1, 2), RouteStripLayout.columnIndices(3, 5))
        assertEquals(emptyList<Int>(), RouteStripLayout.columnIndices(0, 5))
        assertEquals(emptyList<Int>(), RouteStripLayout.columnIndices(5, 0))
    }
}
