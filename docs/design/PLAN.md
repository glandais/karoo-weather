# karoo-weather — IMPLEMENTATION PLAN

**Revision 2** — incorporates CRITIQUE.md. Read `ARCHITECTURE.md` and `DESIGN.md` first; this file only says
**who builds what, in which files, against which signatures**.

Root: `/Users/glandais/code/perso/karoo-weather`
Source root: `app/src/main/kotlin/io/github/glandais/karoo/weather/` (`«src»` below)
Test root: `app/src/test/kotlin/io/github/glandais/karoo/weather/` (`«test»` below)
Package root: `io.github.glandais.karoo.weather` (`«root»` below)

## Rules for concurrent agents

1. **Every work package owns a disjoint set of file paths.** Never create or edit a file outside your list.
   The ownership table in §"File ownership map" is the authority; if a path is not in your row, you may not
   touch it, not even to add one string.
2. **WP0 creates every shared file with its complete, final content** — all strings, all colours, all five
   `<DataType>` entries, all build-file edits. WP1–WP7 read those files and never write them. WP8 is the only
   package permitted to *amend* them, and it runs after all others have finished, so ownership is still
   exclusive in time. There is no "WP8 must add later" mechanism: if you find a string missing, that is a bug
   in WP0's list and it is fixed by a note to the integrator, not by editing the file yourself.
3. **Scheduling.** WP0 runs alone and completes first. Then WP1, WP2 and WP7 run in parallel. Then WP3.
   Then WP4, WP5 and WP6 in parallel. Then WP8.
4. Formatting: `ktfmt` kotlinlang style, 4-space indent, enforced by `./gradlew spotlessApply`. Run it before
   you declare done.
