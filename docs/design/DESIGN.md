# karoo-weather — DESIGN

Visual specification for data fields, map symbols, and the companion app. **Revision 2** — incorporates
CRITIQUE.md.

Target hardware: Karoo 2 / Karoo 3, 3.2" panel, ~293 ppi, outdoors, gloved, vibrating, at 40–60 cm.
**In-ride geometry is portrait 480 × 800** (the panel is portrait-native and ride pages are portrait;
`karoo-ux.md` §1). Consequently a full-width `(60, n)` field is **480 px wide** and `n` grid units tall map to
`n / 60 × 800` px: `(60,15)` ≈ 480 × 200, `(60,30)` ≈ 480 × 400, `(30,30)` ≈ 240 × 400, `(60,60)` ≈ 480 × 800.

Every mock below is drawn against those numbers. **They are still an assumption until spike S3
(ARCHITECTURE §13) logs the real `ViewConfig` on hardware** — which is why no layout hard-codes a column
count: see §3.0.

Design law, in order of precedence:
1. **Legible in direct sunlight.** True black / true white base, saturated accents only.
2. **Understood without interaction.** A field's default state is already the answer.
3. **Native-looking.** Honour `ViewConfig.alignment`, `boundariesEnabled`, `textSize`, `gridSize`, `viewSize`.
4. **No jitter.** Monospace numerals, fixed slots, nothing reflows between updates.

---

## 1. Tokens

### 1.1 Palette

Every colour is a **(day, night) pair**. Glance-drawn elements consume it as
`ColorProvider(Color(pair.day), Color(pair.night))`; `Canvas`-drawn bitmaps consume it as
`pair.pick(isNightMode(context))`, because a Canvas cannot resolve a `ColorProvider` (ARCHITECTURE §7.5).
Karoo's night mode is a ride-lighting mode, not just an OS theme; a single hex is always wrong.

```kotlin
// «root»/ui/theme/Tokens.kt  — the single source of colour truth. Plain Long ARGB:
// no androidx.glance, no androidx.compose imports, so both renderers can consume it.
object Wx {
    // Base
    val bg        = ColorPair(0xFFFFFFFF, 0xFF000000)   // true white / true black
    val fg        = ColorPair(0xFF000000, 0xFFFFFFFF)
    val fgMuted   = ColorPair(0xFF5A5A5A, 0xFFB0B0B0)   // labels, units, axes, probability line
    val divider   = ColorPair(0xFFD0D0D0, 0xFF3A3A3A)

    // Temperature ramp — blue → neutral → amber → red. NO GREEN: see §1.2.
    val tempFreezing = ColorPair(0xFF2E63B8, 0xFF6FA8FF)   // < 0 °C
    val tempCold     = ColorPair(0xFF3E86C4, 0xFF7FC4FF)   // 0–10
    val tempMild     = ColorPair(0xFF000000, 0xFFFFFFFF)   // 10–20  == fg, deliberately uncoloured
    val tempWarm     = ColorPair(0xFFB07200, 0xFFFFC048)   // 20–28
    val tempHot      = ColorPair(0xFFB3341F, 0xFFFF7A5C)   // > 28

    // Wind / headwind semantics. GREEN IS RESERVED FOR THIS SCALE AND NOTHING ELSE.
    val windTail   = ColorPair(0xFF008000, 0xFF00E000)     // helping
    val windCalm   = ColorPair(0xFF5A5A5A, 0xFFB0B0B0)     // neutral
    val windCross  = ColorPair(0xFFBB4300, 0xFFFF9930)     // caution
    val windHead   = ColorPair(0xFFA30000, 0xFFFF5454)     // opposing

    // Rain (bars, route wet cells)
    val rainLight  = ColorPair(0xFF7FB3DC, 0xFF4E86B8)
    val rainMed    = ColorPair(0xFF3D7FB5, 0xFF6FB5EA)
    val rainHeavy  = ColorPair(0xFF1B4F7A, 0xFF9FD4FF)
}
```

**Alert colours are NOT in `Wx`.** `InRideAlert.backgroundColor` / `textColor` are `@ColorRes`
(`models/KarooEffect.kt:249,253`), so the only source of truth is `res/values/colors.xml` +
`res/values-night/colors.xml`:

| resource | values | values-night |
|---|---|---|
| `alert_bg` | `#1B4F7A` | `#0E2C46` |
| `alert_fg` | `#FFFFFF` | `#FFFFFF` |
| `field_fg` | `#000000` | `#FFFFFF` |
| `field_bg` | `#FFFFFF` | `#000000` |

**Thresholds** (shared by fields, strip cells and map icons — one table, no local re-invention):

| Scale | Buckets |
|---|---|
| Temperature °C | `< 0` freezing · `0–10` cold · `10–20` mild (uncoloured) · `20–28` warm · `> 28` hot |
| Headwind m/s (signed, + = head) | `≤ −2.8` tail · `−2.8..1.4` calm · `1.4..4.2` cross/moderate · `> 4.2` head |
| Rain mm per 15 min | `< 0.1` none · `0.1–0.5` light · `0.5–2.0` med · `> 2.0` heavy |
| Wind arrow class | `|rel| < 45°` tail · `45–135°` cross · `> 135°` head |

### 1.2 One colour, one meaning

Green means **exactly one thing: the wind is helping you.** The previous revision also used green for the
10–20 °C temperature bucket, so `weather-now` at `(60,30)` could show a green temperature beside a green wind
arrow — in direct sunlight at 50 cm they are indistinguishable, and only one of them is actionable.
`tempMild` is therefore the plain foreground colour: mild weather needs no colour, and the temperature ramp
now reads blue → nothing → amber → red, which is monotonic and unambiguous.

### 1.3 Typography

- Family: **`FontFamily.Monospace`** for every number and unit. Digits must not change width between ticks.
  Labels (names, "Feels like") may be the default sans.
- Weight: `Bold` for exactly one number per field. Everything else `Normal`. Never `Light`.
- Sizes are **derived from `ViewConfig.textSize`** (the sp Karoo uses for a native numeric field at that grid
  size), never absolute:

| Role | Size | Example at `textSize = 32` |
|---|---|---|
| Primary value | `1.00 × textSize`, bold | 32 sp |
| Secondary value (gust, second metric) | `0.55 ×` | 18 sp |
| Unit suffix | `0.40 ×` | 13 sp |
| Column label (distance / clock) | `0.36 ×`, min **10 sp** | 12 sp |
| Micro annotation (axis ticks) | `0.30 ×`, min **9 sp** | 10 sp |

Floor: nothing the rider is meant to read renders below 10 sp. If the computed size is smaller, **drop the
element rather than shrink it** — that is the whole "fewer things, bigger" rule, and it is enforced
structurally by §3.0 rather than left to each renderer's judgement.

### 1.4 Spacing & geometry

- Field padding: `4.dp` when `boundariesEnabled`, `2.dp` otherwise.
- Row gap `2.dp`, column gap `4.dp`. Route-strip column dividers: 1 px `Wx.divider`, only between columns.
- Icon box: `0.42 ×` the shorter field dimension, capped 24–56 dp.
- **Wind arrow bitmap: 48 × 48 px** (56 px when `viewSize.second > 300`). The previous 128 px figure was
  four times more pixels than a 293 ppi 3.2" field can show and 64 KB per bitmap across the Binder.
  Bearing is quantised to **10°**, so at most 36 rotations per (size, tint) exist.
- Bar chart and route strip: **one bitmap sized to `config.viewSize`** (§3.0), bars fill the width, gap =
  `barWidth × 0.25`, baseline 1 px `Wx.fgMuted`.

### 1.5 Alignment & chrome

`ViewConfig.alignment` maps to Glance horizontal alignment (`LEFT→Start`, `CENTER→CenterHorizontally`,
`RIGHT→End`) and the icon always sits on the **opposite** side from the primary number.
`UpdateGraphicConfig(showHeader = …)`: `false` for `wind`, `rain-next-hour`, `route-forecast` (they need every
pixel); `true` for `weather-now` (its header reads "Weather" and earns its row).

---

## 2. Icon set

All vector drawables, `24 × 24 dp` viewport, **single path group, solid fill `#FF000000`**, no gradients, no
strokes thinner than 1.5 dp, tinted at runtime — one asset serves both themes. Style: filled, rounded,
boxicons-like. Shapes must read at 24 px.

Tinting works two ways and the difference matters: Glance elements use
`ColorFilter.tint(ColorProvider(day, night))` and switch theme automatically; anything drawn into a Canvas
(the route strip, the bar chart, the pre-rotated arrow) has its tint **baked in** at
`pair.pick(isNightMode(context))` and is part of that bitmap's cache key.

