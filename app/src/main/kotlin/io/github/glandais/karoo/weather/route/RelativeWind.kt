package io.github.glandais.karoo.weather.route

import io.github.glandais.karoo.weather.domain.WindClass
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/**
 * Wind expressed relative to the direction of travel.
 *
 * Meteorological wind direction is the direction the wind blows FROM; everything here works with
 * the direction it blows TOWARDS, because that is what a rider feels.
 */
object RelativeWind {

    /** |rel| below this is a tailwind. */
    const val TAIL_LIMIT_DEG = 45.0

    /** |rel| below this (and at or above [TAIL_LIMIT_DEG]) is a crosswind; at or above it, head. */
    const val CROSS_LIMIT_DEG = 135.0

    /**
     * Signed angle between travel bearing and the direction the wind blows TOWARDS, (-180, 180]. 0
     * = pure tailwind, +/-180 = pure headwind.
     */
    fun relativeAngle(travelBearing: Double, windDirFrom: Double): Double {
        val windToDir = Geo.normalizeBearing(windDirFrom + 180.0)
        return Geo.signedAngleDifference(travelBearing, windToDir)
    }

    /** m/s. Positive = headwind, negative = tailwind. */
    fun headwindComponent(relativeAngle: Double, windSpeedMs: Double): Double =
        -cos(Math.toRadians(relativeAngle)) * windSpeedMs

    /** |rel| < 45 -> TAIL, |rel| < 135 -> CROSS, else HEAD. */
    fun classify(relativeAngle: Double): WindClass {
        val a = abs(relativeAngle)
        return when {
            a < TAIL_LIMIT_DEG -> WindClass.TAIL
            a < CROSS_LIMIT_DEG -> WindClass.CROSS
            else -> WindClass.HEAD
        }
    }

    /** Compass label index 0..15 for N, NNE, NE, ... from a bearing in degrees true. */
    fun compassIndex(bearing: Double): Int {
        val b = Geo.normalizeBearing(if (bearing.isFinite()) bearing else 0.0)
        return (floor(b / 22.5 + 0.5).toInt()) % 16
    }
}
