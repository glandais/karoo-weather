package io.github.glandais.karoo.weather.route

import io.github.glandais.karoo.weather.domain.GeoPoint
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Google encoded polyline algorithm format, reader and writer.
 *
 * Decoding is deliberately tolerant: a truncated or corrupt tail simply ends the point list instead
 * of throwing, so a half-received route still renders the part that is intelligible.
 */
object Polyline {

    /** Google encoded polyline. [precision] 5 for coordinates, 1 for the elevation polyline. */
    fun decode(encoded: String, precision: Int = 5): List<GeoPoint> =
        decodePairs(encoded, precision).map { (first, second) -> GeoPoint(first, second) }

    fun encode(points: List<GeoPoint>, precision: Int = 5): String {
        val factor = 10.0.pow(precision)
        val sb = StringBuilder(points.size * 12)
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lon = (p.lon * factor).roundToLong()
            appendValue(sb, lat - prevLat)
            appendValue(sb, lon - prevLon)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    /** `NavigatingRoute.routeElevationPolyline`: pairs of (distanceMetres, elevationMetres). */
    fun decodeElevation(encoded: String): List<Pair<Double, Double>> = decodePairs(encoded, 1)

    private fun decodePairs(encoded: String, precision: Int): List<Pair<Double, Double>> {
        if (encoded.isEmpty()) return emptyList()
        val factor = 10.0.pow(precision)
        val out = ArrayList<Pair<Double, Double>>()
        val cursor = Cursor(encoded)
        var first = 0L
        var second = 0L
        while (cursor.hasNext()) {
            val dFirst = cursor.readSigned() ?: break
            val dSecond = cursor.readSigned() ?: break
            first += dFirst
            second += dSecond
            out.add(Pair(first / factor, second / factor))
        }
        return out
    }

    private fun appendValue(sb: StringBuilder, value: Long) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20L) {
            sb.append(((0x20 or (v and 0x1fL).toInt()) + 63).toChar())
            v = v ushr 5
        }
        sb.append((v.toInt() + 63).toChar())
    }

    /** Single-pass reader; returns null on a truncated or invalid varint. */
    private class Cursor(private val s: String) {
        var index: Int = 0

        fun hasNext(): Boolean = index < s.length

        fun readSigned(): Long? {
            var shift = 0
            var result = 0L
            while (index < s.length) {
                val b = s[index++].code - 63
                if (b < 0 || b > 0x3f) return null
                result = result or ((b.toLong() and 0x1fL) shl shift)
                shift += 5
                if (b < 0x20) {
                    return if (result and 1L != 0L) (result ushr 1).inv() else result ushr 1
                }
                if (shift >= 64) return null
            }
            return null
        }
    }
}
