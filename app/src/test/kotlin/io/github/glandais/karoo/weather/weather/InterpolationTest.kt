package io.github.glandais.karoo.weather.weather

import io.github.glandais.karoo.weather.domain.PrecipBucket
import io.github.glandais.karoo.weather.domain.WeatherSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpolationTest {

    private val t0 = 1_700_000_000L
    private val hour = 3_600L

    private fun sample(
        time: Long,
        temp: Double = 10.0,
        windDir: Double = 0.0,
        windSpeed: Double = 5.0,
        precip: Double = 0.0,
        prob: Int? = null,
        code: Int = 3,
        isDay: Boolean = true,
        apparent: Double? = null,
        cloud: Int? = null,
    ) =
        WeatherSample(
            time = time,
            temp = temp,
            apparentTemp = apparent,
            windSpeed = windSpeed,
            windGusts = windSpeed * 2,
            windDir = windDir,
            precip = precip,
            precipProb = prob,
            wmoCode = code,
            cloudCover = cloud,
            isDay = isDay,
        )

    @Test
    fun `lerp is linear`() {
        assertEquals(10.0, Interpolation.lerp(10.0, 20.0, 0.0), 1e-9)
        assertEquals(15.0, Interpolation.lerp(10.0, 20.0, 0.5), 1e-9)
        assertEquals(20.0, Interpolation.lerp(10.0, 20.0, 1.0), 1e-9)
        assertEquals(-5.0, Interpolation.lerp(-10.0, 0.0, 0.5), 1e-9)
    }

    @Test
    fun `lerpAngle takes the shortest arc across the seam`() {
        assertEquals(0.0, Interpolation.lerpAngle(350.0, 10.0, 0.5), 1e-9)
        assertEquals(355.0, Interpolation.lerpAngle(350.0, 10.0, 0.25), 1e-9)
        assertEquals(0.0, Interpolation.lerpAngle(10.0, 350.0, 0.5), 1e-9)
        assertEquals(90.0, Interpolation.lerpAngle(80.0, 100.0, 0.5), 1e-9)
    }

    @Test
    fun `lerpAngle result is always in 0 until 360`() {
        for (a in 0 until 360 step 17) {
            for (b in 0 until 360 step 23) {
                for (f in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                    val r = Interpolation.lerpAngle(a.toDouble(), b.toDouble(), f)
                    assertTrue("a=$a b=$b f=$f -> $r", r >= 0.0 && r < 360.0)
                }
            }
        }
    }

    @Test
    fun `lerpSample interpolates continuous fields and keeps precip from the containing hour`() {
        val a = sample(t0, temp = 10.0, windDir = 350.0, windSpeed = 4.0, precip = 2.0, prob = 80)
        val b =
            sample(t0 + hour, temp = 20.0, windDir = 10.0, windSpeed = 8.0, precip = 0.0, prob = 5)
        val mid = Interpolation.lerpSample(a, b, 0.5, t0 + hour / 2)

        assertEquals(t0 + hour / 2, mid.time)
        assertEquals(15.0, mid.temp, 1e-9)
        assertEquals(6.0, mid.windSpeed, 1e-9)
        assertEquals(12.0, mid.windGusts, 1e-9)
        assertEquals(0.0, mid.windDir, 1e-9)
        // Precipitation is an accumulation, never averaged.
        assertEquals(2.0, mid.precip, 1e-9)
        assertEquals(80, mid.precipProb)
    }

    @Test
    fun `lerpSample takes categorical fields from the nearest hour`() {
        val a = sample(t0, code = 3, isDay = true)
        val b = sample(t0 + hour, code = 95, isDay = false)

        val early = Interpolation.lerpSample(a, b, 0.2, t0 + 720)
        assertEquals(3, early.wmoCode)
        assertEquals(true, early.isDay)

        val late = Interpolation.lerpSample(a, b, 0.8, t0 + 2_880)
        assertEquals(95, late.wmoCode)
        assertEquals(false, late.isDay)
    }

    @Test
    fun `lerpSample interpolates optional fields only when both sides have them`() {
        val a = sample(t0, apparent = 8.0, cloud = 20)
        val b = sample(t0 + hour, apparent = 12.0, cloud = 80)
        val mid = Interpolation.lerpSample(a, b, 0.5, t0 + hour / 2)
        assertEquals(10.0, mid.apparentTemp!!, 1e-9)
        assertEquals(50, mid.cloudCover)

        val partial = Interpolation.lerpSample(a, sample(t0 + hour), 0.9, t0 + hour)
        assertNull(partial.apparentTemp)
        assertNull(partial.cloudCover)
    }

    @Test
    fun `sampleAt returns null for an empty series`() {
        assertNull(Interpolation.sampleAt(emptyList(), t0))
    }

    @Test
    fun `sampleAt clamps outside the series bounds`() {
        val series = listOf(sample(t0, temp = 10.0), sample(t0 + hour, temp = 20.0))
        assertSame(series.first(), Interpolation.sampleAt(series, t0 - 10_000))
        assertSame(series.last(), Interpolation.sampleAt(series, t0 + 10 * hour))
        assertSame(series.first(), Interpolation.sampleAt(series, t0))
        assertSame(series.last(), Interpolation.sampleAt(series, t0 + hour))
    }

    @Test
    fun `sampleAt interpolates inside the series`() {
        val series =
            listOf(
                sample(t0, temp = 10.0),
                sample(t0 + hour, temp = 20.0),
                sample(t0 + 2 * hour, temp = 30.0),
            )
        val at = Interpolation.sampleAt(series, t0 + hour + 900)!!
        assertEquals(t0 + hour + 900, at.time)
        assertEquals(22.5, at.temp, 1e-9)
    }

    @Test
    fun `sampleAt tolerates a single-entry series`() {
        val series = listOf(sample(t0, temp = 7.0))
        assertSame(series.first(), Interpolation.sampleAt(series, t0 - 1))
        assertSame(series.first(), Interpolation.sampleAt(series, t0 + 1))
    }

    @Test
    fun `bucketsFrom takes the first count buckets at or after the cutoff`() {
        val buckets = (0 until 8).map { PrecipBucket(t0 + it * 900L, 900, it * 0.1) }
        val taken = Interpolation.bucketsFrom(buckets, t0 + 1_800, 3)
        assertEquals(3, taken.size)
        assertEquals(t0 + 1_800, taken.first().time)
        assertEquals(t0 + 3_600, taken.last().time)

        assertEquals(emptyList<PrecipBucket>(), Interpolation.bucketsFrom(buckets, t0, 0))
        assertEquals(
            emptyList<PrecipBucket>(),
            Interpolation.bucketsFrom(buckets, t0 + 100 * hour, 4),
        )
        assertEquals(8, Interpolation.bucketsFrom(buckets, t0, 99).size)
    }

    @Test
    fun `hourlyToBuckets derives hour-long buckets`() {
        val series =
            listOf(
                sample(t0, precip = 0.4, prob = 30),
                sample(t0 + hour, precip = 1.2, prob = 60),
                sample(t0 + 2 * hour, precip = 0.0, prob = 5),
            )
        val buckets = Interpolation.hourlyToBuckets(series, t0 + hour, 5)
        assertEquals(2, buckets.size)
        assertEquals(t0 + hour, buckets[0].time)
        assertEquals(3600, buckets[0].durationSec)
        assertEquals(1.2, buckets[0].mm, 1e-9)
        assertEquals(60, buckets[0].probability)
        assertEquals(emptyList<PrecipBucket>(), Interpolation.hourlyToBuckets(series, t0, 0))
    }
}
