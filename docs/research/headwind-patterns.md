# karoo-headwind — reference analysis for `karoo-weather`

Source analysed: `<ref>/karoo-headwind`
(Apache-2.0, © 2024-2026 karoo-headwind contributors, github.com/timklge/karoo-headwind).
All paths below are relative to that root. All excerpts are quoted for **pattern reference**; re-implement, don't copy wholesale, and keep the Apache-2 attribution if you do reuse code.

Total Kotlin: ~8.6k lines / 70 files. Package `de.timklge.karooheadwind`.

---

## 1. Project structure, Gradle setup, manifest

### 1.1 Layout

```
build.gradle.kts            root, plugins only (apply false)
settings.gradle.kts         repositories incl. karoo-ext GitHub Packages + Mapbox
gradle/libs.versions.toml   version catalog
app/build.gradle.kts        android app module + `generateManifest` task
app/src/main/AndroidManifest.xml
app/src/main/res/xml/extension_info.xml
app/src/main/kotlin/de/timklge/karooheadwind/
    KarooHeadwindExtension.kt   the KarooExtension service (fetch loop)
    DataStore.kt                DataStore persistence + derived flows
    Extensions.kt               KarooSystemService flow helpers
    HeadingFlow.kt              GPS + heading + relative-wind flows
    HeadwindSettings.kt         @Serializable settings / stats / widget settings
    MainActivity.kt             ComponentActivity -> Compose MainScreen
    ServiceStatusSingleton.kt
    datatypes/                  one file per data field (DataTypeImpl)
    weatherprovider/            provider abstraction + openmeteo/ + openweathermap/
    screens/                    Compose settings/live/windy UI + LineGraph/BarChart painters
    theme/Theme.kt
    util/                       AngleDifference, Conversion, TimeFormat
app/src/test/kotlin/…           2 plain kotlin.test unit tests
```

### 1.2 `settings.gradle.kts` — karoo-ext comes from GitHub Packages (needs credentials)

```kotlin
// settings.gradle.kts:33-52
val gprUser = if (env.containsKey("GPR_USER")) env["GPR_USER"] else getLocalProperty("gpr.user")
val gprKey  = if (env.containsKey("GPR_KEY"))  env["GPR_KEY"]  else getLocalProperty("gpr.key")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
                credentials { username = gprUser; password = gprKey } }
        maven { url = uri("https://api.mapbox.com/downloads/v2/releases/maven") }  // turf
    }
}
```

`getLocalProperty` **errors out** if `local.properties` is missing, so a fresh clone needs either the file or `GPR_USER`/`GPR_KEY` env vars. Consider making it tolerant in our project.

### 1.3 `gradle/libs.versions.toml` — versions actually used

| Item | Version |
|---|---|
| AGP `com.android.application` | 8.5.2 |
| Kotlin + compose-compiler plugin | 2.0.0 (serialization plugin pinned separately to **2.0.20** in `app/build.gradle.kts`) |
| `io.hammerhead:karoo-ext` | **1.1.9** |
| `androidx.datastore:datastore-preferences` | 1.1.2 |
| `androidx.glance:glance-appwidget` (+`-preview`, `androidx.glance:glance-preview`) | **1.1.1** |
| `androidx.compose.ui` | 1.7.6 |
| `androidx.compose.material3` | 1.3.1 |
| `androidx.lifecycle` (runtime-compose, viewmodel-compose) | 2.8.7 |
| `androidx.activity:activity-compose` | 1.9.3 |
| `androidx.core:core-ktx` | 1.15.0 |
| `kotlinx-serialization-json` | 1.8.0 |
| `com.mapbox.mapboxsdk:mapbox-sdk-turf` | 7.3.1 |

Note: **no Ktor and no OkHttp**. All HTTP goes through the Karoo system service (`OnHttpResponse.MakeHttpRequest`, §3). Only `kotlinx-serialization-json` is used for parsing. Turf is used for polyline length / `along` / `bearing` / `distance`.

`compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`, Java/Kotlin target **1.8**, `buildFeatures { compose = true; buildConfig = true }`, release build `isMinifyEnabled = true` with default proguard-android-optimize + an **empty** `proguard-rules.pro` (kotlinx-serialization survives thanks to its own consumer rules — worth verifying in our build).

Versioning is CI-driven:
```kotlin
// app/build.gradle.kts:36-38
versionCode = 100 + (System.getenv("BUILD_NUMBER")?.toInt() ?: 1)
versionName = System.getenv("RELEASE_VERSION") ?: "1.0"
```
and the release keystore is decoded from `KEYSTORE_BASE64` into a temp file at configuration time.

### 1.4 Extension-library `manifest.json` generation (important for distribution)

`app/build.gradle.kts:73-121` registers a `generateManifest` task (wired into `assemble` and into `process{Debug,Release}MainManifest`) that writes `app/manifest.json`:

```kotlin
val manifest = mapOf(
    "label" to "Headwind",
    "packageName" to "de.timklge.karooheadwind",
    "iconUrl" to "$baseUrl/karoo-headwind.png",
    "latestApkUrl" to "$baseUrl/app-release.apk",
    "latestVersion" to android.defaultConfig.versionName,
    "latestVersionCode" to android.defaultConfig.versionCode,
    "developer" to "github.com/timklge",
    "description" to "…",
    "releaseNotes" to "…",
    "screenshotUrls" to listOf(...),
    "tags" to listOf("weather")
)
```
It also textually replaces the literal `$BASE_URL$` placeholder inside `AndroidManifest.xml` when `BASE_URL` is set (⚠ this **mutates the source file in place** — a dirty-tree hazard in CI; prefer a manifest placeholder `${baseUrl}` via `manifestPlaceholders` in our project).

CI (`.github/workflows/android.yml`) = JDK 17 + `./gradlew build`, and on tags publishes `app-release.apk`, `app/manifest.json`, icon and previews as release assets.

### 1.5 AndroidManifest

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET"/>

<application android:icon="@drawable/wind" android:theme="@style/Theme.AppCompat">
  <activity android:name=".MainActivity" android:theme="@style/SplashTheme" android:exported="true">
    <intent-filter><action android:name="android.intent.action.MAIN"/>
      <category android:name="android.intent.category.LAUNCHER"/></intent-filter>
  </activity>

  <service android:name=".KarooHeadwindExtension" android:exported="true" tools:ignore="ExportedService">
    <intent-filter><action android:name="io.hammerhead.karooext.KAROO_EXTENSION"/></intent-filter>
    <meta-data android:name="io.hammerhead.karooext.EXTENSION_INFO" android:resource="@xml/extension_info"/>
  </service>

  <meta-data android:name="io.hammerhead.karooext.MANIFEST_URL" android:value="$BASE_URL$/manifest.json"/>
</application>
```
No location permission is declared — location comes from the Karoo `DataType.Type.LOCATION` stream, not from Android's LocationManager. No foreground-service declaration: the extension service is started/stopped by Karoo OS.

### 1.6 `res/xml/extension_info.xml`

```xml
<ExtensionInfo displayName="@string/extension_name" icon="@drawable/wind"
               id="karoo-headwind" scansDevices="false">
  <DataType typeId="tailwind-and-ride-speed" graphical="true"  icon="@drawable/wind"
            displayName="@string/tailwind_and_speed" description="@string/tailwind_and_speed_description"/>
  <DataType typeId="temperature"  graphical="false" icon="@drawable/thermometer" .../>
  …
