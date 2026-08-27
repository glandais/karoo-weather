# karoo-weather — ARCHITECTURE

Status: v1 decisions, binding for implementation. **Revision 2** — incorporates CRITIQUE.md.
Package root: `io.github.glandais.karoo.weather` (referred to below as `«root»`).
Extension id: `karoo-weather` (must equal the first ctor arg of `KarooExtension` and of every `DataTypeImpl`,
and the `id=` attribute of `res/xml/extension_info.xml`).
Full data type id form: `DataType.dataTypeId("karoo-weather", typeId)` = `"TYPE_EXT::karoo-weather::<typeId>"`.

---

## ADR-0 — Summary of binding decisions

| # | Decision | Rationale (short) |
|---|---|---|
| 1 | **No backend.** Device calls `api.open-meteo.com` directly. | 25 route points × 12 h ≈ 31 KB raw — 3× under the 100 KB ceiling. A backend adds an operating cost, a privacy story, an outage surface, and a second deploy target for zero measured benefit. |
| 2 | Provider abstraction kept: `interface WeatherProvider`. | A backend / MET-Norway fallback drops in later with no change above the interface. |
| 3 | **Raw `OnHttpResponse.MakeHttpRequest`, not `ktor-client-karoo`.** Ktor + ktor-client-karoo dependencies are removed from `app/build.gradle.kts`. | `KarooEngine.execute` throws `KarooIsUnsupportedException` when `karooSystem.hardwareType == HardwareType.K2` (verified: `ktor-client-karoo/lib/src/main/java/de/jonasfranz/ktor/client/karoo/KarooEngine.kt`). Karoo 2 is a supported target for this extension, so that alone disqualifies the engine. It also hard-codes `waitForConnection = false` and mangles comma-bearing response headers. The raw path is ~60 lines, works on K2 and K3, and needs only `kotlinx-serialization-json` (already present). |
| 4 | **No Mapbox Turf.** Geodesy (polyline decode, haversine, bearing, `along`) is implemented in-tree in `«root».route`. | Turf lives on `api.mapbox.com/downloads/v2/releases/maven`, which is not in `settings.gradle.kts` and needs credentials. What we need is ~180 lines, is pure JVM, and is therefore unit-testable without Robolectric — which is exactly the code most worth testing. |
| 5 | One process, one `WeatherRepository` singleton **which owns the single `KarooSystemService`**, `StateFlow` state, DataStore persistence. | Extension service and `MainActivity` are in the same process (no `android:process` in the manifest). Two `KarooSystemService` instances with a ref-counted `attach` is a lifecycle trap — see §4.2. |
| 6 | Canonical internal units: **°C, m/s, mm, metres, degrees-true, epoch seconds (UTC)**. Conversion happens only at render time. | Single place for unit logic; matches the request (`wind_speed_unit=ms`, `timeformat=unixtime`). |
| 7 | **v1 ships 5 data fields**, not 7. `apparent-temperature` and `headwind-speed` are deferred. | "Feels like" is already a row inside `weather-now`; karoo-headwind already ships a headwind field, and a `graphical="false"` field cannot render the `+`/`−` sign convention that field needs (§7.3). Deferring them is purely additive later — no contract changes. |
| 8 | **In-ride geometry is portrait 480 × 800.** Column counts are derived at runtime from `ViewConfig.viewSize`, never hard-coded from `gridSize` alone. | The Karoo panel is portrait-native; ride pages are portrait. But `viewSize` is authoritative and user-configurable, so layouts branch on `gridSize` for *which rows exist* and on `viewSize` for *how many columns fit* (§7.4). |
| 9 | **`karoo-ext` resolves from `jitpack.io`.** Verified 2026-08: `https://jitpack.io/io/hammerhead/karoo-ext/1.1.9/karoo-ext-1.1.9.pom` → HTTP 200; `repo1.maven.org` → HTTP 404. | The GitHub-Packages repository block in `settings.gradle.kts` exists only for `de.jonasfranz:ktor-client-karoo`. With ADR #3 removing ktor, the block (and its credential lookup, which NPEs on macOS where `USERNAME` is unset) is deleted with no risk. |

---

## 1. Data source strategy

### 1.1 The measurement that settles it

From `weather-apis.md` §3.3, measured live against Open-Meteo with 6 hourly variables / 12 h window /
`timeformat=unixtime`:

| Points | Raw bytes | per point |
|---|---|---|
| 10 | 9 317 | 932 |
| 25 | 23 323 | 933 |

Our route request uses **8** hourly variables (`temperature_2m`, `precipitation`, `precipitation_probability`,
`weather_code`, `wind_speed_10m`, `wind_direction_10m`, `wind_gusts_10m`, `is_day`), so scale by 8/6:

```
25 points × 933 B × (8/6) ≈ 31.1 KB      → 31 % of the 100 KB Karoo ceiling
40 points × 933 B × (8/6) ≈ 49.8 KB      → still safe, but we cap at 25 (see §5)
```

A second, tiny request covers the rider's own position: `current=` (9 fields) + `minutely_15` for 2 h
(8 × 15 min steps, 2 variables) ≈ **1.8 KB**.

**Two GET requests per refresh cycle, ≈ 33 KB total, ≤ 96 requests/day** against a published budget of
10 000/day, 5 000/h, 600/min. There is no size problem and no rate problem. Request B is therefore
**always** issued (see §5.4) — gating it on field visibility would save 2 % of a budget we are nowhere near.

### 1.2 What a backend would have bought, and why we skip it

| Claimed benefit | Verdict |
|---|---|
| Response compaction | Not needed — 31 % of budget. Would save bandwidth on a tethered BT link, but the payload is already smaller than a single map tile. |
| Shared cache across users | Open-Meteo already caches; we would be re-caching a free API and taking on GDPR-relevant position logs. |
| Multi-model rain nowcast blending | Real value, but v1 does not do model blending. Revisit if `minutely_15` proves poor outside HRRR/ICON-D2/AROME coverage. |
| Hiding an API key | Open-Meteo needs no key. |
| Uptime insurance | A hobby backend is *less* available than Open-Meteo, not more. |

**Decision: no backend in v1.** If one is ever added it implements `WeatherProvider` and is selected by a
settings enum; nothing above `«root».weather` changes. The only thing v1 must not do is bake Open-Meteo's
JSON shape into the domain model — hence §3.

### 1.3 Open-Meteo compliance

- Non-commercial use only → the extension is free, ad-free, no paid tier. Consistent with a Karoo community extension.
- CC BY 4.0 → the exact string **`Weather data by Open-Meteo.com (CC BY 4.0)`** is shown in Settings → About and
  is a `string` resource (`R.string.attribution_open_meteo`), never hard-coded.
- `User-Agent: karoo-weather/<versionName> (+https://github.com/glandais/karoo-weather)` on every request.
- Nothing is fetched before the user has opened the app once and accepted the first-run dialog
  (`Settings.consentAccepted`), mirroring karoo-headwind's `welcomeDialogAccepted` gate. Positions leave the
  device only after explicit consent, and only rounded (§5.3).

---

## 2. Module / package layout

Single Gradle module `:app` (a multi-module split buys nothing here and would slow the build).
All packages under `«root»`:

