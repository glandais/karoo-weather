package io.github.glandais.karoo.weather.weather

import io.github.glandais.karoo.weather.domain.PrecipBucket
import io.github.glandais.karoo.weather.domain.WeatherSample
import kotlin.math.roundToInt

/**
 * Time interpolation of an hourly forecast series.
 *
 * Continuous fields (temperature, wind speed, gusts, cloud cover) are linearly interpolated. Wind
 * direction is interpolated on the shortest arc. Precipitation is an **accumulation over an
 * interval**, not a level, so it is never lerped: it is taken from the containing hour. Categorical
 * fields (WMO code, is-day) are taken from the nearest hour.
 */
object Interpolation {

    fun lerp(a: Double, b: Double, f: Double): Double = a + (b - a) * f

    /** Shortest-arc interpolation of two bearings, result in [0, 360). */
    fun lerpAngle(a: Double, b: Double, f: Double): Double {
        val delta = ((b - a + 540.0) % 360.0 + 360.0) % 360.0 - 180.0
        val raw = a + delta * f
        return ((raw % 360.0) + 360.0) % 360.0
    }

    /**
     * Continuous fields lerped, `windDir` via [lerpAngle], `precip`/`precipProb` taken from the
     * CONTAINING hour (the hour bucket labelled [a], which spans `[a.time, b.time)`), `wmoCode` and
     * `isDay` from the NEAREST hour.
     *
     * [f] is clamped to `[0, 1]`; the result carries [atTime] as its `time`.
     */
    fun lerpSample(a: WeatherSample, b: WeatherSample, f: Double, atTime: Long): WeatherSample {
        val g = f.coerceIn(0.0, 1.0)
        val nearest = if (g < 0.5) a else b
        val aApparent = a.apparentTemp
        val bApparent = b.apparentTemp
        val apparent =
            when {
                aApparent != null && bApparent != null -> lerp(aApparent, bApparent, g)
                else -> nearest.apparentTemp
            }
        val aCloud = a.cloudCover
        val bCloud = b.cloudCover
        val cloud =
            when {
                aCloud != null && bCloud != null ->
                    lerp(aCloud.toDouble(), bCloud.toDouble(), g).roundToInt()
                else -> nearest.cloudCover
            }
        return WeatherSample(
            time = atTime,
            temp = lerp(a.temp, b.temp, g),
            apparentTemp = apparent,
            windSpeed = lerp(a.windSpeed, b.windSpeed, g),
            windGusts = lerp(a.windGusts, b.windGusts, g),
            windDir = lerpAngle(a.windDir, b.windDir, g),
            precip = a.precip,
            precipProb = a.precipProb,
            wmoCode = nearest.wmoCode,
            cloudCover = cloud,
            isDay = nearest.isDay,
        )
    }

    /**
     * The series value at [epochSec]. Null when the series is empty. Outside the series range the
     * first/last entry is returned verbatim (including its own `time`).
     */
    fun sampleAt(series: List<WeatherSample>, epochSec: Long): WeatherSample? {
        if (series.isEmpty()) return null
        val first = series.first()
        val last = series.last()
        if (epochSec <= first.time) return first
        if (epochSec >= last.time) return last
        for (i in 0 until series.size - 1) {
            val a = series[i]
            val b = series[i + 1]
            if (epochSec >= a.time && epochSec <= b.time) {
                val span = (b.time - a.time).toDouble()
                val f = if (span <= 0.0) 0.0 else (epochSec - a.time) / span
                return lerpSample(a, b, f, epochSec)
            }
        }
        return last
    }

    /** First [count] buckets at or after [fromSec]. Empty when the series has none. */
    fun bucketsFrom(series: List<PrecipBucket>, fromSec: Long, count: Int): List<PrecipBucket> {
        if (count <= 0) return emptyList()
        return series.asSequence().filter { it.time >= fromSec }.take(count).toList()
    }

    /** Fallback when minutely15 is unavailable: 3600 s buckets from the hourly series. */
    fun hourlyToBuckets(
        series: List<WeatherSample>,
        fromSec: Long,
        count: Int,
    ): List<PrecipBucket> {
        if (count <= 0) return emptyList()
        return series
            .asSequence()
            .filter { it.time >= fromSec }
            .take(count)
            .map {
                PrecipBucket(
                    time = it.time,
                    durationSec = 3600,
                    mm = it.precip,
                    probability = it.precipProb,
                )
            }
            .toList()
    }
}