</ExtensionInfo>
```
24 `<DataType>` entries. The `id` here (`karoo-headwind`) must equal the first argument of `KarooExtension(...)` and of every `DataTypeImpl(extension, typeId)`. `graphical="true"` means the field renders a custom view (`startView`); `graphical="false"` means it only streams a number and Karoo renders it. Other extensions can consume the fields as `TYPE_EXT::karoo-headwind::<typeId>` (documented in README).

---

## 2. Data field rendering (Glance → RemoteViews)

### 2.1 Two shapes of data field

**Numeric-only fields** subclass `BaseDataType` (`datatypes/BaseDataType.kt`, full file ~92 lines):

```kotlin
// datatypes/BaseDataType.kt:45 (abstract class at :45, startStream at :54)
abstract class BaseDataType(
    private val karooSystemService: KarooSystemService,
    private val applicationContext: Context,
    dataTypeId: String
) : DataTypeImpl("karoo-headwind", dataTypeId) {
    abstract fun getValue(data: WeatherData, userProfile: UserProfile, settings: HeadwindSettings): Double?
    open fun getFormatDataType(): String? = null

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            val currentWeatherData = combine(
                applicationContext.streamCurrentWeatherData(karooSystemService).filterNotNull(),
                karooSystemService.streamUserProfile(),
                applicationContext.streamSettings(karooSystemService)
            ) { w, p, s -> StreamData(w, p, s) }

            val refreshRate = karooSystemService.getRefreshRateInMilliseconds(applicationContext)

            currentWeatherData.filterNotNull().throttle(refreshRate).collect { (data, userProfile, settings) ->
                val value = getValue(data, userProfile, settings)
                if (value != null)
                    emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to value))))
                else
                    emitter.onNext(StreamState.NotAvailable)
            }
        }
        emitter.setCancellable { job.cancel() }   // MUST cancel the job here
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        if (getFormatDataType() != null)
            emitter.onNext(UpdateGraphicConfig(formatDataTypeId = getFormatDataType()))
    }
}
```

`getFormatDataType()` is the key trick for numeric fields: it hands Karoo a **built-in** data type id so Karoo applies that type's unit formatting/decimals. Observed mappings:
- `TemperatureDataType` → `DataType.Type.TEMPERATURE`
- `HeadwindSpeedDataType` → `DataType.Type.SPEED`
- `WindSpeedDataType` / `WindGustsDataType` → `DataType.Type.INTENSITY_FACTOR` (a hack: a plain unit-less 1-decimal number)

A concrete field is then 3 lines (`datatypes/CloudCoverDataType.kt`):
```kotlin
class CloudCoverDataType(karooSystemService: KarooSystemService, context: Context)
    : BaseDataType(karooSystemService, context, "cloudCover") {
    override fun getValue(data: WeatherData, userProfile: UserProfile, settings: HeadwindSettings) = data.cloudCover
}
```

**Graphical fields** implement `DataTypeImpl.startView` directly and push `RemoteViews` produced by Glance.

### 2.2 The Glance → RemoteViews mechanism

Every graphical type holds one `GlanceRemoteViews` instance and composes on demand:

```kotlin
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
private val glance = GlanceRemoteViews()
…
val result = glance.compose(context, DpSize.Unspecified) { /* @Composable Glance tree */ }
emitter.updateView(result.remoteViews)
```

Notes that matter:
- `DpSize.Unspecified` is always used — sizing is driven by the Karoo field box, not by Glance size qualifiers.
- Only **Glance** composables (`androidx.glance.layout.*`, `androidx.glance.text.*`, `androidx.glance.Image`) may appear inside `compose {}` — not `androidx.compose.foundation`. Colors are `androidx.glance.color.ColorProvider(dayColor, nightColor)`, which gives free light/dark handling: `ColorProvider(Color.Black, Color.White)` is the standard "text" color everywhere.
- Anything that can't be expressed in Glance (line graphs, rotated arrows, bar charts) is drawn into an Android `Bitmap` with `Canvas` and shown via `Image(ImageProvider(bitmap), …)`. See `getArrowBitmapByBearing` (§5) and `screens/LineGraph.kt`'s `LineGraphBuilder.drawLineGraph(width, height, gridWidth, gridHeight, lines, labelProvider): Bitmap`.

### 2.3 `ViewConfig` usage

| Field | How it is used |
|---|---|
| `config.preview` | Boolean. When true, swap the real flow for a synthetic `previewFlow()` (random plausible values, `delay(2_000)`/`5_000` between emissions) **and** disable click actions (`clickable` is only attached when `!preview`). Used in the field-picker preview grid. |
| `config.textSize` | Int (sp) chosen by Karoo for the field box. Multiplied by a factor: `(0.6 * fontSize).sp` for the main number, `(0.4 * fontSize).sp` for the sub-label (`datatypes/HeadwindDirectionView.kt:101,161`). Plain text fields use it raw: `TextUnit(config.textSize.toFloat(), TextUnitType.Sp)`. |
| `config.gridSize` (Pair<Int,Int>) | Grid cell units. `config.gridSize.first == 30` ⇒ 1×1 field (weather forecast then shows only one column, `datatypes/ForecastDataType.kt:294`); `== 60` ⇒ 2-wide field (`wideMode` layout in `TailwindAndRideSpeedDataType.kt:228`). |
| `config.viewSize` (Pair<Int,Int>) | Pixel size; passed straight into the bitmap graph builder: `LineGraphBuilder(context).drawLineGraph(config.viewSize.first, config.viewSize.second, config.gridSize.first, config.gridSize.second, …)` (`LineGraphForecastDataType.kt:304`). |
| `config.alignment` | `ViewConfig.Alignment.{LEFT,CENTER,RIGHT}` mapped to Glance horizontal alignment (`datatypes/WindDirectionDataType.kt:127-132`): `LEFT -> Alignment.Horizontal.Start`, etc. |

### 2.4 `UpdateGraphicConfig` and `ShowCustomStreamState`

```kotlin
// datatypes/HeadwindDirectionDataType.kt:131-134
val configJob = CoroutineScope(Dispatchers.IO).launch {
    emitter.onNext(UpdateGraphicConfig(showHeader = false))
    awaitCancellation()          // keep the job alive so the config sticks
}
```
- `UpdateGraphicConfig(showHeader = false)` hides the Karoo field header/title for full-bleed graphics. `WindDirectionDataType` keeps `showHeader = true`.
- `UpdateGraphicConfig(formatDataTypeId = …)` — numeric formatting delegation (§2.1).
- `emitter.onNext(ShowCustomStreamState("", null))` is emitted **once at the start of the view job** to clear Karoo's "no data / searching" overlay so the custom view is visible (`ForecastDataType.kt:257`, `TailwindAndRideSpeedDataType.kt:202`, `WindDirectionDataType.kt:101`).
- `ShowCustomStreamState(message, color)` is also used to display an error *through Karoo's own overlay* instead of a custom widget:
```kotlin
// datatypes/LineGraphForecastDataType.kt:332
emitter.onNext(ShowCustomStreamState(pointData.message,
    if (isNightMode(context)) context.resources.getColor(R.color.white)
    else context.resources.getColor(R.color.black)))
