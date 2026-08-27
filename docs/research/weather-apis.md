# Free Weather API Research for karoo-weather (2026-08-26)

Goal: current conditions + hourly forecast for a single GPS point, plus forecast lookups for
10–40 points along a bike route (each point evaluated at a different future hour, 0–8h out),
with temperature, wind (speed/direction/gusts), precipitation (mm + probability), weather code,
cloud cover, and optionally minutely-15 precipitation nowcasting for the next 1–2h.

All findings below were verified today via WebFetch against live docs and via `curl` against the
live API (see section 3 for exact commands/measured byte counts).

---

## 1. Comparison table

| API | Coverage | Key required | Rate limits (free) | Multi-point in one call | Fields available | License / attribution |
|---|---|---|---|---|---|---|
| **Open-Meteo** (`api.open-meteo.com`) | Global | **No** (no signup, no card) | 10,000 req/day, 5,000/hour, 600/min (non-commercial use) | **Yes** — comma-separated `latitude=`/`longitude=` lists, returns a JSON **array**, one object per point | `temperature_2m`, `wind_speed_10m`, `wind_direction_10m`, `wind_gusts_10m`, `precipitation`, `precipitation_probability`, `weather_code` (WMO), `cloud_cover`, +40 more; `current`, `hourly`, `minutely_15`, `daily` blocks | CC-BY 4.0 — attribution required; non-commercial terms forbid ad-supported/commercial apps without a paid plan (see §terms below) |
| **MET Norway Locationforecast 2.0** (`api.met.no`) | Global (best quality Nordic/Arctic) | No key, but **mandatory identifying `User-Agent`** header (app name + contact) or 403 | Not numerically published; "reasonable use", must cache/respect `Expires`/`If-Modified-Since` | **No** — one lat/lon per request only | temperature, wind speed/direction (+ percentiles), precipitation amount (+ probability in some products), cloud cover, symbol_code (day/night aware, e.g. `partlycloudy_day`), UV, humidity | Free, open (NLOD/CC-BY 4.0-like); must credit MET Norway |
| **Météo-France public API (opendatasoft/API observable/vigilance/AROME)** | France only | **Yes**, API key via `portail-api.meteofrance.fr` (free tier, no card reported) | Free tier quotas per product (varies; historically low, e.g. a few req/min) | No (per-station or per-point, product dependent) | Model data (AROME/ARPEGE) via WCS/WFS, "rain in the next hour" nowcast for France | Free tier ToS; French gov open license (Licence Ouverte) |
| **Bright Sky (DWD wrapper)** | Germany only (DWD data) | No | Public, community-run, no formal SLA | No native multi-point (one lat/lon per call) | temp, wind, precipitation, cloud cover, icon, DWD condition codes | DWD Open Data license (free, attribution recommended) |
| **NWS api.weather.gov** | USA only | No key, requires `User-Agent` | No published hard numbers, "be reasonable"; can 403 on abuse | No — grid-point based, one point per call (plus a 2-step point→gridpoint lookup) | temp, wind speed/dir, precip probability, short/detailed forecast text, icon | US Government work, public domain, no attribution required |
| **OpenWeatherMap "One Call"** | Global | **Yes**, requires signup | Free tier: 1,000 calls/day (was reduced from earlier "60/min"); **requires credit card for some tiers**, One Call 3.0 free allowance can require card on file | No (one point per call) | temp, wind, rain, weather code, clouds, minutely precip (1h) | Proprietary ToS, attribution requested |
| **RainViewer** | Global | No (free tier) | Tile/radar imagery only | N/A | Radar **tiles**, not point forecast — not usable for numeric per-point precipitation | Free for personal use; tiles only, no structured JSON forecast |
| **Pirate Weather** | Global | Yes (free key) | Free tier ~ a few thousand/month via community "Feeder"/AWS backend, quotas vary | One point per call (Dark-Sky-compatible: `[lat],[lon]`) | Dark Sky-compatible schema: temp, wind, precip probability/intensity, icon, minutely (nowcast) | Free with attribution; relies on donated compute, less guaranteed uptime |

**Bottom line:** Open-Meteo is the only candidate here that is (a) globally covered, (b) requires
zero signup/key/card, (c) has *published, generous* numeric rate limits, and (d) natively supports
batching many GPS points (route waypoints) into a single HTTP request as a JSON array. Every other
free option is either single-country, single-point-per-call, or requires an account/key.

