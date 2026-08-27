package io.github.glandais.karoo.weather.datatypes

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.data.WeatherRepository
import io.github.glandais.karoo.weather.datatypes.views.BarChartBuilder
import io.github.glandais.karoo.weather.datatypes.views.FieldChrome
import io.github.glandais.karoo.weather.datatypes.views.GlanceChrome
import io.github.glandais.karoo.weather.domain.DataTypeIds
import io.github.glandais.karoo.weather.domain.PrecipBucket
import io.github.glandais.karoo.weather.karoo.throttle
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * `rain-next-hour` — eight 15-minute nowcast bars (two hours), or hourly bars when the nowcast is
 * unavailable, drawn as **one** bitmap sized to `config.viewSize` (DESIGN §3.3).
 *
 * It has no `startStream`: "millimetres in some window" is not a number another extension could
 * interpret without the window, and ARCHITECTURE §7.2 only asks for a stream where a meaningful
 * single number exists.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class RainNextHourDataType(private val context: Context, private val repo: WeatherRepository) :
    DataTypeImpl(DataTypeIds.EXTENSION, DataTypeIds.RAIN_NEXT_HOUR) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.i(
            FieldChrome.LOG_TAG,
            "startView $typeId grid=${config.gridSize} view=${config.viewSize} " +
                "text=${config.textSize} preview=${config.preview}",
        )
        val glance = GlanceRemoteViews()
        val night = FieldChrome.night(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(FieldChrome.clearState())
            awaitCancellation()
        }

        scope.launch {
            if (config.preview) {
                render(glance, context, config, PreviewData.buckets, night, emitter)
                awaitCancellation()
            }
            val refreshMs = FieldLoop.refreshMs(repo.karooOrNull, repo)
            FieldLoop.flow(repo.karooOrNull, repo, dataTypeId).throttle(refreshMs).collect { data ->
                if (!data.visible) return@collect
                emitter.onNext(FieldLoop.customState(context, data.snapshot, night))
                render(glance, context, config, repo.rainBuckets(NOWCAST_BARS), night, emitter)
            }
        }

        // Cancelling the scope, not the two jobs individually: it releases the parent
        // SupervisorJob too, so nothing survives a stopView.
        emitter.setCancellable {
            scope.cancel()
        }
    }

    private suspend fun render(
        glance: GlanceRemoteViews,
        context: Context,
        config: ViewConfig,
        buckets: List<PrecipBucket>,
        night: Boolean,
        emitter: ViewEmitter,
    ) {
        if (buckets.isEmpty()) return
        val width = config.viewSize.first.coerceIn(MIN_PX, MAX_PX)
        val height = config.viewSize.second.coerceIn(MIN_PX, MAX_PX)
        // Labels only where a 10 sp line fits; the probability overlay only from (60,30) up.
        val showLabels = config.gridSize.first >= 60 && FieldChrome.labelFits(config)
        val showProbability = config.gridSize.second >= 30
        val bitmap =
            BarChartBuilder.render(
                widthPx = width,
                heightPx = height,
                buckets = buckets,
                night = night,
                showLabels = showLabels,
                showProbability = showProbability,
                dryLabel = context.getString(R.string.state_dry),
                summary = if (showLabels) summary(context, buckets) else null,
            )
        val result =
            glance.compose(context, DpSize.Unspecified) {
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        emitter.updateView(result.remoteViews)
    }

    /** "Rain at 14:20" plus the two-hour total, or just the total on a dry forecast. */
    private fun summary(context: Context, buckets: List<PrecipBucket>): String {
        val total = BarChartBuilder.totalMm(buckets)
        val totalText = context.getString(R.string.rain_total_2h, formatMm(total))
        val first = BarChartBuilder.firstWetTime(buckets) ?: return totalText
        return "${context.getString(R.string.rain_starts_at, GlanceChrome.clockLabel(first))} · " +
            totalText
    }

    private fun formatMm(mm: Double): String = String.format(Locale.getDefault(), "%.1f", mm)

    private companion object {
        const val NOWCAST_BARS = 8
        const val MIN_PX = 24
        const val MAX_PX = 1024
    }
}