```
(used for "No route loaded" in the headwind forecast).
- Always `emitter.setCancellable { configJob.cancel(); viewJob.cancel() }`.

### 2.5 Refresh cadence

Two independent cadences:

1. **View/stream repaint rate** — a user setting, hardware-dependent:
```kotlin
// datatypes/Views.kt:100
suspend fun KarooSystemService.getRefreshRateInMilliseconds(context: Context): Long {
    val refreshRate = context.streamSettings(this).first().refreshRate
    return if (hardwareType == HardwareType.K2) refreshRate.k2Ms else refreshRate.k3Ms
}
// HeadwindSettings.kt:73-77
enum class RefreshRate(val id: String, val k2Ms: Long, val k3Ms: Long) {
    FAST("fast", 1_000L, 500L), STANDARD("medium", 2_000L, 1_000L),
    SLOW("slow", 5_000L, 3_000L), MINIMUM("minimum", 10_000L, 10_000L)
}
```
   applied as `flow.throttle(refreshRate)` before every `updateView`. **K2 is deliberately slower** (weaker CPU / slower e-ink-ish redraw).

2. **Don't render invisible fields** — a big battery/CPU win:
```kotlin
// Extensions.kt:74-91
fun KarooSystemService.streamActiveRidePage(): Flow<ActiveRidePage> = callbackFlow {
    val listenerId = addConsumer { p: ActiveRidePage -> trySendBlocking(p) }
    awaitClose { removeConsumer(listenerId) }
}
fun KarooSystemService.streamDatatypeIsVisible(datatype: String): Flow<Boolean> =
    streamActiveRidePage().map { page -> page.page.elements.any { it.dataTypeId == datatype } }
```
   then `flow.filter { it.isVisible }.throttle(refreshRate).collect { … }`.

3. Forecast views additionally throttle their *heading* input at `3 * 60_000L` ms since the forecast graphic doesn't need live bearing.

### 2.6 Tap actions on a data field

The 2×1 weather forecast is tappable to cycle the displayed 3-hour window:

```kotlin
// datatypes/ForecastDataType.kt:275-277
var modifier = GlanceModifier.fillMaxSize()
if (!config.preview) modifier = modifier.clickable(onClick = actionRunCallback<CycleHoursAction>())
```
```kotlin
// datatypes/CycleHoursAction.kt:31
class CycleHoursAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val currentSettings = context.streamWidgetSettings().first()
        val forecastData = context.streamCurrentForecastWeatherData().firstOrNull()
        var hourOffset = currentSettings.currentForecastHourOffset + 3
        …wrap to 0 when past the end…
        saveWidgetSettings(context, currentSettings.copy(currentForecastHourOffset = hourOffset))
    }
}
```
The offset lives in DataStore (`HeadwindWidgetSettings`), so the view re-renders through its normal flow. The 1×1 headwind field instead uses `clickable(actionStartActivity<MainActivity>())`.

---

## 3. Weather fetching (`OpenMeteoWeatherProvider`)

### 3.1 Provider abstraction

```kotlin
// weatherprovider/WeatherProvider.kt:24
interface WeatherProvider {
    suspend fun getWeatherData(karooSystem: KarooSystemService, coordinates: List<GpsCoordinates>,
                               settings: HeadwindSettings, profile: UserProfile?): WeatherDataResponse
}
```
`WeatherProviderFactory.makeWeatherRequest(...)` picks the implementation from settings; `WeatherProviderException(statusCode, message)` is the single error type. Provider-specific DTOs (`OpenMeteoWeatherData`, `OpenMeteoWeatherForecastData`, …) are converted to a **provider-neutral** model:

```kotlin
// weatherprovider/WeatherData.kt / WeatherDataForLocation.kt / WeatherDataResponse.kt
@Serializable data class WeatherData(
    val time: Long,                       // unix seconds
    val temperature: Double,              // °C
    val relativeHumidity: Int, val precipitation: Double,
    val precipitationProbability: Double? = null,
    val cloudCover: Double, val sealevelPressure: Double, val surfacePressure: Double,
    val windSpeed: Double,                // m/s (always!)
    val windDirection: Double,            // degrees, meteorological (FROM)
    val windGusts: Double, val weatherCode: Int,        // WMO
    val isForecast: Boolean, val isNight: Boolean, val uvi: Double)

@Serializable data class WeatherDataForLocation(
    val current: WeatherData, val coords: GpsCoordinates,
    val timezone: String? = null, val elevation: Double? = null,
    val forecasts: List<WeatherData>? = null)

@Serializable data class WeatherDataResponse(
    val error: String? = null, val provider: WeatherDataProvider, val data: List<WeatherDataForLocation>)
```
Everything is stored in SI-ish canonical units (m/s, °C, mm) and converted at render time (`util/Conversion.kt`). Copy this: it keeps the unit-preference logic in exactly one place.

### 3.2 The Open-Meteo URL (verbatim, `weatherprovider/openmeteo/OpenMeteoWeatherProvider.kt:47-49`)

```kotlin
val lats = gpsCoordinates.joinToString(",") { String.format(Locale.US, "%.6f", it.lat) }
val lons = gpsCoordinates.joinToString(",") { String.format(Locale.US, "%.6f", it.lon) }
val url = "https://api.open-meteo.com/v1/forecast" +
  "?latitude=$lats&longitude=$lons" +
  "&current=is_day,surface_pressure,pressure_msl,uv_index,temperature_2m,relative_humidity_2m," +
           "precipitation,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
  "&hourly=uv_index,temperature_2m,precipitation_probability,precipitation,weather_code," +
          "wind_speed_10m,wind_direction_10m,wind_gusts_10m,is_day,surface_pressure,pressure_msl," +
          "relative_humidity_2m,cloud_cover" +
  "&timeformat=unixtime&past_hours=0&forecast_days=1&forecast_hours=12&wind_speed_unit=ms"
```

Key points for us:
- **Multi-location in ONE request**: `latitude=a,b,c&longitude=a,b,c` (comma-separated, `Locale.US` formatting, 6 decimals). Open-Meteo then returns a **JSON array** of location objects instead of a single object — handled explicitly:
```kotlin
// :99-104
val weatherData = if (coordinates.size == 1)
    listOf(json.decodeFromString<OpenMeteoWeatherDataForLocation>(responseBody))
else
    json.decodeFromString<List<OpenMeteoWeatherDataForLocation>>(responseBody)
