# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

`karoo-weather` is a Hammerhead Karoo extension (Android app + `KarooExtension` service) that shows
weather, wind relative to heading, a two-hour rain nowcast, and a forecast along the loaded route at
estimated arrival time. Package root: `io.github.glandais.karoo.weather`.

## Commands

```bash
./gradlew spotlessApply        # ktfmt kotlinlang, 4-space indent — run before every commit
./gradlew testDebugUnitTest    # ~270 pure-JVM tests; no emulator, no Robolectric
./gradlew assembleDebug
./gradlew lintDebug
./gradlew assembleRelease      # R8 + resource shrinking; must stay green
./check.sh                     # spotlessApply + assembleDebug
```

Run a single test class: `./gradlew testDebugUnitTest --tests '*RelativeWindTest'`.

## Architecture

```
domain/      Pure data contracts: GeoPoint, WeatherSample, LocationForecast, RouteForecast,
             Units, WeatherSettings, WeatherProvider, HttpGateway, DataTypeIds. No Android.
route/       Polyline decode, haversine/bearing, route sampling, ETA model, relative wind. Pure JVM.
weather/     Open-Meteo provider + parser + URL builder, WMO code→category→icon, interpolation.
karoo/       The only package that touches karoo-ext: consumer flows, KarooHttpGateway, unit mapping.
data/        WeatherRepository (the singleton), SettingsStore, ForecastCache, RefreshPolicy, WeatherGraph.
datatypes/   The five data fields; datatypes/views/ holds Glance composables and Canvas bitmap builders.
extension/   WeatherMapLayer (map symbols) and RainAlerter (in-ride alerts).
ui/          The companion Compose app; ui/theme/Tokens.kt is the single source of colour truth.
WeatherExtension.kt   The service: types list, startMap, repository attach/detach, alerter lifecycle.
MainActivity.kt       Hosts WeatherApp and nothing else.
```

Key invariants, in rough order of how expensive they are to violate:

* **One process, one repository, one `KarooSystemService`.** `WeatherGraph.repository(context)` is the only
  way to get a `WeatherRepository`; it constructs and owns the service. Never build a `KarooSystemService`
  anywhere else. The manifest deliberately has no `android:process`.
* **`attach()` / `detach()` are ref-counted.** The extension service holds one ref for its lifetime, the
  companion app holds one for the composition's lifetime. The last `detach()` disconnects.
* **SI in, units out.** Everything that crosses a package boundary or a Karoo stream is °C, m/s, mm, metres,
  degrees true, epoch seconds UTC. Conversion happens only in `datatypes/views` and `ui`.
* **Data field lifecycle.** Per-view state (`GlanceRemoteViews`, `ArrowBitmaps`, the coroutine scope) is
  created inside `startView`, never as a property of the shared `DataTypeImpl`. Every `startStream`,
  `startView` or `startMap` that launches a coroutine calls `emitter.setCancellable { scope.cancel() }`.
* **Repaint floor.** `ViewEmitter.updateView` silently drops calls closer together than 900 ms; go through
  `Flow.throttle(viewRefreshMs(settings))`, never a bare loop.
* **`ShowCustomStreamState` is constructed in exactly one place** — `FieldChrome.customState` /
  `FieldChrome.clearState`. Its signature is `(message: String?, @ColorInt color: Int?)`, so passing a
  string resource id or a `ColorPair` compiles and then renders garbage.
* **`consumerFlow<T>()` is legal for 11 event types only** (`RideState`, `Lap`, `UserProfile`,
  `OnLocationChanged`, `OnGlobalPOIs`, `OnNavigationState`, `OnMapZoomLevel`, `SavedDevices`, `Bikes`,
  `ActiveRideProfile`, `ActiveRidePage`). Anything else — `OnStreamState`, `OnHttpResponse` — must use
  `consumerFlowWithParams`, or it throws at runtime. See `karoo/KarooFlows.kt`.
* **`typeId`s live in `DataTypeIds`** and must match `res/xml/extension_info.xml` and
  `WeatherExtension.types` exactly, all five of them. `mapLayer="true"` in `extension_info.xml` is what makes
  `startMap` get called at all.

## Constraints

* **No MapLibre, no raster map overlay.** The map layer emits `Symbol.Icon` effects only.
* **No new runtime dependencies.** In particular: no ktor (`ktor-client-karoo` throws on Karoo 2), no Mapbox
  Turf (geodesy is implemented in `route/`, ~180 lines, and is the most test-worthy code we have).
  `kotlinx-serialization-json` is the only serialization library.
* **HTTP responses must stay under 100 KB** — the Karoo transport's ceiling. The route request is capped at
  25 points (~31 KB) and `OpenMeteoProvider` trims the point list to the size budget before sending.
* **Free APIs only.** Open-Meteo's free tier, no API key, ≤ 96 requests/day.
* **`targetSdk = 32`** — the Karoo runs Android 12. Do not "fix" the `ExpiredTargetSdkVersion` lint
  suppression. `compileSdk` is 37, `minSdk` 26.
* **Kotlin 2.4 / K2 compiler**, AGP 9.3 with its built-in Kotlin support (no `kotlin-android` plugin).
  Tests live in `app/src/test/kotlin` and are plain JUnit 4; `unitTests.isReturnDefaultValues = true` is set,
  so Android framework calls return defaults rather than throwing — keep tests off the framework anyway.
* **Release builds are minified and resource-shrunk.** New `@Serializable` types outside our package or
  reflection-driven libraries need keep rules in `app/proguard-rules.pro`, and a mistake there is invisible
  in debug builds.

## Testing

Everything worth testing is pure: geodesy, sampling, ETA, relative wind, WMO mapping, parsing, interpolation,
refresh policy, layout arithmetic, alert decisions. Push logic out of Android classes rather than reaching
for Robolectric — the project has no instrumentation or Robolectric test dependency and should not gain one.

## Reference

Design and architecture documents, the karoo-ext SDK sources, and a reference extension used for patterns
live outside this repository under the working session's `scratchpad/`. When a karoo-ext signature is in
doubt, grep the SDK sources rather than guessing — several of its models have unobvious nullability
(`ShowCustomStreamState`, `Symbol.Icon.lng`, `UpdateNumericConfig`).
