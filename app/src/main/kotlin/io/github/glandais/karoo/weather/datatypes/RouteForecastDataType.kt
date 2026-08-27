package io.github.glandais.karoo.weather.datatypes

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import io.github.glandais.karoo.weather.MainActivity
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.data.WeatherRepository
import io.github.glandais.karoo.weather.datatypes.views.BitmapSurface
import io.github.glandais.karoo.weather.datatypes.views.FieldChrome
import io.github.glandais.karoo.weather.datatypes.views.GlanceChrome
import io.github.glandais.karoo.weather.datatypes.views.RouteStripLayout
import io.github.glandais.karoo.weather.datatypes.views.StripBitmapBuilder
import io.github.glandais.karoo.weather.domain.DataTypeIds
import io.github.glandais.karoo.weather.domain.DistanceUnit
import io.github.glandais.karoo.weather.domain.RouteForecast
import io.github.glandais.karoo.weather.domain.Units
import io.github.glandais.karoo.weather.domain.WeatherSample
import io.github.glandais.karoo.weather.domain.WeatherSnapshot
import io.github.glandais.karoo.weather.karoo.safeNext
import io.github.glandais.karoo.weather.karoo.safeUpdate
import io.github.glandais.karoo.weather.karoo.throttleEach
import io.github.glandais.karoo.weather.weather.Interpolation
import io.github.glandais.karoo.weather.weather.WmoIcons
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * `route-forecast` — a timeline of the **remaining** route, drawn as **one** bitmap sized to
 * `config.viewSize` (DESIGN §3.4).
 *
 * Column 0 is always the rider's own position, so it always reads `+0 km`. With no route loaded the
 * same layout falls back to the hourly forecast at the rider's position, labelled by clock hour.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class RouteForecastDataType(private val context: Context, private val repo: WeatherRepository) :
    DataTypeImpl(DataTypeIds.EXTENSION, DataTypeIds.ROUTE_FORECAST) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.i(
            FieldChrome.LOG_TAG,
            "startView $typeId grid=${config.gridSize} view=${config.viewSize} " +
                "text=${config.textSize} preview=${config.preview}",
        )
        val glance = GlanceRemoteViews()
        val night = FieldChrome.night(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Per-startView state: a full-field bitmap is ~1.5 MB and this loop repaints every few
        // seconds for the whole ride (see BitmapSurface).
        val surface = BitmapSurface()

        // ONE coroutine: the cleared state must land BEFORE the loop's first custom state, and two
        // coroutines on Dispatchers.IO give no such ordering (a lost race blanks the field).
        scope.launch {
            if (!emitter.safeNext(UpdateGraphicConfig(showHeader = false))) return@launch
            if (!emitter.safeNext(FieldChrome.clearState())) return@launch

            if (config.preview) {
                render(
                    glance,
                    context,
                    config,
                    PreviewData.route,
                    PreviewData.hourly,
                    PreviewData.snapshot.units,
                    night,
                    surface,
                    emitter,
                )
                awaitCancellation()
            }
            FieldLoop.flow(repo.karooOrNull, repo, dataTypeId)
                .throttleEach { it.refreshMs }
                .collect { data ->
                    if (!data.visible) return@collect
                    if (!emitter.safeNext(FieldLoop.customState(context, data.snapshot, night))) {
                        scope.cancel()
                        return@collect
                    }
                    val ok =
                        render(
                            glance,
                            context,
                            config,
                            repo.routeForecast(),
                            hourlyOf(data.snapshot),
                            data.snapshot.units,
                            night,
                            surface,
                            emitter,
                        )
                    if (!ok) scope.cancel()
                }
        }

        // Cancelling the scope releases the parent SupervisorJob too, so nothing survives stopView.
        emitter.setCancellable {
            scope.cancel()
            surface.release()
        }
    }

    /** False when the host emitter is gone and this view must stop. */
    private suspend fun render(
        glance: GlanceRemoteViews,
        context: Context,
        config: ViewConfig,
        route: RouteForecast?,
        hourly: List<WeatherSample>,
        units: Units,
        night: Boolean,
        surface: BitmapSurface,
        emitter: ViewEmitter,
    ): Boolean {
        val rows = RouteStripLayout.rowsFor(config)
        val maxColumns = RouteStripLayout.maxColumnsFor(config)
        val count = FieldChrome.columnsFor(config.viewSize, maxColumns)
        val columns =
            if (route != null && route.points.isNotEmpty()) {
                routeColumns(context, route, units, count)
            } else {
                clockColumns(hourly, count)
            }
        if (columns.isEmpty()) return true

        val width = config.viewSize.first.coerceIn(MIN_PX, MAX_PX)
        val height = config.viewSize.second.coerceIn(MIN_PX, MAX_PX)
        val bitmap =
            StripBitmapBuilder.render(
                context = context,
                widthPx = width,
                heightPx = height,
                columns = columns,
                rows = rows,
                night = night,
                textSizeSp = config.textSize,
                units = units,
                reuse = surface.get(width, height),
            )
        surface.keep(bitmap)
        val result =
            glance.compose(context, DpSize.Unspecified) {
                val modifier =
                    if (config.preview) {
                        GlanceModifier.fillMaxSize()
                    } else {
                        GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>())
                    }
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = null,
                    modifier = modifier,
                    contentScale = ContentScale.Fit,
                )
            }
        return emitter.safeUpdate(result.remoteViews)
    }

    private fun routeColumns(
        context: Context,
        route: RouteForecast,
        units: Units,
        count: Int,
    ): List<StripBitmapBuilder.Column> =
        // Spread across the WHOLE remaining route: `take(count)` would show the nearest five of up
        // to 25 samples, so a storm at +60 km — the thing this field exists to warn about — never
        // reaches a column (DESIGN §3.4 spaces them +0/+18/+36/+54/+72 km).
        RouteStripLayout.columnIndices(route.points.size, count).map { index ->
            val point = route.points[index]
            StripBitmapBuilder.Column(
                icon = WmoIcons.fieldForCode(point.sample.wmoCode, point.sample.isDay),
                tempC = point.sample.temp,
                relativeWindAngle = point.relativeWindAngle,
                headwindMs = point.headwindSpeed,
                label = distanceLabel(context, point.distanceAlong - route.progress, units),
                etaLabel = GlanceChrome.clockLabel(point.eta),
                wet = point.sample.precip >= RouteForecast.WET_THRESHOLD_MM,
                beyondHorizon = point.beyondHorizon,
            )
        }

    /** No route loaded: the same layout, labelled by clock hour (DESIGN §3.4). */
    private fun clockColumns(
        hourly: List<WeatherSample>,
        count: Int,
    ): List<StripBitmapBuilder.Column> =
        hourly.take(count).map { entry ->
            StripBitmapBuilder.Column(
                icon = WmoIcons.fieldForCode(entry.wmoCode, entry.isDay),
                tempC = entry.temp,
                relativeWindAngle = entry.windToDir,
                headwindMs = 0.0,
                label = GlanceChrome.hourLabel(entry.time),
                etaLabel = null,
                wet = entry.precip >= RouteForecast.WET_THRESHOLD_MM,
                beyondHorizon = false,
            )
        }

    /** `+0 km` / `+18 km`, in the rider's distance unit. */
    fun distanceLabel(context: Context, metresAhead: Double, units: Units): String {
        val ahead = if (metresAhead < 0.0) 0.0 else metresAhead
        val value = units.distance(ahead)
        val text =
            if (value < 10.0) String.format(Locale.getDefault(), "%.1f", value)
            else value.roundToInt().toString()
        val res =
            if (units.distance == DistanceUnit.MILES) R.string.dist_ahead_mi
            else R.string.dist_ahead_km
        return context.getString(res, text)
    }

    private fun hourlyOf(snapshot: WeatherSnapshot): List<WeatherSample> {
        val here = snapshot.bundle?.here ?: return emptyList()
        val now = System.currentTimeMillis() / 1000
        val ahead = here.hourly.filter { it.time >= now - HOUR_SEC }
        if (ahead.isNotEmpty()) return ahead
        return listOfNotNull(Interpolation.sampleAt(here.hourly, now) ?: here.current)
    }

    private companion object {
        const val HOUR_SEC = 3600L
        const val MIN_PX = 24
        const val MAX_PX = 1024
    }
}
