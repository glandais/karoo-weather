package io.github.glandais.karoo.weather.route

import io.github.glandais.karoo.weather.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSamplerTest {

    /** A straight route heading due east from (0, 0), with a vertex every 500 m. */
    private fun straightRoute(lengthMetres: Double): RoutePath {
        val vertexCount = (lengthMetres / 500.0).toInt().coerceAtLeast(1)
        val step = lengthMetres / vertexCount
        val origin = GeoPoint(0.0, 0.0)
        return RoutePath((0..vertexCount).map { Geo.destination(origin, it * step, 90.0) })
    }

    @Test
    fun spacingLadderPicksTheFirstSpacingThatFits() {
        assertEquals(1_000.0, RouteSampler.spacingFor(24_000.0), 1e-9)
        assertEquals(1_000.0, RouteSampler.spacingFor(5_000.0), 1e-9)
        assertEquals(2_000.0, RouteSampler.spacingFor(30_000.0), 1e-9)
        assertEquals(5_000.0, RouteSampler.spacingFor(100_000.0), 1e-9)
        assertEquals(10_000.0, RouteSampler.spacingFor(240_000.0), 1e-9)
        assertEquals(20_000.0, RouteSampler.spacingFor(300_000.0), 1e-9)
        assertEquals(50_000.0, RouteSampler.spacingFor(1_200_000.0), 1e-9)
    }

    @Test
    fun spacingBeyondTheLadderRoundsUpToWholeTensOfKilometres() {
        // 2000 km / 24 = 83 333 m, past the 50 km ladder -> ceil to 90 km.
        assertEquals(90_000.0, RouteSampler.spacingFor(2_000_000.0), 1e-9)
    }

    @Test
    fun spacingForDegenerateInputIsTheSmallestSpacing() {
        assertEquals(1_000.0, RouteSampler.spacingFor(0.0), 1e-9)
        assertEquals(1_000.0, RouteSampler.spacingFor(-5_000.0), 1e-9)
        // maxPoints is coerced to at least 1, so the whole remainder is one span.
        assertEquals(10_000.0, RouteSampler.spacingFor(10_000.0, maxPoints = 0), 1e-9)
    }

    @Test
    fun neverExceedsTheBudgetForAnyRouteLength() {
        for (km in listOf(5, 50, 200, 1_000)) {
            val path = straightRoute(km * 1_000.0)
            val samples = RouteSampler.sample(path, 0.0)
            assertTrue(
                "$km km produced ${samples.size} samples",
                samples.size <= RouteSampler.MAX_ROUTE_POINTS,
            )
            assertTrue("$km km produced no samples", samples.isNotEmpty())
        }
    }

    @Test
    fun samplesAreStrictlyAscendingAndEndOnTheRouteEnd() {
        val path = straightRoute(200_000.0)
        val samples = RouteSampler.sample(path, 12_345.0)
        var previous = 12_345.0
        for (s in samples) {
            assertTrue("not ascending at ${s.distanceAlong}", s.distanceAlong > previous)
            previous = s.distanceAlong
        }
        assertEquals(path.length, samples.last().distanceAlong, 1e-6)
    }

    @Test
    fun neverEmitsASampleAtTheRiderPosition() {
        val path = straightRoute(50_000.0)
        for (progress in listOf(0.0, 1.0, 999.0, 25_000.0, 49_000.0)) {
            val samples = RouteSampler.sample(path, progress)
            assertTrue(
                "emitted a sample at progress $progress",
                samples.none { it.distanceAlong <= progress },
            )
        }
    }

    @Test
    fun emptyWhenTheRiderIsAtOrPastTheEnd() {
        val path = straightRoute(20_000.0)
        assertTrue(RouteSampler.sample(path, path.length).isEmpty())
        assertTrue(RouteSampler.sample(path, path.length + 5_000.0).isEmpty())
        assertTrue(RouteSampler.sample(path, path.length - 0.5).isEmpty())
    }

    @Test
    fun everySampleCarriesThePathGeometry() {
        val path = straightRoute(50_000.0)
        val samples = RouteSampler.sample(path, 0.0)
        for (s in samples) {
            assertEquals(90.0, s.routeBearing, 0.5)
            assertEquals(0.0, Geo.distance(path.pointAt(s.distanceAlong), s.point), 1e-6)
        }
    }

    @Test
    fun reversedRouteSamplesRunTowardsTheOriginalStart() {
        val forwardPoints = (0..100).map { Geo.destination(GeoPoint(0.0, 0.0), it * 500.0, 90.0) }
        val reversed = RoutePath(forwardPoints.asReversed().toList())
        val samples = RouteSampler.sample(reversed, 0.0)
        assertTrue(samples.isNotEmpty())
        // Longitudes must decrease as distanceAlong grows.
        var previousLon = reversed.points.first().lon
        for (s in samples) {
            assertTrue(
                "longitude did not decrease at ${s.distanceAlong}",
                s.point.lon < previousLon,
            )
            previousLon = s.point.lon
            assertEquals(270.0, s.routeBearing, 0.5)
        }
        assertEquals(0.0, samples.last().point.lon, 1e-6)
    }

    @Test
    fun aCustomBudgetIsHonoured() {
        val path = straightRoute(200_000.0)
        val samples = RouteSampler.sample(path, 0.0, maxPoints = 5)
        assertTrue(samples.size <= 5)
        assertEquals(path.length, samples.last().distanceAlong, 1e-6)
    }

    // ---- truncateToHorizon -------------------------------------------------

    private fun samplesAt(vararg distances: Double): List<RouteSample> = distances.map {
        RouteSample(GeoPoint(0.0, it / 111_195.1), it, 90.0)
    }

    /** 10 m/s, so 36 km per hour of horizon. */
    private val eta: (Double) -> Long = { d -> (d / 10.0).toLong() }

    @Test
    fun truncateKeepsEverythingInsideTheHorizon() {
        val samples = samplesAt(10_000.0, 20_000.0, 30_000.0)
        val (kept, marker) = RouteSampler.truncateToHorizon(samples, eta, nowSec = 0L)
        assertEquals(samples, kept)
        assertNull(marker)
    }

    @Test
    fun truncateReplacesTheTailWithExactlyOneMarker() {
        // 11 h horizon at 10 m/s = 396 000 m.
        val samples = samplesAt(100_000.0, 300_000.0, 390_000.0, 500_000.0, 700_000.0, 900_000.0)
        val (kept, marker) = RouteSampler.truncateToHorizon(samples, eta, nowSec = 0L)
        assertEquals(3, kept.size)
        assertNotNull(marker)
        assertEquals(2, marker)
        assertEquals(390_000.0, kept.last().distanceAlong, 1e-6)
        assertEquals(390_000.0, kept[marker!!].distanceAlong, 1e-6)
    }

    @Test
    fun truncateFallsBackToTheFirstSampleWhenNothingIsInsideTheHorizon() {
        val samples = samplesAt(500_000.0, 700_000.0, 900_000.0)
        val (kept, marker) = RouteSampler.truncateToHorizon(samples, eta, nowSec = 0L)
        assertEquals(1, kept.size)
        assertEquals(0, marker)
        assertEquals(500_000.0, kept.first().distanceAlong, 1e-6)
    }

    @Test
    fun truncateHonoursNowSec() {
        val samples = samplesAt(100_000.0, 300_000.0, 390_000.0)
        // Ten hours already elapsed: nothing fits the 11 h horizon, so the marker is sample 0.
        val (kept, marker) =
            RouteSampler.truncateToHorizon(samples, { d -> 36_000L + (d / 10.0).toLong() }, 0L)
        assertEquals(1, kept.size)
        assertEquals(0, marker)
    }

    @Test
    fun truncateRespectsACustomHorizon() {
        val samples = samplesAt(10_000.0, 20_000.0, 30_000.0, 40_000.0)
        val (kept, marker) =
            RouteSampler.truncateToHorizon(samples, eta, nowSec = 0L, horizonSec = 2_500L)
        assertEquals(2, kept.size)
        assertEquals(1, marker)
    }

    @Test
    fun truncateOnAnEmptyListIsEmpty() {
        val (kept, marker) = RouteSampler.truncateToHorizon(emptyList(), eta, 0L)
        assertTrue(kept.isEmpty())
        assertNull(marker)
    }

    @Test
    fun truncatedSamplesStayWithinTheBudget() {
        val path = straightRoute(1_000_000.0)
        val samples = RouteSampler.sample(path, 0.0)
        val (kept, _) = RouteSampler.truncateToHorizon(samples, eta, nowSec = 0L)
        assertTrue(kept.size <= RouteSampler.MAX_ROUTE_POINTS)
        assertTrue(kept.isNotEmpty())
    }
}
