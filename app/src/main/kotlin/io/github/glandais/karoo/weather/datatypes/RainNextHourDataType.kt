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
import io.github.glandais.karoo.weather.datatypes.views.BitmapSurface
import io.github.glandais.karoo.weather.datatypes.views.FieldChrome
import io.github.glandais.karoo.weather.datatypes.views.GlanceChrome
import io.github.glandais.karoo.weather.domain.DataTypeIds
import io.github.glandais.karoo.weather.domain.PrecipBucket
import io.github.glandais.karoo.weather.karoo.safeNext
import io.github.glandais.karoo.weather.karoo.safeUpdate
import io.github.glandais.karoo.weather.karoo.throttleEach
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
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
        // One full-view ARGB_8888 bitmap is ~1.5 MB at (60,60); allocating a fresh one every
        // repaint churns megabytes a minute for the whole ride. It is per-startView state, never a
        // property of this shared DataTypeImpl, because previews run concurrently.
        val surface = BitmapSurface()

        // ONE coroutine: the cleared state must land BEFORE the loop's first custom state, and two
        // coroutines on Dispatchers.IO give no such ordering (a lost race blanks the field).
        scope.launch {
            if (!emitter.safeNext(UpdateGraphicConfig(showHeader = false))) return@launch
            if (!emitter.safeNext(FieldChrome.clearState())) return@launch

            if (config.preview) {
                render(glance, context, config, PreviewData.buckets, night, surface, emitter)
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
                    val buckets = repo.rainBuckets(NOWCAST_BARS)
                    if (!render(glance, context, config, buckets, night, surface, emitter)) {
                        scope.cancel()
                    }
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
        buckets: List<PrecipBucket>,
        night: Boolean,
        surface: BitmapSurface,
        emitter: ViewEmitter,
    ): Boolean {
        if (buckets.isEmpty()) return true
        val width = config.viewSize.first.coerceIn(MIN_PX, MAX_PX)
        val height = config.viewSize.second.coerceIn(MIN_PX, MAX_PX)
        // The time axis needs the width of a (60,·) field; the summary only needs a legible line,
        // and DESIGN §3.3's own (30,15) mock keeps the total when the axis is dropped.
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
                summary =
                    if (FieldChrome.labelFits(config)) {
                        summary(context, buckets, showLabels, nowSecFor(config))
                    } else {
                        null
                    },
                reuse = surface.get(width, height),
            )
        surface.keep(bitmap)
        val result =
            glance.compose(context, DpSize.Unspecified) {
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        return emitter.safeUpdate(result.remoteViews)
    }

    /**
     * The total over the window the bars ACTUALLY span — eight hourly fallback buckets are eight
     * hours, not the two the nowcast covers — prefixed with when the rain starts when [full].
     */
    private fun summary(
        context: Context,
        buckets: List<PrecipBucket>,
        full: Boolean,
        nowSec: Long,
    ): String {
        val total = BarChartBuilder.totalMm(buckets)
        val totalText =
            context.getString(
                R.string.rain_total_window,
                formatMm(total),
                formatHours(BarChartBuilder.windowSeconds(buckets)),
            )
        if (!full) return totalText
        val prefix =
            when (val start = BarChartBuilder.firstWetTime(buckets, nowSec)) {
                null -> return totalText
                is BarChartBuilder.WetStart.Now -> context.getString(R.string.rain_now)
                is BarChartBuilder.WetStart.At ->
                    context.getString(
                        R.string.rain_starts_at,
                        GlanceChrome.clockLabel(start.timeSec),
                    )
            }
        return "$prefix · $totalText"
    }

    /** The page editor's preview is pinned to PreviewData's own fixed clock, never the wall one. */
    private fun nowSecFor(config: ViewConfig): Long =
        if (config.preview) PreviewData.BASE_TIME else System.currentTimeMillis() / 1000

    private fun formatMm(mm: Double): String = String.format(Locale.getDefault(), "%.1f", mm)

    /** Whole hours where the window is whole, else one decimal ("2", "0.5"). */
    private fun formatHours(seconds: Long): String {
        val hours = seconds / 3600.0
        return if (abs(hours - hours.roundToInt()) < 0.05) hours.roundToInt().toString()
        else String.format(Locale.getDefault(), "%.1f", hours)
    }

    private companion object {
        const val NOWCAST_BARS = 8
        const val MIN_PX = 24
        const val MAX_PX = 1024
    }
}
