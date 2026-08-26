package io.github.glandais.karoo.weather.domain

import kotlinx.serialization.Serializable

/** A single sampled point along the loaded route, resolved to the weather at its ETA. */
@Serializable
data class RoutePointForecast(
    val point: GeoPoint,
    /** Distance from the route start, metres, in travel direction. */
    val distanceAlong: Double,
    /** Estimated arrival, epoch seconds UTC. */
    val eta: Long,
    /** Route tangent (travel direction) at this point, degrees true. */
    val routeBearing: Double,
    /** Weather interpolated to [eta]. */
    val sample: WeatherSample,
    /**
     * Signed angle between travel direction and the direction the wind blows towards, degrees in
     * (-180, 180]. 0 = pure tailwind, ±180 = pure headwind.
     */
    val relativeWindAngle: Double,
    /** Component of the wind opposing travel, m/s. Positive = headwind, negative = tailwind. */
    val headwindSpeed: Double,
    /** True when this point stands in for everything past the forecast horizon (§6.5). */
    val beyondHorizon: Boolean = false,
)

/** Forecast resolved along the remaining part of the loaded route. */
@Serializable
data class RouteForecast(
    val routeName: String,
    /** Full route length, metres (`NavigatingRoute.routeDistance`). */
    val routeDistance: Double,
    /** Rider progress from route start, metres, at [computedAt]. */
    val progress: Double,
    /** Epoch seconds when this projection was computed. */
    val computedAt: Long,
    /** Assumed speed used for the ETA model, m/s. */
    val assumedSpeed: Double,
    /**
     * Sample points, ascending [RoutePointForecast.distanceAlong]. **Index 0 is always the rider's
     * own position** (`distanceAlong == progress`); indices 1..N-1 are the route samples produced
     * by `RouteSampler.sample`, which never emits a point at `progress`. Size <=
     * [WeatherRequest.MAX_POINTS].
     */
    val points: List<RoutePointForecast> = emptyList(),
    /** Total forecast precipitation over the sampled points, mm. */
    val totalPrecipMm: Double = 0.0,
    /** Distance-along of the first point with precip >= WET_THRESHOLD_MM, or null. */
    val firstWetDistance: Double? = null,
    /** ETA of the first wet point, epoch seconds, or null. */
    val firstWetEta: Long? = null,
) {
    companion object {
        const val WET_THRESHOLD_MM = 0.2
    }
}
