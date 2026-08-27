package io.github.glandais.karoo.weather

import io.github.glandais.karoo.weather.data.WeatherGraph
import io.github.glandais.karoo.weather.data.WeatherRepository
import io.github.glandais.karoo.weather.datatypes.RainNextHourDataType
import io.github.glandais.karoo.weather.datatypes.RouteForecastDataType
import io.github.glandais.karoo.weather.datatypes.TemperatureDataType
import io.github.glandais.karoo.weather.datatypes.WeatherNowDataType
import io.github.glandais.karoo.weather.datatypes.WindDataType
import io.github.glandais.karoo.weather.domain.DataTypeIds
import io.github.glandais.karoo.weather.extension.RainAlerter
import io.github.glandais.karoo.weather.extension.WeatherMapLayer
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The extension service: five data fields, one map layer, one rain alerter.
 *
 * Everything is a property initialiser or `by lazy`, never a `lateinit` assigned in [onCreate]:
 * [types] is dereferenced from a Binder thread, and an `UninitializedPropertyAccessException`
 * inside a Binder call is close to undiagnosable in the field (PLAN §WP8).
 *
 * The repository — not this service — owns the single [io.hammerhead.karooext.KarooSystemService];
 * this class only holds one attach/detach pair for the service's lifetime.
 */
class WeatherExtension : KarooExtension(DataTypeIds.EXTENSION, BuildConfig.VERSION_NAME) {

    private val repo: WeatherRepository by lazy { WeatherGraph.repository(this) }

    private val extensionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var alerterJob: Job? = null

    override val types by lazy {
        listOf(
            WeatherNowDataType(this, repo),
            TemperatureDataType(this, repo),
            WindDataType(this, repo),
            RainNextHourDataType(this, repo),
            RouteForecastDataType(this, repo),
        )
    }

    override fun onCreate() {
        super.onCreate()
        repo.attach()
        repo.karooOrNull?.let { karoo ->
            alerterJob = RainAlerter(this, karoo, repo).start(extensionScope)
        }
    }

    /**
     * A fresh [WeatherMapLayer] per call: `previousIds` is instance state, and the Karoo can start
     * and stop the map layer many times over one service lifetime.
     */
    override fun startMap(emitter: Emitter<MapEffect>) {
        val karoo = repo.karooOrNull ?: return
        val layer = WeatherMapLayer(this, karoo, repo)
        val job = layer.start(emitter, extensionScope)
        emitter.setCancellable {
            job.cancel()
            val ids = layer.previousIds
            // The emitter is a Binder proxy and cancellation often means the other side is already
            // gone; a RemoteException here would propagate into the SDK's Binder thread.
            if (ids.isNotEmpty()) runCatching { emitter.onNext(HideSymbols(ids)) }
        }
    }

    override fun onDestroy() {
        alerterJob = null
        extensionScope.cancel()
        repo.detach()
        super.onDestroy()
    }
}
