# Karoo SDK (karoo-ext 1.1.9) — API deep-dive for `karoo-weather`

All paths below are relative to
`<ref>/`.

Library root: `karoo-ext/lib/src/main/kotlin/io/hammerhead/karooext/` (referred to below as `lib/.../karooext/`).
Sample app: `karoo-ext/app/src/main/kotlin/io/hammerhead/sampleext/`.
HTTP engine: `ktor-client-karoo/lib/src/main/java/de/jonasfranz/ktor/client/karoo/`.

Version pinned in `karoo-ext/lib/build.gradle.kts:15` → `val libVersion = "1.1.9"`.
`compileSdk = 34`, `minSdk = 23`, `jvmTarget = "1.8"` (`karoo-ext/lib/build.gradle.kts:25,28,44`).
The sample app uses `compileSdk = 35`, `targetSdk = 34`, Glance `1.1.1` (`karoo-ext/gradle/libs.versions.toml:9`).

---

## 1. KarooExtension service

### 1.1 Class & lifecycle

`lib/.../karooext/extension/KarooExtension.kt:46`

```kotlin
abstract class KarooExtension(
    /**
     * Extension ID, matching [ExtensionInfo.id] from extension manifest.
     *
     * This is different from your application id (com.something) and cannot contain '.'
     */
    val extension: String,
    /**
     * Extension version (separate from [EXT_LIB_VERSION]).
     */
    val version: String,
) : Service() {
```

- It **is an Android `Service`**. `onBind` is `final` (`KarooExtension.kt:67`) and returns an AIDL `IKarooExtension.Stub`. Karoo OS binds to it; you do your own setup in `onCreate()` / teardown in `onDestroy()`.
- Constructor `init` asserts `extension` contains no `'.'` (`KarooExtension.kt:60-62`).
- Karoo OS calls, per binder (`KarooExtension.kt:72-160`), each with a String `id` used to cancel later:
  `startScan/stopScan`, `connectDevice/disconnectDevice`, `startStream/stopStream`,
  `startView/stopView`, `startMap/stopMap`, `startFit/stopFit`, `onBonusAction`.
- All `stopXxx` calls do `emitters.remove(id)?.cancel()` → this invokes the lambda you registered with
  `emitter.setCancellable { ... }`. **Always call `setCancellable` to cancel your coroutine jobs**, otherwise your
  jobs leak for the process lifetime.
- `startStream(id, typeId, handler)` and `startView(id, typeId, config, handler)` dispatch to
  `types.firstOrNull { it.typeId == typeId }` — i.e. the `types` list is the only routing table.

Open members you can override:

| member | signature | notes |
|---|---|---|
| `types` | `open val types: List<DataTypeImpl> = emptyList()` | must match `typeId`s in `extension_info.xml` (`KarooExtension.kt:167`) |
| `startScan` | `open fun startScan(emitter: Emitter<Device>)` | only called if `scansDevices="true"` |
| `connectDevice` | `open fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>)` | |
| `startMap` | `open fun startMap(emitter: Emitter<MapEffect>)` | only if `mapLayer="true"`; since 1.1.3 |
| `startFit` | `open fun startFit(emitter: Emitter<FitEffect>)` | only if `fitFile="true"` |
| `onBonusAction` | `open fun onBonusAction(actionId: String)` | since 1.1.7 |

**For karoo-weather:** we need `types` (temperature/wind/rain data fields) and, if we want icons drawn along the
route on the map, `mapLayer="true"` + `startMap`.

Lifecycle pattern used by the sample (`app/.../extension/SampleExtension.kt:283-303`):

```kotlin
override fun onCreate() {
    super.onCreate()
    serviceJob = CoroutineScope(Dispatchers.IO).launch {
        karooSystem.connect { connected ->
            if (connected) { /* dispatch effects, subscribe */ }
        }
        launch { /* long-running per-ride logic */ }
    }
}
```
(and `onDestroy` should `serviceJob?.cancel()` + `karooSystem.disconnect()`).

Note the sample extension **also holds its own `KarooSystemService`** (injected via Hilt,
`SampleExtension.kt` `@Inject lateinit var karooSystem: KarooSystemService`) — the extension service is both a
provider (data types) and a consumer (events) at the same time. That is exactly what karoo-weather needs: the
extension consumes `OnNavigationState` + `OnLocationChanged` and provides weather data types.

### 1.2 `extension_info.xml` schema