```
  Results are re-zipped positionally with the request coordinates to carry `distanceAlongRoute` back: `weatherData.zip(coordinates) { d, loc -> d.toWeatherDataForLocation(loc.distanceAlongRoute) }`.
- **Response-size control** = the only lever used against the Karoo HTTP payload limit: `forecast_days=1`, **`forecast_hours=12`**, `past_hours=0`, and at most **10 locations** (enforced upstream by `size < 10` in the route sampler, §4). There is *no* minutely_15 block, no daily block, no `models=` parameter.
- `wind_speed_unit=ms` — request m/s directly so no conversion happens on ingest.
- `timeformat=unixtime` — plain `Long` seconds, avoids date parsing on device. (Note: with `timeformat=unixtime` Open-Meteo returns times in the location's timezone offset unless `timezone=` is given; this app never sets `timezone`, so times are UTC-based and it formats with `ZoneId.systemDefault()`.)
- `hourly` deliberately repeats every field also present in `current`, so a uniform `WeatherData` can be built for both.
- No API key, no attribution header beyond `User-Agent`.

### 3.3 HTTP via the Karoo system service (there is no HTTP client!)

```kotlin
// weatherprovider/openmeteo/OpenMeteoWeatherProvider.kt:44-88
@OptIn(FlowPreview::class)
private suspend fun makeOpenMeteoWeatherRequest(
    karooSystemService: KarooSystemService, gpsCoordinates: List<GpsCoordinates>
): HttpResponseState.Complete {
    val response = callbackFlow {
        val listenerId = karooSystemService.addConsumer(
            OnHttpResponse.MakeHttpRequest(
                "GET", url,
                waitForConnection = false,
                headers = mapOf("User-Agent" to KarooHeadwindExtension.TAG),
            ),
            onEvent = { event: OnHttpResponse ->
                if (event.state is HttpResponseState.Complete) {
                    trySend(event.state as HttpResponseState.Complete); close()
                }
            },
            onError = { err -> close(WeatherProviderException(0, "Http error: $err")) })
        awaitClose { karooSystemService.removeConsumer(listenerId) }
    }.timeout(30.seconds).catch { e ->
        if (e is TimeoutCancellationException) emit(HttpResponseState.Complete(500, mapOf(), null, "Timeout"))
        else throw e
    }.single()

    if (response.statusCode !in 200..299)
        throw WeatherProviderException(response.statusCode, "OpenMeteo API request failed with status code ${response.statusCode}")
    return response
}
```
Body is `ByteArray?` → `String(it)`; a null body throws `WeatherProviderException(500, "Null response…")`. Deserialisation uses a lenient Json instance shared app-wide:
```kotlin
// DataStore.kt:48
val jsonWithUnknownKeys = Json { ignoreUnknownKeys = true }
```

`waitForConnection = false` means "fail fast rather than queue until connectivity returns" — the retry loop (below) handles reconnection instead. This is what makes the extension work identically on Karoo 2 (SIM) and Karoo 3 (phone tether via the companion app): the OS decides the transport.

### 3.4 Refetch policy, caching, retry

There is **no HTTP cache**. The last successful `WeatherDataResponse` is serialised into DataStore (key `currentForecastsUnified`) and every consumer reads that. The *decision to refetch* is driven purely by flow identity (`KarooHeadwindExtension.kt:151-160`):

```kotlin
combine(settingsStream, gpsFlow, karooSystem.streamUserProfile(), karooSystem.streamUpcomingRoute()) {
    settings, gps, profile, upcomingRoute -> StreamData(settings, gps, profile, upcomingRoute)
}
.distinctUntilChangedBy { StreamDataIdentity(it.settings, it.gps?.lat, it.gps?.lon, it.profile, it.upcomingRoute?.routePolyline) }
.transformLatest { value -> while (true) { emit(value); delay(1.hours) } }   // time-based refresh
.map { … make request … }
.retry(Long.MAX_VALUE) { e -> Log.w(TAG, "Failed to get weather data", e); delay(2.minutes); true }
.collect { response -> saveCurrentData(applicationContext, response)
                       saveWidgetSettings(applicationContext, HeadwindWidgetSettings(currentForecastHourOffset = 0)) }
```

So the triggers are:
- **Distance**: the GPS flow is *rounded* to a grid (default 3 km, see §3.5), so `distinctUntilChangedBy(lat, lon)` only fires when you cross a grid cell ⇒ "refetch after ~3 km" for free, with a privacy benefit.
- **Time**: `transformLatest { while(true){ emit(value); delay(1.hours) } }` re-emits the same value hourly ⇒ at latest hourly refresh. `transformLatest` cancels the timer whenever a new distinct value arrives.
- **Route change**: `upcomingRoute?.routePolyline` is part of the identity.
- **Settings change**: any settings edit re-triggers; the UI exploits this with a manual refresh that just writes `settings.copy(lastUpdateRequested = System.currentTimeMillis())` (`screens/MainScreen.kt:66`) — a nice "poke the service" pattern with no IPC.
- **Failure**: `retry(Long.MAX_VALUE) { delay(2.minutes); true }` — infinite retry every 2 minutes (README says 1 minute; the code says 2).

Stats about the last attempt are persisted for the UI (`HeadwindStats(lastSuccessfulWeatherRequest, lastSuccessfulWeatherPosition, failedWeatherRequest, lastSuccessfulWeatherProvider)`).

Also note the upstream gating: `streamSettings(karooSystem).filter { it.welcomeDialogAccepted }` — **nothing is downloaded until the user has opened the app once and accepted the welcome dialog** (privacy/consent).

The GPS flow feeding this is itself throttled and dead-band filtered:
```kotlin
// KarooHeadwindExtension.kt:133-143
val gpsFlow = karooSystem.getGpsCoordinateFlow(this)
    .distinctUntilChanged { old, new ->
        if (old != null && new != null) old.distanceTo(new).absoluteValue < 0.001  // km => 1 m
        else old == new
    }
    .throttle(5_000L)
```

### 3.5 Location rounding (privacy)

```kotlin
// datatypes/GpsCoordinates.kt:28-43
@Serializable
data class GpsCoordinates(val lat: Double, val lon: Double,
                          val bearing: Double? = 0.0, val distanceAlongRoute: Double? = null) {
    companion object {
        private fun roundDegrees(degrees: Double, km: Double): Double {
            val nkm = degrees * 111
            return ((nkm / km).roundToInt() * km) / 111
        }
    }
    fun round(km: Double = 2.0) = copy(lat = roundDegrees(lat, km), lon = roundDegrees(lon, km))
    fun distanceTo(other: GpsCoordinates): Double { /* haversine, returns KILOMETRES, r = 6371.0 */ }
}
```
Applied in `getGpsCoordinateFlow` via the user's `RoundLocationSetting` (1/2/3/5 km, default 3). ⚠ The longitude rounding uses the same 111 km/deg factor as latitude, so at high latitudes the effective longitudinal grid is much coarser than requested — acceptable here, but be aware.

### 3.6 The other provider — batching under a call budget (`OpenWeatherMapWeatherProvider`)

OpenWeatherMap One Call has no multi-location support, so it caps itself:
```kotlin
private const val MAX_API_CALLS = 3
val selectedCoordinates = coordinates.take((MAX_API_CALLS - 1).coerceAtLeast(1)).toMutableList()
if (coordinates.isNotEmpty() && !selectedCoordinates.contains(coordinates.last()))
    selectedCoordinates.add(coordinates.last())      // always include the route end
