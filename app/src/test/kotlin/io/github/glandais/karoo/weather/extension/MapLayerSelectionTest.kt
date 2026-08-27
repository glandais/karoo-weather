package io.github.glandais.karoo.weather.extension

import io.github.glandais.karoo.weather.domain.GeoPoint
import io.github.glandais.karoo.weather.domain.RoutePointForecast
import io.github.glandais.karoo.weather.domain.WeatherSample
import io.github.glandais.karoo.weather.extension.WeatherMapLayer.Companion.selectPoints
import io.github.glandais.karoo.weather.extension.WeatherMapLayer.Companion.symbolSpacingFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLayerSelectionTest {

    private fun sample(precip: Double = 0.0) =
        WeatherSample(
            time = 1_700_000_000L,
            temp = 12.0,
            windSpeed = 5.0,
            windGusts = 8.0,
            windDir = 270.0,
            precip = precip,
            wmoCode = 61,
            isDay = true,
        )

    private fun point(distanceAlong: Double, precip: Double = 0.0) =
        RoutePointForecast(
            point = GeoPoint(45.0 + distanceAlong / 1_000_000.0, 5.0),
            distanceAlong = distanceAlong,
            eta = 1_700_000_000L + distanceAlong.toLong(),
            routeBearing = 90.0,
            sample = sample(precip),
            relativeWindAngle = 180.0,
            headwindSpeed = 5.0,
        )

    @Test
    fun `spacing buckets`() {
        assertEquals(20_000.0, symbolSpacingFor(8.0), 0.0)
        assertEquals(20_000.0, symbolSpacingFor(11.9), 0.0)
        assertEquals(5_000.0, symbolSpacingFor(12.0), 0.0)
        assertEquals(5_000.0, symbolSpacingFor(14.9), 0.0)
        assertEquals(2_000.0, symbolSpacingFor(15.0), 0.0)
        assertEquals(2_000.0, symbolSpacingFor(18.0), 0.0)
    }

    @Test
    fun `default zoom lands in the densest bucket`() {
        assertEquals(2_000.0, symbolSpacingFor(WeatherMapLayer.DEFAULT_ZOOM), 0.0)
    }

    @Test
    fun `empty input selects nothing`() {
        assertTrue(selectPoints(emptyList(), 2_000.0).isEmpty())
    }

    @Test
    fun `single point is kept`() {
        val only = point(0.0)
        assertEquals(listOf(only), selectPoints(listOf(only), 2_000.0))
    }

    @Test
    fun `two points are both kept regardless of spacing`() {
        val points = listOf(point(0.0), point(10.0))
        assertEquals(points, selectPoints(points, 20_000.0))
    }

    @Test
    fun `greedy selection respects spacing and keeps first and last`() {
        val points = (0..10).map { point(it * 1_000.0) }
        val selected = selectPoints(points, 2_000.0)

        assertSame(points.first(), selected.first())
        assertSame(points.last(), selected.last())
        assertEquals(
            listOf(0.0, 2_000.0, 4_000.0, 6_000.0, 8_000.0, 10_000.0),
            selected.map { it.distanceAlong },
        )
    }

    @Test
    fun `wide spacing collapses to first and last only`() {
        val points = (0..10).map { point(it * 1_000.0) }
        val selected = selectPoints(points, 20_000.0)

        assertEquals(listOf(0.0, 10_000.0), selected.map { it.distanceAlong })
    }

    @Test
    fun `selection is ordered and never drops below the spacing except at the tail`() {
        val points = (0..20).map { point(it * 700.0) }
        val selected = selectPoints(points, 5_000.0)

        assertEquals(0.0, selected.first().distanceAlong, 0.0)
        assertEquals(14_000.0, selected.last().distanceAlong, 0.0)
        val interior = selected.dropLast(1)
        interior.zipWithNext { a, b -> assertTrue(b.distanceAlong - a.distanceAlong >= 5_000.0) }
        selected.zipWithNext { a, b -> assertTrue(b.distanceAlong > a.distanceAlong) }
    }

    @Test
    fun `symbol prefix is stable`() {
        assertEquals("wx-", WeatherMapLayer.SYMBOL_PREFIX)
    }
}
