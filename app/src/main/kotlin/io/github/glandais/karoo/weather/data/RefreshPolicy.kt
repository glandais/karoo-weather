package io.github.glandais.karoo.weather.data

import io.github.glandais.karoo.weather.domain.WeatherError
import io.github.glandais.karoo.weather.domain.WeatherRequest
import io.github.glandais.karoo.weather.domain.WeatherSettings
import kotlin.math.max

/**
 * When a fetch is allowed and how long to wait after one fails.
 *
 * Pure: no Android, no coroutines, no clock of its own. Everything the worker decides lives here so
 * the decisions are unit-testable at the same signatures the worker calls.
 */
object RefreshPolicy {

    /**
     * Hard floor between two actual HTTP cycles; a burst of triggers coalesces into one request.
     */
    const val MIN_GAP_SEC = 60L

    /** Interval floor while recording — a ride must not spend its battery on forecasts. */
    const val RECORDING_INTERVAL_FLOOR_SEC = 900L

    /** Smallest point count a reduced request may ask for. */
    const val MIN_POINT_BUDGET = 2

    private val BACKOFF_LADDER_SEC = longArrayOf(30L, 60L, 120L, 300L)

    /** 30, 60, 120, 300, then 300 while recording and 900 otherwise. [attempt] is 1-based. */
    fun backoffSec(attempt: Int, recording: Boolean): Long {
        val index = (attempt - 1).coerceAtLeast(0)
        if (index < BACKOFF_LADDER_SEC.size) return BACKOFF_LADDER_SEC[index]
        return if (recording) 300L else 900L
    }

    fun shouldFetch(nowSec: Long, lastFetchSec: Long?): Boolean =
        lastFetchSec == null || nowSec - lastFetchSec >= MIN_GAP_SEC

    /** `refreshMinutes * 60`, halved to a floor of 900 s while recording. */
    fun intervalSec(settings: WeatherSettings, recording: Boolean): Long {
        val base = settings.refreshMinutes.coerceAtLeast(1) * 60L
        return if (recording) max(base / 2, RECORDING_INTERVAL_FLOOR_SEC) else base
    }

    /** Progress bucket used by the [RefreshKey]: a third of the current sample spacing. */
    fun progressBucket(progress: Double, spacing: Double): Int {
        if (!progress.isFinite() || !spacing.isFinite() || spacing <= 0.0) return 0
        val bucket = progress / spacing / 3.0
        if (!bucket.isFinite()) return 0
        return bucket.toInt()
    }

    /**
     * Halves the point count on the two errors whose remedy is a smaller response
     * ([WeatherError.reducePoints]), floor [MIN_POINT_BUDGET]; anything else resets to the full
     * [WeatherRequest.MAX_POINTS].
     */
    fun nextPointBudget(current: Int, error: WeatherError?): Int =
        if (error != null && error.reducePoints) {
            max(current / 2, MIN_POINT_BUDGET)
        } else {
            WeatherRequest.MAX_POINTS
        }
}

/**
 * The request-identity tuple.
 *
 * Deliberately EXCLUDES `viewRefreshMs`, `mapLayerEnabled`, `rainAlertEnabled`, `tempUnit` and
 * `windUnit`: none of them change the request, and keying on the whole settings object would make
 * toggling "wind arrows on map" cost an HTTP round trip (karoo-headwind pitfall #17).
 */
data class RefreshKey(
    val consentAccepted: Boolean,
    val roundLocationKm: Double,
    val refreshMinutes: Int,
    val assumedSpeedKmh: Int,
    val useMeasuredSpeed: Boolean,
    val lastRefreshRequestedAt: Long?,
    val lat: Double?,
    val lon: Double?,
    val routeKey: String?,
    val progressBucket: Int,
)