```
«root»/
  MainActivity.kt                     companion app entry (exists)
  WeatherExtension.kt                 KarooExtension service (exists, to be filled)

  domain/                             ── SHARED CONTRACTS, no Android imports at all
    WeatherSample.kt                  WeatherSample, PrecipBucket, WmoCategory
    LocationForecast.kt               LocationForecast, ForecastBundle
    RouteForecast.kt                  RouteForecast, RoutePointForecast
    GeoPoint.kt                       GeoPoint
    Units.kt                          Units, WindUnit, TempUnit, DistanceUnit, WindClass
    WeatherProvider.kt                interface WeatherProvider, WeatherRequest, WeatherError
    WeatherState.kt                   WeatherSnapshot
    Http.kt                           HttpGateway, HttpResult
    Settings.kt                       @Serializable WeatherSettings
    DataTypeIds.kt                    const val typeIds + full ids

  route/                              ── pure JVM, zero Android
    Polyline.kt                       decode/encode, precision 5 and 1
    Geo.kt                            haversine, bearing, destination, signedAngleDifference, roundToGrid
    RoutePath.kt                      cumulative-distance index, pointAt(d), bearingAt(d), nearestDistanceTo(p)
    RouteSampler.kt                   adaptive sampling + horizon truncation → List<RouteSample>
    EtaModel.kt                       speed smoothing + eta(distance)
    RelativeWind.kt                   relative angle, headwind component, tail/head classification

  weather/                            ── pure JVM except @DrawableRes / @Serializable
    openmeteo/OpenMeteoDto.kt         @Serializable wire DTOs
    openmeteo/OpenMeteoProvider.kt    WeatherProvider impl
    openmeteo/OpenMeteoUrl.kt         pure URL builder (testable)
    openmeteo/OpenMeteoParser.kt      body → LocationForecast
    WmoCodes.kt                       WMO 4677 → WmoCategory
    WmoIcons.kt                       WmoCategory + isDay → @DrawableRes Int   (no Glance, no Compose)
    Interpolation.kt                  lerp, lerpAngle, lerpSample, sampleAt(instant)

  karoo/
    KarooFlows.kt                     streamDataFlow / consumerFlow / streamNavigation / …
    KarooHttp.kt                      KarooHttpGateway : HttpGateway
    KarooUnits.kt                     UserProfile.PreferredUnit → domain Units

  data/
    SettingsStore.kt                  DataStore<Preferences>, JSON-per-key
    ForecastCache.kt                  persisted last ForecastBundle + fetch stats
    RefreshPolicy.kt                  pure backoff/interval/trigger-key logic
    WeatherRepository.kt              the single source of truth (StateFlow), owns the KarooSystemService
    WeatherGraph.kt                   process-wide singleton holder

  extension/
    WeatherMapLayer.kt                startMap → ShowSymbols/HideSymbols
    RainAlerter.kt                    InRideAlert scheduling

  datatypes/
    NumericDataType.kt                base class for graphical="false" fields
    TemperatureDataType.kt
    WeatherNowDataType.kt             WindDataType.kt RainNextHourDataType.kt RouteForecastDataType.kt
    views/FieldChrome.kt              night mode, unit labels, custom-state helper, column-count helper
    views/WeatherNowView.kt           Glance composables
    views/WindView.kt
    views/StripBitmapBuilder.kt       Canvas→one Bitmap: route strip
    views/BarChartBuilder.kt          Canvas→one Bitmap: rain chart
    views/ArrowBitmaps.kt             rotated arrow LruCache
    PreviewData.kt                    static samples for ViewConfig.preview

  ui/
    theme/Tokens.kt                   ColorPair + object Wx (plain Long ARGB, no Android imports)
    theme/NightMode.kt                isNightMode(context)
    theme/Theme.kt                    Material3 scheme derived from isSystemInDarkTheme()
    NowScreen.kt RouteScreen.kt SettingsScreen.kt AboutSection.kt
    WeatherViewModel.kt components/*.kt

  util/
    Log.kt  TimeFormat.kt  Distance.kt
```

**Dependency direction is strictly downward:** `domain` ← `route`/`weather`/`karoo` ← `data` ←
`datatypes`/`ui`/`extension`. `domain` and `route` compile without Android and hold every unit test.
`domain` has **zero** Android imports — not even `@StringRes`/`@DrawableRes` (see §3, `WindUnit`).

---

## 3. Core domain model (verbatim)

`«root»/domain/WeatherSample.kt`:

```kotlin
package io.github.glandais.karoo.weather.domain

import kotlinx.serialization.Serializable

/**
 * One point-in-time, point-in-space weather observation or forecast.
 * Canonical units: temperature °C, speeds m/s, precipitation mm (per interval),
 * angles degrees true, time epoch seconds UTC.
 */
@Serializable
data class WeatherSample(
    /** Epoch seconds, UTC. */
    val time: Long,
    /** Air temperature at 2 m, °C. */
    val temp: Double,
    /** Apparent ("feels like") temperature, °C. Null when not requested. */
    val apparentTemp: Double? = null,
    /** Mean wind at 10 m, m/s. */
    val windSpeed: Double,
    /** Wind gusts at 10 m, m/s. */
    val windGusts: Double,
    /** Meteorological wind direction: the direction the wind blows FROM, degrees true (0 = N). */
    val windDir: Double,
    /** Precipitation in the sample interval, mm. */
    val precip: Double,
    /** Probability of precipitation, percent 0..100. Null for `current` and `minutely_15`. */
    val precipProb: Int? = null,
    /** WMO 4677 code. */
    val wmoCode: Int,
    /** Cloud cover, percent 0..100. */
    val cloudCover: Int? = null,
    /** True when the sun is up at this point/time. */
    val isDay: Boolean,
) {
    /** Direction the wind blows TOWARDS, degrees true. */
    val windToDir: Double get() = (windDir + 180.0) % 360.0
}

/** One bar of the short-term rain nowcast. */
@Serializable
data class PrecipBucket(
    /** Epoch seconds, UTC, start of the bucket. */
    val time: Long,
    /** Bucket length in seconds (900 for minutely_15, 3600 for hourly fallback). */
    val durationSec: Int,
    /** Precipitation in the bucket, mm. */
    val mm: Double,
    /** Probability 0..100, null when unavailable. */
    val probability: Int? = null,
)

/** Coarse icon/semantic bucket derived from [WeatherSample.wmoCode]. */
enum class WmoCategory {
    CLEAR, MOSTLY_CLEAR, PARTLY_CLOUDY, OVERCAST, FOG,
    DRIZZLE, RAIN, HEAVY_RAIN, SHOWERS, FREEZING,
    SNOW, HEAVY_SNOW, THUNDER, THUNDER_HAIL, UNKNOWN,
}
```

`«root»/domain/GeoPoint.kt`:

```kotlin
package io.github.glandais.karoo.weather.domain

import kotlinx.serialization.Serializable

@Serializable
data class GeoPoint(val lat: Double, val lon: Double)
```

`«root»/domain/LocationForecast.kt`:

```kotlin
package io.github.glandais.karoo.weather.domain

import kotlinx.serialization.Serializable

/** Forecast series for one geographic point, as returned by a provider. */
@Serializable
data class LocationForecast(
    val lat: Double,
    val lon: Double,
    /** Provider "now" observation. Null in route-batch responses. */
    val current: WeatherSample? = null,
    /** Hourly series, ascending time, typically 12 entries. */
    val hourly: List<WeatherSample> = emptyList(),
    /** 15-minute precipitation nowcast, ascending time. Empty when unavailable. */
    val minutely15: List<PrecipBucket> = emptyList(),
    /** Model elevation, m. Informational. */
    val elevation: Double? = null,
)

/** Everything one fetch cycle produced. Persisted verbatim to DataStore. */
@Serializable
data class ForecastBundle(
    /** Epoch seconds when the fetch completed. */
    val fetchedAt: Long,
    /** Forecast at (or near) the rider's own position. */
    val here: LocationForecast,
    /** Forecast along the loaded route; null when no route is loaded. */
    val route: RouteForecast? = null,
    /** Provider identifier, for the About screen. */
    val provider: String = "open-meteo",
)
```

`«root»/domain/RouteForecast.kt`:

