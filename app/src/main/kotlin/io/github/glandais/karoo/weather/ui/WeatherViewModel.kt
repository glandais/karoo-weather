package io.github.glandais.karoo.weather.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.glandais.karoo.weather.data.WeatherGraph
import io.github.glandais.karoo.weather.data.WeatherRepository
import io.github.glandais.karoo.weather.domain.WeatherSettings
import io.github.glandais.karoo.weather.domain.WeatherSnapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The companion app's only view model.
 *
 * It holds no state of its own: [WeatherRepository] already publishes a `StateFlow` that survives
 * `attach`/`detach` cycles, so mirroring it here would only add a second source of truth. The two
 * mutators are fire-and-forget on `viewModelScope` — nothing in `«src»/ui` may call `runBlocking`
 * (PLAN WP5).
 */
class WeatherViewModel(private val repo: WeatherRepository) : ViewModel() {

    val state: StateFlow<WeatherSnapshot> = repo.state

    val settings: StateFlow<WeatherSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, WeatherSettings())

    /** The Refresh button. `force` bypasses the minimum-gap check in the repository. */
    fun refresh() {
        viewModelScope.launch { repo.requestRefresh(force = true) }
    }

    fun update(transform: (WeatherSettings) -> WeatherSettings) {
        viewModelScope.launch { repo.updateSettings(transform) }
    }

    companion object {
        /**
         * The factory resolves the process-wide repository through [WeatherGraph]; it never
         * constructs a `KarooSystemService` (ARCHITECTURE §4.2 — the repository owns the single
         * instance, and `WeatherApp`'s `DisposableEffect` owns its ref count).
         */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer<WeatherViewModel> {
                    WeatherViewModel(WeatherGraph.repository(appContext))
                }
            }
        }
    }
}