**WMO condition icons** (day/night variants only where a sun/moon appears):

| drawable | Used for WMO codes |
|---|---|
| `ic_wmo_clear_day` / `ic_wmo_clear_night` | 0 |
| `ic_wmo_partly_day` / `ic_wmo_partly_night` | 1, 2 |
| `ic_wmo_cloudy` | 3 |
| `ic_wmo_fog` | 45, 48 |
| `ic_wmo_drizzle` | 51, 53, 55 |
| `ic_wmo_freezing` | 56, 57, 66, 67 |
| `ic_wmo_rain` | 61, 63 |
| `ic_wmo_rain_heavy` | 65 |
| `ic_wmo_showers` | 80, 81, 82 |
| `ic_wmo_snow` | 71, 73, 77, 85 |
| `ic_wmo_snow_heavy` | 75, 86 |
| `ic_wmo_thunder` | 95 |
| `ic_wmo_thunder_hail` | 96, 99 |
| `ic_wmo_unknown` | anything else |

**Utility icons:**

| drawable | Shape |
|---|---|
| `ic_wind_arrow` | Solid triangular arrow, tip at 12 o'clock (0° = wind blowing towards the top of the field). Rotated at runtime. |
| `ic_wind_ring` | Thin ring the arrow sits inside, marking the rider's heading frame. |
| `ic_gust` | Arrow with two trailing speed lines. |
| `ic_drop` | Single droplet — precipitation amount. |
| `ic_umbrella` | Umbrella — precipitation probability. |
| `ic_thermometer` | Bulb thermometer — temperature field's picker icon. |
| `ic_compass` | Compass rose with N marked. |
| `ic_route` | Chevron path — route-forecast picker icon. |
| `ic_map_wind_arrow` | **EXEMPT from the single-path rule.** `32 × 32 dp`, **two paths**: a white outline path drawn first, then the black arrow on top. Fixed colours, `android:fillColor` hard-coded, **no runtime tint** — it is drawn over map tiles by Karoo, where our day/night `ColorProvider` never applies, and it needs the halo to survive both light and dark basemaps. |
| `ic_weather` | Existing app / extension icon. |

Icon credits (boxicons MIT, Noto Emoji OFL if reused) go in `res/raw/icon_credits.txt` and are surfaced in
Settings → About.

---

## 3. Data field layouts

Grid recap: `ViewConfig.gridSize` is (column span, row span) on a 60-unit grid.
`(30,30)` = 1×1 square · `(60,30)` = wide · `(30,60)` = tall · `(60,60)` = full page · `(60,15)` = strip.

### 3.0 The structural rule that keeps text above the floor

**`gridSize` decides which rows exist. `viewSize` decides how many columns fit.** Never hard-code a column
count from `gridSize` alone: a five-column strip that is legible at 480 px is illegible on a narrower
`viewSize`, and §1.3's 10 sp floor must be enforced by structure, not by hope.

```kotlin
// datatypes/views/FieldChrome.kt — one implementation, used by every multi-column renderer
const val MIN_CELL_PX = 88
fun columnsFor(viewSize: Pair<Int, Int>, maxColumns: Int): Int =
    (viewSize.first / MIN_CELL_PX).coerceIn(1, maxColumns)
```

| gridSize | rows drawn | maxColumns | columns at 480 px |
|---|---|---|---|
| `first == 30` | icon / temp / distance | 1 | 1 |
| `(60,15)` | icon+temp on one row / distance | 3 | 3 |
| `(60,30)` | icon / temp+arrow / distance | 5 | 5 |
| `(60,60)` | icon / temp / arrow / distance / ETA | 6 | 5 |

Additionally, `route-forecast` and `rain-next-hour` are **rendered as one `viewSize`-sized bitmap** and shown
in a single `Image(ImageProvider(bitmap))`. They are not composed cell-by-cell out of per-cell bitmaps: a
`RemoteViews` is Parcelled across a Binder on every `updateView`, and five arrows plus five WMO icons at
ARGB_8888 would push ~640 KB through a ~1 MB transaction. `weather-now` and `wind` remain Glance-composed
(text plus at most one small icon and one 48 px arrow).

### 3.1 `weather-now`

