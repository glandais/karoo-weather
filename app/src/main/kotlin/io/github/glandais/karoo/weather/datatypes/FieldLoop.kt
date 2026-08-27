package io.github.glandais.karoo.weather.datatypes

import android.content.Context
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.data.WeatherRepository
import io.github.glandais.karoo.weather.datatypes.views.FieldChrome
import io.github.glandais.karoo.weather.domain.WeatherSettings
import io.github.glandais.karoo.weather.domain.WeatherSnapshot
import io.github.glandais.karoo.weather.karoo.SLOW_REFRESH_MS
import io.github.glandais.karoo.weather.karoo.streamDataTypeVisible
import io.github.glandais.karoo.weather.karoo.viewRefreshMs
import io.github.glandais.karoo.weather.ui.theme.Wx
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ShowCustomStreamState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

/** One tick of a graphical field's view loop. */
internal data class FieldViewData(
    val snapshot: WeatherSnapshot,
    val visible: Boolean,
    val settings: WeatherSettings,
)

/**
 * The plumbing every graphical field's `startView` shares.
 *
 * It is a plain object rather than a base class so each field still extends `DataTypeImpl`
 * directly, exactly as PLAN §WP4 declares.
 */
internal object FieldLoop {

    /**
     * `combine(repo.state, visible, repo.settings)`.
     *
     * The visibility flow is seeded with `true`: `streamDataTypeVisible` is derived from
     * `ActiveRidePage`, which only emits when the rider changes page, and `combine` produces
     * nothing until every source has emitted. Without the seed a field would stay blank until the
     * first page swipe.
     */
    fun flow(
        karoo: KarooSystemService?,
        repo: WeatherRepository,
        dataTypeId: String,
    ): Flow<FieldViewData> {
        val visible =
            karoo?.streamDataTypeVisible(dataTypeId)?.onStart { emit(true) } ?: flowOf(true)
        return combine(repo.state, visible, repo.settings) { snapshot, isVisible, settings ->
            FieldViewData(snapshot, isVisible, settings)
        }
    }

    /** Effective repaint interval; the slow side while the service is not connected yet. */
    suspend fun refreshMs(karoo: KarooSystemService?, repo: WeatherRepository): Long =
        karoo?.viewRefreshMs(repo.settings.first()) ?: SLOW_REFRESH_MS

    /**
     * The empty / loading / error message for a field, per DESIGN §6, or a cleared state when
     * there is data to draw. Cached values always win over a spinner: a data field never shows one.
     */
    fun customState(
        context: Context,
        snapshot: WeatherSnapshot,
        night: Boolean,
    ): ShowCustomStreamState =
        when {
            snapshot.hasData -> FieldChrome.clearState()
            !snapshot.consentAccepted ->
                FieldChrome.customState(context, R.string.state_setup, Wx.fg, night)
            snapshot.loading ->
                FieldChrome.customState(context, R.string.state_loading, Wx.fgMuted, night)
            snapshot.position == null ->
                FieldChrome.customState(context, R.string.state_no_gps, Wx.fg, night)
            else -> FieldChrome.customState(context, R.string.state_no_data, Wx.fg, night)
        }
}
