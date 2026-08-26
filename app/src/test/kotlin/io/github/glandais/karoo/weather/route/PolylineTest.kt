package io.github.glandais.karoo.weather.route

import io.github.glandais.karoo.weather.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {

    /** The Google reference string; the character before `@` is a backtick, not an apostrophe. */
    private val reference = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    @Test
    fun decodesTheGoogleReferencePolyline() {
        val points = Polyline.decode(reference)
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].lat, 1e-5)
        assertEquals(-120.2, points[0].lon, 1e-5)
        assertEquals(40.7, points[1].lat, 1e-5)
        assertEquals(-120.95, points[1].lon, 1e-5)
        assertEquals(43.252, points[2].lat, 1e-5)
        assertEquals(-126.453, points[2].lon, 1e-5)
    }

    @Test
    fun reEncodesTheGoogleReferencePolylineByteForByte() {
        assertEquals(reference, Polyline.encode(Polyline.decode(reference)))
    }

    @Test
    fun roundTripsAtPrecision5() {
        val points =
            listOf(
                GeoPoint(48.85661, 2.35222),
                GeoPoint(48.86000, 2.36000),
                GeoPoint(48.84000, 2.30000),
                GeoPoint(-33.86880, 151.20930),
            )
        val decoded = Polyline.decode(Polyline.encode(points))
        assertEquals(points.size, decoded.size)
        points.forEachIndexed { i, p ->
            assertEquals(p.lat, decoded[i].lat, 1e-5)
            assertEquals(p.lon, decoded[i].lon, 1e-5)
        }
    }

    @Test
    fun roundTripsAtPrecision1() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(1000.0, 120.5), GeoPoint(2500.4, 98.1))
        val encoded = Polyline.encode(points, precision = 1)
        val decoded = Polyline.decode(encoded, precision = 1)
        assertEquals(points.size, decoded.size)
        points.forEachIndexed { i, p ->
            assertEquals(p.lat, decoded[i].lat, 1e-9)
            assertEquals(p.lon, decoded[i].lon, 1e-9)
        }
    }

    @Test
    fun decodeElevationYieldsDistanceElevationPairsAtPrecision1() {
        val encoded =
            Polyline.encode(
                listOf(GeoPoint(0.0, 12.0), GeoPoint(500.0, 31.5), GeoPoint(1500.0, 120.3)),
                precision = 1,
            )
        val profile = Polyline.decodeElevation(encoded)
        assertEquals(3, profile.size)
        assertEquals(0.0, profile[0].first, 1e-9)
        assertEquals(12.0, profile[0].second, 1e-9)
        assertEquals(500.0, profile[1].first, 1e-9)
        assertEquals(31.5, profile[1].second, 1e-9)
        assertEquals(1500.0, profile[2].first, 1e-9)
        assertEquals(120.3, profile[2].second, 1e-9)
    }

    @Test
    fun emptyInputDecodesToEmptyList() {
        assertTrue(Polyline.decode("").isEmpty())
        assertTrue(Polyline.decodeElevation("").isEmpty())
        assertEquals("", Polyline.encode(emptyList()))
    }

    @Test
    fun truncatedInputKeepsTheCompleteLeadingPoints() {
        // Drop the last character: the final longitude varint can no longer terminate.
        val points = Polyline.decode(reference.dropLast(1))
        assertEquals(2, points.size)
        assertEquals(40.7, points[1].lat, 1e-5)
    }

    @Test
    fun aDanglingLatitudeVarintIsDiscardedRatherThanThrowing() {
        val complete = Polyline.encode(listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95)))
        val points = Polyline.decode(complete + "_ulL")
        assertEquals(2, points.size)
    }

    @Test
    fun outOfRangeCharactersEndTheDecodeWithoutThrowing() {
        val points = Polyline.decode("_p~iF~ps|Uÿ_ulLnnqC")
        assertEquals(1, points.size)
        assertEquals(38.5, points[0].lat, 1e-5)
    }

    @Test
    fun singlePointRoundTrips() {
        val decoded = Polyline.decode(Polyline.encode(listOf(GeoPoint(38.5, -120.2))))
        assertEquals(1, decoded.size)
        assertEquals(38.5, decoded[0].lat, 1e-5)
        assertEquals(-120.2, decoded[0].lon, 1e-5)
    }
}
