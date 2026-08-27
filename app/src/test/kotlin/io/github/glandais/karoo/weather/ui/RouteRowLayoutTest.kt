package io.github.glandais.karoo.weather.ui

import io.github.glandais.karoo.weather.ui.components.RouteRowMetrics
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Route tab renders on the Karoo itself — 480 x 800 px at 300 dpi, i.e. 256 x 427 dp.
 *
 * Compose lays an unweighted child out at its exact width whatever space is left, so a row whose
 * fixed children plus gaps exceed the content width silently draws the last of them off-screen.
 * This pins the arithmetic so a future width tweak cannot reintroduce that.
 */
class RouteRowLayoutTest {

    @Test
    fun `the fixed part of a route row fits the Karoo panel`() {
        assertTrue(
            "fixed width ${RouteRowMetrics.fixedWidthDp} dp exceeds the " +
                "${RouteRowMetrics.CONTENT_WIDTH_DP} dp available",
            RouteRowMetrics.fixedWidthDp <= RouteRowMetrics.CONTENT_WIDTH_DP,
        )
    }

    @Test
    fun `the weighted distance and eta pair keeps a usable share of the row`() {
        val flexible = RouteRowMetrics.CONTENT_WIDTH_DP - RouteRowMetrics.fixedWidthDp
        // Two monospace fields ("+18 km", "14:55") need roughly 80 dp at bodyMedium.
        assertTrue("only $flexible dp left for distance and ETA", flexible >= 80)
    }
}
