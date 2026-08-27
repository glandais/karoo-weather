package io.github.glandais.karoo.weather.data

import android.content.Context
import io.github.glandais.karoo.weather.BuildConfig
import io.github.glandais.karoo.weather.domain.ForecastBundle
import io.github.glandais.karoo.weather.domain.GeoPoint
import io.github.glandais.karoo.weather.domain.LocationForecast
import io.github.glandais.karoo.weather.domain.PrecipBucket
import io.github.glandais.karoo.weather.domain.RouteForecast
import io.github.glandais.karoo.weather.domain.RoutePointForecast
import io.github.glandais.karoo.weather.domain.TempUnit
import io.github.glandais.karoo.weather.domain.Units
import io.github.glandais.karoo.weather.domain.WeatherError
import io.github.glandais.karoo.weather.domain.WeatherProvider
import io.github.glandais.karoo.weather.domain.WeatherRequest
import io.github.glandais.karoo.weather.domain.WeatherSample
import io.github.glandais.karoo.weather.domain.WeatherSettings
import io.github.glandais.karoo.weather.domain.WeatherSnapshot
import io.github.glandais.karoo.weather.domain.WindUnit
import io.github.glandais.karoo.weather.karoo.KarooHttpGateway
import io.github.glandais.karoo.weather.karoo.streamActiveRideProfile
import io.github.glandais.karoo.weather.karoo.streamBearing
import io.github.glandais.karoo.weather.karoo.streamDistanceToDestination
import io.github.glandais.karoo.weather.karoo.streamLocation
import io.github.glandais.karoo.weather.karoo.streamNavigation
import io.github.glandais.karoo.weather.karoo.streamRideState
import io.github.glandais.karoo.weather.karoo.streamSpeedMs
import io.github.glandais.karoo.weather.karoo.streamUserProfile
import io.github.glandais.karoo.weather.karoo.toUnits
import io.github.glandais.karoo.weather.route.EtaModel
import io.github.glandais.karoo.weather.route.Geo
import io.github.glandais.karoo.weather.route.RelativeWind
import io.github.glandais.karoo.weather.route.RoutePath
import io.github.glandais.karoo.weather.route.RouteSample
import io.github.glandais.karoo.weather.route.RouteSampler
import io.github.glandais.karoo.weather.weather.Interpolation
import io.github.glandais.karoo.weather.weather.openmeteo.OpenMeteoProvider
import io.github.glandais.karoo.weather.weather.openmeteo.WeatherErrorException
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.UserProfile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The single source of weather truth for the extension, the fields, the map layer and the app.
 *
 * Two scopes, and they are not the same scope (ARCHITECTURE §4.2):
 * * `repoScope` is created here and **never cancelled**; it hosts [state], which a `StateFlow`
 *   needs at construction time and which must survive a detach/attach cycle.
 * * `sessionScope` is created on the first [attach] and cancelled on the last [detach]; it hosts
 *   the Karoo collectors, the trigger producer and the fetch worker.
 *
 * The repository constructs and owns **exactly one** [KarooSystemService]; nobody else builds one.
 */
