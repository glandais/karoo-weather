package io.github.glandais.karoo.weather.datatypes

import io.github.glandais.karoo.weather.domain.RouteForecast
import io.github.glandais.karoo.weather.domain.WindClass
import io.github.glandais.karoo.weather.route.RelativeWind
import io.github.glandais.karoo.weather.ui.theme.ColorPair
import io.github.glandais.karoo.weather.ui.theme.Wx
import io.github.glandais.karoo.weather.weather.WmoCodes
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview snapshot is the field's advertisement in the page editor. These assertions guard it
 * against silently degrading into something that exercises none of the visual mechanisms.
 */
class PreviewDataTest {

    @Test
    fun `preview is a complete, plausible snapshot`() {
        val snapshot = PreviewData.snapshot
        assertNotNull(snapshot.bundle)
        assertTrue(snapshot.hasData)
        assertTrue(snapshot.consentAccepted)
        assertNotNull(snapshot.position)
        assertNotNull(snapshot.bearing)
        assertEquals(null, snapshot.error)
        assertTrue("a preview must never look like a loading state", !snapshot.loading)
        assertTrue("a preview must never look stale", !snapshot.isStale(PreviewData.BASE_TIME))
    }

    @Test
    fun `the rider sample is a good day's data`() {
        val sample = PreviewData.sample
        assertEquals(22.0, sample.temp, 1e-9)
        assertEquals(24.0, sample.apparentTemp!!, 1e-9)
        assertEquals(45.0, sample.windDir, 1e-9)
        assertEquals(225.0, sample.windToDir, 1e-9)
        assertEquals(14.0, sample.windSpeed * 3.6, 1e-6)
        assertEquals(26.0, sample.windGusts * 3.6, 1e-6)
        assertEquals(40, sample.precipProb)
        assertTrue(sample.isDay)
        assertTrue(WmoCodes.isWet(sample.wmoCode))
    }

    @Test
    fun `route crosses at least two temperature ramp buckets`() {
        val ramps: Set<ColorPair> =
            PreviewData.route.points.map { Wx.forTemp(it.sample.temp) }.toSet()
        assertTrue("route temperatures must span more than one ramp bucket", ramps.size >= 2)
    }

    @Test
    fun `hourly outlook widens the ramp coverage further`() {
        val temps = PreviewData.hourly.map { it.temp }
        val ramps = temps.map { Wx.forTemp(it) }.toSet()
        assertTrue("outlook must exercise at least two ramp buckets", ramps.size >= 2)
        assertTrue("outlook must show a real spread", temps.max() - temps.min() >= 5.0)
    }

    @Test
    fun `route shows both a tailwind and a headwind arrow`() {
        val classes = PreviewData.route.points.map { RelativeWind.classify(it.relativeWindAngle) }
        assertTrue("no tailwind arrow in the preview", classes.contains(WindClass.TAIL))
        assertTrue("no headwind arrow in the preview", classes.contains(WindClass.HEAD))
    }

    @Test
    fun `route headwind colours cover the helping and the opposing end`() {
        val colours = PreviewData.route.points.map { Wx.forHeadwind(it.headwindSpeed) }
        assertTrue(colours.contains(Wx.windTail))
        assertTrue(colours.contains(Wx.windHead))
    }

    @Test
    fun `headwind component agrees with the relative angle at every point`() {
        PreviewData.route.points.forEach { point ->
            val expected =
                RelativeWind.headwindComponent(point.relativeWindAngle, point.sample.windSpeed)
            assertEquals(expected, point.headwindSpeed, 1e-9)
            // And the angle really is recoverable from the meteorological direction we stored.
            val recovered = RelativeWind.relativeAngle(point.routeBearing, point.sample.windDir)
            assertTrue(abs(recovered - point.relativeWindAngle) < 1e-6)
        }
    }

    @Test
    fun `route has exactly two wet cells and is ordered`() {
        val points = PreviewData.route.points
        assertEquals(5, points.size)
        assertEquals(0.0, points.first().distanceAlong, 1e-9)
        assertEquals(PreviewData.route.progress, points.first().distanceAlong, 1e-9)
        points.zipWithNext().forEach { (a, b) ->
            assertTrue(b.distanceAlong > a.distanceAlong)
            assertTrue(b.eta > a.eta)
        }
        val wet = points.count { it.sample.precip >= RouteForecast.WET_THRESHOLD_MM }
        assertEquals(2, wet)
        assertEquals(points[2].distanceAlong, PreviewData.route.firstWetDistance!!, 1e-9)
        assertEquals(points[2].eta, PreviewData.route.firstWetEta!!)
    }

    @Test
    fun `nowcast buckets build, peak and clear inside two hours`() {
        val buckets = PreviewData.buckets
        assertEquals(8, buckets.size)
        assertTrue(buckets.all { it.durationSec == 900 })
        buckets.zipWithNext().forEach { (a, b) -> assertEquals(900L, b.time - a.time) }
        assertTrue("a preview chart with no rain shows nothing", buckets.any { it.mm > 0.5 })
        assertTrue("the shower must clear", buckets.last().mm < 0.1)
        assertTrue(buckets.all { it.probability != null })
    }

    @Test
    fun `bucket colours cover more than one rain intensity`() {
        val colours = PreviewData.buckets.map { Wx.forRain(it.mm) }.toSet()
        assertTrue(colours.size >= 3)
    }
}
