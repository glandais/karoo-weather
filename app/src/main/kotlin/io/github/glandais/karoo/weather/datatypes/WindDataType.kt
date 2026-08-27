package io.github.glandais.karoo.weather.datatypes

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import io.github.glandais.karoo.weather.data.WeatherRepository
import io.github.glandais.karoo.weather.datatypes.views.ArrowBitmaps
import io.github.glandais.karoo.weather.datatypes.views.FieldChrome
import io.github.glandais.karoo.weather.datatypes.views.WindView
import io.github.glandais.karoo.weather.domain.DataTypeIds
import io.github.glandais.karoo.weather.domain.WeatherSample
import io.github.glandais.karoo.weather.domain.WeatherSnapshot
import io.github.glandais.karoo.weather.karoo.throttle
import io.github.glandais.karoo.weather.weather.Interpolation
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * `wind` — the arrow is the field. It points where the wind pushes the rider, in the rider's own
 * frame, and its colour follows the headwind ramp (DESIGN §3.2). Header hidden: it needs every
 * pixel.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class WindDataType(private val context: Context, private val repo: WeatherRepository) :
    DataTypeImpl(DataTypeIds.EXTENSION, DataTypeIds.WIND) {

    /** Mean wind speed, m/s — canonical SI (ARCHITECTURE §7.3). */
    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            combine(repo.state, tick()) { snapshot, now -> streamState(snapshot, now) }
                .distinctUntilChanged()
                .collect { emitter.onNext(it) }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.i(
            FieldChrome.LOG_TAG,
            "startView $typeId grid=${config.gridSize} view=${config.viewSize} " +
                "text=${config.textSize} preview=${config.preview}",
        )
        val glance = GlanceRemoteViews()
        val arrows = ArrowBitmaps()
        val night = FieldChrome.night(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(FieldChrome.clearState())
            awaitCancellation()
        }

        scope.launch {
            if (config.preview) {
                render(glance, context, config, PreviewData.snapshot, night, arrows, emitter)
                awaitCancellation()
            }
            val refreshMs = FieldLoop.refreshMs(repo.karooOrNull, repo)
            FieldLoop.flow(repo.karooOrNull, repo, dataTypeId).throttle(refreshMs).collect { data ->
                if (!data.visible) return@collect
                emitter.onNext(FieldLoop.customState(context, data.snapshot, night))
                render(glance, context, config, data.snapshot, night, arrows, emitter)
            }
        }

        // Cancelling the scope, not the two jobs individually: it releases the parent
        // SupervisorJob too, so nothing survives a stopView.
        emitter.setCancellable {
            scope.cancel()
            arrows.clear()
        }
    }

    private suspend fun render(
        glance: GlanceRemoteViews,
        context: Context,
        config: ViewConfig,
        snapshot: WeatherSnapshot,
        night: Boolean,
        arrows: ArrowBitmaps,
        emitter: ViewEmitter,
    ) {
        val now = System.currentTimeMillis() / 1000
        val sample = sampleOf(snapshot, now) ?: return
        val result =
            glance.compose(context, DpSize.Unspecified) {
                WindView(
                    context = context,
                    config = config,
                    sample = sample,
                    units = snapshot.units,
                    bearing = snapshot.bearing,
                    night = night,
                    arrows = arrows,
                )
            }
        emitter.updateView(result.remoteViews)
    }

    private fun streamState(snapshot: WeatherSnapshot, nowSec: Long): StreamState {
        val sample = sampleOf(snapshot, nowSec) ?: return StreamState.NotAvailable
        return StreamState.Streaming(
            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to sample.windSpeed))
        )
    }

    private fun sampleOf(snapshot: WeatherSnapshot, nowSec: Long): WeatherSample? {
        val here = snapshot.bundle?.here ?: return null
        return Interpolation.sampleAt(here.hourly, nowSec) ?: here.current
    }

    private fun tick() = flow {
        while (true) {
            emit(System.currentTimeMillis() / 1000)
            delay(NumericDataType.TICK_MS)
        }
    }
}