```
then every requested coordinate is mapped to the nearest fetched one (by `distanceAlongRoute` when available, else haversine). Good fallback pattern if we ever add a per-call-budget provider. It also maps OWM condition ids back onto WMO codes (`convertWeatherCodeToOpenMeteo`, `weatherprovider/openweathermap/OpenWeatherMapWeatherProvider.kt:70`).

---

## 4. Loaded route + forecast along the route

### 4.1 Getting the route (`DataStore.kt:142-174`)

```kotlin
data class UpcomingRoute(val distanceAlongRoute: Double, val routePolyline: LineString, val routeLength: Double)

fun KarooSystemService.streamUpcomingRoute(): Flow<UpcomingRoute?> {
    val distanceToDestinationStream = streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION)
        .map { (it as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.DISTANCE_TO_DESTINATION) }
        .distinctUntilChanged()

    var lastKnownDistanceAlongRoute = 0.0
    var lastKnownRoutePolyline: LineString? = null

    return streamNavigationState()
        .map { it.state as? OnNavigationState.NavigationState.NavigatingRoute }
        .map { st -> st?.let { LineString.fromPolyline(it.routePolyline, 5) } }   // precision 5!
        .distinctUntilChanged()
        .combine(distanceToDestinationStream) { routePolyline, distanceToDestination ->
            if (routePolyline != null) {
                val length = TurfMeasurement.length(routePolyline, TurfConstants.UNIT_METERS)
                if (routePolyline != lastKnownRoutePolyline) lastKnownDistanceAlongRoute = 0.0
                val distanceAlongRoute = distanceToDestination?.let { length - it } ?: lastKnownDistanceAlongRoute
                lastKnownDistanceAlongRoute = distanceAlongRoute
                lastKnownRoutePolyline = routePolyline
                UpcomingRoute(distanceAlongRoute, routePolyline, length)
            } else null
        }
}
```
Ingredients: `OnNavigationState` consumer (see `Extensions.kt:45`), `OnNavigationState.NavigationState.NavigatingRoute.routePolyline` (an **encoded polyline, precision 5**), and the built-in `DISTANCE_TO_DESTINATION` stream to derive progress (`distanceAlongRoute = routeLength - distanceToDestination`).

### 4.2 Sampling points along the route (`KarooHeadwindExtension.kt:175-231`)

ETA model = **constant assumed speed** from settings (`forecastedKmPerHour = 20` / `forecastedMilesPerHour = 12`), not measured speed:

```kotlin
val distancePerHour = settings.getForecastMetersPerHour(isImperial).toDouble()

// distance still to ride before the next full clock hour
val msSinceFullHour = ChronoUnit.MILLIS.between(LocalDateTime.now().truncatedTo(ChronoUnit.HOURS), LocalDateTime.now())
val msToNextFullHour = 3_600_000 - msSinceFullHour
val calculatedDistanceToNextFullHour =
    ((msToNextFullHour / 3_600_000.0) * distancePerHour).coerceIn(0.0, distancePerHour)

requestedGpsCoordinates = buildList {
    add(GpsCoordinates(gps.lat, gps.lon, gps.bearing, distanceAlongRoute = positionOnRoute))   // index 0 = now/here

    var currentPosition = positionOnRoute + calculatedDistanceToNextFullHour
    var lastRequestedPosition = positionOnRoute
    while (currentPosition < upcomingRoute.routeLength && size < 10) {          // hard cap 10 points
        val point = TurfMeasurement.along(upcomingRoute.routePolyline, currentPosition, TurfConstants.UNIT_METERS)
        add(GpsCoordinates(point.latitude(), point.longitude(), distanceAlongRoute = currentPosition))
        lastRequestedPosition = currentPosition
        currentPosition += distancePerHour                                      // one sample per forecast hour
    }
    if (upcomingRoute.routeLength > lastRequestedPosition + 1_000)              // always include the finish
        add(GpsCoordinates(<point at routeLength>, distanceAlongRoute = upcomingRoute.routeLength))
}
```
Elegant detail: aligning the first sample to the **next full clock hour** makes sample *i* line up with hourly forecast index *i*, so `data[i].forecasts[i]` is "the weather where you'll be, when you'll be there". That indexing assumption is used directly in the views:
```kotlin
// datatypes/LineGraphForecastDataType.kt:262-271
val locationData = if (isRouteLoaded) allData.data.getOrNull(i) else allData.data.firstOrNull()
val data = if (i == 0) locationData?.current else locationData?.forecasts?.getOrNull(i)
```
With **no route loaded**, `requestedGpsCoordinates = listOf(gps)` (one point) and the same views fall back to `data[0].forecasts[i]` = a plain 12-hour forecast for the current position.

Batching = **one HTTP request for all ≤10 points** (§3.2). The "10" cap and `forecast_hours=12` are the two knobs keeping the response small.

### 4.3 Route-aware filtering & labelling in views

- Forecast entries older than 1 h are skipped; without a route, entries more than 6 h out are skipped too (`ForecastDataType.kt:333`, `LineGraphForecastDataType.kt:280`).
- The route flow is de-duplicated with a 1 km dead band before it reaches a view:
```kotlin
karooSystem.streamUpcomingRoute().distinctUntilChanged { old, new -> abs(old.distanceAlongRoute - new.distanceAlongRoute) < 1_000 }
```
- The X axis of a line graph switches from *time labels* to *distance labels* as soon as the sampled points carry `distanceAlongRoute` (`LineGraphForecastDataType.kt:311-322`).
- `Weather()` renders "In 12km" / "12km ago" from `distanceFromCurrent = pointDistanceAlongRoute - currentDistanceAlongRoute` (`datatypes/WeatherView.kt:122-143`).

### 4.4 Spatial + temporal interpolation of the "current" weather (`DataStore.kt`)

For point data types the app doesn't just take `data[0]`: it interpolates between the two nearest sampled locations and between the two bracketing hours.

```kotlin
// DataStore.kt:337  Context.streamCurrentWeatherData(karooSystemService): Flow<WeatherData?>
//  - sorts data by turf distance to the live position, takes the 2 closest
//  - lerpFactor = d1 / (d1 + d2)         [note: this weights toward the FARTHER point — likely a bug, use d1/(d1+d2) inverted]
//  - lerpWeather(location1.current, location2.current, lerpFactor)  (+ per-index forecast lerp)
//  - then lerpWeatherTime(forecasts, current) to interpolate to "now"
//  - re-emits every delay(1.minutes)
```
Helpers worth reusing (`DataStore.kt:229-320`): `lerp`, `lerpNullable`, **`lerpAngle`** (shortest-arc angle interpolation, essential for wind direction), `lerpWeather` (per-field; categorical fields like `weatherCode`/`isNight` take the *nearest* sample, not an average), and `lerpWeatherTime` (find previous/next forecast around `System.currentTimeMillis()` and lerp).

⚠ The distance lerp factor `d1/(d1+d2)` gives factor→0 when you're *at* location 1 (correct) — fine actually; but note `.take(2)` crashes if only one location and `weatherData.data.size == 1` isn't caught earlier (it is: `if (location == null || size == 1) data.first()`).

---

## 5. Heading & headwind math

### 5.1 GPS/heading flows (`HeadingFlow.kt`)

```kotlin
// HeadingFlow.kt:38-42
sealed class HeadingResponse {
    data object NoGps: HeadingResponse()
    data object NoWeatherData: HeadingResponse()
    data class Value(val diff: Double): HeadingResponse()
}
```

`getGpsCoordinateFlow(context)` (`HeadingFlow.kt:115`) is the important one:
1. an `initialFlow` that emits the **persisted last known position** (DataStore key `lastKnownPosition`) so fields show something instantly at boot; if none, it takes the first value from the live LOCATION stream;
2. concatenated (`concatenate(initialFlow, gpsFlow)`) with the live stream:
```kotlin
streamDataFlow(DataType.Type.LOCATION).mapNotNull { it as? StreamState.Streaming }.mapNotNull { dp ->
    val lat = dp.dataPoint.values[DataType.Field.LOC_LATITUDE]
    val lng = dp.dataPoint.values[DataType.Field.LOC_LONGITUDE]
    val orientation = dp.dataPoint.values[DataType.Field.LOC_BEARING]
    val accuracy = dp.dataPoint.values[DataType.Field.LOC_ACCURACY]
    if (lat != null && lng != null && accuracy != null && accuracy < 500) GpsCoordinates(lat, lng, orientation) else null
}
```
   (**accuracy < 500 m** gate);
3. rounded per settings, then `.dropNullsIfNullEncountered()` — a custom operator that allows one leading `null` (to render "no GPS") but suppresses later nulls so a transient loss doesn't blank the field.

A separate always-on job persists the position at most once a minute:
```kotlin
// HeadingFlow.kt:103
suspend fun KarooSystemService.updateLastKnownGps(context: Context) {
    while (true) {
        getGpsCoordinateFlow(context).filterNotNull().throttle(60 * 1_000).collect { saveLastKnownPosition(context, it) }
        delay(1_000)   // restart the flow if it ever completes
    }
}
```

Heading itself is just the GPS bearing (`LOC_BEARING`); there is **no magnetometer/compass sensor use** for the wind fields (`CompassDataType` is a separate needle widget).

### 5.2 Relative wind direction

```kotlin
// HeadingFlow.kt:44-69
fun KarooSystemService.getRelativeHeadingFlow(context: Context): Flow<HeadingResponse> =
    getHeadingFlow(this, context).combine(context.streamCurrentWeatherData(this)) { bearing, data -> bearing to data }
        .map { (bearing, data) -> when {
            bearing is HeadingResponse.Value && data != null -> {
                val windBearing = data.windDirection + 180        // meteo "from" -> "towards"
                HeadingResponse.Value(signedAngleDifference(bearing.diff, windBearing))
            }
            bearing is HeadingResponse.NoGps -> HeadingResponse.NoGps
            bearing is HeadingResponse.NoWeatherData || data == null -> HeadingResponse.NoWeatherData
            else -> bearing } }
