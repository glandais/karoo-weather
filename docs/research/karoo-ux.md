# Karoo UX/UI Research for `karoo-weather`

Sources: local checkouts of `hammerheadnav/karoo-ext` (README + `app/`, `lib/`) and
`timklge/karoo-headwind` (a polished, widely-used community extension) under
`scratchpad/ref/`, plus Hammerhead support docs and reviews (web).

---

## 1. Device specs

| Device | Screen | Resolution | PPI | Notes |
|---|---|---|---|---|
| Karoo 2 | 3.2" | 800×480 (landscape) / 480×800 portrait-native panel | ~294 | Dragontail Glass, matte etched, multi-point touch. No SIM data on some units → weather extensions fall back to phone tether. |
| Karoo 3 | 3.2" | 800×480 | ~292 | Gorilla Glass, anti-glare/anti-fingerprint coating, brighter panel than K2. |

Sources: [Karoo Hardware Specifications](https://support.hammerhead.io/hc/en-us/articles/25687624385691-Karoo-Hardware-Specifications), [Karoo 2 Hardware Specifications](https://support.hammerhead.io/hc/en-us/articles/360058709333-Karoo-2-Hardware-Specifications), [Karoo Display](https://support.hammerhead.io/hc/en-us/articles/25696850944795-Karoo-Display).

**Implications for our extension:**
- Karoo-ext abstracts K2 vs K3 mostly transparently; `KarooInfo.hardwareType` (`HardwareType.K2`/`KAROO`/`UNKNOWN`) is the only place you'd branch (e.g. `karoo-headwind` uses it only to pick a different network-refresh interval — `k2Ms` vs `k3Ms` — see `karoo-headwind/app/src/main/kotlin/de/timklge/karooheadwind/datatypes/Views.kt:98-106`).
- Physical size is small (3.2") and ~294 ppi at typical viewing distance (extended on a stem, outdoors, often with vibration and gloved hands). This means:
  - Sunlight readability requires **high contrast** (near-white text on near-black, or vice versa) — no subtle greys.
  - Effective legible font size is large relative to a phone: numerals need to be readable from 40–60cm while bouncing on a bike.
  - Data field cells can be tiny (as small as 1/4 of 1/6 of screen — see grid math in §4b), so text must be short and bold, never wrapped.

---

## 2. Karoo OS visual language

### 2.1 Grid & field sizing (from `karoo-ext` model, ground truth)

`ViewConfig.gridSize` and `RideProfile.Page.Element.gridSize` are both `Pair<Int,Int>` (column-span × row-span) on a **total grid of 60** (both axes use the same 0–60 unit space, i.e. it's not columns/rows count but "60 units = full width" and "60 units = full height").

```kotlin
// io.hammerhead.karooext.models.ViewConfig
/**
 * Pair of column span x row span
 * Total grid size is 60, so Pair(60, 15) would indicate 1/4 height, full width
 */
val gridSize: Pair<Int, Int>
```
(`scratchpad/ref/karoo-ext/lib/src/main/kotlin/io/hammerhead/karooext/models/ViewConfig.kt:28-34`, identical doc comment repeated in `RideProfile.kt:88-93`.)

So common field sizes translate to:
| gridSize | Meaning | Typical use |
|---|---|---|
| `(60, 60)` | full width, full height | single huge field (whole page) |
| `(60, 30)` | full width, half height | 2-up stacked field |
| `(60, 15)` | full width, quarter height | thin strip (e.g. forecast strip) |
| `(30, 30)` | half width, half height ("1x1" in community READMEs) | standard square field |
| `(30, 15)` | half width, quarter height ("2x1" wide-short, called "2x1 field" in karoo-headwind docs) |
| `(20, 20)` | third width, third height | dense 3×3 page layout |

karoo-headwind branches on this directly:
```kotlin
// WindDirectionAndSpeedDataType.kt:167
wideMode = config.gridSize.first == 60,
// TailwindAndRideSpeedDataType.kt:228
val wideMode = config.gridSize.first == 60
// ResistanceForcesDataType.kt:168
small = config.gridSize.first <= 30
// ForecastDataType.kt:294
if (baseIndex > 0 && config.gridSize.first == 30) break   // only 1 forecast column fits in a half-width cell
```
i.e. **only show 1 forecast hour when the field is 30-wide (half screen), show 3 when it's 60-wide (full width)** — a directly reusable pattern for our "Route forecast" strip.

`ViewConfig` also carries:
```kotlin
val viewSize: Pair<Int, Int>   // actual pixel size of the field as configured
val textSize: Int              // sp size Karoo itself uses for a plain numeric field at this grid size
val alignment: Alignment       // LEFT | CENTER | RIGHT — user-configurable
val boundariesEnabled: Boolean // whether Karoo draws a border/box around the field
val preview: Boolean           // true while user is editing the page (no live data yet)
```
(`ViewConfig.kt` full file, `scratchpad/ref/karoo-ext/lib/src/main/kotlin/io/hammerhead/karooext/models/ViewConfig.kt`.) Always respect `alignment` and `boundariesEnabled` in custom graphical views — Karoo passes them so 3rd-party fields visually match native ones. Use `config.textSize` as the sp for your primary numeral (don't hardcode).

### 2.2 Color language

Neither `karoo-ext` nor the OS ships a documented brand palette, but the sample app and headwind extension colors converge on:

- **True black/white** as the base bg/fg pair, switched by night mode (see §2.3) — not a dark grey (`#121212`-style); Karoo is closer to OLED-style true black for outdoor contrast (`karoo-headwind` `HeadwindDirectionView.kt:79`: `.background(dayColor, nightColor)` with `dayColor = Color.White, nightColor = Color.Black`).
- Hammerhead brand marks (sample app, dokka site): `colorPrimary #6200EE` (purple, Material default — *not* actually Hammerhead brand, just Android boilerplate they never changed), `colorAccent #03DAC5` (teal). These come from the **default Android Studio template** and are not a deliberate Hammerhead identity — don't imitate them as "the Karoo brand color."
- karoo-headwind's own semantic palette (used for e.g. relative wind/resistance charts) is a better model of **in-context meaning colors**:
  ```
  green  #00FF00 / hGreen  #008000   -- favorable / tailwind
  orange #FF9930 / hOrange #BB4300   -- caution / neutral-negative
  red    #FF5454 / hRed    #A30000   -- unfavorable / headwind / danger
  gray   #808080                     -- neutral/secondary text
  ```
  (`scratchpad/ref/karoo-headwind/app/src/main/res/values/colors.xml`.) The paired bright/dark variants look like "light-theme variant / dark-theme variant" of each semantic hue, confirming Karoo fields need **both a day and night palette** per semantic color, not one fixed hex.
- The in-app (not data-field) UI in karoo-headwind uses Material3 `lightColorScheme` with `primary = #214559` (dark desaturated blue), `secondary = #636363` (mid grey), `tertiary = #FEF69A` (pale yellow) — a muted, low-saturation, non-flashy set for the companion phone-style settings screens (`theme/Theme.kt`).

### 2.3 Day/night handling in data fields

Karoo fields are typically implemented with an explicit day/night pair rather than reading system dark-mode, because the "night mode" concept on Karoo is tied to ride lighting, not just OS theme:
```kotlin
// HeadwindDirectionView.kt
.background(dayColor, nightColor) // day=white bg / night=black bg by default
style = TextStyle(color = ColorProvider(Color.Black, Color.White)) // (day, night)
```
`BarChartBuilder.kt` in karoo-headwind explicitly checks `isNightMode(context)` and swaps `Color.BLACK`/`Color.WHITE` backgrounds and text colors, plus a translucent label-backing box (`Color.argb(200,0,0,0)` at night vs `Color.argb(200,255,255,255)` by day) so bar labels stay legible over any bar color. **Always author two color values (day, night) for every custom graphical view**, not a single static color.

### 2.4 Typography

- karoo-ext sample dimens: `speedSize = 28sp`, `statusSize = 17sp` (`app/src/main/res/values/dimens.xml`) — rough anchor points for "primary numeral in a small field" vs "secondary label."
- karoo-headwind's `Weather()` composable: `fontSize = if (singleDisplay) 19f else 14f` — i.e. a single big 1x1 field uses ~19sp; when the same composable is reused as a compact column (e.g. inside a wider multi-column forecast strip) it drops to 14sp. Sub-labels are rendered at fractions of the primary size: `0.25×`, `0.4×`, `0.6×`, `0.65×` of a `fontSize` variable — i.e. **label text is roughly 1/3 to 2/3 the size of the primary value**, never equal or larger.
- `FontFamily.Monospace` is used everywhere for numeric/label text in karoo-headwind (`TextStyle(..., fontFamily = FontFamily.Monospace)`) — keeps digit widths stable so values don't jitter/reflow as they update every second, which matters a lot at a glance while riding.
- Bold weight (`FontWeight.Bold`) reserved for the single most important number in a field (e.g. the headline temp/speed), never for sublabels.

### 2.5 Iconography

- Both extensions use **simple, single-color (monochromatic) vector icons** as `ImageVector`/`drawable` XML, tinted at runtime via `ColorFilter.tint(ColorProvider(dayColor, nightColor))` so one asset serves both themes (`CustomSpeed.kt`, `HeadwindDirectionView.kt`).
- karoo-headwind's weather icon set (`drawable/sun.xml`, `cloud.xml`, `cloud_with_rain.xml`, `cloud_with_light_rain.xml`, `cloud_with_snow.xml`, `cloud_with_lightning.xml`, `cloud_with_lightning_and_rain.xml`, `crescent_moon.xml`, `droplet.xml`, `thermometer.xml`) is a flat, filled, rounded glyph style sourced from boxicons.com (MIT-licensed) — a good reference set to imitate or reuse under license for our own weather icons. Their mapping logic (`WeatherView.kt:getWeatherIcon`) is a clean template:
  ```kotlin
  fun getWeatherIcon(interpretation: WeatherInterpretation, isNight: Boolean): Int = when (interpretation) {
      CLEAR -> if (isNight) R.drawable.crescent_moon else R.drawable.sun
      CLOUDY -> R.drawable.cloud
      RAINY -> R.drawable.cloud_with_rain
      SNOWY -> R.drawable.cloud_with_snow
      DRIZZLE -> R.drawable.cloud_with_light_rain
      THUNDERSTORM -> R.drawable.cloud_with_lightning_and_rain
      UNKNOWN -> R.drawable.question_mark_regular_240
  }
  ```
- Directional arrows (wind/heading) are drawn as a single bitmap rotated at runtime with `Canvas.rotate`, snapped to 10° increments to avoid excessive bitmap churn:
  ```kotlin
  // HeadwindDirectionView.kt
  fun getArrowBitmapByBearing(baseBitmap: Bitmap, bearing: Int): Bitmap {
      val bearingRounded = (((bearing + 360) / 10.0).roundToInt() * 10) % 360
      // ... canvas.rotate(bearingRounded, cx, cy); canvas.drawBitmap(...)
  }
  ```
  This is the correct pattern for our "wind arrow relative to heading" field.

### 2.6 Data field construction technology

Custom graphical fields are rendered via **Jetpack Glance** (`GlanceRemoteViews`, `androidx.glance.appwidget.*`), NOT plain Compose — because the view runs out-of-process and Karoo hosts it as a `RemoteViews` (AppWidget-style) tree (see `karoo-ext/README.md` "Methodologies" section, and `CustomSpeedDataType.kt`):
```kotlin
override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
    val result = glance.compose(context, DpSize.Unspecified) { CustomSpeed(speed, config.alignment) }
    emitter.updateView(result.remoteViews)
}
```
This means our graphical fields are constrained to Glance's layout primitives (`Box`, `Row`, `Column`, `Text`, `Image`, `Spacer`) — no arbitrary Canvas drawing inside Glance composables directly (charts are instead pre-rendered to a `Bitmap` via plain `android.graphics.Canvas`, e.g. `BarChartBuilder`/`LineGraphBuilder`, then shown via Glance `Image(ImageProvider(bitmap))`). The companion phone-style app screens (settings, main list) *do* use full Jetpack Compose + Material3 (not Glance) since they run in the extension's own activity, not embedded in Karoo's ride UI.

---

## 3. Recommended design tokens for `karoo-weather`

```kotlin
object WeatherTokens {
    // Base (both day/night pairs, ColorProvider(day, night))
    val bgDay = Color.White;        val bgNight = Color.Black
    val fgDay = Color.Black;        val fgNight = Color.White
    val secondaryFgDay = Color(0xFF808080); val secondaryFgNight = Color(0xFFB0B0B0)

    // Temperature scale (cold -> hot), day-mode hexes; darken ~30% for night variants
    val tempFreezing = Color(0xFF3B7DD8) // < 0°C, icy blue
    val tempCold     = Color(0xFF5AA9E6) // 0-10°C
    val tempMild     = Color(0xFF6FB86B) // 10-20°C, green
    val tempWarm     = Color(0xFFE0A83E) // 20-28°C, amber
    val tempHot      = Color(0xFFE0553E) // > 28°C, red-orange

    // Wind scale (calm -> gale), reuse karoo-headwind's semantic hues
    val windCalm   = Color(0xFF808080) // grey, < 10 km/h
    val windLight  = Color(0xFF6FB86B) // green, 10-20
    val windModerate = Color(0xFFFF9930) // orange, 20-35
    val windStrong = Color(0xFFA30000) // dark red, > 35

    // Rain intensity (blue scale, opacity ramps with mm/h)
    val rainNone  = Color(0x00478AC9)
    val rainLight = Color(0x66478AC9)
    val rainMed   = Color(0xB3478AC9)
    val rainHeavy = Color(0xFF2A5C8A)

    // Typography (sp), scaled from ViewConfig.textSize where available
    const val primaryTextSp1x1 = 19   // single big field
    const val primaryTextSpWide = 28  // full-width field, matches karoo-ext sample speedSize
    const val secondaryTextRatio = 0.4 // sublabel = 0.4x primary, per headwind convention
    const val labelTextSp = 14
}
```

Iconography: flat, single-color (monochrome, tintable), filled/rounded style matching boxicons — sun / cloud / cloud-rain / cloud-drizzle / cloud-snow / cloud-lightning-rain / crescent-moon / droplet / thermometer / compass-arrow, all as vector drawables tinted via `ColorFilter.tint(ColorProvider(day, night))`.

---

## 4. Concrete UX proposals

### 4a. Data fields

**"Weather now" (1x1, gridSize `(30,30)`)**
```
┌───────────────┐
│      ☀        │  <- weather icon, ~40% of field height
│   22°C  ↗12   │  <- temp bold @ 19sp, wind arrow (rotated to heading) + speed @ ~8sp
└───────────────┘
```
Wide variant (2x1, gridSize `(60,30)`), side-by-side like `Weather()`'s `wideMode`:
```
┌─────────────────────────────┐
│   ☀     22°C        ↗ 12    │
│  icon   bold temp   arrow+wind │
└─────────────────────────────┘
```

**"Wind" (1x1, `(30,30)`)** — mirrors `HeadwindDirection` composable exactly:
```
┌───────────────┐
│       ↑        │   <- arrow bitmap rotated to (windBearing - heading)
│      18        │   <- speed, bold, monospace
│     km/h        │   <- unit, 0.4x size
└───────────────┘
```

**"Rain next hour" (2x1, `(60,15)` thin strip)** mini bar chart, one bar per 10-min bucket, using `BarChartBuilder`-style rendering (bitmap pre-render, Glance `Image`):
```
┌────────────────────────────────────────┐
│ ▁ ▃ ▅ █ ▆ ▂    next 60 min, mm/h        │
└────────────────────────────────────────┘
```

**"Temp" (1x1 numeric, `(30,30)` or smaller `(20,20)`)**
```
┌─────────┐
│  22°C   │  <- just the bold numeral + unit, colored by temp scale token
└─────────┘
```

**"Route forecast" (2x1 wide, `(60,15)` or `(60,30)`)** — timeline strip of icons along remaining route, following the exact `ForecastDataType` pattern (show 1 hour when `gridSize.first == 30`, show 3 when `== 60`; use distance-based buckets when a route is loaded, else time-based):
```
┌───────────────────────────────────────────────┐
│  ☀      ⛅      🌧      🌧      ⛅              │
│ 24°C   22°C    19°C    18°C    20°C            │
│ 0km    18km    36km    54km    72km            │
└───────────────────────────────────────────────┘
```
When `gridSize.first == 30` (half-width cell), collapse to a single upcoming icon+temp+distance, matching the "only show first value if placed in a 1x1 grid cell" logic from `ForecastDataType.kt:294`.

### 4b. Main app screen (companion activity, full Compose + Material3, not Glance)

Follow karoo-headwind's `MainScreen` shape: a `TabRow` (Live / Route / Settings) over full-bleed dark background, `PullToRefreshBox` for manual refresh, `AlertDialog` for first-run consent:

```
┌──────────────────────────────────────┐
│  [ Now ]   [ Route ]   [ Settings ]  │  <- TabRow, Material3 tabs
├──────────────────────────────────────┤
│  ┌────────────────────────────────┐  │
│  │      ☀   22°C                  │  │  <- current-conditions card
│  │      Wind 12 km/h ↗ NE          │  │
│  │      Rain: 0% next hour         │  │
│  └────────────────────────────────┘  │
│                                        │
│  Hourly:  ☀ ⛅ 🌧 🌧 ⛅ ☀ ☀ ☀        │  <- horizontal scroll strip
│           14 15 16 17 18 19 20 21     │
│                                        │
│  Route forecast (if loaded):          │
│   ▸ 0 km   ☀  24°C  wind 8 tail       │  <- list, one row per leg/hour
│   ▸ 18 km  ⛅  22°C  wind 14 head      │
│   ▸ 36 km  🌧  19°C  wind 20 head      │
│   ...                                  │
└──────────────────────────────────────┘
```
Settings tab (mirrors `SettingsScreen.kt`): units toggle defaulting to **follow Karoo profile** (`karooSystem.streamUserProfile()` → `UserProfile.preferredUnit`, don't force a separate app-level unit setting unless overridden), refresh-interval `RadioButton`/`Switch` group (karoo-headwind's `RefreshRate` pattern with distinct K2/K3 ms values), backend/API base URL `OutlinedTextField` + "Test connection" `FilledTonalButton`, and a `LinearProgressIndicator` while testing — same primitives already in `SettingsScreen.kt`.

### 4c. Map overlays

`karoo-ext`'s `MapEffect` (`ShowSymbols`, `HideSymbols`, `ShowPolyline`, `HidePolyline`, since 1.1.3) is the only sanctioned way to draw on the map — there is no raster/tile overlay API. Concretely useful for us:

- **Wind arrows along route**: `Symbol.Icon(id, lat, lng, iconRes, orientation)` — drop an arrow icon every N km along the remaining route, `orientation` set to the forecast wind bearing at that point (0=N, 90=E, etc.), icon recolored/sized by wind speed bucket.
- **Rain segments colored along route**: `ShowPolyline(id, encodedPolyline, color, width)` — split the remaining route polyline into segments per forecast bucket and re-issue one `ShowPolyline` per segment with `color` from the rain-intensity token scale (rainNone→rainHeavy) and a fixed `width`; update by re-calling with the same `id` as the ride progresses.
- **POI-style hazard/refuel markers** are out of scope for weather but `Symbol.POI` with `type = CAUTION` exists if we ever want to flag a severe-weather point along the route.
- Update cadence: re-issue `ShowSymbols`/`ShowPolyline` only when the underlying forecast materially changes (e.g. every new hourly bucket or > X km traveled) — don't redraw every GPS tick.

### 4d. In-Ride Alert

`KarooEffect.InRideAlert` (`id, icon (DrawableRes), title, detail, autoDismissMs, backgroundColor (ColorRes), textColor (ColorRes)`) is the sanctioned "important, ride-critical" interrupt style — reserve for things like "Heavy rain starting in 5 min" or "Strong headwind ahead," not routine forecast updates (those belong in the data fields/route list, not an alert). Use `autoDismissMs` (e.g. 8000–12000) so it clears itself; supply both a `backgroundColor`/`textColor` resource pair that resolves correctly in day/night (Android color resources should use `-night` qualifier folder, matching the OS's own day/night handling described in §2.3). For less urgent, dismiss-at-leisure info (e.g. "Weather data updated"), use `SystemNotification` (Control Center) instead, with `style = Style.EVENT`.

---

## 5. Anti-patterns to avoid on Karoo

- **Low-contrast/light themes for in-ride graphical fields.** Karoo's own fields and every reference extension use true black/white with saturated accent colors — a soft light-grey Material "surface" palette that looks fine on a phone becomes unreadable in direct sunlight at arm's length on a moving bike. Always author explicit day/night color pairs (§2.3), never rely on a single medium-contrast palette.
- **Small or thin text.** karoo-headwind's smallest sub-label is still ~0.25× of a ~19sp base (≈4.75sp is the practical floor and only ever used for tertiary annotations); never go below ~10sp real size for anything the rider is meant to read, and never use hairline/light font weights — everything meaningful is regular-or-bolder.
- **Dense multi-metric layouts crammed into small grid cells.** A `(30,30)` field is roughly a quarter of a 480×800 (portrait) or a sixteenth of a `(60,60)` page — cramming icon + 3 numbers + units into that space (as opposed to 1 bold numeral + 1 short sublabel) becomes illegible; follow the "show fewer items, bigger" scaling karoo-headwind uses when `gridSize.first == 30` vs `60` (§2.1/§4a).
- **Anything requiring scrolling or multi-tap drill-down while riding.** Data fields must show their complete meaning at a glance with zero interaction (tap-to-cycle, as in `WeatherForecastDataType`'s "tap to cycle 12h forecast," is acceptable as a *bonus* but the default/untouched state must already be useful). Scrolling belongs only in the companion app's non-riding screens (Settings, Route list), never in a ride-page data field.
- **Ignoring `alignment` / `boundariesEnabled` from `ViewConfig`.** Custom graphical fields that hardcode left/center alignment or always draw/never draw a border ignore explicit user configuration that Karoo passes in specifically so 3rd-party fields match native look — always read and respect these from `ViewConfig`.
- **Non-monospace / reflowing numerals.** Proportional fonts on rapidly-updating numeric fields cause visible width jitter every update tick; use `FontFamily.Monospace` (or a fixed-width numeral font) for anything that updates in near-real-time (speed, temp, wind).
- **Overusing `InRideAlert` for routine updates.** It's a modal-style interrupt meant for critical, actionable information; using it for "forecast refreshed" or "temp changed 1°" trains users to dismiss/ignore it, defeating its purpose for genuine hazard alerts (storm approaching, severe headwind ahead).
- **Redrawing map overlays every tick.** `ShowPolyline`/`ShowSymbols` should be re-issued on meaningful state change (new forecast bucket, route re-load), not on every GPS fix — needless churn costs battery and can visually flicker.
- **A generic Material purple/teal "default template" palette** (`#6200EE`/`#03DAC5` from the karoo-ext sample) is leftover Android Studio boilerplate, not a deliberate Hammerhead identity — don't treat it as "the" Karoo brand and build our theme around it; build from the semantic/day-night approach in §2.2–2.3 instead.

---

## Key files referenced

- `scratchpad/ref/karoo-ext/README.md` — extension architecture, Glance-based views, RemoteViews rationale.
- `scratchpad/ref/karoo-ext/lib/src/main/kotlin/io/hammerhead/karooext/models/ViewConfig.kt` — gridSize/viewSize/textSize/alignment/boundariesEnabled/preview.
- `scratchpad/ref/karoo-ext/lib/src/main/kotlin/io/hammerhead/karooext/models/RideProfile.kt` — page/element gridSize semantics (60-unit grid).
- `scratchpad/ref/karoo-ext/lib/src/main/kotlin/io/hammerhead/karooext/models/MapEffect.kt` — ShowSymbols/HideSymbols/ShowPolyline/HidePolyline.
- `scratchpad/ref/karoo-ext/lib/src/main/kotlin/io/hammerhead/karooext/models/Symbol.kt` — POI/Icon symbol types.
- `scratchpad/ref/karoo-ext/lib/src/main/kotlin/io/hammerhead/karooext/models/KarooEffect.kt` — `SystemNotification`, `InRideAlert`.
- `scratchpad/ref/karoo-ext/app/src/main/kotlin/io/hammerhead/sampleext/extension/CustomSpeedDataType.kt`, `CustomSpeed.kt` — minimal Glance data-field example.
- `scratchpad/ref/karoo-ext/app/src/main/res/values/{colors,dimens,styles}.xml` — sample field sizing/colors (boilerplate, not brand).
- `scratchpad/ref/karoo-headwind/app/src/main/kotlin/de/timklge/karooheadwind/datatypes/HeadwindDirectionView.kt` — arrow-rotation pattern, day/night background.
- `scratchpad/ref/karoo-headwind/app/src/main/kotlin/de/timklge/karooheadwind/datatypes/WeatherView.kt` — weather-icon mapping, temp/wind row layout, wide vs compact modes.
- `scratchpad/ref/karoo-headwind/app/src/main/kotlin/de/timklge/karooheadwind/datatypes/ForecastDataType.kt` — route/time-based forecast strip, `gridSize.first == 30/60` branching.
- `scratchpad/ref/karoo-headwind/app/src/main/kotlin/de/timklge/karooheadwind/screens/BarChart.kt` — pre-rendered bitmap chart with night-mode-aware colors.
- `scratchpad/ref/karoo-headwind/app/src/main/kotlin/de/timklge/karooheadwind/screens/MainScreen.kt`, `SettingsScreen.kt` — Material3 tabbed companion app shell.
- `scratchpad/ref/karoo-headwind/app/src/main/res/values/colors.xml` — semantic green/orange/red day+night hue pairs.
- `scratchpad/ref/karoo-headwind/app/src/main/kotlin/de/timklge/karooheadwind/theme/Theme.kt` — companion-app Material3 color scheme.
- `scratchpad/ref/karoo-headwind/README.md` — field catalogue, sizes, weather-provider notes, extension data-type interop.
- [Karoo Hardware Specifications](https://support.hammerhead.io/hc/en-us/articles/25687624385691-Karoo-Hardware-Specifications), [Karoo 2 Hardware Specifications](https://support.hammerhead.io/hc/en-us/articles/360058709333-Karoo-2-Hardware-Specifications), [Karoo Display](https://support.hammerhead.io/hc/en-us/articles/25696850944795-Karoo-Display) — device specs.
- [Karoo OS - Data Field Design](https://support.hammerhead.io/hc/en-us/articles/25068018005275-Karoo-OS-Data-Field-Design) — user-facing icon/boundary/alignment settings referenced in §4b/§5.