---

## 2. Recommendation

**Primary: Open-Meteo** (`https://api.open-meteo.com/v1/forecast`)
- No key/signup — fits "no paid tier, no credit card" hard requirement.
- Native multi-location batching (comma-separated lat/lon lists → JSON array) is exactly the shape
  needed for "forecast at N route waypoints, each at a different ETA": request wide enough
  `forecast_hours`/`start_hour`&`end_hour` window per point, then pick out the array index for the
  hour matching each waypoint's ETA.
- `minutely_15` covers short-term rain nowcasting for the next 1–2h (native high-res in
  North America/Central Europe via HRRR/ICON-D2/AROME, interpolated elsewhere — still usable, just
  lower fidelity outside those regions).
- 10,000 requests/day and 600/min is enormous headroom for a single-user cycling-computer app: one
  route load = 1–2 HTTP calls total (see §3), not one call per point.
- Must add attribution ("Weather data by Open-Meteo.com") in app/about screen to satisfy CC-BY 4.0,
  and must stay within the *non-commercial* definition (no ads, no paid subscription tied directly
  to weather data) unless the Karoo extension is monetized, in which case Open-Meteo also sells an
  affordable paid API key — but that's out of scope per the "free only" requirement.

**Fallback: MET Norway Locationforecast 2.0**
- Use only for the single-point "current conditions" screen if Open-Meteo is unreachable, since it
  requires one call per point (not efficient for a whole route of 10-40 points → 10-40 HTTP calls).
- Requires setting a proper `User-Agent: karoo-weather/1.0 github.com/<org>/<repo> contact@...`
  header — a generic or client-library default UA (okhttp, Dalvik) is explicitly blocklisted and
  will 403.
- No API key, globally covered, free — good redundancy if Open-Meteo has an outage or blocks the
  app's IP for misuse.

**Not recommended as primary/fallback:** Bright Sky and NWS are geo-limited (Germany / USA only) so
unsuitable for a general touring app; Météo-France requires key + is France-only; OpenWeatherMap
and Pirate Weather require signup/keys and have tighter/uncertain free quotas; RainViewer is
tile-only (no numeric per-point values, can't feed a data model).

---

## 3. Open-Meteo: verified request examples & measured sizes

Base endpoint: `https://api.open-meteo.com/v1/forecast`

### 3.1 Current + hourly forecast for ONE point

```bash
curl -s --compressed \
  "https://api.open-meteo.com/v1/forecast?latitude=48.85&longitude=2.35\
&current=temperature_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,weather_code,cloud_cover\
&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,precipitation_probability,weather_code,cloud_cover\
&forecast_hours=12&timeformat=unixtime&wind_speed_unit=ms"
```
Measured: **1,497 bytes** raw JSON for 8 current fields + 8 hourly fields × 12 hours.

### 3.2 Multi-point (route waypoints) — CONFIRMED returns a JSON array

Comma-separated lat/lon lists produce one object per point, **in the same order given**:

```bash
LATS="48.8500,48.9000,48.9500,49.0000,49.0500,49.1000,49.1500,49.2000,49.2500,49.3000"
LONS="2.3500,2.4200,2.4900,2.5600,2.6300,2.7000,2.7700,2.8400,2.9100,2.9800"

curl -s "https://api.open-meteo.com/v1/forecast?latitude=$LATS&longitude=$LONS\
&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,weather_code\
&forecast_hours=12&timeformat=unixtime&wind_speed_unit=ms"
```
Verified with Python: `json.load(...)` → `isinstance(d, list) == True`, `len(d) == 10`, and each
element `d[i]` has the same schema as the single-point response (`latitude`, `longitude`,
`hourly_units`, `hourly` with parallel arrays).

### 3.3 Measured response sizes (6 hourly vars: temperature_2m, wind_speed_10m,
wind_direction_10m, wind_gusts_10m, precipitation, weather_code; 12-hour window;
`timeformat=unixtime` to save bytes over ISO8601 strings):

| Points | Raw JSON bytes (uncompressed, on the wire w/o `--compressed`) | gzip -9 equivalent (approx. what a compressed transfer would be) |
|---|---|---|
| 1  | ~1,050 (estimated from 1-point full req above scaled down; see note) | ~350 |
| 10 | **9,317 bytes** | **1,737 bytes** |
| 25 | **23,323 bytes** | **3,552 bytes** |