```

### 5.3 `signedAngleDifference` (`util/AngleDifference.kt:21`)

```kotlin
fun signedAngleDifference(angle1: Double, angle2: Double): Double {
    val a1 = angle1 % 360; val a2 = angle2 % 360
    var diff = abs(a1 - a2)
    val sign = if (a1 < a2) { if (diff > 180.0) -1 else 1 } else { if (diff > 180.0) 1 else -1 }
    if (diff > 180.0) diff = 360.0 - diff
    return sign * diff       // in (-180, 180]
}
```

### 5.4 Headwind speed

Used identically in three places (`HeadwindSpeedDataType.kt:61`, `TailwindAndRideSpeedDataType.kt:264`, `HeadwindForecastDataType.kt:124`):

```kotlin
val headwindSpeed = cos((relativeWindDirection + 180) * Math.PI / 180.0) * windSpeed   // m/s
// > 0 = headwind (slowing you), < 0 = tailwind
```
Display sign is inverted for the label: `sign = if (headwindSpeed < 0) "+" else "-"` so a tailwind reads "+5".

For the **forecast along the route**, the "riding direction" is not the live bearing but the route tangent, sampled with Turf (`HeadwindForecastDataType.kt:104-124`):
```kotlin
val coordsAlongRoute     = TurfMeasurement.along(route.routePolyline, distanceAlongRoute,     TurfConstants.UNIT_METERS)
val nextCoordsAlongRoute = TurfMeasurement.along(route.routePolyline, distanceAlongRoute + 5, TurfConstants.UNIT_METERS)
val bearingAlongRoute    = TurfMeasurement.bearing(coordsAlongRoute, nextCoordsAlongRoute)      // +5 m lookahead
val diff = signedAngleDifference(bearingAlongRoute, interpolatedWeather.windDirection + 180)
val headwindSpeed = cos((diff + 180) * Math.PI / 180.0) * interpolatedWeather.windSpeed
```
sampled `HEADWIND_SAMPLE_COUNT = 70` times across the graph, with `lerpWeather` between the bracketing hourly samples. Every Turf call is individually wrapped in try/catch returning `null` (polyline edge cases throw).

### 5.5 Colour ramp for wind (nice, reusable)

```kotlin
// datatypes/TailwindAndRideSpeedDataType.kt:67-85
fun interpolateColor(c1: Color, c2: Color, lo: Double, hi: Double, v: Double): Color {
    val f = if (hi == lo) 0.0 else ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)
    return Color(ColorUtils.blendARGB(c1.toArgb(), c2.toArgb(), f.toFloat()))
}
fun interpolateWindColor(windSpeedInKmh: Double, night: Boolean, context: Context): Color = when {
    windSpeedInKmh <= -10 -> green                                   // strong tailwind
    windSpeedInKmh >= 15  -> red                                     // strong headwind
    windSpeedInKmh in -10.0..0.0 -> interpolateColor(green, default, -10.0, 0.0, windSpeedInKmh)
    windSpeedInKmh in   0.0..10.0 -> interpolateColor(default, orange, 0.0, 10.0, windSpeedInKmh)
    else -> interpolateColor(orange, red, 10.0, 15.0, windSpeedInKmh)
}
```
with separate day/night palettes taken from `res/values/colors.xml` (`green #00ff00` / `hGreen #008000`, `orange #ff9930` / `hOrange #BB4300`, `red #FF5454` / `hRed #A30000` — the `h*` variants are the darker "day" ones).

### 5.6 Arrow bitmap by bearing (`datatypes/HeadwindDirectionView.kt:51`)

```kotlin
fun getArrowBitmapByBearing(baseBitmap: Bitmap, bearing: Int): Bitmap {
    val bearingRounded = (((bearing + 360) / 10.0).roundToInt() * 10) % 360   // 10° quantisation
    val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.save()
    canvas.scale(128f / baseBitmap.width, 128f / baseBitmap.height, 64f, 64f)
    canvas.rotate(bearingRounded.toFloat(), 64f, 64f)
    canvas.drawBitmap(baseBitmap, (128 - baseBitmap.width) / 2f, (128 - baseBitmap.height) / 2f, paint)
    canvas.restore()
    return bitmap
}
```
Base bitmaps are decoded **once per `startView`** (`BitmapFactory.decodeResource(context.resources, R.drawable.arrow_0 / R.drawable.circle)`), not per frame. Tinting for dark mode is done with `ColorFilter.tint(ColorProvider(Color.Black, Color.White))` on the Glance `Image`, so one white/black asset serves both themes.

