# karoo-weather — DESIGN REVIEW / CRITIQUE

Reviewer stance: skeptical senior Karoo-extension developer, pre-implementation.
Every SDK claim below was checked against `scratchpad/ref/karoo-ext/lib/src/main/kotlin/io/hammerhead/karooext/**`
(karoo-ext 1.1.9 sources), `docs/karoo-sdk.md`, `docs/headwind-patterns.md`, `docs/weather-apis.md`,
and the live project skeleton at `/Users/glandais/code/perso/karoo-weather`.

Verdict in one line: **the architecture is sound and the ADRs are well argued (ADR-0 #3 and #4 both verified
correct), but the plan ships roughly 2.5× the scope a v1 should, and there are ~10 hard API/contract errors that
will not compile or will silently misbehave.** Fix S1 before WP0 starts; S1 items are mostly one-line contract
edits, and they are far cheaper now than after 8 parallel agents have built on them.

Legend: **S1** = blocker (wrong API, will not compile / will not work) · **S2** = major (bug, contract gap,
constraint risk) · **S3** = minor (polish, unverified assumption).

---

## S1 — Blockers

### 1. `ShowCustomStreamState` is called with the wrong argument types, in two documents (S1)
**Where** ARCHITECTURE §5.5 (`ShowCustomStreamState(R.string.stale, …)`), DESIGN §6 (`ShowCustomStreamState(R.string.state_setup, fg)`), DESIGN §3.1 (`ShowCustomStreamState(getString(R.string.state_no_data), fg)`).
**Evidence** `models/ViewEvent.kt`: `data class ShowCustomStreamState(val message: String?, @ColorInt val color: Int?)`. The first argument is a **resolved String**, not a `@StringRes Int`; the second is a **resolved @ColorInt Int**, not a `ColorPair`.
**Fix** Everywhere: `emitter.onNext(ShowCustomStreamState(context.getString(R.string.state_no_data), Wx.fg.pick(night)))`. Add this exact form to PLAN WP4's "Implementation rules" so seven files don't each invent it.

### 2. `consumerFlow<T>()` throws for `OnStreamState` and `OnHttpResponse` (S1)
**Where** PLAN WP3: `inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T>`, presented as universal; ARCHITECTURE §8 uses `consumerFlow<OnMapZoomLevel>()`.
**Evidence** `KarooSystemService.kt:228-250` — the no-params `addConsumer` resolves default params from a hard-coded `when (T::class)` covering exactly 11 event types and ends `else -> throw IllegalArgumentException("No default KarooEventParams for ...")`. `OnStreamState` and `OnHttpResponse` are **not** in that list; both require the `addConsumer(params, …)` overload (`OnStreamState.StartStreaming(dataTypeId)` / `OnHttpResponse.MakeHttpRequest(...)`).
**Fix** Restrict `consumerFlow<T>()`'s contract to the 11 whitelisted events (document them), and make `streamDataFlow(dataTypeId)` use the params overload explicitly. Same for `streamLocation()` if it goes through `DataType.Type.LOCATION` rather than `OnLocationChanged`.

### 3. `WeatherRepository.attach(karoo)` with two different `KarooSystemService` instances is a lifecycle trap (S1)
**Where** ARCHITECTURE §4.1/§4.2, PLAN WP3 (`fun attach(karoo: KarooSystemService)` "idempotent, ref-counted"), PLAN WP5 (`WeatherApp` owns *its own* `KarooSystemService` and calls `repo.attach(karoo)`).
**Evidence** `WeatherGraph` is a process singleton and `WeatherExtension` + `MainActivity` share the process (no `android:process`). A ref-counted `attach` keeps the **first** service instance. If the user opens the companion app before Karoo binds the extension (common: that is exactly what the consent gate forces them to do), the repository latches onto the *Activity's* service. `KarooSystemService.disconnect()` (`:129-137`) removes **all** listeners and calls `context.unbindService`; on `onDispose` the repository is then holding a dead service and every stream is silently gone for the remaining process lifetime. `unbindService` on a never-bound service also throws `IllegalArgumentException`.
**Fix** The repository owns exactly one `KarooSystemService(appContext)`, created lazily on first attach and disconnected on last detach. `attach()`/`detach()` take no argument. `MainActivity` and `WeatherExtension` both just call `attach()`/`detach()`. Guard `disconnect()` in try/catch.

