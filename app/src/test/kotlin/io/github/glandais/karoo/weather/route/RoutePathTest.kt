package io.github.glandais.karoo.weather.route

import io.github.glandais.karoo.weather.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePathTest {

    /** Metres in one degree at the equator for the sphere Geo uses. */
    private val metresPerDegree = Math.PI / 180.0 * Geo.EARTH_RADIUS_M

    /** Four points due east along the equator, 0.01 deg (~1112 m) apart. */
    private val eastRoute =
        RoutePath(
            listOf(
                GeoPoint(0.0, 0.0),
                GeoPoint(0.0, 0.01),
                GeoPoint(0.0, 0.02),
                GeoPoint(0.0, 0.03),
            )
        )

    /** Four points due north along the prime meridian, 0.01 deg apart. */
    private val northRoute =
        RoutePath(
            listOf(
                GeoPoint(0.0, 0.0),
                GeoPoint(0.01, 0.0),
                GeoPoint(0.02, 0.0),
                GeoPoint(0.03, 0.0),
            )
        )

    /**
     * A route that crosses itself: A->B runs east along the equator, then C->D runs south across
     * it. The crossing point is exactly (0, 0.01), which lies on both segment 0 and segment 2.
     */
    private val crossingRoute =
        RoutePath(
            listOf(
                GeoPoint(0.0, 0.0),
                GeoPoint(0.0, 0.02),
                GeoPoint(0.01, 0.01),
                GeoPoint(-0.01, 0.01),
            )
        )

    @Test
    fun lengthIsTheSumOfTheSegments() {
        assertEquals(0.03 * metresPerDegree, eastRoute.length, 1.0)
        assertEquals(0.03 * metresPerDegree, northRoute.length, 1.0)
    }

    @Test
    fun cumulativeDistancesAreMonotonic() {
        var previous = -1.0
        var d = 0.0
        while (d <= eastRoute.length) {
            val fromStart = Geo.distance(eastRoute.points.first(), eastRoute.pointAt(d))
            assertTrue("distance went backwards at $d", fromStart >= previous - 1e-6)
            previous = fromStart
            d += 50.0
        }
    }

    @Test
    fun pointAtTheEndsReturnsTheEndpoints() {
        assertEquals(0.0, Geo.distance(eastRoute.points.first(), eastRoute.pointAt(0.0)), 1e-6)
        assertEquals(
            0.0,
            Geo.distance(eastRoute.points.last(), eastRoute.pointAt(eastRoute.length)),
            1e-6,
        )
    }

    @Test
    fun pointAtIsClampedOutsideTheRoute() {
        assertEquals(0.0, Geo.distance(eastRoute.points.first(), eastRoute.pointAt(-5_000.0)), 1e-6)
        assertEquals(
            0.0,
            Geo.distance(eastRoute.points.last(), eastRoute.pointAt(eastRoute.length + 5_000.0)),
            1e-6,
        )
    }

    @Test
    fun pointAtInterpolatesInsideASegment() {
        val segment = 0.01 * metresPerDegree
        val mid = eastRoute.pointAt(segment * 1.5)
        assertEquals(0.0, mid.lat, 1e-9)
        assertEquals(0.015, mid.lon, 1e-6)

        val quarter = northRoute.pointAt(segment * 0.25)
        assertEquals(0.0025, quarter.lat, 1e-6)
        assertEquals(0.0, quarter.lon, 1e-9)
    }

    @Test
    fun bearingAtFollowsTheTravelDirection() {
        assertEquals(90.0, eastRoute.bearingAt(0.0), 0.01)
        assertEquals(90.0, eastRoute.bearingAt(eastRoute.length / 2.0), 0.01)
        assertEquals(0.0, northRoute.bearingAt(northRoute.length / 2.0), 0.01)
    }

    @Test
    fun bearingAtTheFinalVertexClampsTheLookahead() {
        assertEquals(90.0, eastRoute.bearingAt(eastRoute.length), 0.01)
        assertEquals(90.0, eastRoute.bearingAt(eastRoute.length - 1.0), 0.01)
        assertEquals(0.0, northRoute.bearingAt(northRoute.length), 0.01)
    }

    @Test
    fun bearingAtHandlesALookaheadLongerThanTheRoute() {
        val tiny = RoutePath(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0001)))
        assertEquals(90.0, tiny.bearingAt(0.0, lookaheadMetres = 25.0), 0.01)
    }

    @Test
    fun nearestDistanceOnAStraightLeg() {
        val segment = 0.01 * metresPerDegree
        assertEquals(segment * 1.5, eastRoute.nearestDistanceTo(GeoPoint(0.0, 0.015)), 1.0)
    }

    @Test
    fun nearestDistanceAtAVertex() {
        val segment = 0.01 * metresPerDegree
        assertEquals(segment * 2.0, eastRoute.nearestDistanceTo(GeoPoint(0.0, 0.02)), 1.0)
    }

    @Test
    fun nearestDistanceWithA300MetreOffRouteOffset() {
        val segment = 0.01 * metresPerDegree
        val offsetDeg = 300.0 / metresPerDegree
        assertEquals(
            segment * 1.5,
            eastRoute.nearestDistanceTo(GeoPoint(offsetDeg, 0.015)),
            2.0,
        )
    }

    @Test
    fun nearestDistanceIsClampedToTheRouteEnds() {
        assertEquals(0.0, eastRoute.nearestDistanceTo(GeoPoint(0.0, -1.0)), 1.0)
        assertEquals(eastRoute.length, eastRoute.nearestDistanceTo(GeoPoint(0.0, 1.0)), 1.0)
    }

    @Test
    fun nearestDistanceOnASelfCrossingRoutePicksTheEarliestSegment() {
        val expected = 0.01 * metresPerDegree
        assertEquals(expected, crossingRoute.nearestDistanceTo(GeoPoint(0.0, 0.01)), 1.0)
    }

    @Test
    fun nearestDistanceOnASelfCrossingRouteStillFindsTheLaterLeg() {
        // Clearly on the north-south leg only.
        val expected =
            crossingRoute.length - Geo.distance(GeoPoint(0.008, 0.01), GeoPoint(-0.01, 0.01))
        assertEquals(expected, crossingRoute.nearestDistanceTo(GeoPoint(0.008, 0.01)), 5.0)
    }

    @Test
    fun aDegenerateSinglePointPathIsInert() {
        val path = RoutePath(listOf(GeoPoint(1.0, 2.0)))
        assertEquals(0.0, path.length, 1e-9)
        assertEquals(GeoPoint(1.0, 2.0), path.pointAt(500.0))
        assertEquals(0.0, path.bearingAt(0.0), 1e-9)
        assertEquals(0.0, path.nearestDistanceTo(GeoPoint(9.0, 9.0)), 1e-9)
    }

    @Test
    fun fromPolylineRejectsUnusableInput() {
        assertNull(RoutePath.fromPolyline(""))
        assertNull(RoutePath.fromPolyline("   "))
        assertNull(RoutePath.fromPolyline(Polyline.encode(listOf(GeoPoint(38.5, -120.2)))))
    }

    @Test
    fun fromPolylineDecodesInForwardOrder() {
        val path = RoutePath.fromPolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertNotNull(path)
        val points = path!!.points
        assertEquals(3, points.size)
        assertEquals(38.5, points.first().lat, 1e-5)
        assertEquals(43.252, points.last().lat, 1e-5)
    }

    @Test
    fun fromPolylineReversedFlipsTravelDirection() {
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val forward = RoutePath.fromPolyline(encoded)!!
        val reversed = RoutePath.fromPolyline(encoded, reversed = true)!!
        assertEquals(43.252, reversed.points.first().lat, 1e-5)
        assertEquals(38.5, reversed.points.last().lat, 1e-5)
        assertEquals(forward.length, reversed.length, 1e-6)
        // pointAt(0) of the reversed path is the forward path's end.
        assertEquals(
            0.0,
            Geo.distance(forward.pointAt(forward.length), reversed.pointAt(0.0)),
            1e-6,
        )
    }

    @Test
    fun reversedSamplingRunsTowardsTheOriginalStart() {
        val points = (0..30).map { GeoPoint(0.0, it * 0.01) }
        val reversed = RoutePath(points.asReversed().toList())
        assertEquals(0.30, reversed.pointAt(0.0).lon, 1e-6)
        assertEquals(0.0, reversed.pointAt(reversed.length).lon, 1e-6)
        assertEquals(270.0, reversed.bearingAt(reversed.length / 2.0), 0.01)
    }
}
