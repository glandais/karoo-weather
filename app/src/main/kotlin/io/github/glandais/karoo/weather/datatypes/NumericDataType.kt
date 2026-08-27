package io.github.glandais.karoo.weather.datatypes

import android.content.Context
import io.github.glandais.karoo.weather.data.WeatherRepository
import io.github.glandais.karoo.weather.domain.DataTypeIds
import io.github.glandais.karoo.weather.domain.WeatherSnapshot
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateNumericConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Base for `graphical="false"` fields: Karoo draws its own numeric chrome and we supply only the
 * value, in canonical SI (ARCHITECTURE §7.3).
 *
 * The forecast is time-dependent even when the bundle does not change — `sampleNow` interpolates
 * the hourly series — so the stream is driven by the repository state *and* a slow tick.
 */
abstract class NumericDataType(
    protected val context: Context,
    protected val repo: WeatherRepository,
    typeId: String,
) : DataTypeImpl(DataTypeIds.EXTENSION, typeId) {

    /** Canonical SI. Null maps to [StreamState.NotAvailable], never to a sentinel double. */
    abstract fun value(snapshot: WeatherSnapshot, nowSec: Long): Double?

    /**
     * A [DataType.Type] id so Karoo formats units and precision from the user profile. Null means
     * *emit nothing*: [UpdateNumericConfig]'s parameter is non-nullable and Karoo then defaults to
     * integer precision (ARCHITECTURE §7.1).
     */
    open val formatDataTypeId: String? = null

    final override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            combine(repo.state, ticker()) { snapshot, now -> toStreamState(snapshot, now) }
                .distinctUntilChanged()
                .collect { state -> emitter.onNext(state) }
        }
        emitter.setCancellable { scope.cancel() }
    }

    /**
     * Emits [UpdateNumericConfig] once, and only when [formatDataTypeId] is non-null. It launches
     * no coroutine, so per ARCHITECTURE §4.3 it needs no `setCancellable`.
     */
    final override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        formatDataTypeId?.let { emitter.onNext(UpdateNumericConfig(it)) }
    }

    private fun toStreamState(snapshot: WeatherSnapshot, nowSec: Long): StreamState {
        val value = value(snapshot, nowSec) ?: return StreamState.NotAvailable
        return StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to value)))
    }

    private fun ticker(): Flow<Long> = flow {
        while (true) {
            emit(nowSec())
            delay(TICK_MS)
        }
    }

    protected fun nowSec(): Long = System.currentTimeMillis() / 1000

    companion object {
        /** Weather changes far slower than this; the tick only keeps the interpolation honest. */
        const val TICK_MS = 30_000L
    }
}
