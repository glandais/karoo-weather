package io.github.glandais.karoo.weather.datatypes

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import io.github.glandais.karoo.weather.MainActivity
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.data.WeatherRepository
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
import io.github.glandais.karoo.weather.karoo.throttle
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

        val configJob =
            scope.launch {
                emitter.onNext(UpdateGraphicConfig(showHeader = false))
                emitter.onNext(FieldChrome.clearState())
                awaitCancellation()
            }

        val viewJob =
            scope.launch {
                if (config.preview) {
                    render(
                        glance,
                        context,
                        config,
                        PreviewData.route,
                        PreviewData.hourly,
                        PreviewData.snapshot.units,
                        night,
                        emitter,
                    )
                    awaitCancellation()
                }
                val refreshMs = FieldLoop.refreshMs(repo.karooOrNull, repo)
                FieldLoop.flow(repo.karooOrNull, repo, dataTypeId).throttle(refreshMs).collect {
                    data ->
                    if (!data.visible) return@collect
                    emitter.onNext(FieldLoop.customState(context, data.snapshot, night))
                    render(
                        glance,
                        context,
                        config,
                        repo.routeForecast(),
                        hourlyOf(data.snapshot),
                        data.snapshot.units,
                        night,
                        emitter,
                    )
                }
            }

        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
        }
    }

    private suspend fun render(
        glance: GlanceRemoteViews,
        context: Context,
        config: ViewConfig,
        route: RouteForecast?,
        hourly: List<WeatherSample>,
        units: Units,
        night: Boolean,
        emitter: ViewEmitter,
    ) {
        val rows = RouteStripLayout.rowsFor(config)
        val maxColumns = RouteStripLayout.maxColumnsFor(config)
        val count = FieldChrome.columnsFor(config.viewSize, maxColumns)
        val columns =
            if (route != null && route.points.isNotEmpty()) {
                routeColumns(context, route, units, count)
            } else {
                clockColumns(hourly, count)
            }
        if (columns.isEmpty()) return

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
            )
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
        emitter.updateView(result.remoteViews)
    }

    private fun routeColumns(
        context: Context,
        route: RouteForecast,
        units: Units,
        count: Int,
    ): List<StripBitmapBuilder.Column> =
        route.points.take(count).map { point ->
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