### 4. `RouteForecast.points[0]` vs `RouteSampler.sample()` — an off-by-one across a package boundary (S1)
**Where** ARCHITECTURE §3 (`RouteForecast.points`: "First entry is the rider's position"), §5.4 ("Index 0 is the rider's position; indices 1..N-1 are the route samples"), vs PLAN WP1 `RouteSampler.sample(path, progress, maxPoints)` — "Samples **ahead of** `progress`", returns only route samples, `MAX_ROUTE_POINTS = 24`, vs `WeatherRequest.MAX_POINTS = 25`.
**Fix** One owner. Recommend: `RouteSampler` returns route samples only (24 max, never including the rider), and `WeatherRepository` is solely responsible for prepending the rider point and for the 25-point request. State in PLAN WP1 that `sample()` never emits a point at `distanceAlong == progress`.

### 5. `RouteSampler` cannot implement the 11-hour horizon truncation its own test demands (S1)
**Where** ARCHITECTURE §6.4 ("any sample whose ETA exceeds `now + 11 h` is dropped and replaced by a single 'horizon' sample"), ARCHITECTURE §12 `RouteSamplerTest` — "horizon truncation at 11 h".
**Evidence** PLAN WP1 signature `fun sample(path: RoutePath, progress: Double, maxPoints: Int): List<RouteSample>` has no clock, no speed, no ETA. The truncation is unimplementable and untestable at that signature.
**Fix** Either add `fun truncateToHorizon(samples, eta: (Double) -> Long, nowSec: Long, horizonSec: Long = 11*3600): List<RouteSample>` to WP1 (pure, testable), or move the truncation into WP3's `buildRouteForecast` and move the test with it. Do not leave it split.

### 6. `WeatherError.Parse` is declared non-retryable but §11 retries it (S1)
**Where** ARCHITECTURE §3 `data class Parse(val detail: String) : WeatherError("parse", false)`; §5.2 "Non-retryable (`Client 4xx`, `Parse`) stops the loop"; §11 "Response > 100 KB / null body → `WeatherError.Parse`; **point count halved on the next attempt**"; PLAN WP3 maps null body → `Parse("empty")` and oversize → `Parse("oversize")`.
**Evidence** Direct self-contradiction: an oversize or empty response *is* retryable (with fewer points), a malformed JSON body is not, and both are `Parse`.
**Fix** Split the error: `Oversize(val bytes: Int)` and `EmptyBody` (both retryable, both trigger point reduction) vs `Parse(detail)` (non-retryable). This is the karoo-headwind pitfall #15 the architecture explicitly set out to fix — do not reintroduce it under a different name.