5. **Only WP0 may change Gradle files.** The dependency set after WP0 is final: ktor and ktor-client-karoo are
   removed; `kotlinx-coroutines-test` is added as a test dependency; nothing else changes. No Mapbox Turf, no
   ktor, no new runtime dependency (see ARCHITECTURE ADR-0 #3, #4).
6. **SI in, units out.** Every value that crosses a package boundary or a Karoo stream is °C, m/s, mm, metres,
   degrees true, epoch seconds UTC. Conversion happens only in `datatypes/views` and `ui`.
7. **Never write `ShowCustomStreamState(...)` directly.** The SDK signature is
   `ShowCustomStreamState(message: String?, @ColorInt color: Int?)`. Use
   `FieldChrome.customState(context, @StringRes msgRes, pair, night)`.

---

## File ownership map

| Path | Owner |
|---|---|
| `«src»/domain/**` | WP0 |
| `«src»/ui/theme/Tokens.kt`, `«src»/ui/theme/NightMode.kt` | WP0 |
| `app/src/main/res/values/strings.xml`, `values/colors.xml`, `values-night/colors.xml`, `res/xml/extension_info.xml` | WP0 (create) → WP8 (amend only) |
| `app/build.gradle.kts`, `gradle/libs.versions.toml`, `settings.gradle.kts` | WP0 (create) → WP8 (amend only) |
| `app/src/main/res/drawable/*.xml` | WP0 creates placeholders → WP7 replaces path data |
| `app/src/main/res/raw/icon_credits.txt` | WP7 |
| `«src»/route/**`, `«test»/route/**` | WP1 |
| `«src»/weather/**`, `«test»/weather/**`, `app/src/test/resources/fixtures/*` | WP2 |
| `«src»/karoo/**`, `«src»/data/**`, `«test»/data/**` | WP3 |
| `«src»/datatypes/**`, `«test»/datatypes/**` | WP4 |
| `«src»/ui/**` except `theme/Tokens.kt` + `theme/NightMode.kt` | WP5 |
| `«src»/extension/**`, `«test»/extension/**` | WP6 |
| `«src»/WeatherExtension.kt`, `«src»/MainActivity.kt`, `app/src/main/AndroidManifest.xml`, `app/proguard-rules.pro` | WP8 |
| `«src»/util/**` | WP5 (only consumer; WP4 must not create files here) |

---

# WP0 — Shared contracts, resources, build (SINGLE AGENT, FIRST, BLOCKING)

Everything every other package compiles against. Nothing here has behaviour beyond trivial helpers, so it can
be written and reviewed quickly, and once it exists nobody blocks on anybody.

### Files created

| File | Content |
|---|---|
| `«src»/domain/GeoPoint.kt` | `GeoPoint` — verbatim from ARCHITECTURE §3 |
| `«src»/domain/WeatherSample.kt` | `WeatherSample`, `PrecipBucket`, `WmoCategory` — verbatim |
| `«src»/domain/LocationForecast.kt` | `LocationForecast`, `ForecastBundle` — verbatim |
| `«src»/domain/RouteForecast.kt` | `RoutePointForecast` (incl. `beyondHorizon`), `RouteForecast` — verbatim |
| `«src»/domain/Units.kt` | `TempUnit`, `WindUnit` (**no `labelRes`**), `DistanceUnit`, `WindClass`, `Units` — verbatim |
| `«src»/domain/WeatherState.kt` | `WeatherSnapshot` — verbatim |
| `«src»/domain/WeatherProvider.kt` | `WeatherRequest`, `WeatherError` (**with `Oversize`, `EmptyBody`, `reducePoints`**), `WeatherProvider` — verbatim |
| `«src»/domain/Http.kt` | `HttpGateway`, `HttpResult` (below) |
| `«src»/domain/Settings.kt` | `WeatherSettings` (below) |
| `«src»/domain/DataTypeIds.kt` | verbatim — **5 ids** |
| `«src»/ui/theme/Tokens.kt` | `ColorPair`, `object Wx`, ramp helpers (below) |
| `«src»/ui/theme/NightMode.kt` | `fun isNightMode(context: Context): Boolean` (below) |
| `app/src/main/res/drawable/*.xml` (24 files, list in WP7) | **Placeholder** vectors (a filled circle is fine). WP7 replaces the path data in place. |
| `app/src/main/res/values/colors.xml`, `values-night/colors.xml` | `alert_bg`, `alert_fg`, `field_fg`, `field_bg` — values in DESIGN §1.1 |
| `app/src/main/res/values/strings.xml` | **every** string in the complete list below |
| `app/src/main/res/xml/extension_info.xml` | 5 `<DataType>` entries + `mapLayer="true"` (shape in WP8) |
| `settings.gradle.kts` | **delete** the `maven.pkg.github.com/jonasfranz/ktor-client-karoo` block entirely (verified safe: `karoo-ext` resolves from `jitpack.io`, ARCHITECTURE ADR-0 #9). This also removes the `System.getenv("USERNAME")` credential lookup, which is null on macOS/CI. |
| `gradle/libs.versions.toml` | remove `ktor`, `ktor-client-karoo` versions and the four ktor library entries; add `kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }` with `coroutines = "1.10.2"` |
| `app/build.gradle.kts` | remove the four ktor `implementation`s; add `testImplementation(libs.kotlinx.serialization.json)` and `testImplementation(libs.kotlinx.coroutines.test)`; add `testOptions { unitTests.isReturnDefaultValues = true }` |

### Exact contracts other packages depend on

```kotlin
// «root».domain.Http
sealed class HttpResult {
    data class Ok(val status: Int, val body: String) : HttpResult()
    data class Fail(val error: WeatherError) : HttpResult()
}

/** Abstraction over Karoo's HTTP so providers stay unit-testable. */
interface HttpGateway {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult
}
```

```kotlin
// «root».domain.Settings
@Serializable
data class WeatherSettings(
    val consentAccepted: Boolean = false,
    val tempUnit: TempUnit? = null,          // null = follow UserProfile
    val windUnit: WindUnit? = null,          // null = follow UserProfile
    val assumedSpeedKmh: Int = 22,
    val useMeasuredSpeed: Boolean = true,
    val refreshMinutes: Int = 30,
    val roundLocationKm: Double = 3.0,
    val mapLayerEnabled: Boolean = true,
    val rainAlertEnabled: Boolean = false,
    /** User preference only. The EFFECTIVE interval is KarooSystemService.viewRefreshMs(settings). */
    val viewRefreshMs: Long = 2_000L,
    val lastRefreshRequestedAt: Long? = null,
) {
    fun assumedSpeedMs(): Double = assumedSpeedKmh.coerceIn(5, 60) / 3.6

    companion object {
        val DEFAULT_JSON: String = Json.encodeToString(WeatherSettings())
    }
}
```

```kotlin
// «root».ui.theme.Tokens — plain Long ARGB. MUST NOT import androidx.glance or androidx.compose.
data class ColorPair(val day: Long, val night: Long) {
    fun pick(night: Boolean): Int = (if (night) this.night else day).toInt()
}

object Wx {
    val bg: ColorPair; val fg: ColorPair; val fgMuted: ColorPair; val divider: ColorPair
    val tempFreezing: ColorPair; val tempCold: ColorPair; val tempMild: ColorPair
    val tempWarm: ColorPair; val tempHot: ColorPair
    val windTail: ColorPair; val windCalm: ColorPair; val windCross: ColorPair; val windHead: ColorPair
    val rainLight: ColorPair; val rainMed: ColorPair; val rainHeavy: ColorPair

    fun forTemp(celsius: Double): ColorPair
    fun forHeadwind(headwindMs: Double): ColorPair
    fun forRain(mmPerQuarterHour: Double): ColorPair
    fun forWindClass(cls: WindClass): ColorPair
}
```
Hex values and thresholds are given verbatim in DESIGN §1.1. **There is no `Wx.alertBg` / `Wx.alertFg`** —
`InRideAlert` takes `@ColorRes`, so the alert palette lives only in `colors.xml` / `values-night/colors.xml`.
`tempMild` equals `fg` on purpose (DESIGN §1.2: green is reserved for tailwind).

```kotlin
// «root».ui.theme.NightMode
import android.content.Context
import android.content.res.Configuration

/**
 * True when the OS is in night mode. Canvas-drawn bitmaps must call this to pick a ColorPair side;
 * Glance elements use ColorProvider(day, night) and resolve automatically.
 */
fun isNightMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
```

### Complete `strings.xml` list (WP0 writes ALL of these; no other package may add one)

**Extension / fields**
`extension_name`, `field_weather_now`, `field_weather_now_desc`, `field_temperature`,
`field_temperature_desc`, `field_wind`, `field_wind_desc`, `field_rain`, `field_rain_desc`,
`field_route_forecast`, `field_route_forecast_desc`

**Field states**
`state_no_data`, `state_no_gps`, `state_loading`, `state_setup`, `state_stale`, `state_no_route`,
`state_dry`, `horizon_beyond`

**Field labels / units**
`label_gust_short`, `label_feels_short`, `label_from`, `unit_kmh`, `unit_mph`, `unit_ms`, `unit_kn`,
`unit_bft`, `unit_mm`, `unit_percent`, `unit_celsius`, `unit_fahrenheit`, `rain_starts_at`, `rain_total_2h`,
`dist_ahead_km`, `dist_ahead_mi`, `compass_n`, `compass_nne`, `compass_ne`, `compass_ene`, `compass_e`,
`compass_ese`, `compass_se`, `compass_sse`, `compass_s`, `compass_ssw`, `compass_sw`, `compass_wsw`,
`compass_w`, `compass_wnw`, `compass_nw`, `compass_nnw`

**Alerts**
`alert_rain_title`, `alert_rain_detail`

**Companion app**
`tab_now`, `tab_route`, `tab_settings`, `app_refresh`, `app_updated_ago`, `app_no_route_title`,
`app_no_route_body`, `app_offline_banner`, `app_retry`, `app_back`, `settings_units`, `settings_temp_unit`,
`settings_wind_unit`, `settings_follow_karoo`, `settings_route`, `settings_use_measured_speed`,
`settings_assumed_speed`, `settings_updates`, `settings_refresh_every`, `settings_location_privacy`,
`settings_view_refresh`, `settings_on_bike`, `settings_map_layer`, `settings_rain_alert`, `settings_about`,
`attribution_open_meteo`, `icon_credits`, `consent_title`, `consent_body`, `consent_accept`,
`consent_decline`, `version_label`

`attribution_open_meteo` must be exactly `Weather data by Open-Meteo.com (CC BY 4.0)`.

### Also verify (WP0 does not declare done until these pass)

- `./gradlew assembleDebug` still green with the ktor dependencies and the GH-Packages block removed.
- `app/src/test/kotlin/...` is a recognised test source set under AGP 9.3.1's built-in Kotlin. Write a
  throw-away `SanityTest` asserting `2 + 2 == 4` and confirm `./gradlew testDebugUnitTest` runs it. If the
  directory is not picked up, add an explicit `sourceSets { getByName("test").kotlin.srcDir("src/test/kotlin") }`
  block. Delete the throw-away test afterwards.
- `runTest { }` from `kotlinx-coroutines-test` compiles in a test.

### Unit tests (WP0)

`«test»/domain/UnitsTest.kt` — °C→°F; m/s→km/h/mph/kn; Beaufort boundaries at
0.3/1.6/3.4/5.5/8.0/10.8/13.9/17.2/20.8/24.5/28.5/32.7.
`«test»/ui/theme/TokensTest.kt` — `forTemp`/`forHeadwind`/`forRain`/`forWindClass` are total and monotonic at
every threshold boundary from DESIGN §1.1; `ColorPair.pick` returns the right side.

---

# WP1 — Route geometry, sampling, ETA, relative wind

Pure JVM. **No Android imports at all.** This package holds the most bug-prone maths in the project and
therefore the densest tests.

### Files created
`«src»/route/Polyline.kt`, `Geo.kt`, `RoutePath.kt`, `RouteSampler.kt`, `EtaModel.kt`, `RelativeWind.kt`

### Public API it must expose (exact)

```kotlin
package io.github.glandais.karoo.weather.route

import io.github.glandais.karoo.weather.domain.GeoPoint
import io.github.glandais.karoo.weather.domain.WindClass

object Polyline {
    /** Google encoded polyline. `precision` 5 for coordinates, 1 for the elevation polyline. */
    fun decode(encoded: String, precision: Int = 5): List<GeoPoint>

    fun encode(points: List<GeoPoint>, precision: Int = 5): String

    /** NavigatingRoute.routeElevationPolyline: pairs of (distanceMetres, elevationMetres). */
    fun decodeElevation(encoded: String): List<Pair<Double, Double>>
}

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Haversine, metres. */
    fun distance(a: GeoPoint, b: GeoPoint): Double

    /** Initial great-circle bearing, degrees true in [0, 360). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double

    fun destination(from: GeoPoint, metres: Double, bearingDeg: Double): GeoPoint

    /** Signed shortest arc from `a` to `b`, degrees in (-180, 180]. */
    fun signedAngleDifference(a: Double, b: Double): Double

    /** Privacy grid. Latitude uses 111.32 km/deg; longitude uses 111.32 * cos(lat) km/deg. */
    fun roundToGrid(p: GeoPoint, km: Double): GeoPoint
}

class RoutePath(val points: List<GeoPoint>) {
    /** Cumulative length, metres. */
    val length: Double

    /** Clamped to [0, length]. */
    fun pointAt(distance: Double): GeoPoint

    /** Travel-direction tangent, degrees true. Clamps the lookahead at the route end. */
    fun bearingAt(distance: Double, lookaheadMetres: Double = 25.0): Double

    /**
     * Distance-along of the point on the path nearest to [p], metres in [0, length].
     * Used as the progress fallback on breadcrumb routes where DISTANCE_TO_DESTINATION never streams
     * (ARCHITECTURE §6.3). O(n) over segments; perpendicular projection within each segment.
     */
    fun nearestDistanceTo(p: GeoPoint): Double

    companion object {
        /** Returns null for an empty/undecodable polyline or fewer than 2 points. */
        fun fromPolyline(encoded: String, reversed: Boolean = false): RoutePath?
    }
}

data class RouteSample(val point: GeoPoint, val distanceAlong: Double, val routeBearing: Double)

object RouteSampler {
    /** Route samples only. The rider's own point is prepended by WeatherRepository, never here. */
    const val MAX_ROUTE_POINTS = 24
    val SPACINGS_M = listOf(1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0)

    fun spacingFor(remainingMetres: Double, maxPoints: Int = MAX_ROUTE_POINTS): Double

    /**
     * Samples strictly AHEAD of `progress` at the adaptive spacing, always including the route end.
     * Ascending distanceAlong, size <= maxPoints.
     * NEVER emits a point at distanceAlong == progress. Empty when progress >= path.length.
     */
    fun sample(path: RoutePath, progress: Double, maxPoints: Int = MAX_ROUTE_POINTS): List<RouteSample>

    /**
     * Drops every sample whose ETA exceeds `nowSec + horizonSec` and replaces the whole dropped tail
     * with exactly ONE marker sample: the last sample still inside the horizon (or, if none is,
     * the first sample). Pure and testable at this signature (ARCHITECTURE §6.6).
     * Returns Pair(kept samples, index of the marker in the result or null when nothing was dropped).
     */
    fun truncateToHorizon(
        samples: List<RouteSample>,
        eta: (Double) -> Long,
        nowSec: Long,
        horizonSec: Long = 11 * 3600L,
    ): Pair<List<RouteSample>, Int?>
}

class EtaModel(private val assumedSpeedMs: Double, private val tauSeconds: Double = 300.0) {
    /** Feed the SPEED stream. Samples below 2.0 m/s are ignored. */
    fun onSpeedSample(speedMs: Double, atEpochSec: Long)

    fun reset()

    /** EMA when [useMeasured] and at least 3 samples have been fed, else assumedSpeedMs. Never below 1.0. */
    fun effectiveSpeedMs(useMeasured: Boolean): Double

    fun eta(nowSec: Long, progress: Double, distanceAlong: Double, useMeasured: Boolean): Long
}

object RelativeWind {
    /**
     * Signed angle between travel bearing and the direction the wind blows TOWARDS, (-180, 180].
     * 0 = pure tailwind, +/-180 = pure headwind.
     */
    fun relativeAngle(travelBearing: Double, windDirFrom: Double): Double

    /** m/s. Positive = headwind, negative = tailwind. `-cos(toRadians(relativeAngle)) * windSpeedMs`. */
    fun headwindComponent(relativeAngle: Double, windSpeedMs: Double): Double

    /** |rel| < 45 -> TAIL, |rel| < 135 -> CROSS, else HEAD. */
    fun classify(relativeAngle: Double): WindClass

    /** Compass label index 0..15 for N, NNE, NE, ... from a bearing in degrees true. */
    fun compassIndex(bearing: Double): Int
}
```

### Consumes
`«root».domain.GeoPoint`, `«root».domain.WindClass`. Nothing else.

### Unit tests
`«test»/route/PolylineTest.kt`, `GeoTest.kt`, `RoutePathTest.kt`, `RouteSamplerTest.kt`, `EtaModelTest.kt`,
`RelativeWindTest.kt` — cases enumerated in ARCHITECTURE §12. The Google reference polyline is
``_p~iF~ps|U_ulLnnqC_mqNvxq`@`` — the character before `@` is a **backtick**, not an apostrophe; assert the
three points `(38.5, -120.2) (40.7, -120.95) (43.252, -126.453)` to 1e-5.

### Resources requested
None.

---

# WP2 — Weather provider (Open-Meteo), WMO mapping + icons, interpolation

Pure JVM except for `@Serializable` DTOs and `@DrawableRes`. Talks to the network only through
`HttpGateway`, so all of it is testable with a fake gateway.

`WmoIcons` lives here, not in `datatypes/views`: it is a pure `(WmoCategory, isDay) -> @DrawableRes Int` map
with no Glance dependency, and putting it in WP4 would make WP5 depend on WP4 and break the parallel
scheduling of WP4/WP5/WP6.

### Files created
`«src»/weather/WmoCodes.kt`, `«src»/weather/WmoIcons.kt`, `«src»/weather/Interpolation.kt`,
`«src»/weather/openmeteo/OpenMeteoDto.kt`, `OpenMeteoUrl.kt`, `OpenMeteoParser.kt`, `OpenMeteoProvider.kt`
Fixtures: `app/src/test/resources/fixtures/single_point.json`, `multi_point_25.json`, `minutely15.json`,
`error_400.json` — captured live with `curl` using the **exact final URLs** built by `OpenMeteoUrl`, and
committed.

### Public API it must expose (exact)

```kotlin
package io.github.glandais.karoo.weather.weather

import androidx.annotation.DrawableRes
import io.github.glandais.karoo.weather.domain.*

object WmoCodes {
    /** WMO 4677 -> category. Fog 45/48 -> FOG (NOT RAIN). Unknown -> UNKNOWN. */
    fun category(code: Int): WmoCategory

    /** True when the category implies liquid or frozen precipitation reaching the rider. */
    fun isWet(code: Int): Boolean
}

object WmoIcons {
    /** In-field / in-app drawable. Day/night variants only for CLEAR and MOSTLY_CLEAR/PARTLY_CLOUDY. */
    @DrawableRes fun field(category: WmoCategory, isDay: Boolean): Int

    /** Map-symbol drawable (heavier, haloed). Falls back to `field` for categories with no map variant. */
    @DrawableRes fun map(category: WmoCategory, isDay: Boolean): Int
}

object Interpolation {
    fun lerp(a: Double, b: Double, f: Double): Double

    /** Shortest-arc interpolation of two bearings, result in [0, 360). */
    fun lerpAngle(a: Double, b: Double, f: Double): Double

    /**
     * Continuous fields lerped, windDir via lerpAngle, precip taken from the CONTAINING hour
     * (it is an accumulation, not a level), wmoCode/isDay from the NEAREST hour.
     */
    fun lerpSample(a: WeatherSample, b: WeatherSample, f: Double, atTime: Long): WeatherSample

    /** Null when the series is empty. Clamps to the first/last entry outside the range. */
    fun sampleAt(series: List<WeatherSample>, epochSec: Long): WeatherSample?

    /** First `count` buckets at or after `fromSec`. Empty when the series has none. */
    fun bucketsFrom(series: List<PrecipBucket>, fromSec: Long, count: Int): List<PrecipBucket>

    /** Fallback when minutely15 is unavailable: 3600 s buckets from the hourly series. */
    fun hourlyToBuckets(series: List<WeatherSample>, fromSec: Long, count: Int): List<PrecipBucket>
}
```

```kotlin
package io.github.glandais.karoo.weather.weather.openmeteo

import io.github.glandais.karoo.weather.domain.*

object OpenMeteoUrl {
    const val BASE = "https://api.open-meteo.com/v1/forecast"
    const val SIZE_BUDGET_BYTES = 80_000

    /** Appended to BOTH requests so their field semantics can never diverge (ARCHITECTURE §5.4). */
    const val UNIT_PARAMS =
        "&timeformat=unixtime&wind_speed_unit=ms&temperature_unit=celsius&precipitation_unit=mm"

    val HOURLY_VARS: List<String>     // 8 vars, ARCHITECTURE §5.4 request A
    val CURRENT_VARS: List<String>    // 9 vars, request B

    /** Request A. Coordinates formatted "%.4f" with Locale.US. No forecast_days parameter. */
    fun routeBatch(points: List<GeoPoint>, forecastHours: Int = 12): String

    /** Request B. */
    fun hereDetail(point: GeoPoint, forecastHours: Int = 12, nowcastSteps: Int = 8): String

    /**
     * Calibrated against multi_point_25.json with a 1.5x safety factor:
     * ceil((300 + points * (140 + hourlyVars * hours * 12)) * 1.5)
     */
    fun estimateResponseBytes(points: Int, hourlyVars: Int, hours: Int): Int

    /** Largest point count whose estimate fits `budgetBytes`, at least 1. */
    fun maxPointsWithin(budgetBytes: Int, hourlyVars: Int, hours: Int): Int
}

object OpenMeteoParser {
    /** Branches object-vs-array on `expectedPoints == 1`. Zips positionally, never by lat/lon. */
    fun parseBatch(body: String, expectedPoints: Int): List<LocationForecast>

    fun parseDetail(body: String): LocationForecast

    /**
     * Merges request B into request A's index 0 BY TIME (epoch seconds), never by index:
     * the two responses are anchored to "now" independently and can be one hour apart.
     */
    fun mergeDetailInto(routePoint0: LocationForecast, detail: LocationForecast): LocationForecast
}

/** Thrown inside Result.failure. */
class WeatherErrorException(val error: WeatherError) : Exception(error.message)

class OpenMeteoProvider(
    private val http: HttpGateway,
    private val userAgent: String,
) : WeatherProvider {
    override val id: String = "open-meteo"

    /**
     * Issues request A always and request B whenever `request.includeNowcast` (which the repository
     * always sets true - the visibility gate was removed, ARCHITECTURE §5.4).
     * A failure of request B is NON-FATAL: request A's result is returned with no `current`/`minutely15`.
     * A failure of request A is returned as Result.failure(WeatherErrorException(error)).
     */
    override suspend fun fetch(request: WeatherRequest): Result<List<LocationForecast>>
}
```

### Consumes
`«root».domain.*` only.

### Unit tests
`«test»/weather/WmoCodesTest.kt` (includes `WmoIcons.field`/`map` totality),
`InterpolationTest.kt`,
`«test»/weather/openmeteo/OpenMeteoUrlTest.kt` — exact URL for 1 and 25 points, **one assertion under
`Locale.GERMANY`**, and **`assertTrue(estimateResponseBytes(25, 8, 12) >= fixture("multi_point_25.json").length)`**,
`OpenMeteoParseTest.kt`, `OpenMeteoProviderTest.kt` (fake `HttpGateway`: success, 429→`RateLimited`,
500→`Server`, 400→`Client`, malformed→`Parse`, oversize→`Oversize`, empty→`EmptyBody`, request-B failure is
non-fatal, **and a merge case where A and B are one hour apart**).

### Resources requested
Drawables only, all created by WP0: every `ic_wmo_*`.

---

# WP3 — Karoo bridge + data layer

### Files created
`«src»/karoo/KarooFlows.kt`, `KarooHttp.kt`, `KarooUnits.kt`
`«src»/data/SettingsStore.kt`, `ForecastCache.kt`, `RefreshPolicy.kt`, `WeatherRepository.kt`, `WeatherGraph.kt`

### Public API it must expose (exact)

```kotlin
package io.github.glandais.karoo.weather.karoo

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.*

/**
 * Consumer flow for events that have default KarooEventParams.
 *
 * ONLY these 11 types are legal - KarooSystemService's no-params addConsumer resolves defaults from a
 * hard-coded `when (T::class)` and throws IllegalArgumentException for anything else
 * (verified, KarooSystemService.kt:228-250):
 *   RideState, Lap, UserProfile, OnLocationChanged, OnGlobalPOIs, OnNavigationState,
 *   OnMapZoomLevel, SavedDevices, Bikes, ActiveRideProfile, ActiveRidePage
 *
 * OnStreamState and OnHttpResponse are NOT in that list and must use `consumerFlowWithParams`.
 */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T>

/** For events that require explicit params, e.g. OnStreamState.StartStreaming / OnHttpResponse.MakeHttpRequest. */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlowWithParams(params: KarooEventParams): Flow<T>

/** Uses consumerFlowWithParams(OnStreamState.StartStreaming(dataTypeId)). */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState>

fun KarooSystemService.streamNavigation(): Flow<OnNavigationState.NavigationState>

/** LOCATION stream via streamDataFlow; drops fixes with FIELD_LOC_ACCURACY_ID >= 500. */
fun KarooSystemService.streamLocation(): Flow<GeoPoint>

fun KarooSystemService.streamBearing(): Flow<Double?>

/** m/s. */
fun KarooSystemService.streamSpeedMs(): Flow<Double>

fun KarooSystemService.streamDistanceToDestination(): Flow<Double?>

fun KarooSystemService.streamRideState(): Flow<RideState>

fun KarooSystemService.streamUserProfile(): Flow<UserProfile>

/** consumerFlow<ActiveRideProfile>().map { it.profile } - used to suspend fetching on indoor profiles. */
fun KarooSystemService.streamActiveRideProfile(): Flow<RideProfile>

/** Derived from consumerFlow<ActiveRidePage>(). */
fun KarooSystemService.streamDataTypeVisible(dataTypeId: String): Flow<Boolean>

fun <T> Flow<T>.throttle(periodMs: Long): Flow<T>

/**
 * Effective repaint interval. hardwareType is only valid AFTER connect{} fires, so null means
 * "not connected yet" and takes the slow side.
 *   K2 -> max(settings.viewRefreshMs, 3000); null -> 3000; else -> max(settings.viewRefreshMs, 900)
 */
fun KarooSystemService.viewRefreshMs(settings: WeatherSettings): Long

class KarooHttpGateway(
    private val karoo: KarooSystemService,
    private val userAgent: String,
    private val timeoutMs: Long = 20_000L,
) : HttpGateway {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResult
}

fun UserProfile.toUnits(settings: WeatherSettings): Units
```
`KarooHttpGateway.get` calls `karoo.addConsumer(OnHttpResponse.MakeHttpRequest(method = "GET", url = url,
headers = headers + ("User-Agent" to userAgent), waitForConnection = false))` inside a `callbackFlow`,
terminates on `HttpResponseState.Complete`, `awaitClose { karoo.removeConsumer(listenerId) }`, wraps in
`.timeout(timeoutMs.milliseconds)`, and maps:
not connected → `NoConnection` · timeout → `Timeout` · 429 → `RateLimited(retryAfter)` · 5xx → `Server` ·
4xx → `Client` · **null body → `EmptyBody`** · **body length > `OnHttpResponse.MAX_REQUEST_SIZE` (100 000) →
`Oversize(len)`**. It never returns `Parse` — only the parser does.

```kotlin
package io.github.glandais.karoo.weather.data

class SettingsStore(context: Context) {
    val settings: Flow<WeatherSettings>
    suspend fun update(transform: (WeatherSettings) -> WeatherSettings)
    suspend fun pokeRefresh()
}

class ForecastCache(context: Context) {
    val bundle: Flow<ForecastBundle?>
    val lastPosition: Flow<GeoPoint?>
    suspend fun save(bundle: ForecastBundle)
    suspend fun savePosition(point: GeoPoint)
}

/** Pure, unit-tested. No Android, no coroutines. */
object RefreshPolicy {
    const val MIN_GAP_SEC = 60L

    /** 30, 60, 120, 300 then 300 while recording / 900 otherwise. */
    fun backoffSec(attempt: Int, recording: Boolean): Long

    fun shouldFetch(nowSec: Long, lastFetchSec: Long?): Boolean

    /** settings.refreshMinutes * 60, halved to a floor of 900 s while recording. */
    fun intervalSec(settings: WeatherSettings, recording: Boolean): Long

    /** Progress bucket used by the RefreshKey: (progress / spacing / 3).toInt(). */
    fun progressBucket(progress: Double, spacing: Double): Int

    /** Halves on Oversize/EmptyBody, floor 2; resets to 25 otherwise. */
    fun nextPointBudget(current: Int, error: WeatherError?): Int
}

/**
 * The request-identity tuple. Deliberately EXCLUDES viewRefreshMs, mapLayerEnabled, rainAlertEnabled,
 * tempUnit and windUnit - none of them change the request (karoo-headwind pitfall #17).
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

class WeatherRepository(
    private val appContext: Context,
    private val settingsStore: SettingsStore,
    private val cache: ForecastCache,
) {
    /** Lives on repoScope, which is NEVER cancelled. Safe across detach/attach cycles. */
    val state: StateFlow<WeatherSnapshot>
    val settings: Flow<WeatherSettings>

    /**
     * Idempotent, ref-counted, NO ARGUMENT. The repository constructs and owns the single
     * KarooSystemService(appContext) on the first call and connects it (ARCHITECTURE §4.2).
     * Starts the session scope: trigger producer, fetch worker, EtaModel feeder.
     */
    fun attach()

    /** On the LAST detach: cancels sessionScope, calls runCatching { karoo.disconnect() }, drops it. */
    fun detach()

    /** For WeatherMapLayer and RainAlerter, which need the raw service. Null before the first attach. */
    val karooOrNull: KarooSystemService?

    suspend fun requestRefresh(force: Boolean = false)
    suspend fun updateSettings(transform: (WeatherSettings) -> WeatherSettings)

    /** here.hourly interpolated to `nowSec`, falling back to here.current. */
    fun sampleNow(nowSec: Long = System.currentTimeMillis() / 1000): WeatherSample?

    /** Non-null only while a route is loaded and a forecast exists for it. */
    fun routeForecast(): RouteForecast?

    /** Buckets for the rain field: minutely15 when present, else derived hourly. */
    fun rainBuckets(count: Int = 8): List<PrecipBucket>

    internal companion object {
        /**
         * The pure assembly step, extracted so it is unit-testable without a Context.
         * `samples` are ROUTE samples only; this function PREPENDS the rider's own point as index 0
         * (distanceAlong == progress) and is the only place that does so.
         * `forecasts[0]` is the rider's point, `forecasts[i+1]` matches `samples[i]`.
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
        ): RouteForecast
    }
}

object WeatherGraph {
    fun repository(context: Context): WeatherRepository
}
```

### Implementation rules (WP3)

- **Two scopes** (ARCHITECTURE §4.2): `repoScope` created in the constructor and never cancelled hosts
  `state`; `sessionScope` created on first `attach()` and cancelled on last `detach()` hosts the loops.
- **Trigger producer and fetch worker are separate jobs** (ARCHITECTURE §5.1). The producer writes into a
  `MutableStateFlow<RefreshKey?>` and never suspends on I/O; the worker `collectLatest`s it and owns request,
  retry and backoff. A backoff must never block a new trigger.
- The trigger flow `.filter { !activeRideProfile.indoor }` using `streamActiveRideProfile()`.
- `WeatherGraph.repository` builds `OpenMeteoProvider(KarooHttpGateway(karoo, userAgent))` lazily on
  `attach`, with `userAgent = "karoo-weather/${BuildConfig.VERSION_NAME} (+https://github.com/glandais/karoo-weather)"`.
- Route assembly composes WP1 + WP2: `RoutePath.fromPolyline` → `RouteSampler.sample` →
  `RouteSampler.truncateToHorizon` → `OpenMeteoProvider.fetch` → `Interpolation.sampleAt` → `RelativeWind` →
  `buildRouteForecast`.
- Progress: `routeDistance - distanceToDestination` while that stream produces; after 30 s of stream silence
  (breadcrumb routes) `path.nearestDistanceTo(lastGpsPoint)`. Never decrease by more than 200 m per update.

### Consumes
WP0 domain, WP1 `route`, WP2 `weather`.

### Unit tests
`«test»/data/RefreshPolicyTest.kt` — backoff sequence, min gap, interval halving while recording, progress
bucketing, `nextPointBudget` halving/reset, and **`RefreshKey` equality: changing `mapLayerEnabled` or
`viewRefreshMs` must NOT produce a new key**.
`«test»/data/RouteForecastAssemblyTest.kt` — `buildRouteForecast` directly: index 0 is the rider at
`distanceAlong == progress`, ordering, wet-point detection, headwind signs, `beyondHorizon` marker.
`SettingsStore`, `ForecastCache` and `attach` are not unit tested (Android `Context`).

### Resources requested
None beyond WP0.

---

# WP4 — Data fields (5)

### Files created
`«src»/datatypes/NumericDataType.kt`, `TemperatureDataType.kt`, `WeatherNowDataType.kt`, `WindDataType.kt`,
`RainNextHourDataType.kt`, `RouteForecastDataType.kt`, `PreviewData.kt`
`«src»/datatypes/views/FieldChrome.kt`, `WeatherNowView.kt`, `WindView.kt`,
`BarChartBuilder.kt`, `StripBitmapBuilder.kt`, `ArrowBitmaps.kt`

### Public API it must expose (exact)

```kotlin
package io.github.glandais.karoo.weather.datatypes

abstract class NumericDataType(
    protected val context: Context,
    protected val repo: WeatherRepository,
    typeId: String,
) : DataTypeImpl(DataTypeIds.EXTENSION, typeId) {
    /** Canonical SI. Null -> StreamState.NotAvailable. */
    abstract fun value(snapshot: WeatherSnapshot, nowSec: Long): Double?

    /** A DataType.Type id so Karoo formats units/precision. Null -> emit NOTHING (Karoo uses integers). */
    open val formatDataTypeId: String? = null

    final override fun startStream(emitter: Emitter<StreamState>)

    /**
     * Emits UpdateNumericConfig(formatDataTypeId) once, ONLY when formatDataTypeId != null
     * (the SDK parameter is non-nullable). Launches no coroutine, so it needs no setCancellable.
     */
    final override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter)
}

class TemperatureDataType(context: Context, repo: WeatherRepository) :
    NumericDataType(context, repo, DataTypeIds.TEMPERATURE)

class WeatherNowDataType(context: Context, repo: WeatherRepository) :
    DataTypeImpl(DataTypeIds.EXTENSION, DataTypeIds.WEATHER_NOW)

class WindDataType(context: Context, repo: WeatherRepository) :
    DataTypeImpl(DataTypeIds.EXTENSION, DataTypeIds.WIND)

class RainNextHourDataType(context: Context, repo: WeatherRepository) :
    DataTypeImpl(DataTypeIds.EXTENSION, DataTypeIds.RAIN_NEXT_HOUR)

class RouteForecastDataType(context: Context, repo: WeatherRepository) :
    DataTypeImpl(DataTypeIds.EXTENSION, DataTypeIds.ROUTE_FORECAST)

object PreviewData {
    val sample: WeatherSample
    val route: RouteForecast
    val snapshot: WeatherSnapshot
    val buckets: List<PrecipBucket>
}
```
Every constructor is exactly `(context: Context, repo: WeatherRepository)` — WP8 wires them with that shape.

```kotlin
package io.github.glandais.karoo.weather.datatypes.views

object FieldChrome {
    const val MIN_CELL_PX = 88

    /** gridSize decides which rows exist; viewSize decides how many columns fit (DESIGN §3.0). */
    fun columnsFor(viewSize: Pair<Int, Int>, maxColumns: Int): Int

    /** Re-export of «root».ui.theme.isNightMode so view code has one import. */
    fun night(context: Context): Boolean

    /** THE ONLY sanctioned way to build a ShowCustomStreamState. */
    fun customState(
        context: Context,
        @StringRes message: Int?,
        pair: ColorPair,
        night: Boolean,
    ): ShowCustomStreamState

    @StringRes fun windUnitLabel(unit: WindUnit): Int
    @StringRes fun tempUnitLabel(unit: TempUnit): Int
    @StringRes fun compassLabel(index: Int): Int

    fun paddingDp(config: ViewConfig): Int          // 4 when boundariesEnabled else 2
    fun arrowSizePx(config: ViewConfig): Int        // 48, or 56 when viewSize.second > 300
}

object BarChartBuilder {
    /**
     * ONE bitmap at the field's viewSize. Glance cannot draw; `night` picks the ColorPair side because a
     * Canvas cannot resolve a ColorProvider. The probability polyline is fgMuted at 2 px and is drawn
     * only when showProbability (caller passes gridSize.second >= 30).
     */
    fun render(
        widthPx: Int,
        heightPx: Int,
        buckets: List<PrecipBucket>,
        night: Boolean,
        showLabels: Boolean,
        showProbability: Boolean,
    ): Bitmap
}

object StripBitmapBuilder {
    /**
     * ONE bitmap at the field's viewSize for the whole route strip - icons, temperatures, arrows,
     * labels, wet-cell washes and dividers all drawn into a single Canvas.
     * NEVER assemble the strip from per-cell bitmaps: a RemoteViews is Parcelled across a Binder on
     * every updateView and per-cell bitmaps blow the ~1 MB transaction budget (DESIGN §3.0).
     */
    fun render(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        columns: List<Column>,
        rows: Rows,
        night: Boolean,
        textSizeSp: Int,
        units: Units,
    ): Bitmap

    data class Column(
        @DrawableRes val icon: Int,
        val tempC: Double,
        val relativeWindAngle: Double,
        val headwindMs: Double,
        val label: String,
        val etaLabel: String?,
        val wet: Boolean,
        val beyondHorizon: Boolean,
    )

    data class Rows(val icon: Boolean, val temp: Boolean, val arrow: Boolean, val label: Boolean, val eta: Boolean)
}

/**
 * Rotated, pre-tinted arrows. The tint is BAKED IN (a Canvas bitmap cannot be re-tinted by a
 * ColorProvider), so it is part of the cache key.
 *
 * Bounded LruCache: ~2 MB, keyed on "res:bucket10:sizePx:tint". A process-lifetime unbounded object
 * would retain 36 bearings x 4 tints x 2 sizes of bitmap inside the extension service.
 * Callers MUST call clear() from their setCancellable.
 */
class ArrowBitmaps(maxBytes: Int = 2 * 1024 * 1024) {
    fun rotated(context: Context, @DrawableRes res: Int, bearingDeg: Double, sizePx: Int, tint: Int): Bitmap
    fun clear()
}
```

### Implementation rules (all four graphical types)

- **Per-view state, always.** Construct `GlanceRemoteViews()` and `ArrowBitmaps()` *inside* `startView`, never
  as a property of the `DataTypeImpl`: `KarooExtension` calls `startView` on one shared instance and the page
  editor opens several previews at once (ARCHITECTURE §4.3).
- `configJob`: `emitter.onNext(UpdateGraphicConfig(showHeader = …))`,
  `emitter.onNext(ShowCustomStreamState(null, null))`, then `awaitCancellation()`.
- `viewJob`: `combine(repo.state, karoo.streamDataTypeVisible(dataTypeId), repo.settings)`
  `.throttle(karoo.viewRefreshMs(settings))` `.collect { … emitter.updateView(result.remoteViews) }`.
  Skip all compose/bitmap work when not visible.
- `emitter.setCancellable { configJob.cancel(); viewJob.cancel(); arrows.clear() }`.
- `config.preview` ⇒ render `PreviewData.snapshot` once; no repository, no visibility flow, no network.
- Layout: rows from `config.gridSize`, columns from `FieldChrome.columnsFor(config.viewSize, maxColumns)`
  per DESIGN §3.0; sizes derive from `config.textSize`; alignment from `config.alignment`; padding from
  `config.boundariesEnabled`.
- Glance colours via `ColorProvider(Color(pair.day), Color(pair.night))`; Canvas colours via
  `pair.pick(night)`.
- `route-forecast` and `rain-next-hour` produce exactly **one** `Image(ImageProvider(bitmap))`.
- **Before declaring done**, log `gridSize`/`viewSize`/`textSize` at `startView` for `(30,30)`, `(60,15)`,
  `(60,30)`, `(60,60)` on hardware and record the observed values in a comment at the top of `FieldChrome.kt`
  (ARCHITECTURE spike S3).

### Consumes
WP0 (`domain`, `Tokens`, `NightMode`), WP1 (`RelativeWind`, `Geo`), WP2 (`WmoCodes`, `WmoIcons`,
`Interpolation`), WP3 (`WeatherRepository`, `streamDataTypeVisible`, `throttle`, `viewRefreshMs`).
**WP4 has no dependency on WP5 or WP6, and nothing depends on WP4.**

### Unit tests
`«test»/datatypes/PreviewDataTest.kt` — the preview snapshot exercises every temperature ramp bucket and both
wind classes (guards the page-editor advertisement from silently degrading).
`«test»/datatypes/FieldChromeTest.kt` — `columnsFor` clamps at 1 and at `maxColumns`, and yields 5 for
`(480, 400)` with `maxColumns = 5`. Rendering itself is not unit tested.

### Resources used
All strings and drawables listed in WP0. **WP4 does not add any.**

---

# WP5 — Companion app UI

### Files created
`«src»/ui/theme/Theme.kt`
`«src»/ui/WeatherViewModel.kt`, `WeatherApp.kt`, `NowScreen.kt`, `RouteScreen.kt`, `SettingsScreen.kt`,
`AboutSection.kt`, `ConsentDialog.kt`
`«src»/ui/components/CurrentCard.kt`, `HourlyStrip.kt`, `RouteRow.kt`, `StateBanner.kt`, `SettingRow.kt`,
`Dropdown.kt`, `RefreshButton.kt`
`«src»/util/TimeFormat.kt`, `«src»/util/Distance.kt`, `«src»/util/UnitLabels.kt`

(`«src»/ui/theme/Tokens.kt` and `NightMode.kt` belong to WP0 and must not be edited.)

### Public API it must expose (exact)

```kotlin
package io.github.glandais.karoo.weather.ui

/** Material3 scheme derived from isSystemInDarkTheme(), built from the same Wx pairs the fields use. */
@Composable fun AppTheme(content: @Composable () -> Unit)

class WeatherViewModel(private val repo: WeatherRepository) : ViewModel() {
    val state: StateFlow<WeatherSnapshot>
    val settings: StateFlow<WeatherSettings>
    fun refresh()
    fun update(transform: (WeatherSettings) -> WeatherSettings)

    companion object { fun factory(context: Context): ViewModelProvider.Factory }
}

/** The whole app; MainActivity calls exactly this and nothing else. */
@Composable fun WeatherApp(onClose: () -> Unit)
```

### Implementation rules (WP5)

- **`WeatherApp` never constructs a `KarooSystemService`.** The repository owns the single instance
  (ARCHITECTURE §4.2). The composable does:
  ```kotlin
  val repo = remember { WeatherGraph.repository(context) }
  DisposableEffect(Unit) { repo.attach(); onDispose { repo.detach() } }
  ```
- Tabs Now / Route / Settings in a `TabRow` (56 dp). **No `PullToRefreshBox`** — manual refresh is a 56 dp
  Refresh button in the Now tab header (DESIGN §5/§7; a drag gesture is not a reliable gloved input and must
  never be the only path to an action).
- A 48 dp back affordance bottom-left; the consent dialog blocks until accepted.
- Settings persist immediately for switches/dropdowns and on focus loss for text fields.
  **No `runBlocking` anywhere in `«src»/ui`.**
- Unit labels come from `«src»/util/UnitLabels.kt` (`@StringRes fun windUnitLabel(WindUnit): Int` etc.), the
  Compose twin of `FieldChrome`'s helpers. `WindUnit` carries no resource name — resolving one would need the
  deprecated `Resources.getIdentifier()`, which the release build's R8 resource shrinking breaks.

### Consumes
WP0 (`domain`, `Tokens`, `NightMode`), WP2 (`WmoCodes`, `WmoIcons`), WP3 (`WeatherGraph`,
`WeatherRepository`). **No dependency on WP4.**

### Unit tests
None (Compose UI). Correctness is covered below the UI.

### Resources used
All strings listed in WP0. **WP5 does not add any.**

---

# WP6 — Map layer and in-ride alerts

### Files created
`«src»/extension/WeatherMapLayer.kt`, `«src»/extension/RainAlerter.kt`

### Public API it must expose (exact)

```kotlin
package io.github.glandais.karoo.weather.extension

/**
 * ONE INSTANCE PER startMap CALL. `previousIds` is instance state, never in the companion object,
 * so a stopMap -> startMap cycle cannot leak symbol ids across instances.
 */
class WeatherMapLayer(
    private val context: Context,
    private val karoo: KarooSystemService,
    private val repo: WeatherRepository,
) {
    /**
     * Called from KarooExtension.startMap with WeatherExtension.extensionScope (the SDK passes no scope).
     * Returns the job; the caller must
     *   emitter.setCancellable { job.cancel(); emitter.onNext(HideSymbols(previousIds)) }
     */
    fun start(emitter: Emitter<MapEffect>, scope: CoroutineScope): Job

    /** Ids currently shown. Read by the caller's setCancellable. */
    val previousIds: List<String>

    companion object {
        const val SYMBOL_PREFIX = "wx-"
        const val DEFAULT_ZOOM = 15.0

        /** >=15 -> 2000, >=12 -> 5000, else 20000 (metres). */
        fun symbolSpacingFor(zoomLevel: Double): Double

        /** Greedy selection of points at least `spacing` apart, always keeping first and last. Pure. */
        fun selectPoints(points: List<RoutePointForecast>, spacing: Double): List<RoutePointForecast>
    }
}

class RainAlerter(
    private val context: Context,
    private val karoo: KarooSystemService,
    private val repo: WeatherRepository,
) {
    fun start(scope: CoroutineScope): Job

    companion object {
        const val COOLDOWN_SEC = 3600L
        const val LOOKAHEAD_SEC = 1800L
        const val WET_MM = 0.2

        /** Pure decision function. Minutes until the first wet bucket within LOOKAHEAD_SEC, or null. */
        fun rainStartingIn(buckets: List<PrecipBucket>, nowSec: Long): Int?

        fun shouldAlert(
            minutesUntil: Int?,
            lastAlertSec: Long?,
            nowSec: Long,
            enabled: Boolean,
            recording: Boolean,
        ): Boolean
    }
}
```

### Implementation rules (WP6)

- **Seed the zoom flow.** `OnMapZoomLevel`'s KDoc does not promise replay-on-subscribe (unlike `RideState`
  and `UserProfile`), and `combine` emits nothing until every source has emitted — without a seed no symbol
  would appear until the rider pinched the map:
  ```kotlin
  combine(
      repo.state,
      karoo.consumerFlow<OnMapZoomLevel>().onStart { emit(OnMapZoomLevel(DEFAULT_ZOOM)) },
      repo.settings,
  ) { … }.distinctUntilChangedBy { bundleFetchedAt to zoomBucket }
  ```
- `OnMapZoomLevel` IS in the 11-type whitelist, so plain `consumerFlow<OnMapZoomLevel>()` is correct here.
- When `settings.mapLayerEnabled` is false, emit `HideSymbols(previousIds)` once and idle.
- `RainAlerter` dispatches `InRideAlert(id = "karoo-weather-rain", icon = R.drawable.ic_wmo_rain,
  title = getString(R.string.alert_rain_title, minutes), detail = getString(R.string.alert_rain_detail, mm),
  autoDismissMs = 10_000L, backgroundColor = R.color.alert_bg, textColor = R.color.alert_fg)`.
  `backgroundColor`/`textColor` are `@ColorRes` — use the XML resources, never a `Wx` token.

### Consumes
WP0 domain, WP3 (`WeatherRepository`, `consumerFlow`, `streamRideState`).

### Unit tests
`«test»/extension/MapLayerSelectionTest.kt` — spacing buckets at 11.9/12/14.9/15, greedy selection keeps
first and last, empty input, single point.
`«test»/extension/RainAlerterTest.kt` — rain in 12 min detected, dry ignored, cooldown blocks, disabled
blocks, not-recording blocks, rain beyond LOOKAHEAD_SEC ignored.

### Resources used
`alert_rain_title`, `alert_rain_detail`, `ic_wmo_rain`, `ic_map_wind_arrow`, `alert_bg`, `alert_fg` — all
created by WP0.

---

# WP7 — Icon artwork

Replaces the WP0 placeholder path data **in place**. Touches only `app/src/main/res/drawable/*.xml` plus its
own `app/src/main/res/raw/icon_credits.txt`. No Kotlin, no `strings.xml`, no new drawable files (the file list
is fixed by WP0).

### Files edited (24 drawables)
`ic_wmo_clear_day`, `ic_wmo_clear_night`, `ic_wmo_partly_day`, `ic_wmo_partly_night`, `ic_wmo_cloudy`,
`ic_wmo_fog`, `ic_wmo_drizzle`, `ic_wmo_freezing`, `ic_wmo_rain`, `ic_wmo_rain_heavy`, `ic_wmo_showers`,
`ic_wmo_snow`, `ic_wmo_snow_heavy`, `ic_wmo_thunder`, `ic_wmo_thunder_hail`, `ic_wmo_unknown`,
`ic_wind_arrow`, `ic_wind_ring`, `ic_gust`, `ic_drop`, `ic_umbrella`, `ic_thermometer`, `ic_compass`,
`ic_route`, `ic_map_wind_arrow`, `ic_weather`

Specification (DESIGN §2): 24 × 24 dp viewport, single `<path>`, `android:fillColor="#FF000000"`, no
gradients, no stroke below 1.5 dp, silhouettes legible at 24 px.

**One exemption, and it is mandatory:** `ic_map_wind_arrow` is 32 × 32 dp and has **two paths** — a white
outline path drawn first, then the black arrow on top — with hard-coded colours and **no runtime tint**. It
is drawn by Karoo over map tiles, where our day/night `ColorProvider` never applies, so it must carry its own
halo. A single-path black arrow here is unusable on a dark basemap.

Sources must be MIT/OFL/CC0 (boxicons is the reference style); record every source in
`app/src/main/res/raw/icon_credits.txt` — **this file is WP7's, nobody else writes it**.

### Unit tests
None. Verification: `assembleDebug` plus a visual check of each drawable at 24 px.

---

# WP8 — Integration, wiring, verification (LAST, SINGLE AGENT)

### Files created / edited
Owned outright: `«src»/WeatherExtension.kt`, `«src»/MainActivity.kt`, `app/src/main/AndroidManifest.xml`,
`app/proguard-rules.pro`.
Amend-only (created by WP0, amended here if and only if verification demands it):
`res/xml/extension_info.xml`, `res/values/strings.xml`, `values/colors.xml`, `values-night/colors.xml`,
`app/build.gradle.kts`.

### `WeatherExtension.kt` shape

```kotlin
class WeatherExtension : KarooExtension(DataTypeIds.EXTENSION, BuildConfig.VERSION_NAME) {

    // Property initialisers, NOT lateinit assigned in onCreate: `types` is a `by lazy` dereferenced from a
    // Binder thread, and an UninitializedPropertyAccessException inside a Binder call is very hard to
    // diagnose. WeatherGraph.repository needs no connect{} and is safe to build here.
    private val repo: WeatherRepository by lazy { WeatherGraph.repository(this) }
    private val extensionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        repo.attach()                                    // repository owns the KarooSystemService
        repo.karooOrNull?.let { karoo ->
            extensionScope.launch { RainAlerter(this@WeatherExtension, karoo, repo).start(this) }
        }
    }

    override fun startMap(emitter: Emitter<MapEffect>) {
        val karoo = repo.karooOrNull ?: return
        val layer = WeatherMapLayer(this, karoo, repo)   // fresh instance per startMap
        val job = layer.start(emitter, extensionScope)
        emitter.setCancellable {
            job.cancel()
            emitter.onNext(HideSymbols(layer.previousIds))
        }
    }

    override fun onDestroy() {
        extensionScope.cancel()
        repo.detach()                                    // disconnects the service on the last detach
        super.onDestroy()
    }
}
```

`MainActivity` renders `AppTheme { WeatherApp(onClose = { finish() }) }` and nothing else.

### `extension_info.xml` (final, 5 data types)

```xml
<ExtensionInfo displayName="@string/extension_name" icon="@drawable/ic_weather"
               id="karoo-weather" scansDevices="false" mapLayer="true">
  <DataType typeId="weather-now"     graphical="true"  icon="@drawable/ic_weather"
            displayName="@string/field_weather_now"    description="@string/field_weather_now_desc"/>
  <DataType typeId="temperature"     graphical="false" icon="@drawable/ic_thermometer"
            displayName="@string/field_temperature"    description="@string/field_temperature_desc"/>
  <DataType typeId="wind"            graphical="true"  icon="@drawable/ic_wind_arrow"
            displayName="@string/field_wind"           description="@string/field_wind_desc"/>
  <DataType typeId="rain-next-hour"  graphical="true"  icon="@drawable/ic_drop"
            displayName="@string/field_rain"           description="@string/field_rain_desc"/>
  <DataType typeId="route-forecast"  graphical="true"  icon="@drawable/ic_route"
            displayName="@string/field_route_forecast" description="@string/field_route_forecast_desc"/>
</ExtensionInfo>
```
`typeId` values must equal `DataTypeIds` constants exactly; `mapLayer="true"` is required or `startMap` is
never called.

### Manifest additions
- `<meta-data android:name="io.hammerhead.karooext.MANIFEST_URL" android:value="…/manifest.json"/>` inside
  `<application>`, via `manifestPlaceholders` (**never** by rewriting the file at build time).
- `MainActivity` keeps `MAIN`/`LAUNCHER`; add `<action android:name="io.github.glandais.karoo.weather.MAIN"/>`
  so `SystemNotification(actionIntent = …)` can open it.
- No location permission — position comes from the Karoo `LOCATION` stream.
- **No `android:process` on the service**: `WeatherGraph`'s single-process assumption depends on it.

### `app/build.gradle.kts` (amend)
- Add a `generateManifest` task writing `app/manifest.json` with `tags = listOf("weather")`, `label`,
  `packageName`, `latestApkUrl`, `latestVersion`, `latestVersionCode`, `developer`, `description`,
  `releaseNotes`.
- (The ktor removal and test dependencies were already done by WP0.)

### `app/proguard-rules.pro`
Release builds have `isMinifyEnabled = true` with a currently empty rules file — a stripped serializer fails
only at runtime, only in release, and usually only on the user's device. Keep:
- `kotlinx.serialization` `$$serializer` classes for **our** `@Serializable` DTOs *and* for
  `io.hammerhead.karooext.models.**`, which the SDK deserialises reflectively across the Binder;
- `-keep class io.hammerhead.karooext.** { *; }` and `-keepclassmembers class ** { *** Companion; }`;
- Glance / `RemoteViews` classes.

### Verification checklist (run in this order, all must pass)

```bash
cd /Users/glandais/code/perso/karoo-weather
./gradlew spotlessApply        # ktfmt kotlinlang, must leave the tree clean
./gradlew testDebugUnitTest    # every WP0/WP1/WP2/WP3/WP4/WP6 suite green
./gradlew assembleDebug        # the existing green baseline must stay green
./gradlew lintDebug            # no new errors
./gradlew assembleRelease      # R8 + resource shrinking must succeed
```

Then, by inspection:
- [ ] `extension_info.xml` `typeId`s == `DataTypeIds.ALL` (5), and `WeatherExtension.types` has the same 5.
- [ ] `id="karoo-weather"` in three places: `extension_info.xml`, `KarooExtension(...)`, every `DataTypeImpl`.
- [ ] Every `startStream`/`startView`/`startMap` **that launches a coroutine** calls `emitter.setCancellable`.
      (`NumericDataType.startView` launches none and correctly has no cancellable.)
- [ ] Every graphical field emits `ShowCustomStreamState(null, null)` once at view start.
- [ ] `grep -rn "ShowCustomStreamState(" «src»` shows only `FieldChrome.customState` and the two `(null, null)`
      resets — no `R.string.` and no `ColorPair` is ever passed to it directly.
- [ ] `grep -rn "consumerFlow<" «src»` shows only the 11 whitelisted event types.
- [ ] No `updateView` call path can fire faster than 900 ms.
- [ ] `config.preview` never reaches the network (grep for `repo.` inside preview branches).
- [ ] `GlanceRemoteViews(` and `ArrowBitmaps(` appear only inside `startView` bodies.
- [ ] `Weather data by Open-Meteo.com (CC BY 4.0)` is present in `strings.xml` and rendered in About.
- [ ] No `ktor` or `mapbox` string remains in any Gradle file; no `maven.pkg.github.com` in `settings.gradle.kts`.
- [ ] No `runBlocking` anywhere in `«src»/ui`.
- [ ] `grep -rn "getIdentifier" «src»` is empty.
- [ ] **On device, debug APK:** add all 5 fields to a page, load a route, confirm the route strip fills and
      the map shows wind arrows; airplane-mode the device and confirm cached values persist with the stale
      marker; load a **breadcrumb** route and confirm the strip advances (progress GPS fallback).
- [ ] **On device, the MINIFIED release APK:** fields render and a fetch succeeds. R8 damage to
      `kotlinx.serialization` is invisible in debug.

---

## Dependency graph (for scheduling)

```
WP0 ─┬─> WP1 ─┐
     ├─> WP2 ─┼─> WP3 ─┬─> WP4 ─┐
     ├─> WP7  │        ├─> WP5 ─┼─> WP8
     └────────┘        └─> WP6 ─┘
```
WP1, WP2 and WP7 start immediately after WP0 and never touch each other. WP3 needs WP1+WP2 signatures only.
WP4, WP5 and WP6 need WP3's signatures only and are **genuinely independent of one another** — `WmoIcons`
moved to WP2 precisely so WP5 no longer reaches into WP4. In practice: launch WP1, WP2, WP7 together; then
WP3; then WP4, WP5, WP6 together; then WP8.

---

## Rejected and partially accepted findings

Every finding in `CRITIQUE.md` was applied except the following, which were applied in a different form or
rejected outright. Numbering follows the critique.

**#30 — "3 columns and 3 rows at `(60,30)`; 5 columns only at `(60,60)`" — partially accepted.**
The premise (five rows of ~40 px) assumed a landscape 800 × 480 in-ride geometry. The Karoo panel is
portrait-native and ride pages are portrait, so `(60,30)` is ≈ 480 × 400 px and five columns of 96 px clear
the floor comfortably. The *principle* — a column count must not be asserted from `gridSize` alone — is
accepted and implemented structurally: `FieldChrome.columnsFor(viewSize, maxColumns)` derives the count from
the real `viewSize` at 88 px minimum per cell, so a narrower device degrades by itself instead of shipping
illegible text. The ETA row was moved from `(60,30)` to `(60,60)` as the critique asked, and `weather-now`'s
six-hour strip is now `columnsFor(viewSize, 6)` rather than a hard six.

**#31 — the full v1 scope cut — partially accepted.**
Accepted: `apparent-temperature` and `headwind-speed` are cut (ADR-0 #7 and DESIGN §3.5 give the reasons —
"feels like" is already a row in `weather-now`, and a `graphical="false"` field cannot render the `+/-` sign
convention). Field count goes 7 → 5, layout variants ~16 → ~10, icons 26 → 26 (unchanged: the WMO set is what
it is).
Rejected: cutting the map layer, the rain alert, and `rain-next-hour` as its own field. The map layer is the
one thing the brief explicitly constrains ("no raster overlay, no MapLibre"), which is a request for map
integration, not an absence of one; and WP6 is a single agent working in two files with pure, testable
decision functions and **nothing downstream of it** — it does not lengthen the critical path. Rain-in-the-next
-two-hours is one of the three things the brief names. Deferring them would cut the wrong half.

**#34 — "settle the orientation, then re-derive from real `ViewConfig` before WP4 commits" — partially accepted.**
The orientation is settled (portrait 480 × 800, DESIGN header) and the mocks are redrawn against it. The
demand that hardware measurements *precede* WP4 is rejected as a schedule blocker: it would idle every agent
behind one device session. Instead the layouts are made measurement-independent (`columnsFor`), and logging
the real `ViewConfig` values is a **completion** criterion for WP4 (spike S3), not a precondition. If the
device reports something surprising, only the constant `MIN_CELL_PX` and the `maxColumns` table change.

**#46 — the ADR-0 #3 statistic.** Accepted, and noted here only because the fix is a deletion: the technical
claim was verified in `KarooEngine.kt` and kept; the unsourced "half the installed base" figure is gone.

Two critique items were **verified as no-ops** rather than fixed:
- **#10** — the GitHub-Packages block *can* be deleted: `jitpack.io` serves `karoo-ext:1.1.9` (HTTP 200) and
  Maven Central does not (404), checked 2026-08. Recorded as ADR-0 #9 and moved from WP8 to WP0.
- **#23** — `UpdateNumericConfig` is the correct event for a `graphical="false"` field, per the KDoc in
  `models/ViewEvent.kt`; the on-device confirmation is scheduled as spike S2 and it invalidates at most one
  method body.
