package io.github.glandais.karoo.weather.domain

/** Immutable snapshot published by WeatherRepository.state. */
data class WeatherSnapshot(
    val bundle: ForecastBundle? = null,
    val units: Units = Units(),
    /** Rider position, rounded per the privacy setting. Null when no fix and no cached fix. */
    val position: GeoPoint? = null,
    /** GPS bearing, degrees true, null when unknown/stationary. */
    val bearing: Double? = null,
    val loading: Boolean = false,
    val error: WeatherError? = null,
    /** Epoch seconds of the last *successful* fetch, null if never. */
    val lastSuccessAt: Long? = null,
    /** False until the user has accepted the first-run consent dialog. */
    val consentAccepted: Boolean = false,
    /**
     * False once navigation has ended: [ForecastBundle.route] is cached (it even survives a reboot)
     * and outlives the route it describes, so every consumer — the route strip and the map layer —
     * must gate on this rather than on the bundle alone.
     */
    val hasLiveRoute: Boolean = true,
) {
    val hasData: Boolean
        get() = bundle != null

    /** Data older than this is not shown as current. */
    fun isStale(nowSec: Long): Boolean =
        bundle == null || nowSec - bundle.fetchedAt > STALE_AFTER_SEC

    companion object {
        const val STALE_AFTER_SEC = 3 * 3600L
    }
}