class WeatherRepository(
    private val appContext: Context,
    private val settingsStore: SettingsStore,
    private val cache: ForecastCache,
) {

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val attachLock = Any()
    private var refCount = 0
    private var sessionScope: CoroutineScope? = null

    @Volatile private var karoo: KarooSystemService? = null

    @Volatile private var provider: WeatherProvider? = null

    /** For `WeatherMapLayer` and `RainAlerter`, which need the raw service. Null before attach. */
    val karooOrNull: KarooSystemService?
        get() = karoo

    // ---- live inputs ------------------------------------------------------------------------

    private val rawPosition = MutableStateFlow<GeoPoint?>(null)

    /** [rawPosition] snapped to the privacy grid WITH hysteresis; the only gridded position. */
    private val gridPosition = MutableStateFlow<GeoPoint?>(null)

    @Volatile private var gridKm: Double = Double.NaN

    private val routeContext = MutableStateFlow<RouteContext?>(null)
    private val rideState = MutableStateFlow<RideState>(RideState.Idle)
    private val rideProfile = MutableStateFlow<RideProfile?>(null)
    private val userProfile = MutableStateFlow<UserProfile?>(null)
    private val progress = MutableStateFlow(0.0)
    private val status = MutableStateFlow(FetchStatus())
    private val refreshKeys = MutableStateFlow<RefreshKey?>(null)

    @Volatile private var distanceToDestination: Double? = null

    @Volatile private var distanceToDestinationAtMs: Long = 0L

    @Volatile private var etaModel: EtaModel = EtaModel(WeatherSettings().assumedSpeedMs())

    @Volatile private var lastFetchAtSec: Long? = null

    @Volatile private var lastProgress: Double? = null

    @Volatile private var lastPositionSaveMs: Long = 0L

    @Volatile private var lastDestinationRouteAtMs: Long = 0L

    @Volatile private var navigationSeen: Boolean = false

    private val connectedState = MutableStateFlow(false)

    /**
     * True once `KarooSystemService.connect` has reported a live binding.
     *
     * The fields need it because `hardwareType` — and therefore the effective repaint interval — is
     * null until the service connects (karoo-headwind pitfall #1), and a view that latched the "not
     * connected yet" interval would keep it for its whole life.
     */
    val connected: StateFlow<Boolean>
        get() = connectedState

    private val settingsState: StateFlow<WeatherSettings> =
        settingsStore.settings.stateIn(repoScope, SharingStarted.Eagerly, WeatherSettings())

    val settings: Flow<WeatherSettings>
        get() = settingsState

    /** Lives on `repoScope`, which is NEVER cancelled. Safe across detach/attach cycles. */
    val state: StateFlow<WeatherSnapshot> =
        combine(cache.bundle, cache.lastPosition, settingsState, status, userProfile) {
                bundle,
                cachedPosition,
                currentSettings,
                fetchStatus,
                profile ->
                WeatherSnapshot(
                    bundle = bundle,
                    units = profile?.toUnits(currentSettings) ?: defaultUnits(currentSettings),
                    position = fetchStatus.position ?: cachedPosition,
                    bearing = fetchStatus.bearing,
                    loading = fetchStatus.loading,
                    error = fetchStatus.error,
                    lastSuccessAt = fetchStatus.lastSuccessAt ?: bundle?.fetchedAt,
                    consentAccepted = currentSettings.consentAccepted,
                    hasLiveRoute = fetchStatus.routeLive,
                )
            }
            .stateIn(repoScope, SharingStarted.Eagerly, WeatherSnapshot())

    // ---- lifecycle --------------------------------------------------------------------------

    /**
     * Idempotent and ref-counted. The first call constructs and connects the single
     * [KarooSystemService] and starts the session jobs.
     */
    fun attach() {
        // The session start/stop happens INSIDE the monitor: deciding under the lock and acting
        // outside it lets a preempted detach() tear down a session a concurrent attach() just
        // built.
        synchronized(attachLock) {
            refCount += 1
            if (refCount == 1) startSession()
        }
    }

    /** On the LAST detach: cancels the session scope and disconnects the service. */
    fun detach() {
        synchronized(attachLock) {
            if (refCount == 0) return
            refCount -= 1
            if (refCount == 0) stopSession()
        }
    }

    private fun startSession() {
        val system = KarooSystemService(appContext)
        val agent = userAgent()
        provider = OpenMeteoProvider(KarooHttpGateway(system, agent), agent)
        karoo = system
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessionScope = scope
        launchCollectors(scope, system)
        launchTriggerProducer(scope)
        launchFetchWorker(scope)
        system.connect { isConnected ->
            connectedState.value = isConnected
            if (isConnected) scope.launch { requestRefresh() }
        }
    }

    private fun stopSession() {
        sessionScope?.cancel()
        sessionScope = null
        karoo?.let { runCatching { it.disconnect() } }
        karoo = null
        provider = null
        connectedState.value = false
        refreshKeys.value = null
        status.update { it.copy(loading = false) }
    }

    private fun launchCollectors(scope: CoroutineScope, system: KarooSystemService) {
        scope.launch { system.streamLocation().collect { onLocation(it) } }
        scope.launch {
            system.streamBearing().collect { value ->
                val rounded = value?.let { roundBearing(it) }
                status.update { if (it.bearing == rounded) it else it.copy(bearing = rounded) }
            }
        }
        scope.launch {
            system.streamSpeedMs().collect { speed -> etaModel.onSpeedSample(speed, nowSec()) }
        }
        scope.launch {
            system.streamDistanceToDestination().collect { metres ->
                if (metres != null && metres.isFinite()) {
                    distanceToDestination = metres
                    distanceToDestinationAtMs = System.currentTimeMillis()
                }
            }
        }
        scope.launch { system.streamNavigation().collect { onNavigation(it) } }
        scope.launch { system.streamRideState().collect { rideState.value = it } }
        scope.launch { system.streamUserProfile().collect { userProfile.value = it } }
        scope.launch { system.streamActiveRideProfile().collect { rideProfile.value = it } }
        scope.launch {
            settingsState
                .map { it.assumedSpeedMs() }
                .distinctUntilChanged()
                .collect { speed -> etaModel = EtaModel(speed) }
        }
        scope.launch {
            while (isActive) {
                updateProgress()
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    // ---- inputs -----------------------------------------------------------------------------

    private suspend fun onLocation(point: GeoPoint) {
        rawPosition.value = point
        val km = settingsState.value.roundLocationKm
        // Hysteresis: a road that hugs a grid line would otherwise flip the cell on every fix, and
        // every flip publishes a new refresh key that cancels the in-flight fetch (ARCHITECTURE
        // §5.1). The previous cell is only left once the rider is clearly past its boundary.
        val previous = if (km == gridKm) gridPosition.value else null
        val rounded = Geo.roundToGridSticky(previous, point, km)
        gridKm = km
        gridPosition.value = rounded
        status.update { if (it.position == rounded) it else it.copy(position = rounded) }
        val now = System.currentTimeMillis()
        if (now - lastPositionSaveMs >= POSITION_SAVE_INTERVAL_MS) {
            lastPositionSaveMs = now
            runCatching { cache.savePosition(rounded) }
        }
    }

    private fun onNavigation(state: OnNavigationState.NavigationState) {
        navigationSeen = true
        when (state) {
            is OnNavigationState.NavigationState.Idle -> setRoute(null)
            is OnNavigationState.NavigationState.NavigatingRoute ->
                setRoute(
                    RouteContext(
                        key = routeKey(state.routePolyline, state.reversed),
                        name = state.name,
                        path =
                            RoutePath.fromPolyline(state.routePolyline, state.reversed)
                                ?: return setRoute(null),
                        routeDistance = state.routeDistance,
                    )
                )
            is OnNavigationState.NavigationState.NavigatingToDestination -> {
                // The polyline is recomputed on every deviation, so accepting each one would fetch
                // continuously off-route. Debounced (ARCHITECTURE §11).
                val key = routeKey(state.polyline, false)
                val now = System.currentTimeMillis()
                val existing = routeContext.value
                if (
                    existing != null &&
                        existing.key != key &&
                        now - lastDestinationRouteAtMs < DESTINATION_DEBOUNCE_MS
                ) {
                    return
                }
                val path = RoutePath.fromPolyline(state.polyline) ?: return setRoute(null)
                lastDestinationRouteAtMs = now
                setRoute(
                    RouteContext(
                        key = key,
                        name = state.destination.name.orEmpty(),
                        path = path,
                        routeDistance = path.length,
                    )
                )
            }
        }
    }

    private fun setRoute(context: RouteContext?) {
        val previous = routeContext.value
        if (previous?.key == context?.key) {
            routeContext.value = context
            publishRouteLive()
            return
        }
        routeContext.value = context
        publishRouteLive()
        etaModel.reset()
        lastProgress = null
        progress.value = 0.0
        distanceToDestination = null
        distanceToDestinationAtMs = 0L
    }

    /**
     * Publishes the guard [routeForecast] applies, so that everything reading [state] — the route
     * strip AND the map layer — sees the same truth: a finished route stops being drawn the moment
     * navigation goes Idle, not when the next fetch happens to succeed.
     */
    private fun publishRouteLive() {
        val live = routeContext.value != null || !navigationSeen
        status.update { if (it.routeLive == live) it else it.copy(routeLive = live) }
    }

    /**
     * `routeDistance - distanceToDestination` while that stream produces; after
     * [DISTANCE_STREAM_STALE_MS] of silence (breadcrumb routes) the nearest point on the path to
     * the last GPS fix. Never decreases by more than [MAX_PROGRESS_REGRESSION_M] per update.
     */
    private fun updateProgress() {
        val context = routeContext.value
        if (context == null) {
            lastProgress = null
            progress.value = 0.0
            return
        }
        val remaining = distanceToDestination
        val fresh =
            remaining != null &&
                System.currentTimeMillis() - distanceToDestinationAtMs < DISTANCE_STREAM_STALE_MS
        val candidate =
            if (fresh && remaining != null) {
                context.routeDistance - remaining
            } else {
                rawPosition.value?.let { context.path.nearestDistanceTo(it) }
            }
        if (candidate == null || !candidate.isFinite()) return
        // Progress lives in the SDK's routeDistance space and is clamped by it alone; the decoded
        // polyline's own length is a DIFFERENT measure and is applied by [pathDistance] at the
        // point progress is handed to a path-space API.
        val clamped = candidate.coerceIn(0.0, context.routeDistance)
        val previous = lastProgress
        val next =
            if (previous != null && clamped < previous - MAX_PROGRESS_REGRESSION_M) {
                previous - MAX_PROGRESS_REGRESSION_M
            } else {
                clamped
            }
        lastProgress = next
        if (abs(progress.value - next) >= PROGRESS_EPSILON_M) progress.value = next
    }

    // ---- triggers and fetching ----------------------------------------------------------------

    /** Never suspends on I/O: it only ever writes the latest key into a conflating state flow. */
    private fun launchTriggerProducer(scope: CoroutineScope) {
        scope.launch {
            combine(settingsState, gridPosition, routeContext, rideProfile, progress) {
                    currentSettings,
                    position,
                    route,
                    profile,
                    currentProgress ->
                    TriggerInputs(currentSettings, position, route, profile, currentProgress)
                }
                .filter { it.settings.consentAccepted }
                .filter { it.profile?.indoor != true }
                .filter { it.position != null }
                .map { it.toKey() }
                .distinctUntilChanged()
                .collect { refreshKeys.value = it }
        }
    }

    /**
     * Owns request, retry and backoff. `collectLatest` cancels an in-flight attempt (and any
     * pending backoff) the moment a new key arrives, so a five-minute backoff can never delay the
     * fetch for a route the rider just loaded (ARCHITECTURE §5.1).
     */
    private fun launchFetchWorker(scope: CoroutineScope) {
        scope.launch {
            refreshKeys.filterNotNull().collectLatest {
                var attempt = 0
                var budget = WeatherRequest.MAX_POINTS
                while (isActive) {
                    awaitMinGap()
                    status.update { it.copy(loading = true) }
                    // Recorded BEFORE the attempt: `collectLatest` cancels an in-flight fetch on
                    // every new key, and only a start-time gap can stop a jittering key from
                    // restarting the request forever without one ever completing.
                    lastFetchAtSec = nowSec()
                    val error = runFetch(budget)
                    val now = nowSec()
                    lastFetchAtSec = now
                    status.update {
                        it.copy(
                            loading = false,
                            error = error,
                            lastSuccessAt = if (error == null) now else it.lastSuccessAt,
                        )
                    }
                    budget = RefreshPolicy.nextPointBudget(budget, error)
                    val recording = isRecording()
                    when {
                        error == null -> {
                            attempt = 0
                            delay(RefreshPolicy.intervalSec(settingsState.value, recording).seconds)
                        }
                        // A non-retryable error (one truncated body, one 4xx) is permanent for THIS
                        // request, not for the session: retrying it at the normal cadence keeps the
                        // periodic refresh alive without hammering the endpoint.
                        !error.retryable -> {
                            attempt = 0
                            delay(RefreshPolicy.intervalSec(settingsState.value, recording).seconds)
                        }
                        else -> {
                            attempt += 1
                            delay(retryDelaySec(error, attempt, recording).seconds)
                        }
                    }
                }
            }
        }
    }

    private fun retryDelaySec(error: WeatherError, attempt: Int, recording: Boolean): Long =
        if (error is WeatherError.RateLimited) {
            max(error.retryAfterSec, RATE_LIMIT_FLOOR_SEC)
        } else {
            RefreshPolicy.backoffSec(attempt, recording)
        }

    private suspend fun awaitMinGap() {
        val last = lastFetchAtSec ?: return
        val now = nowSec()
        if (RefreshPolicy.shouldFetch(now, last)) return
        delay((RefreshPolicy.MIN_GAP_SEC - (now - last)).coerceAtLeast(1L).seconds)
    }

    /** One HTTP cycle. Returns null on success, or the error that ended it. */
    private suspend fun runFetch(pointBudget: Int): WeatherError? {
        val weatherProvider = provider ?: return WeatherError.NoConnection
        val currentSettings = settingsState.value
        val raw = rawPosition.value ?: return WeatherError.NoConnection
        // Only the rider's own point is privacy-rounded: the route's own geometry is already on the
        // device, and a 3 km grid would collapse 1 km route samples onto each other.
        val rider = gridPosition.value ?: Geo.roundToGrid(raw, currentSettings.roundLocationKm)
        val now = nowSec()
        val context = routeContext.value
        val recording = isRecording()
        val useMeasured = currentSettings.useMeasuredSpeed && recording
        val model = etaModel
        val currentProgress = progress.value
        val eta: (Double) -> Long = { d -> model.eta(now, currentProgress, d, useMeasured) }

        var samples: List<RouteSample> = emptyList()
        var markerIndex: Int? = null
        if (context != null) {
            val maxRoutePoints = (pointBudget - 1).coerceIn(0, RouteSampler.MAX_ROUTE_POINTS)
            if (maxRoutePoints > 0) {
                // `RouteSampler` measures along the decoded polyline, so progress must be converted
                // out of the SDK's routeDistance space first — otherwise a routeDistance that runs
                // 1 % long clamps `from` to `path.length` and the last kilometre samples nothing.
                val pathProgress =
                    pathDistance(currentProgress, context.routeDistance, context.path.length)
                val all = RouteSampler.sample(context.path, pathProgress, maxRoutePoints)
                val truncated = RouteSampler.truncateToHorizon(all, eta, now)
                samples = truncated.first
                markerIndex = truncated.second
            }
        }

        val points = (listOf(rider) + samples.map { it.point }).take(WeatherRequest.MAX_POINTS)
        val request =
            WeatherRequest(points = points, forecastHours = FORECAST_HOURS, includeNowcast = true)
        val result = weatherProvider.fetch(request)
        val fetched = result.getOrElse { failure ->
            return (failure as? WeatherErrorException)?.error
                ?: WeatherError.Parse(failure.message ?: "unknown")
        }
        val forecasts = fetched.forecasts
        if (forecasts.isEmpty()) return WeatherError.EmptyBody

        val route =
            if (context != null && samples.isNotEmpty() && forecasts.size > 1) {
                buildRouteForecast(
                    routeName = context.name,
                    path = context.path,
                    routeDistance = context.routeDistance,
                    progress = currentProgress,
                    riderPoint = rider,
                    samples = samples,
                    horizonMarkerIndex = markerIndex,
                    forecasts = forecasts,
                    eta = eta,
                    nowSec = now,
                    assumedSpeedMs = model.effectiveSpeedMs(useMeasured),
                )
            } else {
                null
            }

        cache.save(
            ForecastBundle(
                fetchedAt = now,
                here = forecasts.first(),
                route = route,
                provider = weatherProvider.id,
            )
        )
        cache.savePosition(rider)
        lastPositionSaveMs = System.currentTimeMillis()
        // The cycle's data stands on its own and is cached above; only a rate limit on the optional
        // nowcast request survives, so its `Retry-After` reaches the backoff ladder instead of the
        // worker issuing a fresh 429 on every normal interval.
        return fetched.nowcastError as? WeatherError.RateLimited
    }

    // ---- public reads --------------------------------------------------------------------------

    suspend fun requestRefresh(force: Boolean = false) {
        if (force) lastFetchAtSec = null
        settingsStore.pokeRefresh()
    }

    suspend fun updateSettings(transform: (WeatherSettings) -> WeatherSettings) {
        settingsStore.update(transform)
    }

    /** `here.hourly` interpolated to [nowSec], falling back to `here.current`. */
    fun sampleNow(nowSec: Long = System.currentTimeMillis() / 1000): WeatherSample? {
        val here = state.value.bundle?.here ?: return null
        return Interpolation.sampleAt(here.hourly, nowSec) ?: here.current
    }

    /** Non-null only while a route is loaded and a forecast exists for it. */
    fun routeForecast(): RouteForecast? {
        val snapshot = state.value
        val route = snapshot.bundle?.route ?: return null
        if (!snapshot.hasLiveRoute) return null
        return route
    }

    /** Buckets for the rain field: `minutely15` when present, else derived from `hourly`. */
    fun rainBuckets(count: Int = 8): List<PrecipBucket> {
        val here = state.value.bundle?.here ?: return emptyList()
        val now = nowSec()
        val nowcast = Interpolation.bucketsFrom(here.minutely15, now - QUARTER_HOUR_SEC, count)
        if (nowcast.isNotEmpty()) return nowcast
        return Interpolation.hourlyToBuckets(here.hourly, now - HOUR_SEC, count)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun isRecording(): Boolean = rideState.value is RideState.Recording

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun userAgent(): String =
        "karoo-weather/${BuildConfig.VERSION_NAME} (+https://github.com/glandais/karoo-weather)"

    private fun defaultUnits(settings: WeatherSettings): Units =
        Units(
            temp = settings.tempUnit ?: TempUnit.CELSIUS,
            wind = settings.windUnit ?: WindUnit.KMH,
        )

    private fun routeKey(polyline: String, reversed: Boolean): String =
        "${polyline.length}:${polyline.hashCode()}:$reversed"

    private data class FetchStatus(
        val loading: Boolean = false,
        val error: WeatherError? = null,
        val lastSuccessAt: Long? = null,
        val position: GeoPoint? = null,
        val bearing: Double? = null,
        /** False once navigation has ended: the cached route must stop being drawn. */
        val routeLive: Boolean = true,
    )

    private data class RouteContext(
        val key: String,
        val name: String,
        val path: RoutePath,
        val routeDistance: Double,
    )

    private data class TriggerInputs(
        val settings: WeatherSettings,
        /** ALREADY on the privacy grid, with hysteresis applied (see `onLocation`). */
        val position: GeoPoint?,
        val route: RouteContext?,
        val profile: RideProfile?,
        val progress: Double,
    ) {
        fun toKey(): RefreshKey {
            val rounded = position
            val spacing = route?.let {
                RouteSampler.spacingFor((it.routeDistance - progress).coerceAtLeast(0.0))
            }
            return RefreshKey(
                consentAccepted = settings.consentAccepted,
                roundLocationKm = settings.roundLocationKm,
                refreshMinutes = settings.refreshMinutes,
                assumedSpeedKmh = settings.assumedSpeedKmh,
                useMeasuredSpeed = settings.useMeasuredSpeed,
                lastRefreshRequestedAt = settings.lastRefreshRequestedAt,
                lat = rounded?.lat,
                lon = rounded?.lon,
                routeKey = route?.key,
                progressBucket =
                    if (spacing == null) 0 else RefreshPolicy.progressBucket(progress, spacing),
            )
        }
    }

    internal companion object {

        /** Hours of hourly forecast requested per cycle. */
        const val FORECAST_HOURS = 12

        /**
         * After this much silence from `DISTANCE_TO_DESTINATION`, fall back to the GPS projection.
         */
        const val DISTANCE_STREAM_STALE_MS = 30_000L

        /** Progress may never fall further than this in one update. */
        const val MAX_PROGRESS_REGRESSION_M = 200.0

        /** How often progress is recomputed from the current inputs. */
        const val PROGRESS_TICK_MS = 5_000L

        /** Progress changes smaller than this do not republish the flow. */
        const val PROGRESS_EPSILON_M = 5.0

        /** A `NavigatingToDestination` polyline is re-accepted at most this often. */
        const val DESTINATION_DEBOUNCE_MS = 5 * 60_000L

        /** `RateLimited` never retries sooner than this, whatever `Retry-After` said. */
        const val RATE_LIMIT_FLOOR_SEC = 900L

        const val POSITION_SAVE_INTERVAL_MS = 60_000L

        const val QUARTER_HOUR_SEC = 900L
        const val HOUR_SEC = 3600L

        /** `precipProb` at or above this counts a point as wet even with little accumulation. */
        const val WET_PROBABILITY_PERCENT = 60

        /**
         * A distance measured in the SDK's `routeDistance` space, expressed along [pathLength] —
         * our own haversine sum over the decoded polyline. The two disagree by the polyline's
         * simplification error, so mixing them silently truncates sampling near the finish.
         */
        fun pathDistance(progress: Double, routeDistance: Double, pathLength: Double): Double {
            if (!progress.isFinite() || pathLength <= 0.0) return 0.0
            if (routeDistance <= 0.0) return progress.coerceIn(0.0, pathLength)
            return (progress * pathLength / routeDistance).coerceIn(0.0, pathLength)
        }

        /**
         * Precipitation the rider actually rides through, mm.
         *
         * [WeatherSample.precip] is an accumulation over the sample's whole forecast hour, not a
         * level, so summing it over route points counts one hour of rain once per point that falls
         * inside it. Each leg is therefore weighted by the time it takes to ride, and the final
         * point closes the route rather than opening another leg.
         */
        fun accumulatedPrecipMm(points: List<RoutePointForecast>): Double {
            if (points.size < 2) return 0.0
            var total = 0.0
            for (i in 0 until points.size - 1) {
                val seconds = (points[i + 1].eta - points[i].eta).coerceAtLeast(0L)
                total += points[i].sample.precip * seconds / HOUR_SEC.toDouble()
            }
            return total
        }

        /**
         * The pure assembly step, extracted so it is unit-testable without a `Context`.
         *
         * [samples] are ROUTE samples only; this function PREPENDS the rider's own point as index 0
         * (`distanceAlong == progress`) and is the only place that does so. `forecasts[0]` is the
         * rider's point and `forecasts[i + 1]` matches `samples[i]`.
         */
        fun buildRouteForecast(
            routeName: String,
            path: RoutePath,
            routeDistance: Double,
            progress: Double,
            riderPoint: GeoPoint,
            samples: List<RouteSample>,
            horizonMarkerIndex: Int?,
            forecasts: List<LocationForecast>,
            eta: (Double) -> Long,
            nowSec: Long,
            assumedSpeedMs: Double,
        ): RouteForecast {
            val points = ArrayList<RoutePointForecast>(samples.size + 1)
            val pathProgress = pathDistance(progress, routeDistance, path.length)

            forecasts.firstOrNull()?.let { here ->
                val sample = Interpolation.sampleAt(here.hourly, nowSec) ?: here.current
                if (sample != null) {
                    points.add(
                        pointForecast(
                            point = riderPoint,
                            distanceAlong = progress,
                            eta = nowSec,
                            routeBearing = path.bearingAt(pathProgress),
                            sample = sample,
                            beyondHorizon = false,
                        )
                    )
                }
            }

            samples.forEachIndexed { index, routeSample ->
                val forecast = forecasts.getOrNull(index + 1) ?: return@forEachIndexed
                val arrival = eta(routeSample.distanceAlong)
                val sample =
                    Interpolation.sampleAt(forecast.hourly, arrival)
                        ?: forecast.current
                        ?: return@forEachIndexed
                points.add(
                    pointForecast(
                        point = routeSample.point,
                        distanceAlong = routeSample.distanceAlong,
                        eta = arrival,
                        routeBearing = routeSample.routeBearing,
                        sample = sample,
                        beyondHorizon = horizonMarkerIndex != null && index == horizonMarkerIndex,
                    )
                )
            }

            val wet = points.firstOrNull { isWet(it.sample) }
            return RouteForecast(
                routeName = routeName,
                routeDistance = routeDistance,
                progress = progress,
                computedAt = nowSec,
                assumedSpeed = assumedSpeedMs,
                points = points.toList(),
                totalPrecipMm = accumulatedPrecipMm(points),
                firstWetDistance = wet?.distanceAlong,
                firstWetEta = wet?.eta,
            )
        }

        private fun pointForecast(
            point: GeoPoint,
            distanceAlong: Double,
            eta: Long,
            routeBearing: Double,
            sample: WeatherSample,
            beyondHorizon: Boolean,
        ): RoutePointForecast {
            val relative = RelativeWind.relativeAngle(routeBearing, sample.windDir)
            return RoutePointForecast(
                point = point,
                distanceAlong = distanceAlong,
                eta = eta,
                routeBearing = routeBearing,
                sample = sample,
                relativeWindAngle = relative,
                headwindSpeed = RelativeWind.headwindComponent(relative, sample.windSpeed),
                beyondHorizon = beyondHorizon,
            )
        }

        private fun isWet(sample: WeatherSample): Boolean =
            sample.precip >= RouteForecast.WET_THRESHOLD_MM ||
                (sample.precipProb ?: 0) >= WET_PROBABILITY_PERCENT
    }
}

/**
 * Whole degrees in [0, 360): a bearing that jitters below a degree must not republish the state.
 */
private fun roundBearing(value: Double): Double? {
    if (!value.isFinite()) return null
    val wrapped = ((value % 360.0) + 360.0) % 360.0
    return wrapped.roundToInt().toDouble() % 360.0
}
