package io.github.glandais.karoo.weather.route

import io.github.glandais.karoo.weather.domain.GeoPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Spherical-earth geodesy. Pure JVM, no Android, no third-party geometry library. */
object Geo {

    /** IUGG mean earth radius, metres. */
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Kilometres per degree of latitude, used only by [roundToGrid]. */
    private const val KM_PER_DEG_LAT = 111.32

    /** Haversine, metres. */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val sinLat = sin(dLat / 2.0)
        val sinLon = sin(dLon / 2.0)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        return 2.0 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** Initial great-circle bearing, degrees true in [0, 360). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        if (y == 0.0 && x == 0.0) return 0.0
        return normalizeBearing(Math.toDegrees(atan2(y, x)))
    }

    fun destination(from: GeoPoint, metres: Double, bearingDeg: Double): GeoPoint {
        val angular = metres / EARTH_RADIUS_M
        val brg = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val sinLat2 = sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(brg)
        val lat2 = asin(sinLat2.coerceIn(-1.0, 1.0))
        val lon2 =
            lon1 +
                atan2(
                    sin(brg) * sin(angular) * cos(lat1),
                    cos(angular) - sin(lat1) * sin(lat2),
                )
        return GeoPoint(Math.toDegrees(lat2), normalizeLongitude(Math.toDegrees(lon2)))
    }

    /** Signed shortest arc from [a] to [b], degrees in (-180, 180]. */
    fun signedAngleDifference(a: Double, b: Double): Double {
        val raw = normalizeBearing(b - a)
        return if (raw > 180.0) raw - 360.0 else raw
    }

    /**
     * Privacy grid. Latitude uses 111.32 km/deg; longitude uses `111.32 * cos(lat)` km/deg, so
     * cells stay roughly square at any latitude. A non-positive [km] returns the point untouched.
     */
    fun roundToGrid(p: GeoPoint, km: Double): GeoPoint {
        if (km <= 0.0 || !km.isFinite()) return p
        val latStep = km / KM_PER_DEG_LAT
        val lat = (Math.round(p.lat / latStep) * latStep).coerceIn(-90.0, 90.0)
        val cosLat = cos(Math.toRadians(lat))
        val lonStep = km / (KM_PER_DEG_LAT * cosLat.coerceAtLeast(1e-6))
        val lon =
            if (lonStep >= 360.0) 0.0 else normalizeLongitude(Math.round(p.lon / lonStep) * lonStep)
        return GeoPoint(lat, lon)
    }

    /**
     * [roundToGrid] with hysteresis: while the rider is still within [MARGIN] of the previous
     * cell's own half-width, [previous] is kept.
     *
     * Without it, metre-scale GPS noise on a road that runs along a cell boundary flips the rounded
     * position on every fix, and each flip publishes a refresh key that cancels the fetch the last
     * one started.
     */
    fun roundToGridSticky(previous: GeoPoint?, p: GeoPoint, km: Double): GeoPoint {
        val fresh = roundToGrid(p, km)
        if (previous == null || km <= 0.0 || !km.isFinite() || previous == fresh) return fresh
        val threshold = km * 1000.0 * 0.5 * (1.0 + MARGIN)
        return if (distance(p, previous) <= threshold) previous else fresh
    }

    /** How far past a cell boundary the rider must be before the cell changes. */
    const val MARGIN = 0.2

    /** Wraps any angle into [0, 360). */
    internal fun normalizeBearing(deg: Double): Double {
        val m = deg % 360.0
        return if (m < 0.0) m + 360.0 else m
    }

    /** Wraps a longitude into [-180, 180). */
    internal fun normalizeLongitude(deg: Double): Double {
        val m = (deg + 180.0) % 360.0
        return (if (m < 0.0) m + 360.0 else m) - 180.0
    }

    /** Shortest signed longitude delta from [fromLon] to [toLon], degrees in (-180, 180]. */
    internal fun lonDelta(fromLon: Double, toLon: Double): Double =
        signedAngleDifference(fromLon, toLon)
}
