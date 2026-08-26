package io.github.glandais.karoo.weather.route

import io.github.glandais.karoo.weather.domain.WeatherSample
import io.github.glandais.karoo.weather.domain.WindClass
import kotlin.math.abs
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeWindTest {

    private val v = 8.0

    @Test
    fun pureTailwind() {
        // Riding east; the wind comes from the west, so it blows towards the east with the rider.
        val rel = RelativeWind.relativeAngle(travelBearing = 90.0, windDirFrom = 270.0)
        assertEquals(0.0, rel, 1e-9)
        assertEquals(-v, RelativeWind.headwindComponent(rel, v), 1e-9)
        assertEquals(WindClass.TAIL, RelativeWind.classify(rel))
    }

    @Test
    fun pureHeadwind() {
        val rel = RelativeWind.relativeAngle(travelBearing = 90.0, windDirFrom = 90.0)
        assertEquals(180.0, rel, 1e-9)
        assertEquals(v, RelativeWind.headwindComponent(rel, v), 1e-9)
        assertEquals(WindClass.HEAD, RelativeWind.classify(rel))
    }

    @Test
    fun a45DegreeCrossHasACosineHeadwindComponent() {
        // Riding east, wind from the south-west: blowing towards the north-east, 45 deg off travel.
        val rel = RelativeWind.relativeAngle(travelBearing = 90.0, windDirFrom = 225.0)
        assertEquals(-45.0, rel, 1e-9)
        assertEquals(-v * cos(Math.toRadians(45.0)), RelativeWind.headwindComponent(rel, v), 1e-9)
    }

    @Test
    fun a90DegreeCrossHasNoHeadwindComponent() {
        val rel = RelativeWind.relativeAngle(travelBearing = 0.0, windDirFrom = 270.0)
        assertEquals(90.0, rel, 1e-9)
        assertEquals(0.0, RelativeWind.headwindComponent(rel, v), 1e-9)
        assertEquals(WindClass.CROSS, RelativeWind.classify(rel))
    }

    @Test
    fun theRelativeAngleStaysInsideTheHalfOpenRange() {
        var bearing = 0.0
        while (bearing < 360.0) {
            var from = 0.0
            while (from < 360.0) {
                val rel = RelativeWind.relativeAngle(bearing, from)
                assertTrue(
                    "rel $rel out of range for $bearing / $from",
                    rel > -180.0 && rel <= 180.0,
                )
                from += 7.0
            }
            bearing += 13.0
        }
    }

    @Test
    fun theRelativeAngleCrossesTheSeamCorrectly() {
        // Travel 350, wind blowing towards 10 -> 20 degrees off the nose-tail axis, not 340.
        assertEquals(20.0, RelativeWind.relativeAngle(350.0, 190.0), 1e-9)
        assertEquals(-20.0, RelativeWind.relativeAngle(10.0, 170.0), 1e-9)
    }

    @Test
    fun theRelativeAngleAcceptsUnnormalisedBearings() {
        assertEquals(
            RelativeWind.relativeAngle(90.0, 270.0),
            RelativeWind.relativeAngle(450.0, -90.0),
            1e-9,
        )
    }

    @Test
    fun classificationBoundariesAreExact() {
        assertEquals(WindClass.TAIL, RelativeWind.classify(0.0))
        assertEquals(WindClass.TAIL, RelativeWind.classify(44.999))
        assertEquals(WindClass.TAIL, RelativeWind.classify(-44.999))
        assertEquals(WindClass.CROSS, RelativeWind.classify(45.0))
        assertEquals(WindClass.CROSS, RelativeWind.classify(-45.0))
        assertEquals(WindClass.CROSS, RelativeWind.classify(134.999))
        assertEquals(WindClass.HEAD, RelativeWind.classify(135.0))
        assertEquals(WindClass.HEAD, RelativeWind.classify(-135.0))
        assertEquals(WindClass.HEAD, RelativeWind.classify(180.0))
    }

    @Test
    fun classificationIsSymmetric() {
        var a = 0.0
        while (a <= 180.0) {
            assertEquals(RelativeWind.classify(a), RelativeWind.classify(-a))
            a += 1.0
        }
    }

    @Test
    fun headwindIsSymmetricInTheSignOfTheAngle() {
        for (angle in listOf(0.0, 30.0, 60.0, 120.0, 179.0)) {
            assertEquals(
                RelativeWind.headwindComponent(angle, v),
                RelativeWind.headwindComponent(-angle, v),
                1e-12,
            )
        }
    }

    @Test
    fun headwindNeverExceedsTheWindSpeed() {
        var a = -180.0
        while (a <= 180.0) {
            assertTrue(abs(RelativeWind.headwindComponent(a, v)) <= v + 1e-9)
            a += 3.0
        }
    }

    @Test
    fun itAgreesWithWeatherSampleWindToDir() {
        val sample =
            WeatherSample(
                time = 0L,
                temp = 12.0,
                windSpeed = v,
                windGusts = v * 1.4,
                windDir = 225.0,
                precip = 0.0,
                wmoCode = 3,
                isDay = true,
            )
        val travel = 90.0
        val rel = RelativeWind.relativeAngle(travel, sample.windDir)
        assertEquals(Geo.signedAngleDifference(travel, sample.windToDir), rel, 1e-9)
    }

    @Test
    fun compassIndexBucketsEveryBearing() {
        assertEquals(0, RelativeWind.compassIndex(0.0))
        assertEquals(0, RelativeWind.compassIndex(11.24))
        assertEquals(1, RelativeWind.compassIndex(22.5))
        assertEquals(2, RelativeWind.compassIndex(45.0))
        assertEquals(4, RelativeWind.compassIndex(90.0))
        assertEquals(8, RelativeWind.compassIndex(180.0))
        assertEquals(12, RelativeWind.compassIndex(270.0))
        assertEquals(15, RelativeWind.compassIndex(337.5))
        assertEquals(15, RelativeWind.compassIndex(348.74))
        // 348.75 is the start of the N bucket, which wraps back to index 0.
        assertEquals(0, RelativeWind.compassIndex(348.75))
        assertEquals(0, RelativeWind.compassIndex(360.0))
        assertEquals(0, RelativeWind.compassIndex(-1.0))
        assertEquals(0, RelativeWind.compassIndex(-11.25))
    }

    @Test
    fun compassIndexIsAlwaysInRange() {
        var b = -720.0
        while (b <= 720.0) {
            val i = RelativeWind.compassIndex(b)
            assertTrue("index $i out of range for bearing $b", i in 0..15)
            b += 0.7
        }
    }
}