### 7. WP0 and WP8 both own five files; PLAN rule 1 says ownership is disjoint (S1)
**Where** PLAN "Rules for concurrent agents" #1/#2 vs WP0 "Files created" vs WP8 "Files created / edited".
**Evidence** `app/src/main/res/values/strings.xml`, `values/colors.xml`, `values-night/colors.xml`, `res/xml/extension_info.xml` and `app/build.gradle.kts` appear in **both** WP0 and WP8. WP4/WP5/WP6 additionally have "Resources requested (WP8 must add)" tables for strings WP0 was told to create.
**Fix** State it once: **WP0 creates these files with the complete, final content** (all strings from every WP's request table, all colors, all 7 `<DataType>` entries); **WP8 may only edit them**, and WP1–WP7 may not touch them at all. Then delete the "WP8 must add" tables and fold their contents into WP0's list, or the last agent to run silently drops half the strings.

### 8. WP5 depends on WP4, but the dependency graph schedules them in parallel (S1)
**Where** PLAN WP5 "Consumes: … **WP4 `views.WmoIcons`** (read-only)" vs the dependency graph at the end, which puts WP4, WP5, WP6 as parallel siblings under WP3.
**Fix** `WmoIcons` is a pure `WmoCategory + isDay → @DrawableRes Int` map with no Glance dependency. Move it to **WP2** (`weather/WmoIcons.kt`, alongside `WmoCodes`). Then WP4/WP5/WP6 are genuinely independent.

### 9. `WindUnit.labelRes: String` forces `Resources.getIdentifier()` at render time (S1)
**Where** ARCHITECTURE §3 `enum class WindUnit(val perMs: Double, val labelRes: String) { MS(1.0, "unit_ms"), … }`; PLAN WP4 requests string resources named `unit_ms`, `unit_kmh`, ….
**Evidence** The enum stores a resource *name*, not an id. Resolving it needs `resources.getIdentifier(name, "string", pkg)` — deprecated since API 29, broken by R8 resource shrinking, and the release build already has `isMinifyEnabled = true`. Using `@StringRes Int` instead would put an Android import in `domain`, which ARCHITECTURE §2 forbids.
**Fix** Drop `labelRes` from the enum. Put a `fun WindUnit.labelRes(): Int` (a `when` returning `R.string.*`) in `datatypes/views/FieldChrome.kt` and a Compose twin in `ui/`. Keeps `domain` Android-free and R8-safe.

### 10. Verify `karoo-ext` still resolves before WP8 deletes the GitHub-Packages repository (S1 risk)
**Where** PLAN WP8: "remove the GitHub-Packages repository block from `settings.gradle.kts` (it needs credentials no CI has)."
**Evidence** `settings.gradle.kts` currently declares `maven.pkg.github.com/jonasfranz/ktor-client-karoo` **with credentials**, plus `google()`, `mavenCentral()`, `jitpack.io`. headwind-patterns §1.2 records that karoo-ext itself comes from GitHub Packages. If `io.hammerhead:karoo-ext:1.1.9` resolves only from a GH-Packages repo, deleting the block breaks the build for everyone.
**Fix** Before WP8, run `./gradlew --refresh-dependencies assembleDebug` with an empty `~/.gradle.properties` (no `gpruser`/`gprkey`) and confirm karoo-ext resolves from `mavenCentral()`/`jitpack`. Also note the current `providers.gradleProperty("gpruser").getOrElse(System.getenv("USERNAME"))` NPEs on macOS/CI where `USERNAME` is unset (`USER`/`LOGNAME` are the POSIX names) — headwind pitfall #10 in a new costume.

---

## S2 — Major

### 11. Per-cell bitmaps in one `RemoteViews` will blow the Binder budget (S2)
**Where** DESIGN §3.4 (5-column route strip: icon + temp + arrow per column), §1.3 (arrow bitmap "fixed 128 × 128 px"), PLAN WP4 `ArrowBitmaps.rotated(..., sizePx = 128)`.
**Evidence** A `RemoteViews` is Parcelled across a Binder on every `updateView`. 128×128 ARGB_8888 = **64 KB per bitmap**. A 5-column strip with 5 arrows + 5 WMO icons ≈ 640 KB in a single transaction, against a ~1 MB Binder limit — `TransactionTooLargeException` or severe jank, worst on K2. karoo-headwind avoids this by rendering the *whole* graph as **one** bitmap sized to `config.viewSize` (`LineGraphBuilder.drawLineGraph(config.viewSize.first, config.viewSize.second, …)`, headwind-patterns §2.2/§2.3).
**Fix** Render `route-forecast` and `rain-next-hour` as a single `config.viewSize`-sized bitmap. Keep Glance composition for `weather-now`/`wind` only, and drop the arrow bitmap to 48–64 px (a 3.2"/293 ppi field never shows a 128 px arrow at more than ~1:1 anyway).

### 12. `ArrowBitmaps` cache has no bound and nothing calls `clear()` (S2)
**Where** PLAN WP4 `object ArrowBitmaps { fun rotated(context, res, bearingDeg, sizePx, tint): Bitmap; fun clear() }` — "results cached per (res, bucket, size, tint)".
**Evidence** Process-lifetime `object`, 36 bearing buckets × 4 headwind tint colours × 2 sizes × 64 KB ≈ **18 MB** of retained bitmaps, in the same process as the extension service. Nothing in PLAN calls `clear()`.
**Fix** `LruCache<String, Bitmap>` with an explicit byte budget (~2 MB), keyed on `(res, bucket10, sizePx, tint)`; call `clear()` from `setCancellable` when the last view of that type stops. Also use `Bitmap.Config.ARGB_8888` only where alpha is needed.

### 13. `GlanceRemoteViews` and the bitmap caches are shared across concurrent `startView` calls on one `DataTypeImpl` (S2)
**Where** PLAN WP4 implementation rules; ARCHITECTURE §7.
**Evidence** `KarooExtension`'s binder resolves `types.firstOrNull { it.typeId == typeId }` and calls `startView` on that **single shared instance** (`extension/KarooExtension.kt`, `startView`). The page editor instantiates several previews at once (DESIGN §8 says so explicitly), and the same field can be on two profile pages. Two concurrent `startView`s therefore share one `GlanceRemoteViews` and one mutable cache.
**Fix** Create `GlanceRemoteViews()` **inside** `startView`, per view. Make any per-view mutable state local to the `startView` body.

### 14. Nothing specifies how a Glance/Canvas renderer learns it is in night mode (S2)
**Where** DESIGN §1.1 ("every colour is a (day, night) pair"), PLAN WP0 `ColorPair.pick(night: Boolean)`, PLAN WP4 `BarChartBuilder.render(..., night: Boolean, ...)` and `ArrowBitmaps.rotated(..., tint: Int)`.
**Evidence** `ColorProvider(day, night)` resolves automatically only for **Glance-drawn** elements. A `Canvas`-drawn bitmap has to pick a side itself, and no contract in PLAN produces that boolean. karoo-headwind uses `isNightMode(context)` from `Configuration.uiMode`.
**Fix** Add `fun isNightMode(context: Context): Boolean` to WP0 (or `datatypes/views/FieldChrome.kt`) and name it in WP4's rules, so four view files don't each invent it — and so DESIGN §2's "tinted at runtime with `ColorFilter.tint(ColorProvider(day, night))` — one asset serves both themes" is not applied to pre-rotated bitmaps, where it does not work.

### 15. `WeatherRepository.state` is a `val StateFlow` but its scope only exists after `attach()` (S2)
**Where** ARCHITECTURE §4.2 ("the repository owns a `CoroutineScope(...)` created on first `attach` and cancelled on the last `detach`") vs PLAN WP3 `val state: StateFlow<WeatherSnapshot>`.
**Evidence** A `stateIn(scope)` needs its scope at construction. Cancelling that scope on last detach also kills the `StateFlow`, so a second `attach` gets a dead flow.
**Fix** Two scopes: a repository-lifetime scope for `state`/DataStore-backed flows (never cancelled), and an attach-lifetime `SupervisorJob` child for the fetch loop, map layer and alerter.

### 16. The fetch loop's retry runs inside the trigger collector, so a backoff swallows triggers for up to 5 minutes (S2)
**Where** ARCHITECTURE §5.1 (`transformLatest { while(true){ emit(it); delay(refreshInterval) } }.conflate()`) + §5.2 (backoff up to 300 s, per-request timeout 20 s).
**Evidence** With `.conflate()` and a suspending collector, a 20 s request + 300 s backoff blocks the collector for 320 s and keeps only the *last* trigger. Loading a route mid-backoff would show nothing for five minutes — precisely when the rider is looking.
**Fix** The trigger flow writes a `RefreshKey` into a `Channel(CONFLATED)` / `MutableStateFlow`; a separate long-lived job does request + retry + backoff and restarts immediately when the key changes.

### 17. `RefreshKey` keys on the whole settings object — headwind pitfall #17, reproduced (S2)
**Where** ARCHITECTURE §5.1 `RefreshKey(settings = it.settings, …)`.
**Evidence** `WeatherSettings` contains `viewRefreshMs`, `mapLayerEnabled`, `rainAlertEnabled`, `tempUnit`, `windUnit` — none of which change the *request*. Toggling "wind arrows on map" costs an HTTP round trip. headwind-patterns pitfall #17 flags this exact behaviour.
**Fix** Key on `(consentAccepted, roundLocationKm, refreshMinutes, assumedSpeedKmh, useMeasuredSpeed, lastRefreshRequestedAt)` only.

### 18. Progress falls back to 0 forever on breadcrumb routes (S2)
**Where** ARCHITECTURE §6.3 "When the stream is absent (no turn-by-turn yet) fall back to the last known progress, else 0."
**Evidence** `NavigatingRoute.breadcrumb = true` means turn-by-turn is disabled; `DataType.Type.DISTANCE_TO_DESTINATION` may then never stream. Progress stays 0 for the entire ride, so the route strip permanently shows the weather at the route *start* — silently wrong, not visibly broken, which is worse.
**Fix** When the stream is absent, project the live GPS position onto the path. That needs `fun nearestDistanceTo(p: GeoPoint): Double` on `RoutePath` — **it is not in PLAN WP1's API list**. Add it, with a test.

### 19. Request A and request B are merged by index across two independent responses (S2)
**Where** ARCHITECTURE §5.4; PLAN WP2 "`fetch` … merges request B's `current`, `apparentTemperature` hourly and `minutely15` into index 0 of the result."
**Evidence** Both requests use `forecast_hours=12` anchored to "now". Issued seconds apart across an hour boundary, their hourly arrays are offset by one hour, and an index merge shifts "feels like" by 60 minutes with no symptom.
**Fix** Merge by `time` (epoch seconds), not by index. Cheap, and the DTOs already carry `hourly.time`.

### 20. `estimateResponseBytes` under-estimates against the project's own measured numbers (S2)
**Where** ARCHITECTURE §5.4 size guard `bytes ≈ 260 + points × (120 + hourlyVars × hours × 9)`, budget 80 000; vs §1.1's own figure of 31.1 KB for 25 points.
**Evidence** The formula gives `260 + 25 × (120 + 8×12×9) = 24 950` B — **20 % below** the architecture's own estimate, and its 6-var/12 h case gives 768 B/point against a **measured 933 B/point** (weather-apis.md §3.3). The guard is optimistic in the one direction that matters.
**Fix** Calibrate the constants against the committed `multi_point_25.json` fixture (make `OpenMeteoUrlTest` assert `estimateResponseBytes(25, 8, 12) >= fixture.length`), and apply a 1.5× safety factor. There is enormous headroom (31 KB of a 100 KB ceiling), so being conservative costs nothing.

### 21. Nothing can tell the repository that `rain-next-hour` is visible or that the app is foregrounded (S2)
**Where** ARCHITECTURE §5.4: request B is "issued only when `rain-next-hour` is visible, the rain alert is on, or the app is in the foreground."
**Evidence** Visibility is derived from `ActiveRidePage` inside the *view* (PLAN WP3 `streamDataTypeVisible`). PLAN WP3's `WeatherRepository` API has no way to receive that signal, and no foreground signal exists at all. The condition is unimplementable at the stated contract.
**Fix** Simplest correct answer: **always issue request B** — it is ~1.8 KB and 2 % of the budget. Delete the condition. If you insist on the gate, add `fun setNowcastWanted(key: String, wanted: Boolean)` to the repository and name the callers.

### 22. `streamActiveRideProfile()` is used but never declared (S2)
**Where** ARCHITECTURE §5.1 `.filter { !it.rideProfileIndoor }` and §11 ("Indoor ride profile (`RideProfile.indoor`) ⇒ fetching suspended").
**Evidence** PLAN WP3's exposed flow list has no `streamActiveRideProfile()`. The event is `ActiveRideProfile` (`KarooEvent.kt`, since 1.1.5) carrying `RideProfile.indoor`.
**Fix** Add `fun KarooSystemService.streamActiveRideProfile(): Flow<RideProfile>` to WP3's exact API list.

### 23. Which ViewEvent configures a numeric field is never decided, and the SDK has two candidates (S2)
**Where** ARCHITECTURE §7 / DESIGN §3.5 say `UpdateNumericConfig(formatDataTypeId = …)`; ARCHITECTURE §4.3's non-negotiable list mentions only `UpdateGraphicConfig`; PLAN WP4's `NumericDataType` declares `open val formatDataTypeId: String? = null` and never says what is emitted.
**Evidence** `models/ViewEvent.kt` has both. `UpdateNumericConfig(val formatDataTypeId: String)` — non-nullable, "Update the way a numeric data types are shown" — is the right one for `graphical="false"`. `UpdateGraphicConfig.formatDataTypeId` is documented as an *overlay on top of graphical fields*. karoo-headwind's `BaseDataType` emits the **graphic** one (headwind-patterns §2.1), which is either a bug or an older API.
**Fix** Bind it in PLAN: numeric fields emit `UpdateNumericConfig(formatDataTypeId)` in `startView`, **only when non-null** (the parameter is non-nullable, so "null → raw integer" must mean *emit nothing*). Verify on device with `temperature` before WP4 builds three fields on the assumption.

### 24. Cross-extension stream units are never stated — and that is the public API (S2)
**Where** ARCHITECTURE §7 ("other extensions can consume `TYPE_EXT::karoo-weather::wind`").
**Evidence** Nothing says whether `DataPoint(Field.SINGLE to value)` carries m/s or the user's display unit. Karoo's own `UpdateNumericConfig(SPEED)` formatting assumes SI in, converted out. Get this wrong once and every downstream consumer is wrong forever.
**Fix** State in ARCHITECTURE §7, in bold: **every `StreamState.Streaming` value is canonical SI (°C, m/s, mm, degrees true)**; conversion happens only in graphical rendering and in the companion app.

### 25. `headwind-speed`'s design cannot be produced by a `graphical="false"` field (S2)
**Where** DESIGN §3.5 mock shows `+8 km/h` with an explicit plus sign; ARCHITECTURE §7 sets `formatDataTypeId = DataType.Type.SPEED`.
**Evidence** With `UpdateNumericConfig`, the extension supplies only a `Double`; Karoo owns the glyphs, so we cannot inject a `+`. Karoo's SPEED formatter is also untested against negative values (it may clamp, abs, or render `-8` — unknown). karoo-headwind deliberately inverts the sign for display and renders it itself.
**Fix** Either drop the `+` from the mock and accept `-8` for tailwind (documenting "positive = wind against you" in the field description string, which ARCHITECTURE already does), or verify negative SPEED rendering on device first. Given #31 below, the cleaner answer is to cut this field from v1 — karoo-headwind already ships it.

### 26. `PullToRefreshBox` contradicts DESIGN §7's own "no drag gestures" rule (S2)
**Where** DESIGN §5 ("a `PullToRefreshBox` over the whole content") vs DESIGN §7 ("No swipe-to-dismiss, no long-press, **no drag**: gloves and vibration make them unreliable").
**Fix** Replace with an explicit 56 dp "Refresh" button in the Now tab header. It is one tap, it is discoverable, and it works with gloves. Keep the pull gesture as an optional extra if you like, never as the only path.

### 27. The map layer may never draw, because `OnMapZoomLevel` is not documented to replay (S2)
**Where** ARCHITECTURE §8 / PLAN WP6: `combine(repo.state, karoo.consumerFlow<OnMapZoomLevel>())`.
**Evidence** `RideState`, `UserProfile` and others explicitly document "a consumer will be provided with the current state and then subsequently called on changes." `OnMapZoomLevel`'s KDoc says no such thing. `combine` emits nothing until *every* source has emitted, so if Karoo only pushes zoom on change, no symbol ever appears until the rider pinches the map.
**Fix** `.onStart { emit(OnMapZoomLevel(15.0)) }` on the zoom flow, or `combine` with a `MutableStateFlow` seeded at 15.0.

### 28. `startMap`'s scope is undeclared, and re-`startMap` after `stopMap` must not leak symbol ids (S2)
**Where** PLAN WP6 `fun start(emitter: Emitter<MapEffect>, scope: CoroutineScope): Job` vs PLAN WP8's `WeatherExtension` sketch, which has only `serviceJob` and passes an undefined `scope`; ARCHITECTURE §8 keeps `previousIds` state.
**Evidence** `KarooExtension.startMap(emitter)` gets no scope from the SDK (`extension/KarooExtension.kt`), and `stopMap` only calls `emitters.remove(id)?.cancel()` — your `setCancellable` is the only cleanup.
**Fix** Give `WeatherExtension` a named `private val extensionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` in WP8's sketch; hold `previousIds` in the `WeatherMapLayer` **instance** (not the companion), construct a fresh instance per `startMap`, and emit `HideSymbols(previousIds)` from `setCancellable`.

### 29. `viewRefreshMs` exists in two places with conflicting authority, and `hardwareType` is null before `connect{}` (S2)
**Where** ARCHITECTURE §10 (`viewRefreshMs: Long = 2000 (K3) / 3000 (K2)` as a *settings field*) vs PLAN WP3 `suspend fun KarooSystemService.viewRefreshMs(settings): Long // K2 -> 3000, else settings`.
**Evidence** A `@Serializable data class` default cannot be hardware-dependent, so the settings field's documented default is a fiction. And headwind-patterns pitfall #1: `karooSystem.hardwareType` is only valid **after** `connect{}` fires — `startView` may run before that and read `null`.
**Fix** Keep the settings field as a user preference with a plain `2000L` default; let `viewRefreshMs()` return `max(settings.viewRefreshMs, 3000)` when `hardwareType == K2` and **3000 when `hardwareType` is null** (safe side), re-reading once connected.

### 30. `route-forecast` at `(60,30)` puts five rows below the 10 sp legibility floor DESIGN itself sets (S2)
**Where** DESIGN §3.4 `(60,30)` mock: 5 columns × 5 rows (icon / temp / arrow / distance / ETA), with distance at `0.36 ×` and ETA at `0.30 ×`; DESIGN §1.2 floor: "nothing the rider is meant to read renders below 10 sp… drop the element rather than shrink it".
**Evidence** A `(60,30)` field is half the screen. At 480 px wide (see #34) five columns is ~96 px per cell; five stacked rows in ~200 px of height leaves ~40 px per row, and the two bottom rows land at or under the floor. karoo-ux.md §284 names exactly this as the anti-pattern.
**Fix** 3 columns and 3 rows (icon / temp / distance) at `(60,30)`; 5 columns only at `(60,60)`; ETA row only at `(60,60)`. Same treatment for `weather-now`'s `(60,60)` six-hour strip — six columns of icon+temp on a 3.2" screen is four too many.

---

## S3 — Minor / verify

### 31. Scope is roughly 2.5× a defensible v1 (S3, but the most consequential item here)
**Evidence** 7 data fields, of which 4 are custom-drawn with 3–4 grid variants each = **~16 distinct layouts** (DESIGN §3); plus a map layer, in-ride alerts, a 3-tab companion app, **24 hand-drawn vector icons** (WP7), 13 test suites, 8 work packages.
**Recommended v1 cut:** `weather-now`, `wind`, `temperature`, `route-forecast` — four fields, two grid variants each (`first == 30` / `first == 60`). **Defer:** the map layer (WP6 half), rain alerts (WP6 half), `apparent-temperature`, `headwind-speed` (karoo-headwind already ships it — see #25), `rain-next-hour` as a separate field (fold the 2 h bars into `weather-now`'s tall variant), Beaufort, and the elevation polyline. That removes WP6 entirely, halves WP4, and cuts WP7 from 24 icons to ~14. Everything deferred is genuinely additive — the `WeatherProvider`/`RouteForecast` contracts do not change.

### 32. `forecast_hours` + `forecast_days` together is untested (S3)
**Where** ARCHITECTURE §5.4 request A: `&forecast_hours=12&past_hours=0&forecast_days=1`.
**Evidence** weather-apis.md §3.5 documents `forecast_hours=N` as the bounding parameter and never combines it with `forecast_days`. Their interaction is unverified against the live API.
**Fix** Drop `forecast_days=1`; capture the fixtures with the exact final URL and let `OpenMeteoUrlTest` assert it byte-for-byte.

### 33. Request A and request B disagree on unit parameters (S3)
**Where** §5.4: request A sends `wind_speed_unit=ms&temperature_unit=celsius&precipitation_unit=mm`; request B sends only `wind_speed_unit=ms`.
**Fix** One shared `const val UNIT_PARAMS` in `OpenMeteoUrl`, appended to both. The index-0 merge (#19) mixes their fields, so a unit divergence would be invisible and wrong.

### 34. DESIGN's stated screen geometry contradicts the reference doc and the brief (S3)
**Where** DESIGN header: "3.2" 800×480"; karoo-ux.md:13: K2 panel is "800×480 (landscape) / **480×800 portrait-native**"; the project brief says 480×800.
**Evidence** Every ASCII mock in DESIGN §3 is drawn assuming ~800 px of horizontal room (the `(60,15)` rain strip fits 8 bars *and* "starts 14:20 · 1.4mm" on one line).
**Fix** Settle the in-ride orientation, then re-derive column counts from real `config.viewSize` values logged on device before WP4 commits to layouts. Related to #30.

### 35. Green means two different things on the same field (S3)
**Where** DESIGN §1.1: `tempMild = #2E7D32` (green) and `windTail = #008000` (green).
**Evidence** `weather-now` at `(60,30)` puts a green temperature next to a green wind arrow, where green-on-wind means "helping you" and green-on-temp means "10–20 °C". In direct sunlight on a 3.2" screen at 50 cm these are indistinguishable, and one of them is actionable.
**Fix** Reserve green **exclusively** for favourable wind. Colour temperature on a blue→grey→amber→red ramp only (drop `tempMild`'s green to the neutral `fg`), or drop temperature colour-coding in fields entirely — the number and the icon already carry it.

### 36. `rainProb #8A8A8A` on white is ~3.5:1 — below the sunlight-legibility bar (S3)
**Where** DESIGN §1.1 `rainProb = ColorPair(0xFF8A8A8A, 0xFF9A9A9A)`, drawn as a thin polyline over the bars (§3.3).
**Fix** A thin line needs more contrast than body text, not less. Use `fgMuted` (`#5A5A5A`, ~7:1) and give it a 2 px stroke, or drop the probability overlay at every grid size below `(60,60)`.

### 37. `weather-now` at `(30,30)` shows four elements in a quarter screen (S3)
**Where** DESIGN §3.1 `(30,30)`: header + WMO icon + temperature + arrow + wind speed.
**Evidence** karoo-ux.md §284 names this exact pattern ("icon + 3 numbers + units" in a `(30,30)`) as the thing to avoid, prescribing "1 bold numeral + 1 short sublabel".
**Fix** `(30,30)` = WMO icon + temperature. The wind belongs to the `wind` field.

### 38. `ic_map_wind_arrow` cannot have a white halo under DESIGN's own icon rule (S3)
**Where** DESIGN §2: all icons are "single path group, **solid fill `#FF000000`**" — and `ic_map_wind_arrow` "carries a built-in 1 dp white halo".
**Fix** Explicitly exempt the map icon: two paths (white outline path drawn first, black arrow on top), fixed colours, no runtime tint — it is drawn over map tiles where the day/night ColorProvider does not apply anyway. Say so in WP7's brief or the artwork agent will produce an unusable asset.

### 39. `Wx.alertBg` / `Wx.alertFg` are dead tokens that will silently drift (S3)
**Where** DESIGN §1.1 defines them as `ColorPair`s; `InRideAlert.backgroundColor`/`textColor` are `@ColorRes` (`models/KarooEffect.kt:249,253`), so the real values live in `res/values/colors.xml` + `res/values-night/colors.xml` (WP0).
**Fix** Delete them from `Wx` and add a comment pointing at the XML, or add a WP0 test asserting the two agree. Two sources of truth for the same colour is how alerts end up white-on-white at night.

### 40. `PolylineTest`'s Google reference string looks corrupted (S3)
**Where** ARCHITECTURE §12: ``_p~iF~ps|U_ulLnnqC_mqNvxq'@``.
**Evidence** The canonical Google reference string ends in a **backtick**, `vxq`@`, not an apostrophe. A one-character copy-paste error in the single most load-bearing test fixture in the project.
**Fix** Re-copy from the Google polyline algorithm docs and assert the three known points `(38.5,-120.2) (40.7,-120.95) (43.252,-126.453)`.

### 41. `NumericDataType.startView` needs no `setCancellable`, but §4.3 says "every" (S3)
**Where** ARCHITECTURE §4.3 ("Every `startStream`/`startView` … **always** finish with `emitter.setCancellable`") vs PLAN WP4's `NumericDataType.startView`, which emits one config event and holds no job (as karoo-headwind's `BaseDataType` does).
**Fix** Reword to "every `startView`/`startStream` **that launches a coroutine**". As written, WP8's verification checklist item ("Every `startStream`/`startView`/`startMap` calls `emitter.setCancellable`") will fail a correct implementation.

### 42. `types by lazy` dereferences a `lateinit` from a Binder thread (S3)
**Where** PLAN WP8's `WeatherExtension` sketch: `override val types by lazy { listOf(WeatherNowDataType(this, repo), …) }` with `private lateinit var repo`.
**Fix** Initialise `repo = WeatherGraph.repository(this)` as a property initialiser or `by lazy` (it needs no `connect`), not in `onCreate`. `KarooExtension.onCreate` runs before `onBind`, so this works today — but it is a null-window that a future refactor will step into, and `UninitializedPropertyAccessException` inside a Binder call is very hard to diagnose.

### 43. Test source set and test dependencies are unverified (S3)
**Where** PLAN test root `app/src/test/kotlin/…`; `app/build.gradle.kts` currently has only `testImplementation(libs.junit)`; PLAN rule 5 says "No new Gradle dependencies" while WP0 says add `testImplementation(libs.kotlinx.serialization.json)`.
**Fix** Before WP0 declares done: confirm AGP 9.3.1's built-in Kotlin registers `src/test/kotlin` (add an explicit `sourceSets` block if not), and confirm the WP2/WP3 suspend-function tests compile with plain `runBlocking` (from the coroutines transitively pulled by karoo-ext) or add `kotlinx-coroutines-test` — and then fix rule 5's wording, which currently forbids the dependency WP0 is told to add.

### 44. Release build is never verified, though `isMinifyEnabled = true` is already on (S3)
**Where** PLAN WP8 checklist: `spotlessApply`, `testDebugUnitTest`, `assembleDebug`, `lintDebug`.
**Evidence** `app/build.gradle.kts` `release { isMinifyEnabled = true }` with a currently-empty `proguard-rules.pro` — headwind pitfall #16. R8 must keep `kotlinx.serialization` `$$serializer` classes for **our** `@Serializable` DTOs *and* for `io.hammerhead.karooext.models.**`, which the library deserialises reflectively across the Binder.
**Fix** Add `./gradlew assembleRelease` to the checklist plus one on-device smoke test of the **minified** APK (fields render, a fetch succeeds). A R8-stripped serializer fails only at runtime, only in release, and usually only on the user's device.

### 45. Companion app forced to a light scheme (S3)
**Where** DESIGN §5: "Light scheme only; the app is used indoors, before the ride."
**Evidence** Karoo OS applies night mode system-wide; a forced-light app is a full-screen white flash for a rider checking the forecast at dusk with the extension's own fields correctly dark beside it.
**Fix** Derive the Material3 scheme from `isSystemInDarkTheme()` using the same `Wx` pairs. It is ~10 lines and it is the same token table the fields already use.

### 46. Unverified claim used to justify a binding decision (S3)
**Where** ADR-0 #3: "`KarooEngine.execute` throws `KarooIsUnsupportedException` on `HardwareType.K2` — **that is half the installed base dead**."
**Evidence** The technical half is **correct and verified** (`ktor-client-karoo/lib/src/main/java/de/jonasfranz/ktor/client/karoo/KarooEngine.kt`, line ~30: `if (karooSystem.hardwareType == HardwareType.K2) throw KarooIsUnsupportedException()`). The "half the installed base" figure has no source.
**Fix** Keep the decision — it stands on the K2 exception alone. Drop the unsourced statistic, or the next reviewer will discount the whole ADR table.

---

## What I would do before writing a line of code

1. Fix **#1–#10** in ARCHITECTURE/PLAN. They are contract edits, they take under an hour, and eight of them are unfixable-in-parallel once agents start.
2. Take the **#31** scope cut, or accept that v1 ships late and half-tested.
3. Run the two verification spikes that gate real decisions: **#10** (does karoo-ext resolve without GitHub Packages?) and **#23** (`UpdateNumericConfig` vs `UpdateGraphicConfig` on a real device, with a negative value for #25). Both are ~30 minutes and both invalidate work if they come back wrong after WP4 is built.
4. Log real `ViewConfig` values (`gridSize`, `viewSize`, `textSize`) for `(30,30)`, `(60,15)`, `(60,30)`, `(60,60)` on the actual hardware, then redraw DESIGN §3's mocks against those numbers (**#30**, **#34**). Every layout in DESIGN is currently drawn against an assumed pixel budget.