`(30,30)` — 1×1, header shown. **Two elements only.** The previous revision put icon + temp + arrow + wind
speed in a quarter-screen field; `karoo-ux.md` §5 names exactly that as the anti-pattern and prescribes
"1 bold numeral + 1 short sublabel". Wind belongs to the `wind` field.
```
┌───────────────┐
│ ☁ Weather     │  header (Karoo-drawn)
│      ☀        │  WMO icon, 0.42× short side
│     22°C      │  bold 1.0×
└───────────────┘
```

`(60,30)` — wide, three columns:
```
┌───────────────────────────────────────┐
│ ☁ Weather                             │
│    ☀        22°C        ↗ 14 km/h     │
│   icon     bold 1.0×    arrow + 0.55× │
└───────────────────────────────────────┘
```

`(30,60)` — tall, stacked with two extra rows:
```
┌───────────────┐
│ ☁ Weather     │
│      ☀        │
│     22°C      │  bold
│   feels 24°   │  0.55×, muted
│   ↗ 14 km/h   │  0.55×
│   ☂ 20%       │  0.55×, muted
└───────────────┘
```

`(60,60)` — full page, adds an hourly outlook strip. Column count is `columnsFor(viewSize, maxColumns = 6)`
— **4 to 5 at 480 px, not the six the previous revision drew.** Six columns of icon + temp on a 3.2" screen
puts the labels under the floor.
```
┌─────────────────────────────────────────────────┐
│ ☁ Weather                                       │
│      ☀           22°C          ↗ 14 km/h        │
│                feels 24°       G 26 km/h        │
│ ─────────────────────────────────────────────── │
│   14h      15h      16h      17h      18h       │
│    ☀        ☀        ⛅       🌧       🌧        │
│   22°      23°      22°      20°      19°       │
└─────────────────────────────────────────────────┘
```

States: stale (> 3 h) renders the temperature in `fgMuted` with a leading `~`. No data ⇒
`FieldChrome.customState(context, R.string.state_no_data, Wx.fg, night)`.

### 3.2 `wind`

Header hidden. The arrow is the field; it points where the wind pushes the rider, in the rider's own frame
(`rel = Geo.signedAngleDifference(heading, windToDir)`, so straight up = pure tailwind, straight down =
headwind). Arrow colour follows the headwind ramp — and is the **only** green on the device.

`(30,30)`:
```
┌───────────────┐
│      ↑        │  arrow in ring, 0.5× field height, coloured
│      18       │  bold 1.0×, monospace
│     km/h      │  0.40×, muted
└───────────────┘
```

`(60,30)`:
```
┌───────────────────────────────────────┐
│    (↑)       18 km/h        G 27      │
│   arrow      bold 1.0×      0.55×     │
└───────────────────────────────────────┘
```

`(30,60)` / `(60,60)` — adds the meteorological origin under the value:
```
┌───────────────┐
│      ↑        │
│      18       │
│     km/h      │
│   G 27 km/h   │  0.55×
│   from NE     │  0.40×, muted
└───────────────┘
```

`(60,15)` strip: single row `(↑) 18 km/h  G 27  from NE`.

### 3.3 `rain-next-hour`

Header hidden. **One bitmap at `config.viewSize`.** 8 buckets × 15 min = 2 h from `minutely_15`; when the
nowcast is unavailable, 3 hourly bars labelled by clock hour and a `~` marker in the corner to signal the
coarser source. Bar colour from the rain ramp; a dry forecast still draws the baseline and the word "Dry" so
the field never looks broken.

`(60,15)` — the design target (480 × 200 px):
```
┌────────────────────────────────────────────────┐
│ ▁ ▃ ▅ █ ▆ ▂ ▁ ▁            starts 14:20 · 1.4mm│
│ └──────────── 2 h ───────────┘                 │
└────────────────────────────────────────────────┘
```

`(30,15)` — labels dropped, bars only:
```
┌──────────────────────┐
│ ▁▃▅█▆▂▁▁      1.4mm  │
└──────────────────────┘
```

