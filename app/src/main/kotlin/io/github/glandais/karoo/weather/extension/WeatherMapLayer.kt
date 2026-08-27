package io.github.glandais.karoo.weather.extension

import android.content.Context
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.data.WeatherRepository
import io.github.glandais.karoo.weather.domain.RouteForecast
import io.github.glandais.karoo.weather.domain.RoutePointForecast
import io.github.glandais.karoo.weather.domain.WeatherSettings
import io.github.glandais.karoo.weather.domain.WeatherSnapshot
import io.github.glandais.karoo.weather.karoo.consumerFlow
import io.github.glandais.karoo.weather.weather.WmoCodes
import io.github.glandais.karoo.weather.weather.WmoIcons
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnMapZoomLevel
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Draws the route forecast on the Karoo map as vector symbols.
 *
 * ONE INSTANCE PER `startMap` CALL. [previousIds] is instance state, never in the companion object, so a
 * `stopMap` -> `startMap` cycle cannot leak symbol ids across instances.
 */
class WeatherMapLayer(
    private val context: Context,
    private val karoo: KarooSystemService,
    private val repo: WeatherRepository,
) {

    /** Snapshot of what this instance has asked the map to draw. */
    @Volatile private var shownIds: List<String> = emptyList()

    /** Ids currently shown. Read by the caller's `setCancellable`. */
    val previousIds: List<String>
        get() = shownIds

    /**
     * Called from `KarooExtension.startMap` with `WeatherExtension.extensionScope` (the SDK passes no
     * scope). Returns the job; the caller must
     * ```
     * emitter.setCancellable { job.cancel(); emitter.onNext(HideSymbols(previousIds)) }
     * ```
     */
    fun start(emitter: Emitter<MapEffect>, scope: CoroutineScope): Job =
        scope.launch {
            // OnMapZoomLevel does not promise replay-on-subscribe and `combine` emits nothing until every
            // source has emitted, so the zoom flow is seeded: without it no symbol would appear until the
            // rider pinched the map.
            combine(
                    repo.state,
                    karoo
                        .consumerFlow<OnMapZoomLevel>()
                        .onStart { emit(OnMapZoomLevel(DEFAULT_ZOOM)) },
                    repo.settings,
                ) { snapshot: WeatherSnapshot, zoom: OnMapZoomLevel, settings: WeatherSettings ->
                    Render(
                        points = snapshot.bundle?.route?.points.orEmpty(),
                        fetchedAt = snapshot.bundle?.fetchedAt,
                        spacing = symbolSpacingFor(zoom.zoomLevel),
                        enabled = settings.mapLayerEnabled,
                    )
                }
                // Re-emit only when the bundle or the zoom BUCKET changes, never on a GPS tick.
                .distinctUntilChangedBy { Triple(it.fetchedAt, it.spacing, it.enabled) }
                .collect { render -> applyRender(emitter, render) }
        }

    private data class Render(
        val points: List<RoutePointForecast>,
        val fetchedAt: Long?,
        val spacing: Double,
        val enabled: Boolean,
    )

    private fun applyRender(emitter: Emitter<MapEffect>, render: Render) {
        if (!render.enabled || render.points.isEmpty()) {
            hideAll(emitter)
            return
        }
        val symbols = buildSymbols(selectPoints(render.points, render.spacing))
        if (symbols.isEmpty()) {
            hideAll(emitter)
            return
        }
        val ids = symbols.map { it.id }
        val idSet = ids.toSet()
        val stale = shownIds.filterNot { it in idSet }
        if (stale.isNotEmpty()) emitter.onNext(HideSymbols(stale))
        // Re-emitting with the same ids updates the symbols in place.
        emitter.onNext(ShowSymbols(symbols))
        shownIds = ids
    }

    private fun hideAll(emitter: Emitter<MapEffect>) {
        val stale = shownIds
        if (stale.isEmpty()) return
        shownIds = emptyList()
        emitter.onNext(HideSymbols(stale))
    }

    private fun buildSymbols(selected: List<RoutePointForecast>): List<Symbol> {
        val symbols = ArrayList<Symbol>(selected.size * 2)
        selected.forEachIndexed { index, forecast ->
            val sample = forecast.sample
            symbols.add(
                Symbol.Icon(
                    id = "$SYMBOL_PREFIX$index",
                    lat = forecast.point.lat,
                    lng = forecast.point.lon,
                    iconRes = R.drawable.ic_map_wind_arrow,
                    // Symbol.Icon.orientation is "0 is North, 90 is East" - the meteorological convention
                    // after the +180 flip, so the arrow points where the wind is going.
                    orientation = sample.windToDir.toFloat(),
                )
            )
            if (sample.precip >= RouteForecast.WET_THRESHOLD_MM) {
                symbols.add(
                    Symbol.Icon(
                        id = "${SYMBOL_PREFIX}rain-$index",
                        lat = forecast.point.lat,
                        lng = forecast.point.lon,
                        iconRes = WmoIcons.map(WmoCodes.category(sample.wmoCode), sample.isDay),
                        orientation = 0f,
                    )
                )
            }
        }
        return symbols
    }

    companion object {
        const val SYMBOL_PREFIX = "wx-"
        const val DEFAULT_ZOOM = 15.0

        /** >=15 -> 2000, >=12 -> 5000, else 20000 (metres). */
        fun symbolSpacingFor(zoomLevel: Double): Double =
            when {
                zoomLevel >= 15.0 -> 2_000.0
                zoomLevel >= 12.0 -> 5_000.0
                else -> 20_000.0
            }

        /** Greedy selection of points at least [spacing] apart, always keeping first and last. Pure. */
        fun selectPoints(
            points: List<RoutePointForecast>,
            spacing: Double,
        ): List<RoutePointForecast> {
            if (points.size <= 2) return points
            val result = ArrayList<RoutePointForecast>(points.size)
            result.add(points.first())
            var lastKept = points.first().distanceAlong
            for (i in 1 until points.size - 1) {
                val candidate = points[i]
                if (candidate.distanceAlong - lastKept >= spacing) {
                    result.add(candidate)
                    lastKept = candidate.distanceAlong
                }
            }
            result.add(points.last())
            return result
        }
    }
}
