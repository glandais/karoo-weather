package io.github.glandais.karoo.weather.route

import io.github.glandais.karoo.weather.domain.GeoPoint
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    private val paris = GeoPoint(48.8566, 2.3522)
    private val berlin = GeoPoint(52.5200, 13.4050)
    private val london = GeoPoint(51.5074, -0.1278)
    private val newYork = GeoPoint(40.7128, -74.0060)
    private val sydney = GeoPoint(-33.8688, 151.2093)
    private val melbourne = GeoPoint(-37.8136, 144.9631)
    private val tokyo = GeoPoint(35.6762, 139.6503)
    private val osaka = GeoPoint(34.6937, 135.5023)

    private fun assertWithin(expected: Double, actual: Double, relativeTolerance: Double) {
        val delta = abs(expected - actual) / expected
        assertTrue(
            "expected $expected, got $actual (relative error $delta)",
            delta <= relativeTolerance,
        )
    }

    @Test
    fun haversineMatchesKnownCityPairsWithin0Point3Percent() {
        assertWithin(877_464.0, Geo.distance(paris, berlin), 0.003)
        assertWithin(5_570_230.0, Geo.distance(london, newYork), 0.003)
        assertWithin(713_428.0, Geo.distance(sydney, melbourne), 0.003)
        assertWithin(392_442.0, Geo.distance(tokyo, osaka), 0.003)
    }

    @Test
    fun haversineIsSymmetricAndZeroForIdenticalPoints() {
        assertEquals(Geo.distance(paris, berlin), Geo.distance(berlin, paris), 1e-6)
        assertEquals(0.0, Geo.distance(paris, paris), 1e-9)
    }

    @Test
    fun haversineIsCorrectAcrossTheAntimeridian() {
        val west = GeoPoint(0.0, 179.99)
        val east = GeoPoint(0.0, -179.99)
        // 0.02 degrees of longitude at the equator, not 359.98.
        assertEquals(0.02 * 111_195.1, Geo.distance(west, east), 5.0)
    }

    @Test
    fun bearingParisToBerlin() {
        // Great-circle initial bearing for these exact coordinates is 58.18 degrees.
        assertEquals(58.18, Geo.bearing(paris, berlin), 0.1)
    }

    @Test
    fun bearingIsNormalisedIntoZeroTo360() {
        val origin = GeoPoint(45.0, 0.0)
        assertEquals(0.0, Geo.bearing(origin, GeoPoint(46.0, 0.0)), 1e-6)
        assertEquals(180.0, Geo.bearing(origin, GeoPoint(44.0, 0.0)), 1e-6)
        val west = Geo.bearing(origin, GeoPoint(45.0, -1.0))
        assertTrue("west bearing $west must be in [0,360)", west >= 0.0 && west < 360.0)
        assertEquals(270.0, west, 0.5)
    }

    @Test
    fun destinationIsTheInverseOfDistanceAndBearing() {
        for (bearing in listOf(0.0, 45.0, 90.0, 175.0, 270.0, 359.0)) {
            val target = Geo.destination(paris, 25_000.0, bearing)
            assertEquals(25_000.0, Geo.distance(paris, target), 1.0)
            assertEquals(bearing, Geo.bearing(paris, target), 0.5)
        }
    }

    @Test
    fun destinationWrapsLongitudeAcrossTheAntimeridian() {
        val start = GeoPoint(0.0, 179.9)
        val target = Geo.destination(start, 50_000.0, 90.0)
        assertTrue("longitude ${target.lon} must stay in [-180,180)", target.lon in -180.0..180.0)
        assertTrue("expected a wrap to negative longitude", target.lon < 0.0)
    }

    @Test
    fun signedAngleDifferenceCrossesThe180Seam() {
        assertEquals(20.0, Geo.signedAngleDifference(350.0, 10.0), 1e-9)
        assertEquals(-20.0, Geo.signedAngleDifference(10.0, 350.0), 1e-9)
        assertEquals(0.0, Geo.signedAngleDifference(90.0, 90.0), 1e-9)
        assertEquals(90.0, Geo.signedAngleDifference(0.0, 90.0), 1e-9)
        assertEquals(-90.0, Geo.signedAngleDifference(90.0, 0.0), 1e-9)
        // Exactly opposite resolves to +180, never -180: the range is (-180, 180].
        assertEquals(180.0, Geo.signedAngleDifference(0.0, 180.0), 1e-9)
        assertEquals(180.0, Geo.signedAngleDifference(180.0, 0.0), 1e-9)
        assertEquals(-179.0, Geo.signedAngleDifference(0.0, 181.0), 1e-9)
    }

    @Test
    fun signedAngleDifferenceAcceptsUnnormalisedInput() {
        assertEquals(20.0, Geo.signedAngleDifference(-10.0, 10.0), 1e-9)
        assertEquals(20.0, Geo.signedAngleDifference(710.0, 730.0), 1e-9)
    }

    @Test
    fun roundToGridSnapsToACellAtTheEquator() {
        val rounded = Geo.roundToGrid(GeoPoint(0.4, 0.4), 3.0)
        val step = 3.0 / 111.32
        assertEquals(Math.round(0.4 / step) * step, rounded.lat, 1e-9)
        // Longitude uses km / (111.32 * cos(lat)); at 0.4 deg that is the latitude step to 1e-4.
        assertEquals(Math.round(0.4 / step) * step, rounded.lon, 1e-4)
    }

    @Test
    fun roundToGridCellsStayRoughlySquareAtHigherLatitudes() {
        for (lat in listOf(0.0, 45.0, 70.0)) {
            val p = GeoPoint(lat + 0.037, 12.0311)
            val rounded = Geo.roundToGrid(p, 3.0)
            // The rounded point is never further than half a diagonal of a 3 km cell away.
            assertTrue(
                "lat $lat displaced ${Geo.distance(p, rounded)} m",
                Geo.distance(p, rounded) <= 3_000.0 * 0.71 + 1.0,
            )
        }
    }

    @Test
    fun roundToGridIsStableForNearbyPoints() {
        val a = Geo.roundToGrid(GeoPoint(45.0000, 5.0000), 3.0)
        val b = Geo.roundToGrid(GeoPoint(45.0001, 5.0001), 3.0)
        assertEquals(a, b)
    }

    @Test
    fun roundToGridWithNonPositiveKmIsTheIdentity() {
        val p = GeoPoint(45.1234, 5.6789)
        assertEquals(p, Geo.roundToGrid(p, 0.0))
        assertEquals(p, Geo.roundToGrid(p, -1.0))
    }

    @Test
    fun `a sticky grid does not flip on jitter along a cell boundary`() {
        val km = 1.0
        // A point close to the boundary between two 1 km cells, and its neighbour a few metres on.
        val a = GeoPoint(45.0045, 5.0)
        val b = Geo.destination(a, 12.0, 0.0)
        val cellA = Geo.roundToGrid(a, km)
        val cellB = Geo.roundToGrid(b, km)

        // Plain rounding is free to disagree about two points 12 m apart; sticky rounding is not.
        assertEquals(cellA, Geo.roundToGridSticky(cellA, a, km))
        assertEquals(cellA, Geo.roundToGridSticky(cellA, b, km))

        // Far enough past the boundary and the cell does change.
        val far = Geo.destination(a, 2_000.0, 0.0)
        assertNotEquals(cellA, Geo.roundToGridSticky(cellA, far, km))

        // With no previous cell it is exactly `roundToGrid`.
        assertEquals(cellB, Geo.roundToGridSticky(null, b, km))
    }

    @Test
    fun `a sticky grid is a no-op when rounding is disabled`() {
        val p = GeoPoint(45.0, 5.0)
        assertEquals(p, Geo.roundToGridSticky(GeoPoint(44.0, 4.0), p, 0.0))
    }
}