Full sample (`karoo-ext/app/src/main/res/xml/extension_info.xml`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<ExtensionInfo
    displayName="@string/extension_name"
    icon="@drawable/ic_sample"
    id="sample"
    scansDevices="true"
    mapLayer="true"
    fitFile="true">
    <DataType
        description="@string/custom_speed_description"
        displayName="@string/custom_speed"
        graphical="true"
        icon="@drawable/ic_speed"
        typeId="custom-speed" />
    <DataType
        description="@string/bespoke_description"
        displayName="@string/bespoke"
        graphical="false"
        icon="@drawable/ic_sample"
        typeId="bespoke" />
    <BonusAction
        displayName="@string/action_open"
        actionId="open" />
</ExtensionInfo>
```

Attributes are parsed into `ExtensionInfo` (`lib/.../karooext/models/ExtensionInfo.kt`):

```kotlin
data class ExtensionInfo(
    val id: String,
    val displayName: String,
    val icon: Drawable,
    val scansDevices: Boolean,
    val mapLayer: Boolean,   // @since 1.1.3
    val fitFile: Boolean,
    val dataTypes: List<DataType>,
    val bonusActions: List<BonusAction>,  // @since 1.1.7
)
```

`<DataType>` → `lib/.../karooext/models/DataType.kt:37`:

```kotlin
data class DataType(
    val extension: String,
    val typeId: String,
    val displayName: String,
    val description: String,
    val graphical: Boolean,
    val icon: Drawable,
)
```

- `typeId` — extension-local id (no `::`). Full data type id is
  `DataType.dataTypeId(extension, typeId)` = `"TYPE_EXT::$extension::$typeId"` (`DataType.kt:1830-1832`,
  separator `"::"` at `DataType.kt:1857`). Reverse parse: `DataType.fromDataType(id): Pair<extension, typeId>?`.
- `graphical="true"` ⇒ you draw the field yourself via `RemoteViews` in `startView`. `graphical="false"` ⇒ Karoo
  renders the standard numeric field from your `startStream` values (see `BespokeDataType.kt`, which implements
  neither method).
- `displayName` / `description` / `icon` are string/drawable **resources** and are what the user sees in the page
  editor. Both are resolved by Karoo OS in its own process, so they must be real resources in your APK.

`<BonusAction>` → `lib/.../karooext/models/BonusAction.kt`:

```kotlin
data class BonusAction(
    val extension: String,
    val actionId: String,
    val displayName: String,
)
```
A bonus action can be bound by the user to a controller button; the press arrives at `onBonusAction(actionId)`.

### 1.3 Manifest declaration

`karoo-ext/app/src/main/AndroidManifest.xml:26-43`:

```xml
<service
    android:name=".extension.SampleExtension"
    android:exported="true"
    tools:ignore="ExportedService">
    <!-- Required for this extension to be discovered by the Karoo System -->
    <intent-filter>
        <action android:name="io.hammerhead.karooext.KAROO_EXTENSION" />
    </intent-filter>
    <!-- Required for this extension to define resources and definitions -->
    <meta-data
        android:name="io.hammerhead.karooext.EXTENSION_INFO"
        android:resource="@xml/extension_info" />
</service>

<!-- Provide Karoo System with information about delivery of your app -->
<meta-data
    android:name="io.hammerhead.karooext.MANIFEST_URL"
    android:value="https://github.com/hammerheadnav/karoo-ext/releases/latest/download/manifest.json" />
```

Constants (`lib/.../karooext/Constants.kt`):
`KAROO_EXTENSION_INTENT_FILTER = "io.hammerhead.karooext.KAROO_EXTENSION"`,
`EXTENSION_INFO_META_KEY = "io.hammerhead.karooext.EXTENSION_INFO"`,
`MANIFEST_URL_META = "io.hammerhead.karooext.MANIFEST_URL"`,
`EXT_LIB_VERSION = BuildConfig.LIB_VERSION`.

The `MANIFEST_URL` points to a JSON matching `KarooAppManifest`
(`lib/.../karooext/models/KarooAppManifest.kt`) used by Karoo's app store for sideload/update:
fields `label, packageName, latestApkUrl, latestVersion, latestVersionCode, iconUrl?, developer?, description?,
releaseNotes?, screenshotUrls?, tags?`. **`tags` explicitly documents `"weather"` as a supported tag** —
karoo-weather should set `tags = ["weather"]`.

---

## 2. `DataTypeImpl`

`lib/.../karooext/extension/DataTypeImpl.kt:39`:

```kotlin
abstract class DataTypeImpl(
    val extension: String,
    val typeId: String,
) {
    val dataTypeId: String
        get() = DataType.dataTypeId(extension, typeId)

    open fun startStream(emitter: Emitter<StreamState>) {}

    open fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {}
}
```

KDoc on the class: *"If `RemoteViews` are being updated in `startView`, `DataType.graphical` should be true."*
KDoc on `startStream`: *"Streaming will start as soon as a UI element or another streaming data type subscribes."*
KDoc on `startView`: *"Start is called when a view is attached to a UI (ride app or pages app)."*

### 2.1 `startStream` — `StreamState` / `DataPoint` / field ids

`lib/.../karooext/models/StreamState.kt`:

```kotlin
@Serializable
sealed class StreamState {
    @Serializable data object Idle : StreamState()
    @Serializable data class Streaming(val dataPoint: DataPoint) : StreamState()
    @Serializable data object Searching : StreamState()
    @Serializable data object NotAvailable : StreamState()
}
```

`lib/.../karooext/models/DataPoint.kt`:

```kotlin
@Serializable
data class DataPoint(
    val dataTypeId: String,          // FULL id, not the extension-local typeId
    val values: Map<String, Double> = emptyMap(),
    val sourceId: String? = null,
) {
    val singleValue: Double?
        get() = values.values.firstOrNull()
}
```

Everything is a `Double`. For a single-value custom field use the generic field key
`DataType.Field.SINGLE = "FIELD_SINGLE_ID"` (`DataType.kt:1303`). Sample
(`app/.../extension/CustomSpeedDataType.kt:45-69`):

```kotlin
override fun startStream(emitter: Emitter<StreamState>) {
    val job = CoroutineScope(Dispatchers.IO).launch {
        karooSystem.streamDataFlow(DataType.Type.SPEED).collect {
            when (it) {
                is StreamState.Streaming -> emitter.onNext(
                    it.copy(dataPoint = it.dataPoint.copy(
                        dataTypeId = dataTypeId,
                        values = mapOf(DataType.Field.SINGLE to it.dataPoint.singleValue!!),
                    )),
                )
                else -> emitter.onNext(it)
            }
        }
    }
    emitter.setCancellable { job.cancel() }
}
```

Field ids relevant to weather (`DataType.Field`, `DataType.kt:1252+`):
`FIELD_SINGLE_ID`, `FIELD_TEMPERATURE_ID`, `FIELD_LOC_LATITUDE_ID`, `FIELD_LOC_LONGITUDE_ID`,
`FIELD_LOC_BEARING_ID`, `FIELD_LOC_ACCURACY_ID`, `FIELD_HEADING_ID`, `FIELD_ELEVATION_GRADE_ID`,
`FIELD_DISTANCE_ID`, `FIELD_DISTANCE_TO_DESTINATION_ID`, `FIELD_TIME_TO_DESTINATION_ID`,
`FIELD_TIME_OF_ARRIVAL_ID`, `FIELD_ASCENT_REMAINING_ID`, `FIELD_NAVIGATION_STATE_ID`, `FIELD_ON_ROUTE_ID`.

Built-in `DataType.Type` ids you can stream *from* Karoo for weather logic:
`TYPE_TEMPERATURE_ID` (air temperature, `DataType.kt:986`), `TYPE_LOCATION_ID` (lat/lng/bearing/accuracy/altitude/speed,
`DataType.kt:992`), `TYPE_HEADING_ID` (`:1108`), `TYPE_SPEED_ID`, `TYPE_DISTANCE_ID`,
`TYPE_DISTANCE_TO_DESTINATION_ID` (`:1072`), `TYPE_TIME_TO_DESTINATION_ID` (`:1096`),
`TYPE_TIME_OF_ARRIVAL_ID` (`:1102`), `TYPE_ELEVATION_REMAINING_ID` (`:1084`),
`TYPE_TIME_TO_SUNSET_ID` / `TYPE_TIME_TO_SUNRISE_ID` (`:830-848`), `TYPE_ELEVATION_GRADE_ID` (`:700`).

`TYPE_DISTANCE_TO_DESTINATION_ID` is documented as carrying fields
`[DISTANCE_TO_DESTINATION, NAVIGATION_STATE, REROUTING_ENABLED, ON_ROUTE]` — this is the cheapest way to get
"distance remaining on the loaded route" and whether the rider is on route, as a stream.

Helper to consume streams (sample, `app/.../extension/Extensions.kt:12-21`) — copy this into karoo-weather:

```kotlin
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val listenerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
        trySendBlocking(event.state)
    }
    awaitClose { removeConsumer(listenerId) }
}

inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> = callbackFlow {
    val listenerId = addConsumer<T> { trySend(it) }
    awaitClose { removeConsumer(listenerId) }
}
```

### 2.2 `startView` — `ViewConfig`

`lib/.../karooext/models/ViewConfig.kt:28`:

```kotlin
@Serializable
data class ViewConfig(
    /**
     * Pair of column span x row span
     * Total grid size is 60, so Pair(60, 15) would indicate 1/4 height, full width
     */
    val gridSize: Pair<Int, Int>,
    /**
     * Size (in pixels) of the current view as configured in the user profile
     */
    val viewSize: Pair<Int, Int>,
    /**
     * Font size used in standard numeric view of this grid size in sp
     */
    val textSize: Int,
    /**
     * User-configured alignment of this data field
     * @since 1.1.2
     */
    val alignment: Alignment = Alignment.RIGHT,
    /**
     * Whether the user has configured their data field to include boundaries
     * @since 1.1.2
     */
    val boundariesEnabled: Boolean = false,
    /**
     * Whether the view is in preview mode (page editing) or in ride
     * @since 1.1.2
     */
    val preview: Boolean = false,
) {
    @Serializable
    enum class Alignment { LEFT, CENTER, RIGHT }
}
```

`RIGHT` is the default and is also what pre-1.1.2 devices report. See §8 for grid-size values.

### 2.3 Rendering the view — `RemoteViews` (+ Glance)

The transport is `RemoteViews` because the view is rendered in Karoo OS's process (README:222-224:
*"Where third-party views are needed by Karoo OS, RemoteViews ... allow describing a view hierarchy that can safely
be displayed in another process."*).

`ViewEmitter` (`lib/.../karooext/internal/Emitter.kt:110-136`) — **verbatim, note the rate limit**:

```kotlin
/**
 * Special [Emitter] that includes a function to update [RemoteViews] in addition
 * to [ViewEvent]s.
 *
 * [updateView] can only be called at 1Hz, views emitted more frequently will be dropped.
 */
class ViewEmitter(
    private val packageName: String,
    private val handler: IHandler,
    private val eventEmitter: Emitter<ViewEvent> = Emitter.create<ViewEvent>(packageName, handler),
) : Emitter<ViewEvent> by eventEmitter {
    private var lastViewUpdate: Long = 0

    fun updateView(view: RemoteViews) {
        val now = System.currentTimeMillis()
        // Intention is to limit to ~1Hz with 100ms for slop
        if (now - lastViewUpdate < 900) {
            Timber.w("ViewEmitter: ignoring updateView, too soon")
            return
        }
        lastViewUpdate = now
        val bundle = Bundle()
        bundle.putParcelable("view", view)
        bundle.putString(BUNDLE_PACKAGE, packageName)
        handler.onNext(bundle)
    }
}
```

**Hard constraint: `updateView` is throttled to ~1 Hz (silently dropped if < 900 ms apart).** For weather that is
irrelevant — we would refresh a field every few minutes — but any "animate" idea is out.

The sample builds the `RemoteViews` with **androidx.glance** `GlanceRemoteViews`
(`app/.../extension/CustomSpeedDataType.kt:38-98`):

```kotlin
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class CustomSpeedDataType(private val karooSystem: KarooSystemService, extension: String) :
    DataTypeImpl(extension, "custom-speed") {
    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val configJob = CoroutineScope(Dispatchers.IO).launch {
            // Show numeric speed data numerically
            emitter.onNext(UpdateGraphicConfig(formatDataTypeId = DataType.Type.SPEED))
            // Toggle header config forever
            repeat(Int.MAX_VALUE) {
                emitter.onNext(UpdateGraphicConfig(showHeader = it % 2 == 0))
                delay(2000)
            }
            awaitCancellation()
        }
        val viewJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.streamDataFlow(DataType.Type.SPEED).collect {
                val speed = (it as? StreamState.Streaming)?.dataPoint?.singleValue?.toInt() ?: 0
                val result = glance.compose(context, DpSize.Unspecified) {
                    CustomSpeed(speed, config.alignment)
                }
                emitter.updateView(result.remoteViews)
            }
        }
        emitter.setCancellable { configJob.cancel(); viewJob.cancel() }
    }
}
```

Gradle deps needed (`karoo-ext/app/build.gradle.kts:72-75`): `androidx.glance:glance-appwidget`,
`glance-preview`, `glance-appwidget-preview`, version `1.1.1`. Compose is enabled with
`buildFeatures { compose = true }` and `kotlinCompilerExtensionVersion = "1.5.14"`.

Glance composables are widget-flavoured Compose (`GlanceModifier`, `ImageProvider`, `ColorProvider`) — see
`app/.../extension/CustomSpeed.kt`. **Note "headwind"-style extensions do the same thing**: there is nothing in
karoo-ext beyond `RemoteViews`; Glance is only a convenience for producing them and is explicitly *not* required
(README:172-173: *"While this sample app uses Jetpack Compose, Hilt, ViewModels, and Glance, these are not strict
dependencies"*). A plain `RemoteViews(packageName, R.layout.my_field)` + `setTextViewText`/`setImageViewResource`
works and is lighter.

**How to update a view periodically**: `startView` is a "fire and forget" call — launch a coroutine, loop
(`while (true) { … ; delay(n) }` or collect a flow), call `emitter.updateView(...)` on each tick, and register
`emitter.setCancellable { job.cancel() }`. There is no pull/refresh callback from Karoo.

### 2.4 `ViewEvent` effects

`lib/.../karooext/models/ViewEvent.kt` — verbatim:

```kotlin
@Serializable
sealed class ViewEvent

/**
 * Updates the way the graphic data field is shown in the view.
 *
 * Can be sparsely populated to preserve values from previous.
 */
@Serializable
data class UpdateGraphicConfig(
    /**
     * Show data type icon and name.
     * If never non-null, defaults to true
     */
    val showHeader: Boolean? = null,
    /**
     * If [Field.SINGLE] is present in streaming data point (from startStream),
     * control how it is formatted and rendered.
     * ...
     * If never included, defaults to null and streaming data is not rendered.
     */
    val formatDataTypeId: String? = null,
) : ViewEvent()

/**
 * Display an alternate message in the standard stream container.
 */
@Serializable
data class ShowCustomStreamState(
    val message: String?,
    @ColorInt val color: Int?,
) : ViewEvent()

/**
 * Update the way a numeric data types are shown in the view.
 */