---

## 6. Settings UI, DataStore, activity structure

### 6.1 DataStore pattern (`DataStore.kt`)

```kotlin
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Log.w(TAG, "Error reading settings, using default values"); emptyPreferences()
    })

val jsonWithUnknownKeys = Json { ignoreUnknownKeys = true }

val settingsKey        = stringPreferencesKey("settings")
val widgetSettingsKey  = stringPreferencesKey("widgetSettings")
val currentDataKey     = stringPreferencesKey("currentForecastsUnified")
val statsKey           = stringPreferencesKey("stats")
val lastKnownPositionKey = stringPreferencesKey("lastKnownPosition")

suspend fun saveSettings(context: Context, settings: HeadwindSettings) =
    context.dataStore.edit { it[settingsKey] = Json.encodeToString(settings) }

fun Context.streamSettings(karooSystemService: KarooSystemService): Flow<HeadwindSettings> =
    dataStore.data.map { prefs ->
        try { jsonWithUnknownKeys.decodeFromString<HeadwindSettings>(prefs[settingsKey] ?: HeadwindSettings.defaultSettings) }
        catch (e: Throwable) { Log.e(TAG, "Failed to read preferences", e)
                               jsonWithUnknownKeys.decodeFromString(HeadwindSettings.defaultSettings) }
    }.distinctUntilChanged()
```

The whole pattern in one line: **one `stringPreferencesKey` per concern, holding a `Json.encodeToString` of an `@Serializable data class` with defaults for every field**, read through `ignoreUnknownKeys` + try/catch + `distinctUntilChanged`. Schema migration is free (add a field with a default). Note the field-order gotcha: renaming the DataStore key (`currentForecastsUnified`) is how they versioned an incompatible payload change.

Defaults are precomputed as JSON strings on the companion:
```kotlin
@Serializable data class HeadwindSettings(
    val welcomeDialogAccepted: Boolean = false,
    val roundLocationTo: RoundLocationSetting = RoundLocationSetting.KM_3,
    val forecastedKmPerHour: Int = 20, val forecastedMilesPerHour: Int = 12,
    val lastUpdateRequested: Long? = null,          // manual refresh poke
    val showDistanceInForecast: Boolean = true,
    val weatherProvider: WeatherDataProvider = WeatherDataProvider.OPEN_METEO,
    val openWeatherMapApiKey: String = "",
    val refreshRate: RefreshRate = RefreshRate.STANDARD,
    val windUnit: WindUnit? = null,                 // null = follow karoo profile
) { companion object { val defaultSettings = Json.encodeToString(HeadwindSettings()) }
    fun getForecastMetersPerHour(isImperial: Boolean) = if (isImperial) forecastedMilesPerHour * 1609 else forecastedKmPerHour * 1000
    fun getWindUnit(isImperial: Boolean) = windUnit ?: defaultWindUnit(isImperial) }
```
`windUnit: WindUnit? = null` meaning "inherit the Karoo profile" is a good pattern for our temperature/wind/precip unit settings.

### 6.2 Screens

```
MainActivity (ComponentActivity)
  └ AppTheme { MainScreen(close = ::finish) }
        TabRow: "Live" | "Setup" | "Windy"
          0 -> WeatherScreen   (current + forecast cards, last-update stats, BarChart)
          1 -> SettingsScreen  (dropdowns + text fields + switches)
          2 -> WindyScreen     (embedded windy.com WebView; K2-specific handling at WindyScreen.kt:149)
        PullToRefreshBox wraps everything; onRefresh writes settings.lastUpdateRequested
        Welcome AlertDialog shown until settings.welcomeDialogAccepted (gates all downloads)
        Custom back button Image (bottom-start) — Karoo has no system back bar
```

`MainScreen` owns the `KarooSystemService` lifecycle from Compose:
```kotlin
val karooSystem = remember { KarooSystemService(ctx) }
LaunchedEffect(Unit) { karooSystem.connect { connected -> karooConnected = connected } }
DisposableEffect(Unit) { onDispose { karooSystem.disconnect() } }
```

`SettingsScreen` persists on every change *and* on dispose:
```kotlin
DisposableEffect(Unit) { onDispose { runBlocking { updateSettings() } } }   // ⚠ runBlocking on main
BackHandler { coroutineScope.launch { updateSettings(); onFinish() } }
```
Text fields save on focus loss (`onFocusChanged` + a `wasFocused` latch) rather than per keystroke; dropdowns save immediately. Values are clamped on save: `forecastedKmPerHour.toIntOrNull()?.coerceIn(5, 50) ?: 20`.

Reusable `Dropdown` wrapper (`screens/Dropdown.kt`) — `ExposedDropdownMenuBox` + read-only `OutlinedTextField` over a `data class DropdownOption(val id: String, val name: String)`; enums expose `id`/`label` so they map 1:1.

### 6.3 Theme

```kotlin
// theme/Theme.kt:24
@Composable fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF214559), secondary = Color(0xFF636363), tertiary = Color(0xFFFEF69A)
    ), content = content)
}
```
Light scheme only for the app UI. Data-field widgets do their own day/night via Glance `ColorProvider(day, night)` and `isNightMode(context)`:
```kotlin
// screens/LineGraph.kt:33
fun isNightMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
```

---

## 7. WMO weather codes → icons

```kotlin
// weatherprovider/WeatherInterpretation.kt:19
enum class WeatherInterpretation {
    CLEAR, CLOUDY, RAINY, SNOWY, DRIZZLE, THUNDERSTORM, UNKNOWN;
    companion object {
        fun fromWeatherCode(code: Int?): WeatherInterpretation = when (code) {
            0                                          -> CLEAR
            1, 2, 3                                    -> CLOUDY
            45, 48, 61, 63, 65, 66, 67, 80, 81, 82     -> RAINY
            71, 73, 75, 77, 85, 86                     -> SNOWY
            51, 53, 55, 56, 57                         -> DRIZZLE
            95, 96, 99                                 -> THUNDERSTORM
            else                                       -> UNKNOWN
        }
        fun getKnownWeatherCodes(): Set<Int> = setOf(0,1,2,3,45,48,61,63,65,66,67,80,81,82,71,73,75,77,85,86,51,53,55,56,57,95,96,99)
    }
}
```

Full mapping table (with the standard WMO meaning, and the bucket this app assigns):