```kotlin
package io.github.glandais.karoo.weather.domain

import kotlinx.serialization.Serializable

/** A single sampled point along the loaded route, resolved to the weather at its ETA. */
@Serializable
data class RoutePointForecast(
    val point: GeoPoint,
    /** Distance from the route start, metres, in travel direction. */
    val distanceAlong: Double,
    /** Estimated arrival, epoch seconds UTC. */
    val eta: Long,
    /** Route tangent (travel direction) at this point, degrees true. */
    val routeBearing: Double,
    /** Weather interpolated to [eta]. */
    val sample: WeatherSample,
    /**
     * Signed angle between travel direction and the direction the wind blows towards,
     * degrees in (-180, 180]. 0 = pure tailwind, ±180 = pure headwind.
     */
    val relativeWindAngle: Double,
    /** Component of the wind opposing travel, m/s. Positive = headwind, negative = tailwind. */
    val headwindSpeed: Double,
    /** True when this point stands in for everything past the forecast horizon (§6.5). */
    val beyondHorizon: Boolean = false,
)

/** Forecast resolved along the remaining part of the loaded route. */
@Serializable
data class RouteForecast(
    val routeName: String,
    /** Full route length, metres (`NavigatingRoute.routeDistance`). */
    val routeDistance: Double,
    /** Rider progress from route start, metres, at [computedAt]. */
    val progress: Double,
    /** Epoch seconds when this projection was computed. */
    val computedAt: Long,
    /** Assumed speed used for the ETA model, m/s. */
    val assumedSpeed: Double,
    /**
     * Sample points, ascending [RoutePointForecast.distanceAlong].
     * **Index 0 is always the rider's own position** (`distanceAlong == progress`); indices 1..N-1 are
     * the route samples produced by `RouteSampler.sample`, which never emits a point at `progress`.
     * Size <= [WeatherRequest.MAX_POINTS].
     */
    val points: List<RoutePointForecast> = emptyList(),
    /** Total forecast precipitation over the sampled points, mm. */
    val totalPrecipMm: Double = 0.0,
    /** Distance-along of the first point with precip >= WET_THRESHOLD_MM, or null. */
    val firstWetDistance: Double? = null,
    /** ETA of the first wet point, epoch seconds, or null. */
    val firstWetEta: Long? = null,
) {
    companion object {
        const val WET_THRESHOLD_MM = 0.2
    }
}
```

`«root»/domain/Units.kt` — note there is **no** `labelRes` on `WindUnit`. Storing a resource *name* would
force `Resources.getIdentifier()` (deprecated since API 29, broken by the release build's R8 resource
shrinking); storing a `@StringRes Int` would put an Android import in `domain`. Unit labels are resolved in
the render layer instead: `FieldChrome.windUnitLabel(context, unit)` and a Compose twin in `ui/`.

```kotlin
package io.github.glandais.karoo.weather.domain

import kotlinx.serialization.Serializable

enum class TempUnit { CELSIUS, FAHRENHEIT }

/** [perMs] is the multiplier from m/s. BEAUFORT is handled by [Units.beaufort], not by [perMs]. */
enum class WindUnit(val perMs: Double) {
    MS(1.0),
    KMH(3.6),
    MPH(2.236936),
    KNOTS(1.943844),
    BEAUFORT(1.0),
}

enum class DistanceUnit(val perMetre: Double) { KM(0.001), MILES(0.000621371) }

/** Wind direction relative to travel. */
enum class WindClass { TAIL, CROSS, HEAD }

/** Resolved display units. Built from UserProfile.preferredUnit unless the user overrode them. */
@Serializable
data class Units(
    val temp: TempUnit = TempUnit.CELSIUS,
    val wind: WindUnit = WindUnit.KMH,
    val distance: DistanceUnit = DistanceUnit.KM,
) {
    fun temp(celsius: Double): Double =
        if (temp == TempUnit.FAHRENHEIT) celsius * 9.0 / 5.0 + 32.0 else celsius

    fun wind(ms: Double): Double = if (wind == WindUnit.BEAUFORT) beaufort(ms) else ms * wind.perMs

    fun distance(metres: Double): Double = metres * distance.perMetre

    companion object {
        fun beaufort(ms: Double): Double =
            when { ms < 0.3 -> 0.0; ms < 1.6 -> 1.0; ms < 3.4 -> 2.0; ms < 5.5 -> 3.0
                   ms < 8.0 -> 4.0; ms < 10.8 -> 5.0; ms < 13.9 -> 6.0; ms < 17.2 -> 7.0
                   ms < 20.8 -> 8.0; ms < 24.5 -> 9.0; ms < 28.5 -> 10.0; ms < 32.7 -> 11.0
                   else -> 12.0 }
    }
}
```

`«root»/domain/WeatherState.kt`:

```kotlin
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
) {
    val hasData: Boolean get() = bundle != null
    /** Data older than this is not shown as current. */
    fun isStale(nowSec: Long): Boolean =
        bundle == null || nowSec - bundle.fetchedAt > STALE_AFTER_SEC

    companion object { const val STALE_AFTER_SEC = 3 * 3600L }
}
```

`«root»/domain/WeatherProvider.kt` — note the error taxonomy. `Oversize` and `EmptyBody` are **retryable
with fewer points**; `Parse` (malformed JSON) is permanent. Collapsing them into one `Parse` case is
karoo-headwind's pitfall #15 in a new costume, and the previous revision of this document both declared
`Parse` non-retryable and retried it in §11.

```kotlin
package io.github.glandais.karoo.weather.domain

/** What one fetch cycle asks for. */
data class WeatherRequest(
    /** Rider position first, then route sample points, in order. Max [MAX_POINTS]. */
    val points: List<GeoPoint>,
    /** Hours of hourly forecast to request (1..24). */
    val forecastHours: Int = 12,
    /** Request the 15-min nowcast + `current` for points[0]. */
    val includeNowcast: Boolean = true,
) {
    init { require(points.isNotEmpty() && points.size <= MAX_POINTS) }
    companion object { const val MAX_POINTS = 25 }
}

sealed class WeatherError(val message: String, val retryable: Boolean) {
    data object NoConnection : WeatherError("no_connection", true)
    data object Timeout : WeatherError("timeout", true)
    data class RateLimited(val retryAfterSec: Long) : WeatherError("rate_limited", true)
    data class Server(val status: Int) : WeatherError("server_$status", true)
    data class Client(val status: Int) : WeatherError("client_$status", false)
    /** Response body exceeded the Karoo transport ceiling. Retry with fewer points. */
    data class Oversize(val bytes: Int) : WeatherError("oversize", true)
    /** Transport reported success with no body. Retry with fewer points. */
    data object EmptyBody : WeatherError("empty_body", true)
    /** Body was present but is not the JSON we expect. Permanent; do not retry. */
    data class Parse(val detail: String) : WeatherError("parse", false)

    /** True for the two errors whose remedy is to ask for a smaller response. */
    val reducePoints: Boolean get() = this is Oversize || this is EmptyBody
}

/**
 * Source of forecast data. One implementation in v1 (Open-Meteo, direct from device);
 * a thin backend or MET Norway would implement the same interface.
 */
interface WeatherProvider {
    val id: String

    /** Returns one [LocationForecast] per requested point, in request order. */
    suspend fun fetch(request: WeatherRequest): Result<List<LocationForecast>>
}
```