@Serializable
data class UpdateNumericConfig(
    /**
     * If a single field is present in streaming data point (from startStream),
     * control how it is formatted and rendered. Use an ID string from [DataType.Type]
     * of the matching type which will account for precision and unit conversion.
     *
     * If never applied, defaults to integer precision.
     */
    val formatDataTypeId: String,
) : ViewEvent()
```

Practical consequences for karoo-weather:
- `UpdateNumericConfig(formatDataTypeId = DataType.Type.TEMPERATURE)` on a **non-graphical** data type gets you
  free unit conversion (°C/°F) + precision from the user's `UserProfile.preferredUnit.temperature`. Same trick for
  wind speed with `DataType.Type.SPEED`. **This is the cheapest correct way to respect user units.**
- `ShowCustomStreamState("No data", color)` is the right way to show "no forecast yet / offline" instead of a blank
  field.
- `UpdateGraphicConfig(showHeader = false)` reclaims the header row for a dense wind/rain graphic.

---

## 3. `KarooSystemService`

`lib/.../karooext/KarooSystemService.kt:56`. Constructed with a `Context` (`KarooSystemService(context)`).

```kotlin
fun connect(onConnection: ((Boolean) -> Unit)? = null)   // binds to io.hammerhead.appstore/.service.AppStoreService, action "KarooSystem"
fun disconnect()                                          // removes all consumers + unbinds
val connected: Boolean
val libVersion: String?
val info: KarooInfo?          // KarooInfo(serial: String, hardwareType: HardwareType)
val serial: String?
val hardwareType: HardwareType?   // K2 | KAROO | UNKNOWN
fun dispatch(effect: KarooEffect): Boolean   // false if not connected
fun removeConsumer(consumerId: String)
```

Consumers (`KarooSystemService.kt:192-248`):

```kotlin
inline fun <reified T : KarooEvent> addConsumer(
    params: KarooEventParams,
    noinline onError: ((String) -> Unit)? = null,
    noinline onComplete: (() -> Unit)? = null,
    noinline onEvent: (T) -> Unit,
): String

inline fun <reified T : KarooEvent> addConsumer(
    noinline onError: ((String) -> Unit)? = null,
    noinline onComplete: (() -> Unit)? = null,
    noinline onEvent: (T) -> Unit,
): String
```

Important behaviours:
- Consumers can be registered **before** connect and survive reconnects (`onBindingDied` re-binds and re-registers,
  `KarooSystemService.kt:84-89`).
- `onError`/`onComplete` **auto-remove the consumer** (`KarooSystemService.kt:199-206`) — a stream that errors is
  gone; re-register if you need resilience.
- The no-params overload only supports the event types with a `Params` default
  (`KarooSystemService.kt:233-246`): `RideState, Lap, UserProfile, OnLocationChanged, OnGlobalPOIs,
  OnNavigationState, OnMapZoomLevel, SavedDevices, Bikes, ActiveRideProfile, ActiveRidePage`.
  `OnStreamState` and `OnHttpResponse` **require** explicit params and throw
  `IllegalArgumentException` otherwise.

### 3.1 All `KarooEvent` subclasses (`lib/.../karooext/models/KarooEvent.kt`)

| Event | Params | Fields | Since |
|---|---|---|---|
| `RideState` (sealed) | `RideState.Params` | `Idle` \| `Recording` \| `Paused(auto: Boolean)` | — |
| `Lap` | `Lap.Params` | `number: Int, durationMs: Long, trigger: String` | — |
| `OnStreamState` | `OnStreamState.StartStreaming(dataTypeId: String)` | `state: StreamState` | — |
| `UserProfile` | `UserProfile.Params` | `weight: Float, preferredUnit: PreferredUnit, maxHr: Int, restingHr: Int, heartRateZones: List<Zone>, ftp: Int, powerZones: List<Zone>` | — |
| `OnHttpResponse` | `OnHttpResponse.MakeHttpRequest(...)` | `state: HttpResponseState` | — |
| `OnLocationChanged` | `.Params` | `lat: Double, lng: Double, orientation: Double?` | 1.1.3 |
| `OnGlobalPOIs` | `.Params` | `pois: List<Symbol.POI>` | 1.1.3 |
| `OnNavigationState` | `.Params` | `state: NavigationState` (see §5) | 1.1.3 |
| `OnMapZoomLevel` | `.Params` | `zoomLevel: Double` in `[8.0, 18.0]` | 1.1.3 |
| `SavedDevices` | `.Params` | `devices: List<SavedDevice>` (id, connectionType, name, enabled, details, components, supportedDataTypes, gearInfo) | 1.1.5 |
| `Bikes` | `.Params` | `bikes: List<Bike(id, name, odometer)>` | 1.1.5 |
| `ActiveRideProfile` | `.Params` | `profile: RideProfile` | 1.1.5 |
| `ActiveRidePage` | `.Params` | `page: RideProfile.Page` | 1.1.5 |

`UserProfile.PreferredUnit` verbatim (`KarooEvent.kt:159-185`):

```kotlin
@Serializable
data class PreferredUnit(
    val distance: UnitType,
    val elevation: UnitType,
    val temperature: UnitType,
    val weight: UnitType,
) {
    enum class UnitType { METRIC, IMPERIAL }
}
```
→ **`preferredUnit.temperature` is what karoo-weather must honour for °C/°F**, and `preferredUnit.distance` for
wind speed (km/h vs mph).

`OnLocationChanged` verbatim (`KarooEvent.kt:262-277`):

```kotlin
@Serializable
data class OnLocationChanged(
    val lat: Double,
    val lng: Double,
    /**
     * Current orientation, heading, direction
     * - 0 is North, 180 is South
     */
    val orientation: Double?,
) : KarooEvent()
```
Wind-relative-to-heading (headwind/tailwind) = `windDirection - orientation`.

`OnMapZoomLevel` doc (`KarooEvent.kt:477-483`): *"Zoom level: [8.0, 18.0] where smaller is more zoomed out ...
the map page default cycle of zooms uses value [13.0, 15.0, 16.0]"*. Useful to pick the density of weather icons
along the route (the sample does exactly this in `startMap`).

### 3.2 All `KarooEffect` subclasses (`lib/.../karooext/models/KarooEffect.kt`)

| Effect | Payload | Since |
|---|---|---|
| `PlayBeepPattern(tones: List<Tone>)` | `Tone(frequency: Int?, durationMs: Int)`; null frequency = silence | — |
| `PerformHardwareAction` (sealed objects) | `TopLeftPress, TopRightPress, BottomLeftPress, BottomRightPress, ControlCenterComboPress, DrawerActionComboPress` | — |
| `TurnScreenOff` / `TurnScreenOn` | objects | — |
| `RequestBluetooth(resourceId)` / `ReleaseBluetooth(resourceId)` | — | — |
| `RequestAnt(resourceId)` / `ReleaseAnt(resourceId)` | — | 1.1.2 |
| `MarkLap` | object | — |
| `PauseRide` / `ResumeRide` | objects; `ResumeRide` only valid when `Paused(auto = false)` | — |
| `SystemNotification(id, message, subText?, header?, style = Style.EVENT, action?, actionIntent?)` | `Style = EVENT, ERROR, UPDATE, EDUCATION, SETUP` | — |
| `InRideAlert(id, @DrawableRes icon, title, detail?, autoDismissMs?, @ColorRes backgroundColor, @ColorRes textColor)` | — | — |
| `ApplyLauncherBackground(url: String?)` | — | — |
| `ShowMapPage(zoom: Boolean = true)` | — | — |
| `ZoomPage(zoomIn: Boolean = true)` | — | — |
| `LaunchPinDrop(pin: Symbol.POI)` | — | 1.1.3 |

Note `ShowSymbols` / `ShowPolyline` / `HideSymbols` / `HidePolyline` are **`MapEffect`, not `KarooEffect`** — they
are emitted through the `startMap` emitter, not `dispatch()`. See §7.

`InRideAlert` verbatim (`KarooEffect.kt:225-254`) — the natural vehicle for a "rain in 10 min" warning:

```kotlin
@Serializable
data class InRideAlert(
    val id: String,
    @DrawableRes val icon: Int,
    val title: String,
    val detail: String?,
    val autoDismissMs: Long?,
    @ColorRes val backgroundColor: Int,
    @ColorRes val textColor: Int,
) : KarooEffect()
```
Sample usage: `SampleExtension.kt:322-330` (`autoDismissMs = 10_000`).

`SystemNotification` supports `actionIntent` as an **action string** (`"io.hammerhead.sampleext.MAIN"` in the
sample, `SampleExtension.kt:294-300`) — so declare a matching `<intent-filter>` action on your settings Activity.

---

## 4. HTTP

### 4.1 `MakeHttpRequest` / `OnHttpResponse`

`lib/.../karooext/models/KarooEvent.kt:200-254` — verbatim, including the documented limits:

```kotlin
/**
 * Make an HTTP request via Karoo's best network connection.
 *
 * A wifi connection will be used if connected, otherwise, if supported, the request
 * can be performed via BT to a connected companion app. Because of this, HTTP calls
 * made via this method should be:
 *   1. limited in size (<100K, uploading or downloading large files will take a long time)
 *   2. targeted to an in-ride experience that is important to the current ride state
 *
 * Require params [MakeHttpRequest].
 */