| WMO code | Meaning | Bucket |
|---|---|---|
| 0 | Clear sky | CLEAR |
| 1, 2, 3 | Mainly clear / partly cloudy / overcast | CLOUDY |
| **45, 48** | **Fog, depositing rime fog** | **RAINY** ← misclassified; consider a FOG bucket |
| 51, 53, 55 | Drizzle light/moderate/dense | DRIZZLE |
| 56, 57 | Freezing drizzle light/dense | DRIZZLE |
| 61, 63, 65 | Rain slight/moderate/heavy | RAINY |
| 66, 67 | Freezing rain light/heavy | RAINY |
| 71, 73, 75 | Snow fall slight/moderate/heavy | SNOWY |
| 77 | Snow grains | SNOWY |
| 80, 81, 82 | Rain showers slight/moderate/violent | RAINY |
| 85, 86 | Snow showers slight/heavy | SNOWY |
| 95 | Thunderstorm slight/moderate | THUNDERSTORM |
| 96, 99 | Thunderstorm with slight/heavy hail | THUNDERSTORM |
| anything else | — | UNKNOWN |

Icon selection (`datatypes/WeatherView.kt:64`) — day/night split only for CLEAR:

```kotlin
fun getWeatherIcon(interpretation: WeatherInterpretation, isNight: Boolean): Int = when (interpretation) {
    CLEAR        -> if (isNight) R.drawable.crescent_moon else R.drawable.sun
    CLOUDY       -> R.drawable.cloud
    RAINY        -> R.drawable.cloud_with_rain
    SNOWY        -> R.drawable.cloud_with_snow
    DRIZZLE      -> R.drawable.cloud_with_light_rain
    THUNDERSTORM -> R.drawable.cloud_with_lightning_and_rain
    UNKNOWN      -> R.drawable.question_mark_regular_240
}
```
`isNight` comes from Open-Meteo's `is_day == 0` (per-hour, `is_day` requested in both `current` and `hourly`) — no sunrise/sunset computation needed. Icons are Noto Color Emoji glyphs exported as vector drawables (SIL OFL) + boxicons (MIT); see `icon_credits.txt`. There is an unused `R.drawable.cloud_with_lightning` (no hail/thunder-without-rain bucket).

---

## 8. Pitfalls & gotchas observed

**Karoo platform**
1. **`hardwareType` differs (K2 vs K3)** and is used for cadence only (`Views.kt:100-106`, K2 gets 1s/2s/5s/10s, K3 500ms/1s/3s/10s) and for the WebView path (`WindyScreen.kt:149`). `karooSystem.hardwareType` is only valid **after** `connect{}` fires — `SettingsScreen.kt:113` reads it inside the connect callback for that reason.
2. **HTTP only through Karoo.** `OnHttpResponse.MakeHttpRequest` is the only network path; there's no OkHttp/Ktor at all. `waitForConnection = false` + external retry beats waiting on the queue. On K2 traffic goes over the SIM, on K3 over the phone (companion app) — the extension code is identical, but expect failures/timeouts to be *normal*, hence the infinite `retry`.
3. **Keep responses small.** The 10-location cap and `forecast_hours=12` are the guards. Multi-location Open-Meteo changes the JSON shape from object to array — branch on `coordinates.size == 1`.
4. **`emitter.setCancellable { job.cancel() }` on every `startStream`/`startView`** — otherwise coroutines leak per field add/remove. Two jobs are used for views: a `configJob` that emits `UpdateGraphicConfig` then `awaitCancellation()` (so the config isn't garbage-collected/reset), and a `viewJob` that renders.
5. **`streamDatatypeIsVisible`** — Karoo starts views for fields on *inactive* pages too; filter on `ActiveRidePage` before doing bitmap work.
6. **`ShowCustomStreamState("", null)` must be emitted** at view start or Karoo's own "no data" overlay covers the custom view.
7. **KarooSystemService connect/disconnect** happens in `KarooExtension.onCreate` / `onDestroy` for the service, and in `LaunchedEffect`/`DisposableEffect` for each Compose screen (`MainScreen`, `SettingsScreen` each create their **own** `KarooSystemService`; nested screens each connect/disconnect independently — mildly wasteful but avoids lifetime coupling).
8. **Service restart resilience**: no in-memory cache is trusted. Last position and last weather response are persisted to DataStore, and `getGpsCoordinateFlow` seeds from the persisted position so fields render immediately after a restart instead of showing "No GPS". `updateLastKnownGps` runs in a `while(true) { flow.collect(); delay(1_000) }` loop so the job survives flow completion.
9. **Cross-extension contract**: `TYPE_EXT::karoo-headwind::<typeId>`; sentinel values `-1.0` no GPS, `-2.0` no weather data, `-3.0` not set up (`HeadwindDirectionDataType` companion). Sentinels-in-band is fragile but it's the documented public API — if we expose fields, prefer `StreamState.NotAvailable` (as `BaseDataType` does).

**Code-level traps to avoid in our implementation**
10. `getLocalProperty` in `settings.gradle.kts` calls `error("File from not found")` when `local.properties` is absent — breaks fresh clones/CI without env vars.
11. `generateManifest` rewrites `AndroidManifest.xml` in place (`$BASE_URL$` substitution) — use `manifestPlaceholders` instead.
12. `SettingsScreen`'s `DisposableEffect { onDispose { runBlocking { updateSettings() } } }` blocks the main thread on a DataStore write.
13. `WeatherInterpretation` maps **fog (45/48) to RAINY** and has no FOG/HAIL bucket.
14. `GpsCoordinates.round` uses 111 km/deg for longitude too → grid is latitude-dependent.
15. `retry(Long.MAX_VALUE) { delay(2.minutes); true }` retries *everything*, including permanent 401s from OpenWeatherMap — a bad API key means an infinite 2-minute retry loop. Classify errors (`WeatherProviderException.statusCode`) before retrying.
16. Empty `proguard-rules.pro` with `isMinifyEnabled = true` in release — verify serialization/Glance/karoo-ext survive R8 before shipping.
17. `distinctUntilChangedBy` includes the whole `settings` object, so **any** settings write (including the `lastUpdateRequested` poke) triggers a full weather refetch — intentional here, but it means an unrelated setting edit costs an HTTP request.
18. `combine(...)` with 6+ flows must use the vararg/array overload (`{ data -> data[0] as X … }`) — see `ForecastDataType.kt:237-253`; unchecked casts, easy to break when reordering.

---

## 9. Shortlist of what to reuse for `karoo-weather`

- Version catalog & module layout (§1.3), `manifest.json` generation (fixed per §8.11), CI shape.
- `Extensions.kt` flow helpers verbatim in spirit: `streamDataFlow`, `streamNavigationState`, `streamRideState`, `streamActiveRidePage`, `streamDatatypeIsVisible`, `throttle`.
- `BaseDataType` + `getFormatDataType()` for all numeric fields; `GlanceRemoteViews` + `configJob`/`viewJob` + `ShowCustomStreamState` skeleton for graphical ones.
- The DataStore-of-JSON pattern (§6.1) with `ignoreUnknownKeys` and per-concern keys.
- Provider-neutral `WeatherData` in canonical units + conversion at render time (§3.1, `util/Conversion.kt`).
- The rounded-GPS-grid refetch trigger + hourly `transformLatest` timer + `retry` (§3.4) — but classify errors.
- Route sampling aligned to full clock hours with a 10-point cap and single multi-location request (§4.2).
- `signedAngleDifference`, `cos(θ+180)·v` headwind, `lerpAngle`/`lerpWeather`, `getArrowBitmapByBearing`, `interpolateWindColor`.