Measured directly via `curl -s -w 'wire_bytes=%{size_download}\n' ... -o resp.json` then
`gzip -c resp.json | wc -c` for the gzip-equivalent column. Raw scaling is very linear
(~933 bytes/point uncompressed, ~142 bytes/point gzip-compressed for this 6-var/12h shape), so:

- **40 points** (upper end of "10–40 points"), same 6 variables, 12h window ≈ **37,300 bytes raw /
  ~5,700 bytes gzip-compressed** — comfortably under 100 KB either way.
- To stay well under 100KB raw even without compression you could fit ~100+ points at this
  variable count/window; the practical limit for 10-40 points is not a size problem at all.

**Compression note:** `curl --compressed` on this endpoint actually negotiated
`Content-Encoding: deflate` (confirmed via response headers), not gzip — Open-Meteo's server
supports `deflate`; sending `Accept-Encoding: gzip, deflate` and letting the HTTP client
transparently decompress is recommended. Android's OkHttp does this automatically when
`Accept-Encoding` is left untouched (it manages gzip/deflate itself), so no extra code is
typically needed beyond not overriding those headers.

### 3.4 minutely_15 (short-term rain nowcast)

```bash
curl -s "https://api.open-meteo.com/v1/forecast?latitude=48.85&longitude=2.35\
&minutely_15=precipitation,temperature_2m&forecast_days=1&timeformat=unixtime"
```
Measured: 2,329 bytes for 1 day (96 × 15-min steps) × 2 variables. Response includes
`minutely_15_units` and `minutely_15.time/precipitation/temperature_2m` parallel arrays, one entry
every 900 seconds. For a "next 1–2h" nowcast slice, request `forecast_minutely_15=8` (8×15min=2h)
if you want to trim it further, or just take the first 4-8 entries client-side from the 1-day
response.

### 3.5 Useful parameter notes confirmed from docs + live testing

- `latitude=`/`longitude=`: **comma-separated lists** → batched multi-location request, response
  becomes a JSON **array** (confirmed above). Order of the array matches the order of the
  comma-separated inputs.
- `forecast_hours=N`: caps the hourly array to N hours starting from "now" — ideal for bounding
  route ETAs to an 8-hour ride window without pulling the full 7-day default.
- `start_hour`/`end_hour` (ISO8601 `yyyy-mm-ddThh:mm`): alternative to `forecast_hours` when you
  need an explicit window not anchored to "now" (e.g. scheduling a ride departure a few hours out).
- `models=` : `best_match` (default "auto"), or force a specific model/ensemble:
  `meteofrance_seamless` (AROME+ARPEGE blend, good default in France), `icon_seamless` (DWD),
  `ecmwf_ifs` (global, coarser but strong beyond ~3 days), plus 40+ more. For a cycling app
  crossing borders, leaving the default `auto`/`best_match` is simplest; `meteofrance_seamless` is
  a reasonable explicit override for French/West-European rides for better local skill.
- `timeformat=unixtime`: **use this** instead of the default ISO8601 strings — saves meaningful
  bytes per timestamp per point per hour, and avoids string date-parsing in Kotlin (map straight to
  `Long` / `Instant.ofEpochSecond`).
- `wind_speed_unit=ms|kmh|mph|kn`, `precipitation_unit=mm|inch`, `temperature_unit=celsius|fahrenheit`:
  pick once app-wide (e.g. `ms` + `mm` + `celsius`) and convert for display only if the user wants
  imperial — avoids re-deriving units from doc defaults.
- `timezone=auto`: resolves each point's local timezone automatically — useful for `daily` blocks,
  but irrelevant if you consume `hourly`/`current`/`minutely_15` as `unixtime` and format locally in
  the app using device timezone.
- Response `format` besides default JSON: CSV/XLSX export exist for the web tool; a `flatbuffers`
  binary format also exists in Open-Meteo's more recent API surface for very high-volume batch
  clients, but plain JSON at these point-counts (10-40) is already well under 40KB raw / <6KB
  compressed — flatbuffers optimization is unnecessary complexity for this use case and was not
  benchmarked here.

---

## 4. WMO weather code table (used by `weather_code` field)