@Serializable
data class OnHttpResponse(val state: HttpResponseState) : KarooEvent() {
    @Serializable
    data class MakeHttpRequest(
        /** HTTP request method: GET, POST, PUT, etc. */
        val method: String,
        /** URL to send the request to */
        val url: String,
        /** Any custom headers to include */
        val headers: Map<String, String> = emptyMap(),
        /** Body of the request */
        val body: ByteArray? = null,
        /** Queue this request until a connection becomes available */
        val waitForConnection: Boolean = true,
    ) : KarooEventParams() {
        init {
            body?.size?.let {
                check(it <= MAX_REQUEST_SIZE) {
                    "REQUEST_TOO_LARGE"
                }
            }
        }
    }

    companion object {
        // 100KB maximum for request/response body
        const val MAX_REQUEST_SIZE = 100_000
    }
}
```

`lib/.../karooext/models/HttpResponseState.kt` — verbatim:

```kotlin
@Serializable
sealed class HttpResponseState {
    @Serializable data object Queued : HttpResponseState()
    @Serializable data object InProgress : HttpResponseState()
    @Serializable data class Complete(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: ByteArray?,
        val error: String?,
    ) : HttpResponseState()
}
```

Key facts, grepped and confirmed:
- **`MAX_REQUEST_SIZE = 100_000` (`KarooEvent.kt:252`) is the only size constant in the library.** It is
  *enforced client-side only on the request body* via `check()` in `init` → an oversized body throws
  `IllegalStateException("REQUEST_TOO_LARGE")` when you construct `MakeHttpRequest`. The comment
  `// 100KB maximum for request/response body` and the KDoc `<100K` state the **response** is bound by the same
  limit, but the library does not enforce it — a large response presumably fails or truncates on the OS side.
  The prior project docs' "100KB response limit on the new Karoo" is consistent with this and with
  `ktor-client-karoo/README.md:16` (*"Requests and Responses are limited to 100KB in size"*).
- **No gzip/compression support anywhere.** `grep -rniE "gzip|deflate|compress|content-encoding"` over
  `karoo-ext/lib`, `karoo-ext/app` and `ktor-client-karoo/lib` returns only two false positives
  (suspension "Low Speed Compression" data types at `DataType.kt:1047,1053`). Whether the OS-side implementation
  sets `Accept-Encoding` is not observable from this source; you can set your own `Accept-Encoding` header, but you
  would then have to inflate `body` yourself. **Safest plan: request the smallest possible JSON (limit forecast
  fields/hours) rather than rely on compression.**
