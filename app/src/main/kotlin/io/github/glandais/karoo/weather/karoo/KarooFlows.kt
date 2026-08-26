package io.github.glandais.karoo.weather.karoo

import io.github.glandais.karoo.weather.domain.DataTypeIds
import io.github.glandais.karoo.weather.domain.GeoPoint
import io.github.glandais.karoo.weather.domain.WeatherSettings
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlin.math.max
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transform

/**
 * Consumer flow for events that have default [KarooEventParams].
 *
 * ONLY these 11 types are legal — `KarooSystemService`'s no-params `addConsumer` resolves the
 * defaults from a hard-coded `when (T::class)` and throws `IllegalArgumentException` for anything
 * else (verified against `KarooSystemService.kt`):
 *
 * `RideState`, `Lap`, `UserProfile`, `OnLocationChanged`, `OnGlobalPOIs`, `OnNavigationState`,
 * `OnMapZoomLevel`, `SavedDevices`, `Bikes`, `ActiveRideProfile`, `ActiveRidePage`.
 *
 * `OnStreamState` and `OnHttpResponse` are NOT in that list and must use [consumerFlowWithParams].
 */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> = callbackFlow {
    val listenerId = addConsumer<T> { event: T -> trySendBlocking(event) }
    awaitClose { removeConsumer(listenerId) }
}

/**
 * For events that require explicit params, e.g. `OnStreamState.StartStreaming` or
 * `OnHttpResponse.MakeHttpRequest`.
 */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlowWithParams(
    params: KarooEventParams
): Flow<T> = callbackFlow {
    val listenerId = addConsumer<T>(params) { event: T -> trySendBlocking(event) }
    awaitClose { removeConsumer(listenerId) }
}

/** Uses [consumerFlowWithParams] with `OnStreamState.StartStreaming(dataTypeId)`. */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> =
    consumerFlowWithParams<OnStreamState>(OnStreamState.StartStreaming(dataTypeId)).map { it.state }

fun KarooSystemService.streamNavigation(): Flow<OnNavigationState.NavigationState> =
    consumerFlow<OnNavigationState>().map { it.state }

/**
 * `LOCATION` stream via [streamDataFlow]; drops fixes whose reported accuracy is
 * [MAX_LOCATION_ACCURACY_M] or worse (ARCHITECTURE §5.6). A fix with no accuracy field is kept —
 * some sources omit it and a missing value is not evidence of a bad fix.
 */
fun KarooSystemService.streamLocation(): Flow<GeoPoint> =
    streamDataFlow(DataType.Type.LOCATION).mapNotNull { state ->
        val values = (state as? StreamState.Streaming)?.dataPoint?.values ?: return@mapNotNull null
        val accuracy = values[DataType.Field.LOC_ACCURACY]
        if (accuracy != null && accuracy >= MAX_LOCATION_ACCURACY_M) return@mapNotNull null
        val lat = values[DataType.Field.LOC_LATITUDE] ?: return@mapNotNull null
        val lon = values[DataType.Field.LOC_LONGITUDE] ?: return@mapNotNull null
        GeoPoint(lat, lon)
    }

/**
 * GPS bearing, degrees true. Null while the fix carries no bearing or the stream is not running.
 */
fun KarooSystemService.streamBearing(): Flow<Double?> =
    streamDataFlow(DataType.Type.LOCATION).map { state ->
        (state as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.LOC_BEARING)
    }

/** m/s. */
fun KarooSystemService.streamSpeedMs(): Flow<Double> =
    streamDataFlow(DataType.Type.SPEED).mapNotNull { state ->
        (state as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.SPEED)
    }

/** Metres to the end of the route. Null whenever the stream is not producing a value. */
fun KarooSystemService.streamDistanceToDestination(): Flow<Double?> =
    streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION).map { state ->
        (state as? StreamState.Streaming)
            ?.dataPoint
            ?.values
            ?.get(DataType.Field.DISTANCE_TO_DESTINATION)
    }

fun KarooSystemService.streamRideState(): Flow<RideState> = consumerFlow<RideState>()

fun KarooSystemService.streamUserProfile(): Flow<UserProfile> = consumerFlow<UserProfile>()

/** Used to suspend fetching on indoor ride profiles. */
fun KarooSystemService.streamActiveRideProfile(): Flow<RideProfile> =
    consumerFlow<ActiveRideProfile>().map { it.profile }

/**
 * True while a field of this type sits on the visible ride page.
 *
 * The page reports the *full* data type id for extension fields, so both spellings are accepted:
 * callers pass whichever they hold.
 */
fun KarooSystemService.streamDataTypeVisible(dataTypeId: String): Flow<Boolean> {
    val full = DataTypeIds.full(dataTypeId)
    return consumerFlow<ActiveRidePage>()
        .map { event ->
            event.page.elements.any { it.dataTypeId == dataTypeId || it.dataTypeId == full }
        }
        .distinctUntilChanged()
}

/** Emits at most one value per [periodMs], keeping the most recent one. */
fun <T> Flow<T>.throttle(periodMs: Long): Flow<T> =
    conflate().transform {
        emit(it)
        if (periodMs > 0) delay(periodMs)
    }

/**
 * Effective repaint interval.
 *
 * `hardwareType` is only valid AFTER `connect {}` fires (karoo-headwind pitfall #1), so null means
 * "not connected yet" and takes the slow side.
 */
fun KarooSystemService.viewRefreshMs(settings: WeatherSettings): Long =
    when (hardwareType) {
        HardwareType.K2 -> max(settings.viewRefreshMs, SLOW_REFRESH_MS)
        null -> SLOW_REFRESH_MS
        else -> max(settings.viewRefreshMs, MIN_REFRESH_MS)
    }

/** `ViewEmitter.updateView` drops calls closer together than this. */
const val MIN_REFRESH_MS = 900L

/** K2 and "not connected yet" both repaint at this interval or slower. */
const val SLOW_REFRESH_MS = 3_000L

/** Fixes at or above this reported accuracy (metres) are discarded. */
const val MAX_LOCATION_ACCURACY_M = 500.0