`«root»/domain/DataTypeIds.kt` — **5 ids in v1** (ADR-0 #7):

```kotlin
package io.github.glandais.karoo.weather.domain

object DataTypeIds {
    const val EXTENSION = "karoo-weather"

    const val WEATHER_NOW = "weather-now"
    const val TEMPERATURE = "temperature"
    const val WIND = "wind"
    const val RAIN_NEXT_HOUR = "rain-next-hour"
    const val ROUTE_FORECAST = "route-forecast"

    val ALL = listOf(WEATHER_NOW, TEMPERATURE, WIND, RAIN_NEXT_HOUR, ROUTE_FORECAST)

    fun full(typeId: String) = "TYPE_EXT::$EXTENSION::$typeId"
}
```

Deferred to v1.1, in this order: `apparent-temperature`, `headwind-speed`. Both are `NumericDataType`
subclasses and need no contract change.

---

## 4. Threading, lifecycle, shared state

### 4.1 The singleton

```kotlin
// «root»/data/WeatherGraph.kt
object WeatherGraph {
    @Volatile private var repo: WeatherRepository? = null

    fun repository(context: Context): WeatherRepository =
        repo ?: synchronized(this) {
            repo ?: WeatherRepository(
                appContext = context.applicationContext,
                settingsStore = SettingsStore(context.applicationContext),
                cache = ForecastCache(context.applicationContext),
            ).also { repo = it }
        }
}
```

`WeatherExtension` (service) and `MainActivity` are in the **same process** (no `android:process` attribute),
so this is one instance. Its `StateFlow<WeatherSnapshot>` is what every data field, the map layer and the
Compose UI collect. Nothing is passed across a Binder inside our own app.

### 4.2 Ownership of the Karoo connection and the fetch loop — **one service, two scopes**

The previous revision had `WeatherExtension` and `MainActivity` each construct a `KarooSystemService` and
hand it to a ref-counted `repository.attach(karoo)`. That is a trap: the repository latches onto whichever
instance attached first (very often the Activity's, because the consent gate *forces* the user to open the
app first), and `KarooSystemService.disconnect()` removes **all** listeners and calls
`context.unbindService` — so when the Activity is disposed the repository is left holding a dead service and
every stream is silently gone for the rest of the process lifetime. `unbindService` on a never-bound service
additionally throws `IllegalArgumentException`.

**Binding rules:**

1. The repository constructs and owns **exactly one** `KarooSystemService(appContext)`, lazily on the first
   `attach()`. Nobody else constructs one.
2. `attach()` and `detach()` take **no arguments** and are ref-counted. `WeatherExtension.onCreate` calls
   `attach()`; `MainActivity` calls `attach()` in a `DisposableEffect` and `detach()` in `onDispose`.
3. On the *last* `detach()` the repository calls `karoo.disconnect()` inside `runCatching { }` and drops the
   instance; the next `attach()` builds a fresh one.
4. **Two scopes, and they are not the same scope:**
   - `repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` — created in the constructor, **never
     cancelled**. It hosts `state` (`stateIn(repoScope, SharingStarted.Eagerly, WeatherSnapshot())`), the
     settings flow and the cache flow. A `StateFlow` needs its scope at construction time; cancelling that
     scope on the last detach would leave a second `attach()` holding a dead flow.
   - `sessionScope` — a fresh `CoroutineScope(SupervisorJob() + Dispatchers.IO)` created on first `attach()`
     and cancelled on last `detach()`. It hosts the trigger collector, the fetch worker, the `EtaModel`
     feeder and the alerter.

```kotlin
class WeatherRepository(...) {
    val state: StateFlow<WeatherSnapshot>          // repoScope, alive for the process
    val settings: Flow<WeatherSettings>
    fun attach()                                    // idempotent, ref-counted, no argument
    fun detach()
    /** For the map layer and the alerter, which need the raw service. Null before the first attach. */
    val karooOrNull: KarooSystemService?
    suspend fun requestRefresh(force: Boolean = false)
}
```

### 4.3 Data field lifecycle rules (non-negotiable)

- Any `startStream` / `startView` / `startMap` **that launches a coroutine** must finish with
  `emitter.setCancellable { … }` cancelling every job it launched — `stopView`/`stopStream`/`stopMap` call
  `emitters.remove(id)?.cancel()` and nothing else cleans up. A `startView` that only emits one config event
  and holds no job (see `NumericDataType`) needs no cancellable.
- Graphical fields emit, in a `configJob` that then `awaitCancellation()`:
  `emitter.onNext(UpdateGraphicConfig(showHeader = …))` and `emitter.onNext(ShowCustomStreamState(null, null))`
  once at start — without the latter Karoo's own "no data" overlay covers the custom view.
- **`ShowCustomStreamState` takes a resolved `String?` and a resolved `@ColorInt Int?`** (verified against
  `models/ViewEvent.kt`). It never takes a `@StringRes Int` and never takes a `ColorPair`. The only sanctioned
  call form is `FieldChrome.customState(context, R.string.x, night)` (§7.5).
- `ViewEmitter.updateView` is dropped if called < 900 ms apart. All view flows are throttled to
  `viewRefreshMs` (§10) before `updateView`; weather changes far slower than that.
- **`GlanceRemoteViews` and every mutable cache are created inside `startView`, per view.** `KarooExtension`'s
  binder resolves `types.firstOrNull { it.typeId == typeId }` and calls `startView` on that *single shared
  instance*; the page editor instantiates several previews at once and the same field can sit on two profile
  pages, so any per-instance mutable state is shared across concurrent views.
- Views subscribe to `streamDataTypeVisible(dataTypeId)` (derived from `ActiveRidePage`) and skip all
  compose/bitmap work when the field is not on the visible page.
- `config.preview == true` ⇒ render `PreviewData` and never touch the repository or the network.

---

## 5. Fetch policy

### 5.1 Triggers — a key producer, then a separate worker

The previous revision ran the request, the retry and the backoff *inside* the trigger collector
(`transformLatest { … }.conflate()`), so a 20 s request plus a 300 s backoff blocked the collector for over
five minutes and only the last trigger survived. Loading a route mid-backoff would show nothing for five
minutes — precisely when the rider is looking. Split in two:

```kotlin
// Producer — never suspends on I/O.
private val refreshKeys = MutableStateFlow<RefreshKey?>(null)

sessionScope.launch {
    combine(settings, roundedPosition, navigationState, rideState, activeRideProfile) { … }
        .filter { it.settings.consentAccepted }
        .filter { !it.profile.indoor }                     // indoor ride profiles never fetch
        .map { RefreshKey(it) }
        .distinctUntilChanged()
        .collect { refreshKeys.value = it }                // non-suspending, conflating by nature
}

// Worker — owns request + retry + backoff, restarts immediately when the key changes.
sessionScope.launch {
    refreshKeys.filterNotNull().collectLatest { key ->     // collectLatest cancels the in-flight attempt
        var attempt = 0
        while (isActive) {
            val outcome = runFetch(key)
            attempt = if (outcome.isSuccess) 0 else attempt + 1
            val wait = if (outcome.isSuccess) RefreshPolicy.intervalSec(settings, recording)
                       else RefreshPolicy.backoffSec(attempt, recording)
            if (outcome.isPermanentFailure) break          // wait for the next key
            delay(wait.seconds)
        }
    }
}
```

`RefreshKey` keys **only on fields that change the request** — keying on the whole `WeatherSettings` object
means toggling "wind arrows on map" costs an HTTP round trip (karoo-headwind pitfall #17):

```kotlin
data class RefreshKey(
    val consentAccepted: Boolean,
    val roundLocationKm: Double,
    val refreshMinutes: Int,
    val assumedSpeedKmh: Int,
    val useMeasuredSpeed: Boolean,
    val lastRefreshRequestedAt: Long?,
    val lat: Double?,          // grid-rounded
    val lon: Double?,
    val routeKey: String?,     // hash of routePolyline, null when no route
    val progressBucket: Int,   // (progress / spacing / 3).toInt()
)
```
`viewRefreshMs`, `mapLayerEnabled`, `rainAlertEnabled`, `tempUnit`, `windUnit` are deliberately **absent**.

| Trigger | Condition |
|---|---|
| Moved | rounded position changed — rounding grid = `Settings.roundLocationKm` (1/2/**3**/5 km) |
| Route changed | `NavigatingRoute.routePolyline` hash changed, or nav state entered/left `Idle` |
| Progress | advanced by ⅓ of the current sample spacing (keeps the route strip anchored ahead of the rider) |
| Periodic | the worker's own `delay(intervalSec)`: `Settings.refreshMinutes` 15 / **30** / 60, halved to a floor of 15 min while `RideState.Recording` |
| Manual | `Settings.lastRefreshRequestedAt` poke written by the app's Refresh button (no IPC needed) |
| Reconnect | `karoo.connect { connected -> if (connected) requestRefresh() }` |

### 5.2 Throttling, retry, backoff

- **Hard floor: 60 s between two actual HTTP cycles.** Enforced in `RefreshPolicy.shouldFetch`, so a burst of
  triggers coalesces into one request.
- Retry, driven by `WeatherError.retryable`: `30 s → 60 s → 120 s → 300 s`, then 300 s forever while
  `Recording`, 900 s otherwise. `RateLimited` jumps straight to `max(retryAfter, 900 s)`.
- `WeatherError.reducePoints` (`Oversize`, `EmptyBody`) additionally **halves the requested point count**
  (floor 2) for the next attempt; the count resets on the next success or the next `RefreshKey`.
- Non-retryable (`Client 4xx`, `Parse`) **stops the loop** and surfaces the error; the next *trigger* retries.
  This deliberately fixes karoo-headwind's pitfall #15 (infinite retry on permanent errors).
- Per-request timeout **20 s** (our own `.timeout(20.seconds)` around the `callbackFlow`, not ktor's 10 s).
- `waitForConnection = false` everywhere; our retry loop *is* the queue, and a queued request that lands
  40 minutes later would deliver a stale forecast anyway.

### 5.3 Privacy rounding

Coordinates are rounded to a grid before leaving the device:

```kotlin
fun Geo.roundToGrid(p: GeoPoint, km: Double): GeoPoint
```
Latitude uses 111.32 km/deg; **longitude uses `111.32 * cos(lat)`** — fixing karoo-headwind's pitfall #14,
where the same factor was used for both and the grid silently coarsened towards the poles.

### 5.4 The two requests

Both requests share one **`UNIT_PARAMS` constant** so their field semantics can never diverge — they are
merged field-by-field in §5.5, and a unit divergence there would be invisible and wrong.

```kotlin
const val UNIT_PARAMS = "&timeformat=unixtime&wind_speed_unit=ms&temperature_unit=celsius&precipitation_unit=mm"
```

**Request A — route batch + here (one call, N ≤ 25 points):**

```
GET https://api.open-meteo.com/v1/forecast
  ?latitude=<lat0,lat1,…>&longitude=<lon0,lon1,…>          // %.4f, Locale.US, ~11 m resolution
  &hourly=temperature_2m,precipitation,precipitation_probability,weather_code,
          wind_speed_10m,wind_direction_10m,wind_gusts_10m,is_day
  &forecast_hours=12&past_hours=0
  <UNIT_PARAMS>
```
`forecast_days` is **not** sent: `weather-apis.md` §3.5 documents `forecast_hours=N` as the bounding
parameter and never combines the two, and their interaction is unverified against the live API.
Index 0 is the rider's position; indices 1..N-1 are the route samples in ascending `distanceAlong`.
Response is a JSON **object** when N == 1 and a JSON **array** when N > 1 — the parser branches on
`expectedPoints == 1`, exactly as karoo-headwind does. Points are re-zipped **positionally**, never by
lat/lon equality (Open-Meteo snaps to its grid: requested `2.35` comes back as `2.3599997`).

**Request B — here, current + nowcast. Always issued** (≈ 1.8 KB, 2 % of the budget). The previous revision
gated it on "`rain-next-hour` is visible, the rain alert is on, or the app is in the foreground" — none of
which the repository can observe, since visibility is derived inside the *view* and no foreground signal
exists at all. The gate was unimplementable; the request is cheap; the gate is deleted.

```
GET …/v1/forecast?latitude=<lat0>&longitude=<lon0>
  &current=temperature_2m,apparent_temperature,precipitation,weather_code,cloud_cover,
           wind_speed_10m,wind_direction_10m,wind_gusts_10m,is_day
  &minutely_15=precipitation,precipitation_probability
  &forecast_minutely_15=8                                   // 8 × 15 min = 2 h
  &hourly=apparent_temperature&forecast_hours=12
  <UNIT_PARAMS>
```

**Size guard** (`OpenMeteoUrl.estimateResponseBytes(points, hourlyVars, hours)`), applied before sending.
The formula is calibrated **against the committed fixture, with a 1.5× safety factor** — the previous
formula produced 24 950 B for the 25-point case against this document's own 31.1 KB estimate and the
measured 933 B/point, i.e. it was optimistic in the only direction that matters:

```
raw   ≈ 300 + points × (140 + hourlyVars × hours × 12)
bytes = ceil(raw × 1.5)
```
`OpenMeteoUrlTest` asserts `estimateResponseBytes(25, 8, 12) >= multi_point_25.json.length`. If the estimate
exceeds **`SIZE_BUDGET_BYTES = 80_000`** the point count is reduced until it fits; the URL is never sent
blind. Actual responses are additionally checked against `OnHttpResponse.MAX_REQUEST_SIZE` (100 000, verified
in `models/KarooEvent.kt:252`) on receipt and reported as `WeatherError.Oversize(bytes)`.

### 5.5 Merging request A and request B — **by time, never by index**

Both requests carry `forecast_hours=12` anchored to "now". Issued seconds apart across an hour boundary,
their hourly arrays are offset by one hour, and an index merge shifts "feels like" by 60 minutes with no
visible symptom. The merge therefore joins on `WeatherSample.time` (epoch seconds), which both DTOs carry:

```kotlin
// A[0].hourly enriched with B.hourly.apparentTemperature, joined on time
val bByTime = b.hourly.associateBy { it.time }
val merged = a0.hourly.map { it.copy(apparentTemp = bByTime[it.time]?.apparentTemp) }
```
`current` and `minutely15` come from B verbatim.

### 5.6 Offline & cold start

- Last `ForecastBundle` and last known position are persisted to DataStore. On process start the repository
  emits the cached snapshot immediately, so fields render within one frame instead of showing "searching".
- Age > `STALE_AFTER_SEC` (3 h) ⇒ numeric fields emit `StreamState.NotAvailable`; graphical fields render the
  value greyed with a `~` prefix and the field header carries
  `ShowCustomStreamState(context.getString(R.string.state_stale), Wx.fgMuted.pick(night))`.
- No GPS fix and no cached fix ⇒ `ShowCustomStreamState(context.getString(R.string.state_no_gps), …)` /
  `StreamState.Searching`.
- Location accuracy gate: ignore `LOCATION` points with `FIELD_LOC_ACCURACY_ID >= 500`.

---

## 6. Route forecast algorithm

Input: `OnNavigationState.NavigationState.NavigatingRoute`, the `DISTANCE_TO_DESTINATION` stream, the
`SPEED` stream, the `LOCATION` stream, `Settings.assumedSpeedKmh`.

1. **Decode.** `Polyline.decode(routePolyline, precision = 5) → List<GeoPoint>`.
   If `reversed`, reverse the list so `distanceAlong` always increases in the direction of travel.
2. **Index.** `RoutePath(points)` precomputes cumulative haversine distances. `routeDistance` from the event is
   used for progress; the decoded length is used for geometry (they differ by < 0.5 % in practice).
3. **Progress, with a GPS fallback that actually works.**
   `progress = routeDistance − distanceToDestination` while that stream is producing.
   `NavigatingRoute.breadcrumb == true` means turn-by-turn is disabled and `DISTANCE_TO_DESTINATION` may
   never stream at all — the previous revision then fell back to "last known progress, else 0", so a
   breadcrumb route showed the weather at the route *start* for the entire ride: silently wrong, which is
   worse than visibly broken. **When the stream has produced nothing for 30 s, progress is instead
   `RoutePath.nearestDistanceTo(lastGpsPoint)`.** Clamp to `[0, routeDistance]` and never let it decrease by
   more than 200 m between updates (guards against a projection snapping to a crossing loop).
4. **Adaptive sampling.** Remaining `R = routeDistance − progress`. Target ≤ 24 **route** points; the rider's
   own position is *not* one of them (§6.6):

   ```kotlin
   val raw = R / RouteSampler.MAX_ROUTE_POINTS
   val spacing = SPACINGS_M.firstOrNull { it >= raw } ?: (ceil(raw / 10_000.0) * 10_000.0)
   ```
   Samples at `progress + spacing, progress + 2·spacing, …` while `< path.length`, always plus the route end.
   **`sample()` never emits a point at `distanceAlong == progress`.**
5. **ETA.** `eta(d) = now + (d − progress) / v`, `v` in m/s where

   ```
   v = EMA(SPEED stream, τ = 5 min), used only while RideState.Recording, useMeasuredSpeed, and v ≥ 2.0 m/s
   otherwise v = Settings.assumedSpeedKmh / 3.6      (default 22 km/h → 6.11 m/s)
   ```
   The EMA is reset on route change. No elevation-aware speed model in v1 — the elevation polyline is decoded
   but only used for display, because a bad climb model is worse than a transparent constant.
6. **Horizon truncation** is a *pure function in `RouteSampler`*, so it is testable at the same signature as
   the sampling it modifies (the previous revision specified it in §6 but gave `sample()` no clock, no speed
   and no ETA, making it unimplementable and untestable):

   ```kotlin
   fun truncateToHorizon(
       samples: List<RouteSample>,
       eta: (Double) -> Long,          // distanceAlong -> epoch seconds
       nowSec: Long,
       horizonSec: Long = 11 * 3600L,
   ): Pair<List<RouteSample>, Int?>    // kept samples, index of the horizon marker or null
   ```
   Every sample whose ETA exceeds `nowSec + horizonSec` is dropped, and the whole dropped tail is replaced by
   **one** marker: the last sample still inside the horizon. `WeatherRepository` flags the resulting
   `RoutePointForecast` at that index with `beyondHorizon = true`, and the strip's last column reads `>12h`.
7. **Weather at ETA.** For sample *i*, take `LocationForecast.hourly` of response index *i+1* and
   **linearly interpolate** between the two bracketing hours:
   - continuous fields (`temp`, `apparentTemp`, `windSpeed`, `windGusts`, `precipProb`) → `lerp`
   - `windDir` → `lerpAngle` (shortest arc; a naive lerp across 350°→10° yields 180°, which is the single
     most damaging bug in this whole file)
   - `precip` → **no interpolation**, take the hour the ETA falls in (it is an accumulation, not a level)
   - categorical (`wmoCode`, `isDay`) → nearest hour
8. **Route bearing.** `routeBearing = RoutePath.bearingAt(d)`, computed as `bearing(pointAt(d), pointAt(d + 25 m))`
   clamped at the route end (`bearing(pointAt(d − 25 m), pointAt(d))`).
9. **Relative wind.**

   ```kotlin
   val rel = Geo.signedAngleDifference(routeBearing, sample.windToDir)   // (-180, 180]
   val headwind = -cos(Math.toRadians(rel)) * sample.windSpeed           // + head, − tail
   ```
   `rel == 0` means the wind blows exactly along travel ⇒ pure tailwind ⇒ `headwind = −v`.
   `|rel| == 180` ⇒ pure headwind ⇒ `headwind = +v`. Classification for colour/label:
   `|rel| < 45°` TAIL, `45..135°` CROSS, `> 135°` HEAD.
10. **Rain summary.** `totalPrecipMm = Σ points.precip`; `firstWetDistance/firstWetEta` = first point with
    `precip ≥ 0.2 mm` **or** `precipProb ≥ 60`.

**Assembly ownership (§6.6).** `RouteSampler` returns route samples only. `WeatherRepository` is solely
responsible for **prepending the rider's own position** as `points[0]` and for building the 25-point
`WeatherRequest` (`1 + min(24, samples.size)`). No other component adds or removes a point.

**No route loaded** ⇒ `RouteForecast == null`; the route-forecast field falls back to a *time* axis using
`here.hourly` (now, +1 h, +2 h, …) and labels columns with clock times instead of distances.

---

## 7. Data fields

5 fields in v1 — enough to build a full weather page, few enough that the picker stays scannable and every
layout gets built and tested rather than sketched.

| typeId | displayName | graphical | What it shows | Grid behaviour |
|---|---|---|---|---|
| `weather-now` | Weather | **true** | WMO icon (day/night) + temperature; wind, feels-like and precip probability as space allows | `(30,30)` icon + temp only; `(60,30)` icon ∥ temp ∥ wind; `(·,60)` adds "feels like" and precip rows; `(60,60)` adds an hourly outlook strip |
| `temperature` | Temperature | false | Air temperature | numeric, `UpdateNumericConfig(DataType.Type.TEMPERATURE)` |
| `wind` | Wind | **true** | Arrow rotated to wind-relative-to-heading, mean speed, gust below | `(30,30)` arrow + speed; `(60,·)` arrow ∥ speed ∥ "G 27"; `(·,60)` adds compass label |
| `rain-next-hour` | Rain next 2 h | **true** | One bitmap: 8 × 15 min bars from `minutely_15`, else 3 × 1 h from `hourly` | `(60,15)` is the design target |
| `route-forecast` | Route forecast | **true** | One bitmap: timeline strip along the remaining route | column count derived from `viewSize` (§7.4) |

### 7.1 Numeric fields

`temperature` is `graphical="false"`. Its `startView` emits **`UpdateNumericConfig(formatDataTypeId)`** —
`models/ViewEvent.kt` documents `UpdateNumericConfig` as "Update the way a numeric data types are shown" and
`UpdateGraphicConfig.formatDataTypeId` as an *overlay on top of graphical fields*, so the numeric one is
correct here even though karoo-headwind's `BaseDataType` emits the graphic one. `UpdateNumericConfig`'s
parameter is **non-nullable**, so `NumericDataType.formatDataTypeId == null` means *emit nothing* (Karoo then
defaults to integer precision) — it does not mean emit `null`.

> **Spike S2, before WP4 builds on this:** put `temperature` on a page on real hardware, confirm
> `UpdateNumericConfig(DataType.Type.TEMPERATURE)` renders with the profile's unit and precision. 20 minutes.
> If it does not, the fallback is `UpdateGraphicConfig(formatDataTypeId = …)` with `graphical="true"` and no
> custom view, and only `NumericDataType.startView` changes.

### 7.2 Graphical fields

- Composed with `GlanceRemoteViews().compose(context, DpSize.Unspecified) { … }` — **constructed inside
  `startView`** — and pushed via `emitter.updateView(result.remoteViews)`.
- **`route-forecast` and `rain-next-hour` render as exactly ONE bitmap sized to `config.viewSize`**, wrapped
  in a single `Image(ImageProvider(bitmap))`. A `RemoteViews` is Parcelled across a Binder on every
  `updateView`; a 5-column strip built from 5 arrow bitmaps plus 5 WMO icons at 128 × 128 ARGB_8888 is
  ≈ 640 KB in one transaction against a ~1 MB Binder budget — `TransactionTooLargeException` or severe jank,
  worst on K2. karoo-headwind avoids this by drawing the whole graph as one `viewSize`-sized bitmap; so do we.
- `weather-now` and `wind` stay Glance-composed (text + at most one small icon + one arrow bitmap).
- The arrow bitmap is **48 px** (56 px when `viewSize.second > 300`), not 128 px. At 293 ppi a 3.2" field
  never shows an arrow at more than about 1:1.
- Every graphical field also implements `startStream` where a meaningful single number exists
  (`weather-now` → temperature, `wind` → wind speed) so other extensions can consume
  `TYPE_EXT::karoo-weather::wind`. Unavailable ⇒ `StreamState.NotAvailable`, never a sentinel double.
- `weather-now` and `route-forecast` are `clickable(actionStartActivity<MainActivity>())` when
  `!config.preview`.

### 7.3 Cross-extension stream contract — **SI, always**

**Every `StreamState.Streaming` value this extension emits is canonical SI: temperature in °C, speed in m/s,
precipitation in mm, angles in degrees true.** Unit conversion happens only in graphical rendering and in the
companion app. This is a public API — a downstream consumer that guesses wrong is wrong forever — and it is
also what Karoo's own `UpdateNumericConfig` formatting expects (SI in, user units out).

`headwind-speed` was cut from v1 partly for this reason: with `UpdateNumericConfig` the extension supplies
only a `Double` and Karoo owns the glyphs, so the `+8 km/h` sign convention the design called for cannot be
rendered, and Karoo's `SPEED` formatter is untested against negative values.

### 7.4 Column counts are derived, not hard-coded

`gridSize` decides **which rows exist**; `viewSize` decides **how many columns fit**. A helper in
`FieldChrome` is the single implementation:

```kotlin
/** MIN_CELL_PX = 88. Never returns more than [maxColumns] or fewer than 1. */
fun columnsFor(viewSize: Pair<Int, Int>, maxColumns: Int): Int =
    (viewSize.first / MIN_CELL_PX).coerceIn(1, maxColumns)
```

| gridSize | rows in the route strip | maxColumns |
|---|---|---|
| `first == 30` | icon / temp / distance | 1 |
| `(60,15)` | icon+temp on one row / distance | 3 |
| `(60,30)` | icon / temp+arrow / distance | 5 |
| `(60,60)` | icon / temp / arrow / distance / ETA | 6 |

At the settled portrait geometry (480 × 800) a `(60,30)` field is 480 × ~400 px, so `columnsFor` yields 5 and
every row clears the 10 sp floor. On a device that reports something narrower it degrades to 4 or 3 by
itself. **Before WP4 declares done, log the real `ViewConfig` (`gridSize`, `viewSize`, `textSize`) for
`(30,30)`, `(60,15)`, `(60,30)`, `(60,60)` on hardware and record them in a comment in `FieldChrome.kt`.**

### 7.5 The two chrome helpers everybody uses

Written once in `datatypes/views/FieldChrome.kt`; no field may reinvent either:

```kotlin
/** Configuration.uiMode & UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES. Canvas code cannot use ColorProvider. */
fun isNightMode(context: Context): Boolean          // declared in «root».ui.theme.NightMode, re-exported here

/** The ONLY sanctioned way to build a ShowCustomStreamState. */
fun customState(context: Context, @StringRes message: Int?, pair: ColorPair, night: Boolean): ShowCustomStreamState =
    ShowCustomStreamState(message?.let(context::getString), pair.pick(night))
```
`ColorProvider(day, night)` resolves the theme automatically only for **Glance-drawn** elements. Anything
drawn into a `Canvas` — the strip bitmap, the bar chart, the rotated arrow — must pick a side itself, which is
what `isNightMode` is for. Consequently a pre-rotated, pre-tinted arrow bitmap is **not** re-tintable at
render time; the tint is baked in and is part of the cache key.

---

## 8. Map layer

`extension_info.xml` gets `mapLayer="true"`; `WeatherExtension.startMap(emitter: Emitter<MapEffect>)`
delegates to a **freshly constructed `WeatherMapLayer` instance per call**. Vector symbols only — no raster
overlay, no MapLibre.

```kotlin
emitter.onNext(ShowSymbols(symbols))     // re-emit with the same ids to update in place
emitter.onNext(HideSymbols(staleIds))
```

- One `Symbol.Icon(id = "wx-$index", lat, lng, iconRes = R.drawable.ic_map_wind_arrow, orientation = windToDir.toFloat())`
  per selected route sample. `Symbol.Icon.orientation` is documented as "0 is North, 90 is East" — exactly the
  meteorological convention after the `+180` flip, so the arrow points where the wind is going.
- A second icon `"wx-rain-$index"` with the WMO drawable is added only where `precip ≥ 0.2 mm`, so a dry route
  stays visually clean.
- **Zoom-aware density**, driven by `OnMapZoomLevel` (range 8.0–18.0, map page cycles 13/15/16):

  | zoom | min spacing between drawn symbols |
  |---|---|
  | ≥ 15 | 2 km |
  | 12–15 | 5 km |
  | < 12 | 20 km |

  Symbols are selected from the existing `RouteForecast.points` by greedy spacing; no extra fetch.
- **The zoom flow must be seeded.** `OnMapZoomLevel`'s KDoc — unlike `RideState`'s and `UserProfile`'s —
  does *not* promise that a new consumer receives the current value, and `combine` emits nothing until every
  source has emitted. Without a seed no symbol would ever appear until the rider pinched the map:
  `karoo.consumerFlow<OnMapZoomLevel>().onStart { emit(OnMapZoomLevel(15.0)) }`.
- Re-emit only when the forecast bundle changes or the zoom bucket changes — never on a GPS tick
  (`.distinctUntilChangedBy { bundleFetchedAt to zoomBucket }`).
- `previousIds` lives on the `WeatherMapLayer` **instance**, never in a companion object, so a
  `stopMap` → `startMap` cycle cannot leak ids across instances. `emitter.setCancellable { job.cancel();
  emitter.onNext(HideSymbols(previousIds)) }`.
- The coroutine scope comes from `WeatherExtension.extensionScope`, a named
  `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — the SDK passes `startMap` no scope of its own.
- Toggled by `Settings.mapLayerEnabled`; when off, the layer emits `HideSymbols(previousIds)` once and idles.
- No `ShowPolyline` in v1: one colour per polyline id means N segment ids to colour a route by rain, and the
  route line is Karoo's own — recolouring it is intrusive. Revisit after user feedback.

---

## 9. In-ride alerts

Opt-in (`Settings.rainAlertEnabled`, default **off**). `RainAlerter` runs inside `sessionScope` and
dispatches at most one alert per condition per hour:

```kotlin
karoo.dispatch(InRideAlert(
    id = "karoo-weather-rain",
    icon = R.drawable.ic_wmo_rain,
    title = context.getString(R.string.alert_rain_title, minutes),   // "Rain in 12 min"
    detail = context.getString(R.string.alert_rain_detail, mm),      // "1.4 mm expected"
    autoDismissMs = 10_000L,
    backgroundColor = R.color.alert_bg,
    textColor = R.color.alert_fg,
))
```
`InRideAlert.backgroundColor` / `textColor` are `@ColorRes` (verified, `models/KarooEffect.kt:249,253`), so
the alert palette lives **only** in `res/values/colors.xml` + `res/values-night/colors.xml`. There are no
`Wx.alertBg` / `Wx.alertFg` tokens — two sources of truth for one colour is how alerts end up white-on-white.

Conditions, all gated on `RideState.Recording`:
- **Rain starting**: first `minutely_15` bucket with `mm ≥ 0.2` within the next 30 min, when the current
  bucket is dry. Cooldown 60 min, re-armed after 30 min of dry buckets.
- **Rain stopping** is *not* alerted (not actionable).
- No alert for temperature or wind in v1 — `InRideAlert` is a modal interrupt and over-use trains riders to
  dismiss it (karoo-ux.md §5).

---

## 10. Settings

`@Serializable data class WeatherSettings`, stored as one JSON string under
`stringPreferencesKey("settings")`, read with `Json { ignoreUnknownKeys = true }` + try/catch + default —
so adding a field is a free migration.

| Key | Type | Default | Notes |
|---|---|---|---|
| `consentAccepted` | Boolean | `false` | Gates **all** network access |
| `tempUnit` | `TempUnit?` | `null` | `null` = follow `UserProfile.preferredUnit.temperature` |
| `windUnit` | `WindUnit?` | `null` | `null` = km/h when metric, mph when imperial |
| `assumedSpeedKmh` | Int | `22` | clamped 5..60; used when no measured speed |
| `useMeasuredSpeed` | Boolean | `true` | EMA of the `SPEED` stream overrides the assumption while riding |
| `refreshMinutes` | Int | `30` | 15 / 30 / 60 |
| `roundLocationKm` | Double | `3.0` | 1 / 2 / 3 / 5 — privacy grid and the "moved" trigger |
| `mapLayerEnabled` | Boolean | `true` | wind arrows on the map |
| `rainAlertEnabled` | Boolean | `false` | opt-in `InRideAlert` |
| `viewRefreshMs` | Long | `2000` | user preference only — a `@Serializable` default cannot be hardware-dependent |
| `lastRefreshRequestedAt` | Long? | `null` | manual refresh poke |

**Effective repaint interval** is computed, not stored (karoo-headwind pitfall #1: `karooSystem.hardwareType`
is only valid *after* `connect{}` fires, and `startView` can run before that):

```kotlin
suspend fun KarooSystemService.viewRefreshMs(settings: WeatherSettings): Long = when (hardwareType) {
    HardwareType.K2 -> max(settings.viewRefreshMs, 3_000L)
    null            -> 3_000L                                  // not connected yet: assume the slow side
    else            -> max(settings.viewRefreshMs, 900L)       // never below the 900 ms updateView floor
}
```
Views re-read it once `connected` becomes true.

About section, verbatim and non-removable: **`Weather data by Open-Meteo.com (CC BY 4.0)`**, linked to
`https://open-meteo.com/`, plus icon credits and the extension version.

---

## 11. Error and edge handling

| Situation | Behaviour |
|---|---|
| No route loaded | `RouteForecast = null`; `route-forecast` field switches to a time axis; map layer hides its symbols |
| `NavigatingToDestination` | Treated as a route using `polyline`; the polyline changes on every deviation, so the route-change trigger is additionally debounced by 5 min on this branch |
| Breadcrumb route / no `DISTANCE_TO_DESTINATION` | Progress from `RoutePath.nearestDistanceTo(lastGpsPoint)` after 30 s of stream silence (§6.3) |
| No GPS, no cache | `StreamState.Searching` (numeric) / `ShowCustomStreamState("No GPS", …)` (graphical) |
| GPS accuracy ≥ 500 m | Point ignored |
| **K2 hardware** | Fully supported. We do **not** use `ktor-client-karoo` (it throws `KarooIsUnsupportedException` on K2); the raw `MakeHttpRequest` path is hardware-agnostic. K2 only differs in `viewRefreshMs` (3 s vs 2 s) |
| `karoo.connected == false` | `dispatch` returns `false`; the repository parks and retries on the `connect` callback |
| Indoor ride profile (`ActiveRideProfile.profile.indoor`) | Fetching suspended; fields show the cached value, no network |
| HTTP 4xx (not 429) | `WeatherError.Client`, non-retryable; error surfaced in the app's Now screen, fields fall back to cache |
| Response > `MAX_REQUEST_SIZE` | `WeatherError.Oversize`, **retryable**; point count halved on the next attempt |
| Success with null body | `WeatherError.EmptyBody`, **retryable**; point count halved |
| Malformed JSON | `WeatherError.Parse`, **not** retryable; cache retained, error shown; next trigger retries |
| Route longer than the 12 h horizon | Samples past `now + 11 h` collapse into one `beyondHorizon` marker |
| Process death mid-ride | Cached bundle + last position restored from DataStore; fields repaint immediately |

---

## 12. Testing strategy

Pure JVM (`app/src/test/kotlin`, JUnit 4, no Robolectric, no Android imports). The whole `route/`, `weather/`
and `domain/` surface is deliberately Android-free so this is possible.

| Suite | What it pins down |
|---|---|
| `PolylineTest` | Decode of the Google reference string ``_p~iF~ps|U_ulLnnqC_mqNvxq`@`` (note the **backtick** before `@`) → the three known points `(38.5,-120.2) (40.7,-120.95) (43.252,-126.453)` within 1e-5; precision 1 (distance/elevation); round-trip encode/decode; empty and malformed input |
| `GeoTest` | Haversine against 4 known city pairs (± 0.3 %); bearing Paris→Berlin ≈ 68°; `signedAngleDifference` across the ±180 seam (350,10)→20, (10,350)→−20; `roundToGrid` at lat 0/45/70 |
| `RoutePathTest` | Cumulative distances monotonic; `pointAt(0)`/`pointAt(len)` are the endpoints; `pointAt` mid-segment interpolation; `bearingAt` at the final vertex; **`nearestDistanceTo` on a straight leg, at a vertex, off-route by 300 m, and on a route that crosses itself** |
| `RouteSamplerTest` | N ≤ 24 for routes of 5/50/200/1000 km; spacing selection; end always included; **no sample at `distanceAlong == progress`**; reversed route ordering; `truncateToHorizon` drops beyond 11 h and leaves exactly one marker |
| `EtaModelTest` | EMA convergence and reset; fallback to `assumedSpeedKmh` when stopped, when not recording, and when speed < 2 m/s; monotonic ETAs |
| `RelativeWindTest` | Pure tailwind (route bearing 90°, `windDir` = 270° so `windToDir` = 90°) ⇒ rel = 0, headwind = −v, class TAIL; pure headwind (route 90°, `windDir` = 90°) ⇒ rel = 180, headwind = +v, class HEAD; 45° cross ⇒ headwind = −v·cos 45°; classification boundaries at exactly 45° and 135° |
| `WmoCodesTest` | Every code in the WMO 4677 table maps to the intended `WmoCategory` (fog 45/48 → `FOG`, **not** `RAIN` — karoo-headwind's bug); unknown codes → `UNKNOWN`; `WmoIcons.field` day/night selection is total |
| `InterpolationTest` | `lerpAngle` across the seam; precip is not lerped; categorical fields take the nearest hour; `sampleAt` before/after the series bounds |
| `OpenMeteoUrlTest` | Exact URL string for 1 and 25 points; `%.4f` `Locale.US` formatting (one assertion runs under `Locale.GERMANY` to catch decimal commas); **`estimateResponseBytes(25, 8, 12) >= multi_point_25.json.length`**; monotonicity; `maxPointsWithin` under the 80 KB guard |
| `OpenMeteoParseTest` | Fixtures `single_point.json`, `multi_point_25.json`, `minutely15.json`, `error_400.json` under `app/src/test/resources/fixtures/` → object-vs-array branch, positional zip, null-field tolerance, unknown-key tolerance |
| `OpenMeteoProviderTest` | Fake `HttpGateway`: success, 429→`RateLimited`, 500→`Server`, 400→`Client`, malformed→`Parse`, oversize→`Oversize`, empty→`EmptyBody`; **the A/B merge joins on `time` and survives a one-hour offset between the two responses** |
| `UnitsTest` | °C→°F, m/s→km/h/mph/kn, Beaufort boundaries at 0.3/1.6/…/32.7 |
| `RefreshPolicyTest` | `RefreshKey` equality: identical position/route ⇒ no refetch; **changing `mapLayerEnabled` or `viewRefreshMs` ⇒ no refetch**; 3 km move ⇒ refetch; backoff sequence; `reducePoints` halving; non-retryable stops the loop |
| `RouteForecastAssemblyTest` | `buildRouteForecast` — index 0 is the rider, ordering, wet-point detection, headwind signs, `beyondHorizon` marker |
| `MapLayerSelectionTest` | spacing buckets, greedy selection keeps first and last, empty input |
| `RainAlerterTest` | rain in 12 min detected, dry ignored, cooldown blocks, disabled blocks, not-recording blocks |
| `TokensTest` | temp/headwind/rain ramp selection is total and monotonic |
| `PreviewDataTest` | the preview snapshot exercises every ramp bucket and both wind classes |

Fixtures are captured from the live API once, with the **exact final URL**, and committed; they are the
contract for the parser. Anything requiring `Context`, Glance or `KarooSystemService` is **not** unit tested —
it is verified by `assembleDebug`, `assembleRelease` and manual on-device checks.

---

## 13. Pre-implementation spikes (both gate work that would otherwise be rebuilt)

| # | Question | How | Blocks |
|---|---|---|---|
| S1 | Does `karoo-ext` resolve with the GitHub-Packages block deleted? | **Done, 2026-08.** `jitpack.io` serves `io/hammerhead/karoo-ext/1.1.9/karoo-ext-1.1.9.pom` (HTTP 200); Maven Central 404s. Deletion is safe. | WP0 (build file edit) |
| S2 | Does `UpdateNumericConfig(DataType.Type.TEMPERATURE)` render correctly on a `graphical="false"` field? | Put `temperature` on a page on real hardware; 20 min. | WP4 (`NumericDataType`) |
| S3 | Real `ViewConfig` values on hardware for the four grid sizes. | Log `gridSize`/`viewSize`/`textSize` in `startView`; record in `FieldChrome.kt`. | WP4 layout tuning (not its structure — `columnsFor` absorbs the answer) |
