package io.github.glandais.karoo.weather.route

import io.github.glandais.karoo.weather.domain.GeoPoint
import kotlin.math.cos

/**
 * A decoded route with precomputed cumulative haversine distances.
 *
 * All distances are metres from the route start, in travel direction; construct with an already
 * reversed point list when the rider is riding the route backwards (see [fromPolyline]).
 */
class RoutePath(val points: List<GeoPoint>) {

    init {
        require(points.isNotEmpty()) { "RoutePath needs at least one point" }
    }

    private val cumulative: DoubleArray =
        DoubleArray(points.size).also { c ->
            for (i in 1 until points.size) {
                c[i] = c[i - 1] + Geo.distance(points[i - 1], points[i])
            }
        }

    /** Cumulative length, metres. */
    val length: Double = cumulative[cumulative.size - 1]

    /** Clamped to [0, length]. */
    fun pointAt(distance: Double): GeoPoint {
        if (points.size == 1 || length <= 0.0) return points[0]
        val d = if (distance.isNaN()) 0.0 else distance.coerceIn(0.0, length)
        val i = segmentIndexFor(d)
        val segStart = cumulative[i]
        val segLength = cumulative[i + 1] - segStart
        if (segLength <= 0.0) return points[i]
        val f = ((d - segStart) / segLength).coerceIn(0.0, 1.0)
        val a = points[i]
        val b = points[i + 1]
        val lat = a.lat + (b.lat - a.lat) * f
        val lon = Geo.normalizeLongitude(a.lon + Geo.lonDelta(a.lon, b.lon) * f)
        return GeoPoint(lat, lon)
    }

    /** Travel-direction tangent, degrees true. Clamps the lookahead at the route end. */
    fun bearingAt(distance: Double, lookaheadMetres: Double = 25.0): Double {
        if (points.size == 1 || length <= 0.0) return 0.0
        // coerceIn(0, length) and not coerceIn(1e-3, length): on a route shorter than a
        // millimetre the latter would be an inverted range and throw.
        val look = lookaheadMetres.coerceIn(0.0, length)
        val d = if (distance.isNaN()) 0.0 else distance.coerceIn(0.0, length)
        return if (d + look <= length) {
            Geo.bearing(pointAt(d), pointAt(d + look))
        } else {
            Geo.bearing(pointAt(length - look), pointAt(length))
        }
    }

    /**
     * Distance-along of the point on the path nearest to [p], metres in [0, length].
     *
     * Used as the progress fallback on breadcrumb routes where `DISTANCE_TO_DESTINATION` never
     * streams. O(n) over segments, perpendicular projection within each segment using a local
     * equirectangular approximation (exact enough well below the 200 m/update progress guard).
     *
     * On a self-crossing route several segments are equally near; the tie-break is deterministic:
     * the earliest segment wins.
     */
    fun nearestDistanceTo(p: GeoPoint): Double {
        if (points.size == 1 || length <= 0.0) return 0.0
        var best = 0.0
        var bestSq = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val cosLat = cos(Math.toRadians((a.lat + b.lat) / 2.0))
            val bx = Geo.lonDelta(a.lon, b.lon) * cosLat
            val by = b.lat - a.lat
            val px = Geo.lonDelta(a.lon, p.lon) * cosLat
            val py = p.lat - a.lat
            val segSq = bx * bx + by * by
            val t = if (segSq <= 0.0) 0.0 else ((px * bx + py * by) / segSq).coerceIn(0.0, 1.0)
            val dx = px - t * bx
            val dy = py - t * by
            val dSq = dx * dx + dy * dy
            if (dSq < bestSq) {
                bestSq = dSq
                best = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
            }
        }
        return best.coerceIn(0.0, length)
    }

    /** Index of the segment containing [d], in [0, points.size - 2]. */
    private fun segmentIndexFor(d: Double): Int {
        var lo = 0
        var hi = points.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulative[mid] <= d) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        /** Returns null for an empty/undecodable polyline or fewer than 2 points. */
        fun fromPolyline(encoded: String, reversed: Boolean = false): RoutePath? {
            if (encoded.isBlank()) return null
            val decoded = Polyline.decode(encoded, precision = 5)
            if (decoded.size < 2) return null
            return RoutePath(if (reversed) decoded.asReversed().toList() else decoded)
        }
    }
}