- Headers: request headers are a flat `Map<String, String>`; response headers are `Map<String, String>` (a single
  value per name — see the ktor adapter's `split(",")` hack below).
- `waitForConnection = true` (default) queues the request until a connection exists → you will get `Queued` then
  `InProgress` then `Complete`. For in-ride weather refreshes use `waitForConnection = false` plus your own timeout,
  as both the sample and the ktor engine do.
- The consumer emits **multiple** events for one request. Terminate on `is HttpResponseState.Complete` and remove
  the consumer.

Reference usage (`app/.../MainViewModel.kt:110-146`), including the `.timeout(10.seconds)` idiom:

```kotlin
callbackFlow {
    val listenerId = karooSystem.addConsumer(
        OnHttpResponse.MakeHttpRequest(
            "POST",
            "https://httpbin.org/anything",
            headers = mapOf("Content-Type" to "text/plain"),
            body = payload.toByteArray(),
            // Don't queue this
            waitForConnection = false,
        ),
    ) { event: OnHttpResponse ->
        val message = when (val state = event.state) {
            is HttpResponseState.Complete -> "Status ${state.statusCode}: ${state.error ?: /*...*/}"
            is HttpResponseState.InProgress -> "In Progress"
            is HttpResponseState.Queued -> "Queued"
        }
        trySend(message)
    }
    awaitClose { karooSystem.removeConsumer(listenerId) }
}.timeout(10.seconds).collect { /* ... */ }
```

### 4.2 `ktor-client-karoo`

A third-party (`de.jonasfranz`, Apache-2) Ktor `HttpClientEngine` that funnels every request through
`MakeHttpRequest`. Usage (`ktor-client-karoo/README.md:49-57`):

```kotlin
val client = HttpClient(Karoo(karooSystem))
val response = client.get("https://api.example.com/forecast")
```

Engine (`ktor-client-karoo/lib/.../KarooEngine.kt:19-59`) — verbatim core:

```kotlin
class KarooEngine(override val config: KarooEngineConfig) : HttpClientEngineBase("karoo") {
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        if (!karooSystem.connected) throw KarooSystemNotConnectedException()
        if (karooSystem.hardwareType == HardwareType.K2) throw KarooIsUnsupportedException()

        return callbackFlow {
            val callContext = callContext()
            val headers = mutableMapOf<String, String>()
            mergeHeaders(data.headers, data.body, headers::put)
            val listenerId = karooSystem.addConsumer(
                params = OnHttpResponse.MakeHttpRequest(
                    method = data.method.value,
                    url = data.url.toString(),
                    body = data.body.toByteArray(),
                    headers = headers,
                    waitForConnection = false,
                ),
                onError = { close(KarooServiceException(it)) },
            ) { event: OnHttpResponse ->
                val state = event.state
                if (state is HttpResponseState.Complete) {
                    trySend(state.toHttpResponseData(callContext))
                }
            }
            awaitClose { karooSystem.removeConsumer(listenerId) }
        }.timeout(config.requestTimeout).first()
    }
}
```

Config (`KarooEngineConfig.kt`): `var requestTimeout: Duration = 10.seconds` — **the only timeout**; Ktor's own
`HttpTimeout` plugin is not wired in.

Response mapping (`KarooEngineUtils.kt:15-25`):

```kotlin
internal fun HttpResponseState.Complete.toHttpResponseData(callContext: CoroutineContext): HttpResponseData {
    val body = body?.run { ByteReadChannel(this) } ?: ByteReadChannel.Empty
    return HttpResponseData(
        statusCode = HttpStatusCode.fromValue(statusCode),
        headers = HeadersImpl(headers.mapValues { it.value.split(",") }),
        body = body,
        version = HttpProtocolVersion.HTTP_1_1,
        callContext = callContext,
        requestTime = GMTDate(),
    )
}
```

Caveats to carry into karoo-weather:
- **`waitForConnection = false` is hard-coded** — no offline queuing through the ktor engine.
- Response headers are re-split on `","`, so a header whose value legitimately contains a comma (e.g. a `Date`
  header) is mangled into multiple values.
- `Queued` / `InProgress` states are dropped; only `Complete` resolves the flow.
- K2 hardware throws `KarooIsUnsupportedException`; disconnected system throws `KarooSystemNotConnectedException`.
- No websockets/SSE (README:15). No streaming bodies — everything is materialised to a `ByteArray`
  (`OutgoingContent.toByteArray()`), so the 100 KB ceiling applies.
- `api(libs.ktor.client.core)` only — bring your own `ContentNegotiation`/`kotlinx-serialization` plugins.

**Recommendation:** ktor-client-karoo is a thin, correct wrapper and worth using for JSON weather APIs (it gives
you `ContentNegotiation` + `kotlinx.serialization` for free). If you need `waitForConnection = true` for
opportunistic pre-ride prefetch, call `MakeHttpRequest` directly for that path.

---

## 5. Navigation / loaded route — the key API

**`OnNavigationState` is the only event that exposes the loaded route geometry.** Verbatim from
`lib/.../karooext/models/KarooEvent.kt:309-468`:

```kotlin
/**
 * Observe the state of navigation: route selection or destination
 *
 * @since 1.1.3
 */
@Serializable
data class OnNavigationState(
    val state: NavigationState,
) : KarooEvent() {
    @Serializable
    sealed class NavigationState {
        /**
         * No navigation is currently running
         */
        @Serializable
        data object Idle : NavigationState()

        /**
         * Navigating a saved route
         */
        @Serializable
        data class NavigatingRoute(
            /**
             * Google encoded polyline, precision 5, of the selected route.
             */
            val routePolyline: String,
            /**
             * Distance (in meters) of the full route.
             */
            val routeDistance: Double,
            /**
             * Pair of distance, elevation, encoded as Google polyline, precision 1, for the selected route.
             *
             * @since 1.1.6
             */
            val routeElevationPolyline: String? = null,
            /**
             * Google encoded polyline, precision 5, of the path to navigate back to the route.
             *
             * Null when on route or off route and using breadcrumb navigation.
             */
            val rejoinPolyline: String?,
            /**
             * Distance along `routePolyline` that `rejoinPolyline` meets.
             */
            val rejoinDistance: Double?,
            /**
             * Name of the route
             */
            val name: String,
            /**
             * Whether navigating in reverse
             */
            val reversed: Boolean,
            /**
             * If breadcrumb navigation is being used (disabled turn-by-turn)
             */
            val breadcrumb: Boolean,
            /**
             * POIs associated with the route
             */
            val pois: List<Symbol.POI>,
            /**
             * Climbs along the route
             *
             * @since 1.1.6
             */
            val climbs: List<Climb> = emptyList(),
        ) : NavigationState()

        /**
         * Navigation to a destination POI
         */
        @Serializable
        data class NavigatingToDestination(
            /**
             * Destination the rider selected to navigate to.
             */
            val destination: Symbol.POI,
            /**
             * The polyline from the rider's original location to the destination.
             *
             * This will change if the rider deviates from the previous suggested path to the destination.
             */
            val polyline: String,
            /**
             * Pair of distance, elevation, encoded as Google polyline, precision 1, along the suggested path.
             *
             * @since 1.1.6
             */
            val elevationPolyline: String? = null,
            /**
             * Climbs along the path to destination
             *
             * @since 1.1.6
             */
            val climbs: List<Climb> = emptyList(),
        ) : NavigationState()

        /**
         * Data for a climb within a route
         *
         * @since 1.1.6
         */
        @Serializable
        data class Climb(
            /** Distance along the route (m) */
            val startDistance: Double,
            /** Length of the climb (m) */
            val length: Double,
            /** Average grade over the climb (%) */
            val grade: Double,
            /** Total ascent of the climb (m) */
            val totalElevation: Double,
        )
    }

    @Serializable
    data object Params : KarooEventParams()
}
```

### What this gives karoo-weather

- **Detecting a loaded route**: `karooSystem.addConsumer<OnNavigationState> { }`; `state is NavigatingRoute` ⇒ a
  saved route is loaded. `Idle` ⇒ no navigation. `NavigatingToDestination` ⇒ ad-hoc routing to a POI (its
  `polyline` changes on deviation, so debounce/rate-limit forecast fetches on that branch).
- **Route geometry**: `routePolyline` — **Google encoded polyline, precision 5** (i.e. the standard
  `PolylineUtils.decode(routePolyline, 5)`). Not a list of points; you must decode it. The sample already depends
  on `com.mapbox.mapboxsdk:mapbox-sdk-turf` (`karoo-ext/app/build.gradle.kts:94`) and uses
  `com.mapbox.geojson.utils.PolylineUtils.encode(points, 5)` (`SampleExtension.kt`), plus
  `TurfMeasurement.destination(...)` / `TurfConstants.UNIT_METERS`. That is the obvious toolkit for
  sampling forecast points every N km along the route and for measuring distance-along-route.
- **Route length**: `routeDistance` in **metres**.
- **Elevation profile**: `routeElevationPolyline` — a **precision-1** polyline whose "coordinate pairs" are
  `(distance, elevation)`. Decode with precision 1 and read `.latitude()`/`.longitude()` as distance/elevation.
- **`reversed`** must be honoured when mapping distance-along-route to a point.
- **No distance-remaining or ETA on this event.** Those come from the data streams instead:
  - `DataType.Type.DISTANCE_TO_DESTINATION` = `"TYPE_DISTANCE_TO_DESTINATION_ID"`, fields
    `[DISTANCE_TO_DESTINATION, NAVIGATION_STATE, REROUTING_ENABLED, ON_ROUTE]` (`DataType.kt:1068-1072`)
  - `DataType.Type.TIME_TO_DESTINATION` = `"TYPE_TIME_TO_DESTINATION_ID"` (`DataType.kt:1096`)
  - `DataType.Type.TIME_OF_ARRIVAL` = `"TYPE_TIME_OF_ARRIVAL_ID"` (`DataType.kt:1102`) — **this is the ETA you need
    to time-index the forecast** ("what will the weather be when I get there").
  - `DataType.Type.ELEVATION_REMAINING` / `DESCENT_REMAINING` (`:1084`, `:1090`).
  - Current position from `OnLocationChanged` or `DataType.Type.LOCATION`.
- **Suggested forecast-along-route algorithm**: on `NavigatingRoute`, decode `routePolyline` (precision 5), walk it
  with Turf to produce sample points every X km, take current `TIME_OF_ARRIVAL`/`TIME_TO_DESTINATION` and current
  speed to estimate a timestamp for each sample, and issue **one** batched forecast request (many weather APIs take
  multiple lat/lon in one call) — remember the 100 KB response ceiling and drop unneeded fields.

---

## 6. RideState — riding / paused

`lib/.../karooext/models/KarooEvent.kt:37-74` — verbatim:

```kotlin
/**
 * Observe the current ride state (activity recording).
 *
 * On starting, a consumer will be provided with the current state and then subsequently called
 * when the state changes.
 */
@Serializable
sealed class RideState : KarooEvent() {
    /**
     * Recording not yet started or already finished.
     */
    @Serializable
    data object Idle : RideState()

    /**
     * Ride is actively recording
     */
    @Serializable
    data object Recording : RideState()

    /**
     * Ride is paused
     */
    @Serializable
    data class Paused(
        /**
         * true - ride is paused by auto-pause
         * false - ride is manually paused
         */
        val auto: Boolean,
    ) : RideState()

    @Serializable
    data object Params : KarooEventParams()
}
```

- Register with `karooSystem.addConsumer<RideState> { state -> … }`; **the current state is delivered immediately**
  on registration, then on every change.
- `Recording` ⇒ riding. `Paused(auto = true)` ⇒ auto-pause (stopped at a light); `Paused(auto = false)` ⇒ manual.
- Use for polling policy: fetch forecasts aggressively while `Recording`, back off to a long interval while
  `Idle`/`Paused` to save battery and radio.
- Corresponding effects: `PauseRide`, `ResumeRide` (only valid from `Paused(auto = false)`), `MarkLap`.

---

## 7. Symbols / map POIs — drawing weather icons on the map

Requires `mapLayer="true"` in `extension_info.xml` and `override fun startMap(emitter: Emitter<MapEffect>)`.

`lib/.../karooext/models/Symbol.kt` — verbatim:

```kotlin
@Serializable
sealed interface Symbol {
    /** ID unique to this extension which identifies the symbol */
    val id: String

    /**
     * Point of interest which denotes a position and optional types/name information.
     */
    @Serializable
    data class POI(
        override val id: String,
        val lat: Double,
        val lng: Double,
        /** The type of POI */
        @SerialName("poiType")
        val type: String = Types.GENERIC,
        /** Optional name of the POI */
        val name: String? = null,
        /**
         * Optional distances that a route POI is found along the route polyline
         * ...
         * @since 1.1.6
         */
        val distancesAlongRoute: List<Double> = emptyList(),
    ) : Symbol {
        object Types {
            const val AID_STATION = "aid_station"; const val ATM = "atm"; const val BAR = "bar"
            const val BIKE_PARKING = "bike_parking"; const val BIKE_SHARE = "bike_share"
            const val BIKE_SHOP = "bike_shop"; const val CAMPING = "camping"; const val CAUTION = "caution"
            const val COFFEE = "coffee"; const val CONTROL = "control"; const val CONVENIENCE_STORE = "convenience_store"
            const val FERRY = "ferry"; const val FIRST_AID = "first_aid"; const val FOOD = "food"
            const val GAS_STATION = "gas_station"; const val GENERIC = "generic"; const val GEOCACHE = "geocache"
            const val HOME = "home"; const val HOSPITAL = "hospital"; const val LIBRARY = "library"
            const val LODGING = "lodging"; const val MONUMENT = "monument"; const val PARK = "park"
            const val PARKING = "parking"; const val REST_STOP = "rest_stop"; const val RESTROOM = "restroom"
            const val SHOPPING = "shopping"; const val SHOWER = "shower"; const val SUMMIT = "summit"
            const val SWIMMING = "swimming"; const val TRAILHEAD = "trailhead"
            const val TRANSIT_CENTER = "transit_center"; const val VIEWPOINT = "viewpoint"
            const val WATER = "water"; const val WINERY = "winery"
        }
    }

    /**
     * An icon on the map (
     */
    @Serializable
    data class Icon(
        override val id: String,
        val lat: Double,
        val lng: Double,
        /** Resource ID of the drawable for this symbol */
        @DrawableRes val iconRes: Int,
        /**
         * Direction the icon is drawn on the map. 0 is North, 90 is East, 180 is South, -90 is West.
         */
        val orientation: Float,
    ) : Symbol
}
```

**`Symbol.Icon` is exactly what karoo-weather wants**: an arbitrary drawable from *your* APK, placed at a lat/lng,
with an `orientation` in degrees. Wind arrows come free — set `orientation` to the wind bearing. Rain/cloud icons
use `orientation = 0f`. `iconRes` is a `@DrawableRes` int resolved by Karoo OS across the process boundary; use
**vector drawables** (`app/src/main/res/drawable/ic_arrow.xml` in the sample is a vector).

`lib/.../karooext/models/MapEffect.kt` — verbatim:

```kotlin
@Serializable sealed class MapEffect

/**
 * Show a list of symbols on the map.
 * This can be called again with the same ID to update symbols.
 */
@Serializable data class ShowSymbols(val symbols: List<Symbol>) : MapEffect()

/** Remove symbols by `id` that were previously added with [ShowSymbols] */
@Serializable data class HideSymbols(val symbolIds: List<String>) : MapEffect()

/** Show a polyline on the map with style */
@Serializable data class ShowPolyline(
    val id: String,
    /**
     * Google Encoded polyline format of a list of points.
     * Precision 5.
     */
    val encodedPolyline: String,
    @ColorInt val color: Int,
    val width: Int,
) : MapEffect()

/** Hide a previously shown polyline */
@Serializable data class HidePolyline(val id: String) : MapEffect()
```

Reference `startMap` implementation (`app/.../extension/SampleExtension.kt`, abridged) — note the
zoom-aware density and the re-emit-to-update pattern:

```kotlin
override fun startMap(emitter: Emitter<MapEffect>) {
    val job = CoroutineScope(Dispatchers.IO).launch {
        combine(karooSystem.consumerFlow<OnLocationChanged>(), karooSystem.consumerFlow<OnMapZoomLevel>()) { l, z -> l to z }
            .collect { (location, mapZoom) ->
                val source = Point.fromLngLat(location.lng, location.lat)
                val totalDistance = when {
                    mapZoom.zoomLevel >= 15.0 -> 100.0
                    mapZoom.zoomLevel >= 12.0 -> 200.0
                    else -> 300.0
                }
                val dest = TurfMeasurement.destination(source, totalDistance, 45.0, TurfConstants.UNIT_METERS)
                emitter.onNext(ShowSymbols(listOf(
                    Symbol.POI(id = "away", lat = dest.latitude(), lng = dest.longitude()),
                    Symbol.Icon(id = "half", lat = /*..*/, lng = /*..*/, orientation = 0f, iconRes = R.drawable.ic_arrow),
                )))
                emitter.onNext(ShowPolyline("45", PolylineUtils.encode(listOf(source, dest), 5), getColor(R.color.colorPrimary), 4))
            }
    }
    emitter.setCancellable { job.cancel() }
}
```

Notes:
- Re-emitting `ShowSymbols` with the **same ids** updates them in place; `HideSymbols(ids)` removes them.
- `ShowPolyline` could colour-code a route by rain intensity — but there is one colour per polyline id, so you would
  emit N segment polylines with distinct ids.
- `MapEffect`s are only deliverable while `startMap` is active (i.e. while the map layer is running); there is no
  `dispatch()` path for them.
- `Symbol.Icon` has no size/anchor/z-order/label control. Design icons accordingly (single-colour vectors read best).

---

## 8. Data-field sizes and text-size hints

- **`ViewConfig.gridSize: Pair<Int, Int>` is (column span, row span) on a grid whose total is 60.**
  KDoc verbatim (`ViewConfig.kt:29-32`, identical wording repeated at `RideProfile.kt` `Page.Element`):
  > *"Pair of column span x row span. Total grid size is 60, so Pair(60, 15) would indicate 1/4 height, full width"*

  So `first = 60` is full width, `first = 30` is half width, `first = 20` is a third; `second = 60` is full height,
  `30` half, `20` a third, `15` a quarter. A typical 2×2 page field is `Pair(30, 30)`; a full-width quarter-height
  strip is `Pair(60, 15)`. **Do not hard-code pixel sizes — derive layout from `gridSize`, and use
  `viewSize` (pixels) only for exact drawing.**
- **`ViewConfig.viewSize: Pair<Int, Int>`** — actual width/height **in pixels** of the field as configured in the
  user's profile. This is what you pass to a canvas/bitmap if you draw manually. With Glance the sample passes
  `DpSize.Unspecified` and lets `fillMaxSize()` handle it.
- **`ViewConfig.textSize: Int`** — *"Font size used in standard numeric view of this grid size in **sp**"*. Use it
  as the baseline so a custom field's number visually matches the stock fields next to it; scale secondary text
  down from it (e.g. `textSize * 0.5`) rather than picking absolute sp values.
- **`ViewConfig.alignment`** — `LEFT | CENTER | RIGHT`, default `RIGHT`. The sample aligns its graphic on the
  *opposite* side from the number (`CustomSpeed.kt`).
- **`ViewConfig.boundariesEnabled`** — whether the user's profile draws field borders; adjust padding/dividers.
- **`ViewConfig.preview`** — `true` in the page editor. **Show static placeholder weather data when
  `preview == true`** instead of firing network requests; the editor may instantiate several views at once.
- The current page layout is also readable at runtime: `ActiveRideProfile` → `RideProfile.pages[].elements[]` with
  `Element(dataTypeId, gridSize)`, and `ActiveRidePage` → the visible `Page` (`mapPage: Boolean`,
  `elements: List<Element>`). Useful to skip work when your field is not on the visible page.
- `RideProfile` also exposes `indoor: Boolean` — **skip weather entirely on indoor profiles** — plus
  `defaultActivityType` (`RIDE, EBIKE, MOUNTAIN_BIKE, GRAVEL, EMOUNTAIN_BIKE, VELOMOBILE`) and
  `routingPreference` (`ROAD, GRAVEL, MTB`).

---

## 9. Cheat-sheet for karoo-weather

| Need | API |
|---|---|
| Loaded route geometry | `OnNavigationState` → `NavigatingRoute.routePolyline` (Google polyline, precision 5) |
| Route length | `NavigatingRoute.routeDistance` (metres) |
| Route elevation profile | `NavigatingRoute.routeElevationPolyline` (precision 1, distance/elevation pairs) |
| Distance remaining | stream `DataType.Type.DISTANCE_TO_DESTINATION` |
| ETA | stream `DataType.Type.TIME_OF_ARRIVAL` / `TIME_TO_DESTINATION` |
| Current position + heading | `OnLocationChanged(lat, lng, orientation)` |
| Riding / paused | `RideState` (`Idle`/`Recording`/`Paused(auto)`) |
| Units (°C/°F, km/mi) | `UserProfile.preferredUnit.{temperature,distance}` — or delegate to `UpdateNumericConfig(formatDataTypeId = DataType.Type.TEMPERATURE)` |
| Network | `OnHttpResponse.MakeHttpRequest` (≤100 KB body, no gzip) or `HttpClient(Karoo(karooSystem))` |
| Numeric data field | `DataTypeImpl.startStream` + `graphical="false"` + `UpdateNumericConfig` |
| Custom graphic field | `graphical="true"` + `startView` → `ViewEmitter.updateView(RemoteViews)`, **max 1 Hz** |
| Wind arrows / rain icons on map | `mapLayer="true"` + `startMap` → `ShowSymbols(listOf(Symbol.Icon(..., iconRes, orientation)))` |
| "Rain in 10 min" warning | `karooSystem.dispatch(InRideAlert(...))` |
| Non-critical message | `karooSystem.dispatch(SystemNotification(...))` |
| App store metadata | `KarooAppManifest` at `MANIFEST_URL` meta-data, `tags = ["weather"]` |
