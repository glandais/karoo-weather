package io.github.glandais.karoo.weather.extension

import android.content.Context
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.data.WeatherRepository
import io.github.glandais.karoo.weather.domain.PrecipBucket
import io.github.glandais.karoo.weather.domain.WeatherSettings
import io.github.glandais.karoo.weather.karoo.streamRideState
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Opt-in in-ride "rain starting" alert.
 *
 * Dispatches at most one [InRideAlert] per [COOLDOWN_SEC], only while the ride is recording, only when the
 * rider is not already in the rain, and only when a wet bucket falls within [LOOKAHEAD_SEC].
 */
class RainAlerter(
    private val context: Context,
    private val karoo: KarooSystemService,
    private val repo: WeatherRepository,
) {

    private var lastAlertSec: Long? = null

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            combine(
                    repo.settings,
                    karoo.streamRideState().onStart { emit(RideState.Idle) },
                    ticker(),
                ) { settings: WeatherSettings, rideState: RideState, _: Unit ->
                    settings.rainAlertEnabled to (rideState is RideState.Recording)
                }
                .collect { (enabled, recording) -> evaluate(enabled, recording) }
        }

    private fun evaluate(enabled: Boolean, recording: Boolean) {
        if (!enabled || !recording) return
        val nowSec = System.currentTimeMillis() / 1000
        val bucket = firstWetBucket(repo.rainBuckets(BUCKET_COUNT), nowSec) ?: return
        val minutes = minutesUntil(bucket.time, nowSec)
        if (!shouldAlert(minutes, lastAlertSec, nowSec, enabled, recording)) return
        lastAlertSec = nowSec
        karoo.dispatch(
            InRideAlert(
                id = ALERT_ID,
                icon = R.drawable.ic_wmo_rain,
                title = context.getString(R.string.alert_rain_title, minutes),
                detail =
                    context.getString(
                        R.string.alert_rain_detail,
                        String.format(Locale.US, "%.1f", bucket.mm),
                    ),
                autoDismissMs = AUTO_DISMISS_MS,
                backgroundColor = R.color.alert_bg,
                textColor = R.color.alert_fg,
            )
        )
    }

    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(CHECK_INTERVAL_MS)
        }
    }

    companion object {
        const val COOLDOWN_SEC = 3600L
        const val LOOKAHEAD_SEC = 1800L
        const val WET_MM = 0.2

        const val ALERT_ID = "karoo-weather-rain"
        const val AUTO_DISMISS_MS = 10_000L
        const val CHECK_INTERVAL_MS = 60_000L

        /** Enough 15-minute buckets to cover [LOOKAHEAD_SEC] plus the bucket containing "now". */
        const val BUCKET_COUNT = 8

        /** Pure decision function. Minutes until the first wet bucket within [LOOKAHEAD_SEC], or null. */
        fun rainStartingIn(buckets: List<PrecipBucket>, nowSec: Long): Int? =
            firstWetBucket(buckets, nowSec)?.let { minutesUntil(it.time, nowSec) }

        fun shouldAlert(
            minutesUntil: Int?,
            lastAlertSec: Long?,
            nowSec: Long,
            enabled: Boolean,
            recording: Boolean,
        ): Boolean {
            if (!enabled || !recording || minutesUntil == null) return false
            val last = lastAlertSec ?: return true
            return nowSec - last >= COOLDOWN_SEC
        }

        /**
         * The first bucket starting after [nowSec] and within [LOOKAHEAD_SEC] whose precipitation reaches
         * [WET_MM]. Null when the rider is ALREADY in the rain (the bucket containing `now` is wet), which
         * is not a "rain starting" event, and null when the dry spell outlasts the lookahead.
         */
        private fun firstWetBucket(buckets: List<PrecipBucket>, nowSec: Long): PrecipBucket? {
            for (bucket in buckets.sortedBy { it.time }) {
                val end = bucket.time + bucket.durationSec
                if (end <= nowSec) continue
                val wet = bucket.mm >= WET_MM
                if (bucket.time <= nowSec) {
                    // The bucket containing "now".
                    if (wet) return null
                    continue
                }
                if (bucket.time - nowSec > LOOKAHEAD_SEC) return null
                if (wet) return bucket
            }
            return null
        }

        /** Whole minutes until [startSec], rounded up, never below 1. */
        private fun minutesUntil(startSec: Long, nowSec: Long): Int =
            ceil((startSec - nowSec) / 60.0).toInt().coerceAtLeast(1)
    }
}
