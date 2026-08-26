package io.github.glandais.karoo.weather.route

import io.github.glandais.karoo.weather.domain.GeoPoint
import kotlin.math.ceil

/** One sampled point on the remaining part of the route. */
data class RouteSample(val point: GeoPoint, val distanceAlong: Double, val routeBearing: Double)

/**
 * Adaptive sampling of the route ahead of the rider.
 *
 * The rider's own position is never one of these samples: `WeatherRepository` prepends it as index
 * 0 of the assembled [io.github.glandais.karoo.weather.domain.RouteForecast], and it is the only
 * place that does so.
 */
object RouteSampler {

    /** Route samples only; the request budget is `1 + MAX_ROUTE_POINTS`. */
    const val MAX_ROUTE_POINTS = 24

    val SPACINGS_M = listOf(1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0)

    /** Two sample distances closer together than this are treated as the same point. */
    private const val EPSILON_M = 1.0

    fun spacingFor(remainingMetres: Double, maxPoints: Int = MAX_ROUTE_POINTS): Double {
        val n = maxPoints.coerceAtLeast(1)
        val remaining = if (remainingMetres.isFinite()) remainingMetres else 0.0
        if (remaining <= 0.0) return SPACINGS_M.first()
        val raw = remaining / n
        return SPACINGS_M.firstOrNull { it >= raw } ?: (ceil(raw / 10_000.0) * 10_000.0)
    }

    /**
     * Samples strictly AHEAD of [progress] at the adaptive spacing, always including the route end.
     * Ascending `distanceAlong`, size <= [maxPoints]. NEVER emits a point at `distanceAlong ==
     * progress`. Empty when `progress >= path.length`.
     */
    fun sample(
        path: RoutePath,
        progress: Double,
        maxPoints: Int = MAX_ROUTE_POINTS,
    ): List<RouteSample> {
        val n = maxPoints.coerceAtLeast(1)
        val end = path.length
        val from = if (progress.isFinite()) progress.coerceIn(0.0, end) else 0.0
        val remaining = end - from
        if (remaining <= EPSILON_M) return emptyList()

        val spacing = spacingFor(remaining, n)
        val distances = ArrayList<Double>(n)
        var d = from + spacing
        while (d < end - EPSILON_M && distances.size < n) {
            distances.add(d)
            d += spacing
        }
        distances.add(end)

        // Belt and braces: the spacing ladder keeps this within budget, but a rounding edge must
        // never push a 25th point into a 25-point request.
        val capped =
            if (distances.size <= n) distances else distances.take(n - 1) + distances.last()

        return capped.map { RouteSample(path.pointAt(it), it, path.bearingAt(it)) }
    }

    /**
     * Drops every sample whose ETA exceeds `nowSec + horizonSec` and replaces the whole dropped
     * tail with exactly ONE marker sample: the last sample still inside the horizon (or, if none
     * is, the first sample).
     *
     * Returns `Pair(kept samples, index of the marker in the result or null when nothing was
     * dropped)`.
     */
    fun truncateToHorizon(
        samples: List<RouteSample>,
        eta: (Double) -> Long,
        nowSec: Long,
        horizonSec: Long = 11 * 3600L,
    ): Pair<List<RouteSample>, Int?> {
        if (samples.isEmpty()) return Pair(emptyList(), null)
        val limit = nowSec + horizonSec
        var lastInside = -1
        for (i in samples.indices) {
            if (eta(samples[i].distanceAlong) <= limit) lastInside = i else break
        }
        if (lastInside == samples.lastIndex) return Pair(samples, null)
        val markerIndex = if (lastInside < 0) 0 else lastInside
        return Pair(samples.subList(0, markerIndex + 1).toList(), markerIndex)
    }
}