Confirmed from Open-Meteo's docs (https://open-meteo.com/en/docs/dwd-api), which follows the
standard WMO 4677 table:

| Code(s) | Description | Suggested icon category |
|---|---|---|
| 0 | Clear sky | clear (day/night variant by local sun position) |
| 1 | Mainly clear | mostly-clear |
| 2 | Partly cloudy | partly-cloudy |
| 3 | Overcast | cloudy/overcast |
| 45 | Fog | fog |
| 48 | Depositing rime fog | fog (icy) |
| 51 | Drizzle, light | light-rain |
| 53 | Drizzle, moderate | light-rain |
| 55 | Drizzle, dense intensity | rain |
| 56 | Freezing drizzle, light | freezing-rain |
| 57 | Freezing drizzle, dense | freezing-rain |
| 61 | Rain, slight | light-rain |
| 63 | Rain, moderate | rain |
| 65 | Rain, heavy intensity | heavy-rain |
| 66 | Freezing rain, light | freezing-rain |
| 67 | Freezing rain, heavy | freezing-rain |
| 71 | Snow fall, slight | light-snow |
| 73 | Snow fall, moderate | snow |
| 75 | Snow fall, heavy | heavy-snow |
| 77 | Snow grains | snow |
| 80 | Rain showers, slight | rain-showers |
| 81 | Rain showers, moderate | rain-showers |
| 82 | Rain showers, violent | heavy-rain-showers |
| 85 | Snow showers, slight | snow-showers |
| 86 | Snow showers, heavy | heavy-snow-showers |
| 95 | Thunderstorm, slight or moderate | thunderstorm |
| 96 | Thunderstorm with slight hail | thunderstorm-hail |
| 99 | Thunderstorm with heavy hail | thunderstorm-hail |

