# karoo-weather

A weather extension for [Hammerhead Karoo 2 and Karoo 3](https://www.hammerhead.io/) cycling computers.

It answers three questions while you ride, without you touching the screen:

* **What is the weather right now, where I am?**
* **Is the wind helping or hurting, and from where?**
* **Is it going to rain in the next two hours — and what will it be like further along my route?**

Weather comes from [Open-Meteo](https://open-meteo.com/) and is fetched by the Karoo itself over its own
HTTP transport. There is no backend, no account and no tracking.

## Features

### Five data fields

| Field | What it shows |
|---|---|
| **Weather now** | Condition icon, temperature, "feels like", wind arrow and speed, rain, for your current position. |
| **Temperature** | Forecast air temperature, as a plain numeric field the Karoo formats in your own units. |
| **Wind** | An arrow drawn relative to the direction you are travelling — green tailwind, red headwind, grey cross — with speed and gusts. |
| **Rain next 2 h** | A 15-minute nowcast bar chart, with the time the rain starts and the total expected. |
| **Route forecast** | A strip of conditions along the rest of the loaded route, each column at your **estimated arrival time**, not at "now". |

All five appear in the Karoo page editor with live previews and can be placed on any ride page at any
size; layouts derive their column count from the real field size, so nothing gets clipped.

### Wind arrows on the map

When a route is loaded, wind-direction arrows and rain markers are drawn along it as native map symbols.
Symbol spacing follows the map zoom level, so the layer thins out instead of crowding. No raster overlay
and no MapLibre are involved.

### Rain alert

An optional in-ride alert when rain is forecast to start within the next half hour. Off by default, and
only while a ride is recording.

### Companion app

An on-device app (Now / Route / Settings) for the full forecast, unit preferences, refresh interval,
location-privacy rounding, and the map-layer and alert toggles. Nothing is sent anywhere until you accept
the consent dialog on first launch.

### Offline and privacy

* The last successful forecast is cached and survives a reboot; fields repaint from it immediately and mark
  values older than three hours.
* Your position is rounded to a configurable grid (3 km by default) before it is sent.
* No location permission is requested — position comes from the Karoo's own ride data.

## Screenshots

Companion app on a Karoo (480×800). Data-field captures will be added once fields are recorded on a ride page.

| Now | Route | Settings |
|---|---|---|
| ![Now](docs/screenshots/now.png) | ![Route](docs/screenshots/route.png) | ![Settings](docs/screenshots/settings.png) |

## Documentation

Research, architecture and design documents live in [docs/](docs/README.md).

## Install

### From the Karoo extension store

Open **Extensions** on the Karoo, find **Weather**, tap install.

### Sideload

1. Download `app-release.apk` from the
   [latest release](https://github.com/glandais/karoo-weather/releases/latest).
2. Enable developer options and USB debugging on the Karoo (Settings → About → tap the build number).
3. Install over ADB:

   ```bash
   adb connect <karoo-ip>:8080        # Karoo 3 (wireless); Karoo 2 uses USB
   adb install -r app-release.apk
   ```

4. Add the fields from the ride-page editor, and open **Weather** once to accept the data-sharing consent.

## Build

Requirements: JDK 17+, the Android SDK (compile SDK 37, build tools 36.x). The Gradle wrapper handles
everything else.

```bash
./gradlew spotlessApply        # ktfmt (kotlinlang), 4-space indent
./gradlew testDebugUnitTest    # pure-JVM unit tests, no emulator needed
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minified; needs keystore.properties to be signed
```

`assembleRelease` is unsigned unless a `keystore.properties` exists at the repository root with
`keyAlias`, `keyPassword`, `storeFile` and `storePassword`. Every build also regenerates `app/manifest.json`,
the file the Karoo extension store polls for updates; override the URLs it points at with the
`KAROO_BASE_URL`, `KAROO_VERSION_NAME` and `KAROO_VERSION_CODE` environment variables.

## Release

CI (`.github/workflows/ci.yml`) runs `spotlessCheck`, `testDebugUnitTest`, `lintDebug`,
`assembleDebug` and `assembleRelease` on every push and pull request to `main`.

Pushing a `v<major>.<minor>.<patch>` tag runs `.github/workflows/release.yml`, which builds a signed
release APK and publishes `app-release.apk` and `manifest.json` as GitHub Release assets — the URLs
the generated manifest points at. `versionCode` is derived from the tag as
`major * 10000 + minor * 100 + patch`.

```bash
git tag v1.0.0 && git push origin v1.0.0
```

Signing needs four repository secrets: `KAROO_KEYSTORE_BASE64` (`base64 -i release.keystore`),
`KAROO_KEYSTORE_PASSWORD`, `KAROO_KEY_ALIAS` and `KAROO_KEY_PASSWORD`.

## Attribution

**Weather data by Open-Meteo.com (CC BY 4.0)**

Weather icons are derived from [boxicons](https://boxicons.com/) (MIT); see
`app/src/main/res/raw/icon_credits.txt`.

Built against [karoo-ext](https://github.com/hammerheadnav/karoo-ext) (Apache-2.0).

## License

Apache License 2.0.