`(60,30)` / `(60,60)` — adds the probability polyline and a time axis. The polyline is **`Wx.fgMuted`
(#5A5A5A, ~7:1 on white) with a 2 px stroke**, not the old `rainProb` #8A8A8A at 1 px: a thin line needs
*more* contrast than body text, not less, and #8A8A8A on white is ~3.5:1. The overlay is **drawn only when
`gridSize.second >= 30`** — below that there is no room for it to mean anything.
```
┌────────────────────────────────────────────────┐
│         ╭──╮                                   │  probability 0–100 %, fgMuted, 2 px
│ ▁ ▃ ▅ █ ▆ ▂ ▁ ▁                                │
│ 14:00      14:30      15:00      15:30         │
│ Rain starts 14:20 · 1.4 mm in 2 h              │
└────────────────────────────────────────────────┘
```

### 3.4 `route-forecast`

Header hidden. **One bitmap at `config.viewSize`.** A timeline of the **remaining** route. Rows come from
`gridSize`, columns from `columnsFor(viewSize, maxColumns)` per the table in §3.0. Bottom label is
distance-ahead when a route is loaded, clock time when not.

`(60,15)` — 3 columns:
```
┌────────────────────────────────────────────────┐
│   ☀       ⛅        🌧                          │
│  22°  ↗  20°  ↘   18°  ↓                       │
│ +0km    +18km    +36km                         │
└────────────────────────────────────────────────┘
```

`(60,30)` — up to 5 columns, wind arrow gets its own row. Note the ETA row is **not** here: at 480 × 400 px
minus a divider, five stacked rows put the two bottom labels at the floor. ETA moves to `(60,60)`.
```
┌────────────────────────────────────────────────┐
│   ☀      ⛅      🌧      🌧      ⛅             │
│  22°    21°     18°     18°     20°            │
│   ↗      ↗       ↓       ↓       ↖             │
│ +0km   +18km   +36km   +54km   +72km           │
└────────────────────────────────────────────────┘
```

`(60,60)` — full page, ETA row added:
```
┌────────────────────────────────────────────────┐
│   ☀      ⛅      🌧      🌧      ⛅             │
│  22°    21°     18°     18°     20°            │
│   ↗      ↗       ↓       ↓       ↖             │
│ +0km   +18km   +36km   +54km   +72km           │
│ 14:03  14:55   15:48   16:40   17:32           │  ETA row, 0.36×
└────────────────────────────────────────────────┘
```

`(30,·)` — single upcoming column:
```
┌───────────────┐
│      🌧        │
│     18°C      │
│      ↓ 22     │
│    in 36 km   │
└───────────────┘
```

Column 0 is always **the rider's own position** (`RouteForecast.points[0]`, `distanceAlong == progress`),
which is why it always reads `+0km`.

Cell background tint: wet cells (`precip ≥ 0.2 mm`) get a 12 % `rainMed` wash so the wet stretch of the route
is findable in one glance. Temperature digits carry the temperature ramp colour (never green). Arrows carry
the headwind ramp colour and point in the rider's frame at that point (route tangent as "up").

No route loaded ⇒ same layout, time labels (`14h  15h  16h`), and a small `ic_route` glyph struck through in
the corner. A column flagged `beyondHorizon` reads `>12h` in `fgMuted`.

### 3.5 Numeric fields

`temperature` is `graphical="false"`. Karoo renders it with its own numeric chrome; we send the value in
**canonical SI (°C)** and one `UpdateNumericConfig(formatDataTypeId = DataType.Type.TEMPERATURE)`, and Karoo
does the unit conversion and precision from the user profile. Do not draw it ourselves — matching the native
field pixel-for-pixel is impossible and unnecessary.

```
┌─────────┐
│ TEMP    │
│  22°C   │
└─────────┘
```

`apparent-temperature` and `headwind-speed` are **deferred to v1.1** (ARCHITECTURE ADR-0 #7). "Feels like" is
already a row inside `weather-now`, and a `graphical="false"` field cannot render the `+8 / −8` sign
convention a headwind field needs: with `UpdateNumericConfig` we supply only a `Double` and Karoo owns the
glyphs, so the leading `+` is not ours to draw and Karoo's `SPEED` formatter is untested against negatives.

---

## 4. Map symbols

- Wind arrow every 2 / 5 / 20 km by zoom bucket, `Symbol.Icon(orientation = windToDir.toFloat())`,
  `ic_map_wind_arrow`. `Symbol.Icon.orientation` is documented as "0 is North, 90 is East, 180 is South,
  −90 is West" — the meteorological convention after the `+180` flip, so the arrow points where the wind goes.
- A WMO icon is added only where `precip ≥ 0.2 mm`, so the map stays quiet on a dry ride.
- `Symbol.Icon` offers no size, anchor, z-order or label control, so the asset itself carries a built-in
  white halo (§2, the two-path exemption) and is drawn heavier than the in-field arrow.
- The rider's own position gets no symbol — Karoo already draws it.

---

## 5. Companion app

Compose + Material3 (**not** Glance — this runs in our own Activity). Three tabs in a `TabRow`, and an
explicit back affordance at the bottom-left because Karoo has no system back bar.

**The scheme follows `isSystemInDarkTheme()`**, built from the same `Wx` pairs the fields use. Karoo OS
applies night mode system-wide; a forced-light app is a full-screen white flash for a rider checking the
forecast at dusk while the extension's own fields are correctly dark beside it. It is ~10 lines and one token
table.

Light: `primary = #1B4F7A`, `secondary = #5A5A5A`, `tertiary = #B07200`, `background = #FFFFFF`,
`onBackground = #000000`. Dark: `primary = #9FD4FF`, `secondary = #B0B0B0`, `tertiary = #FFC048`,
`background = #000000`, `onBackground = #FFFFFF`. Deliberately **not** the `#6200EE / #03DAC5` Android
template palette — that is boilerplate in the karoo-ext sample, not a Hammerhead identity.

**Refresh is a button, not a gesture.** The previous revision specified a `PullToRefreshBox` over the whole
content while §7 forbade drag gestures in the same document; gloves and vibration make a pull unreliable, and
it was the *only* path to a manual refresh. It is replaced by an explicit **56 dp "Refresh" button in the Now
tab header**. (A pull gesture may be added later as a redundant extra, never as the only path.)

```
┌──────────────────────────────────────────┐
│   [ Now ]     [ Route ]    [ Settings ]  │  TabRow, 56 dp tall
├──────────────────────────────────────────┤
│  Now                          [ ⟳ ]      │  Refresh, 56 dp target
│  ┌────────────────────────────────────┐  │
│  │  ☀   22°C          Paris, 14:03    │  │  current card
│  │      feels 24°                     │  │
│  │  ↗ 14 km/h  G 26   from NE         │  │
│  │  ☂ 20 %  ·  0.0 mm next 2 h        │  │
│  └────────────────────────────────────┘  │
│  Next 12 hours                            │
│  ┌────────────────────────────────────┐  │
│  │ 14 15 16 17 18 19 20 21 22 23 00 01│  │  horizontal scroll
│  │ ☀  ☀  ⛅ 🌧 🌧 ⛅ ☀  ☀  ☀  🌙 🌙 🌙│  │
│  │ 22 23 22 20 19 19 18 17 16 15 14 14│  │
│  │ ▁  ▁  ▂  ▆  █  ▃  ▁  ▁  ▁  ▁  ▁  ▁│  │  rain bars
│  └────────────────────────────────────┘  │
│  Updated 3 min ago · Open-Meteo           │
│  ◀                                        │  back, 48 dp target
└──────────────────────────────────────────┘
```

**Route tab** — one row per sampled point, scrollable; header summarises the ride. Row 0 is the rider:
```
┌──────────────────────────────────────────┐
│  Tour du Vexin · 78 km · ETA 17:40       │
│  🌧 Rain from km 36 (14:20) · 2.1 mm     │
├──────────────────────────────────────────┤
│  +0 km    14:03  ☀   22°  ↗  tail  6     │
│  +18 km   14:55  ⛅  21°  ↗  cross 12    │
│  +36 km   15:48  🌧  18°  ↓  head  22    │  ← row tinted rainMed 12 %
│  +54 km   16:40  🌧  18°  ↓  head  20    │
│  +72 km   17:32  ⛅  20°  ↖  cross 9     │
└──────────────────────────────────────────┘
```
No route loaded ⇒ empty state (§6). Rows past the forecast horizon are greyed and labelled `>12h`.

**Settings tab** — one `Switch`/`Dropdown` per row, 56 dp rows, saved immediately (dropdowns/switches) or on
focus loss (text fields), clamped on save:
```
┌──────────────────────────────────────────┐
│ Units                                     │
│   Temperature      [ Follow Karoo  ▾ ]    │
│   Wind speed       [ Follow Karoo  ▾ ]    │
│ Route forecast                            │
│   Use measured speed          [ ●— ]      │
│   Assumed speed    [ 22 ] km/h            │
│ Updates                                   │
│   Refresh every    [ 30 min       ▾ ]     │
│   Location privacy [ 3 km grid    ▾ ]     │
│   Field repaint    [ 2 s          ▾ ]     │
│ On the bike                               │
│   Wind arrows on map          [ ●— ]      │
│   Rain alert                  [ —○ ]      │
│ About                                     │
│   Weather data by Open-Meteo.com          │
│   (CC BY 4.0)                             │
│   Icons: boxicons (MIT)                   │
│   karoo-weather 1.0.0                     │
└──────────────────────────────────────────┘
```

**First run**: a blocking `AlertDialog` explaining that the extension sends the rider's position, rounded to
a 3 km grid, to Open-Meteo, with Accept / Cancel. `consentAccepted` gates all network access — nothing is
fetched before Accept.

---

## 6. Empty / loading / error states

Data-field messages go through `FieldChrome.customState(context, @StringRes, ColorPair, night)` — never a raw
`ShowCustomStreamState(R.string.x, colorPair)`, which does not compile: the SDK's signature is
`ShowCustomStreamState(message: String?, @ColorInt color: Int?)`.

| State | Data field | App |
|---|---|---|
| Never fetched, consent pending | `customState(ctx, R.string.state_setup, Wx.fg, night)` — "Open app" | first-run dialog |
| Loading, no cache | `StreamState.Searching` / `customState(ctx, R.string.state_loading, Wx.fgMuted, night)` | skeleton card + `LinearProgressIndicator` |
| Loading, cache present | Cached values stay on screen; no spinner in a data field, ever | inline progress under the Refresh button |
| No GPS | `customState(ctx, R.string.state_no_gps, Wx.fg, night)` | "Waiting for GPS fix" with `ic_compass` |
| Offline / retrying | Cached values, `~` prefix once stale | banner "Offline — showing data from 14:03", `Retry` button |
| Permanent error (4xx / parse) | `StreamState.NotAvailable` | red banner with the message and a `Retry` button |
| No route (route field) | Time-axis fallback, struck-through `ic_route` | "No route loaded — load a route on the Karoo to see the forecast along it" + `ic_route` |
| Beyond forecast horizon | last column `>12h`, muted | rows past the horizon greyed |

Never show a raw exception or an HTTP status to the rider. Errors are one short sentence plus a `Retry`.

---

## 7. Touch targets

Only the companion app is interactive; data fields have at most a whole-field tap.

- Minimum touch target **48 × 48 dp**, preferred 56 dp for anything used with gloves on.
- Tab row 56 dp; settings rows 56 dp; Refresh button 56 dp; back button 48 dp with 8 dp margin from both edges.
- No swipe-to-dismiss, no long-press, no drag: gloves and vibration make them unreliable. Nothing in this
  document may specify a drag gesture as the only path to an action (§5).
- Data-field taps (`weather-now`, `route-forecast` → open the app) are attached only when
  `!config.preview`, and the whole field is the target.

---

## 8. Page-editor previews

`ViewConfig.preview == true` means the page editor may instantiate several fields at once — including two
instances of the *same* field, on one shared `DataTypeImpl`. Each `startView` therefore builds its own
`GlanceRemoteViews` and its own bitmap cache (ARCHITECTURE §4.3), renders `PreviewData` — a fixed, plausible
snapshot — and makes **no** network or repository call:

```
temp 22 °C · feels 24 °C · wind 14 km/h from NE (45°) · gusts 26 km/h
precip 0.4 mm · precipProb 40 % · wmoCode 61 (rain) · cloudCover 60 % · isDay true
route: 5 points at 0/18/36/54/72 km, ETAs +0/+55/+108/+160/+212 min,
       codes 0/2/61/61/2, temps 22/21/18/18/20, headwind −6/+3/+22/+20/+9 km/h
```

The values are chosen so every visual mechanism is exercised in the picker: a clear cell, a cloudy cell, two
wet cells, a tailwind arrow, a headwind arrow, and a temperature spread that crosses two ramp buckets
(`PreviewDataTest` asserts exactly that). The preview must look like a good day's data, not like an error
state — it is the field's advertisement.

Icons in the page editor come from `<DataType icon="…">`: `ic_weather` (weather-now), `ic_thermometer`
(temperature), `ic_wind_arrow` (wind), `ic_drop` (rain-next-hour), `ic_route` (route-forecast).