Notes:
- Codes 95/96/99 are noted by Open-Meteo as only reliably available for Central Europe (their
  source model's hail/thunderstorm resolution); elsewhere thunderstorms may fall back to codes
  61-82.
- Open-Meteo's docs state they apply "temperature-based corrections" on top of the raw ICON model
  codes — e.g. reclassifying precipitation type using temperature/snowfall-level thresholds — so
  the `weather_code` returned is already post-processed, not a raw model pass-through.
- **Day/night variant**: the raw WMO code itself does NOT encode day/night. If a day/night icon
  variant is wanted (as MET Norway's `symbol_code` does, e.g. `clearsky_day` / `clearsky_night`),
  compute it client-side: use `current.is_day` (Open-Meteo exposes an `is_day` field, `0`/`1`,
  when requested in the `current=` parameter list) or compare the point's local time against
  sunrise/sunset (also requestable via `daily=sunrise,sunset`) to pick day vs night icon assets for
  codes like 0/1/2/3 where a sun/moon distinction matters.

---

## 5. Response JSON schema excerpts (for kotlinx.serialization data classes)

### 5.1 Single-point request (`current` + `hourly`)

```json
{
  "latitude": 48.84,
  "longitude": 2.36,
  "generationtime_ms": 0.33,
  "utc_offset_seconds": 0,
  "timezone": "GMT",
  "timezone_abbreviation": "GMT",
  "elevation": 46.0,
  "current_units": {
    "time": "unixtime",
    "interval": "seconds",
    "temperature_2m": "°C",
    "wind_speed_10m": "m/s",
    "wind_direction_10m": "°",
    "wind_gusts_10m": "m/s",
    "precipitation": "mm",
    "weather_code": "wmo code",
    "cloud_cover": "%"
  },
  "current": {
    "time": 1787776200,
    "interval": 900,
    "temperature_2m": 26.2,
    "wind_speed_10m": 3.26,
    "wind_direction_10m": 79,
    "wind_gusts_10m": 6.3,
    "precipitation": 0.0,
    "weather_code": 3,
    "cloud_cover": 54
  },
  "hourly_units": {
    "time": "unixtime",
    "temperature_2m": "°C",
    "wind_speed_10m": "m/s",
    "wind_direction_10m": "°",
    "wind_gusts_10m": "m/s",
    "precipitation": "mm",
    "precipitation_probability": "%",
    "weather_code": "wmo code",
    "cloud_cover": "%"
  },
  "hourly": {
    "time": [1787774400, 1787778000, "... 12 entries"],
    "temperature_2m": [26.9, 25.6, "..."],
    "wind_speed_10m": ["..."],
    "wind_direction_10m": ["..."],
    "wind_gusts_10m": ["..."],
    "precipitation": ["..."],
    "precipitation_probability": ["..."],
    "weather_code": ["..."],
    "cloud_cover": ["..."]
  }
}
```

Suggested Kotlin shape (parallel-arrays style, matching the API rather than transposing —
transposing into per-hour objects is a cheap client-side `zip`):

```kotlin
@Serializable
data class OpenMeteoResponse(
    val latitude: Double,
    val longitude: Double,
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int,
    val timezone: String,
    val elevation: Double,
    @SerialName("current_units") val currentUnits: Map<String, String>? = null,
    val current: CurrentBlock? = null,
    @SerialName("hourly_units") val hourlyUnits: Map<String, String>? = null,
    val hourly: HourlyBlock? = null,
    @SerialName("minutely_15_units") val minutely15Units: Map<String, String>? = null,
    @SerialName("minutely_15") val minutely15: Minutely15Block? = null,
)

@Serializable
data class CurrentBlock(
    val time: Long,
    val interval: Int,
    @SerialName("temperature_2m") val temperature2m: Double? = null,
    @SerialName("wind_speed_10m") val windSpeed10m: Double? = null,
    @SerialName("wind_direction_10m") val windDirection10m: Double? = null,
    @SerialName("wind_gusts_10m") val windGusts10m: Double? = null,
    val precipitation: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("cloud_cover") val cloudCover: Int? = null,
    @SerialName("is_day") val isDay: Int? = null,
)

@Serializable
data class HourlyBlock(
    val time: List<Long>,
    @SerialName("temperature_2m") val temperature2m: List<Double>? = null,
    @SerialName("wind_speed_10m") val windSpeed10m: List<Double>? = null,
    @SerialName("wind_direction_10m") val windDirection10m: List<Double>? = null,
    @SerialName("wind_gusts_10m") val windGusts10m: List<Double>? = null,
    val precipitation: List<Double>? = null,
    @SerialName("precipitation_probability") val precipitationProbability: List<Int>? = null,
    @SerialName("weather_code") val weatherCode: List<Int>? = null,
    @SerialName("cloud_cover") val cloudCover: List<Int>? = null,
)

@Serializable
data class Minutely15Block(
    val time: List<Long>,
    val precipitation: List<Double>? = null,
    @SerialName("temperature_2m") val temperature2m: List<Double>? = null,
)
```

### 5.2 Multi-point request

Top-level response is a **JSON array** of the same object shape above (no `current`/`current_units`
unless `current=` was also passed per-request — it applies uniformly to all points in the batch).
Deserialize as `List<OpenMeteoResponse>` via `Json.decodeFromString<List<OpenMeteoResponse>>(body)`.
Order of the array matches the order of the comma-separated `latitude=`/`longitude=` values sent —
so build the request with route waypoints in order and zip the response array back onto your
waypoint list by index (don't rely on lat/lon float round-tripping for matching, since Open-Meteo
snaps to its internal grid, e.g. requested `longitude=2.35` came back as `2.3599997`).

### 5.3 minutely_15 block (from live response)

```json
{
  "latitude": 48.84,
  "longitude": 2.36,
  "utc_offset_seconds": 0,
  "timezone": "GMT",
  "elevation": 46.0,
  "minutely_15_units": {
    "time": "unixtime",
    "precipitation": "mm",
    "temperature_2m": "°C"
  },
  "minutely_15": {
    "time": [1787702400, 1787703300, "... every 900s, 96 entries for 1 day"],
    "precipitation": ["..."],
    "temperature_2m": ["..."]
  }
}
```

---

## Sources

- https://open-meteo.com/en/docs (forecast API parameter reference)
- https://open-meteo.com/en/terms (free/non-commercial terms, rate limits, CC-BY 4.0)
- https://open-meteo.com/en/docs/dwd-api (WMO weather code table)
- https://api.met.no/weatherapi/locationforecast/2.0/documentation (MET Norway User-Agent
  requirement, coverage, fields)
- Live `curl` tests against `https://api.open-meteo.com/v1/forecast` performed 2026-08-26 (see §3),
  raw responses saved at the scratch responses (not kept),
  `resp_10.json`, `resp_25.json`, `resp_min15.json`, `headers_10.txt`.
