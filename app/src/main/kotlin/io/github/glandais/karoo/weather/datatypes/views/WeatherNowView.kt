package io.github.glandais.karoo.weather.datatypes.views

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.domain.Units
import io.github.glandais.karoo.weather.domain.WeatherSample
import io.github.glandais.karoo.weather.route.RelativeWind
import io.github.glandais.karoo.weather.ui.theme.Wx
import io.github.glandais.karoo.weather.weather.WmoIcons
import io.hammerhead.karooext.models.ViewConfig
import kotlin.math.roundToInt

/** Which rows `weather-now` draws at a given `gridSize` (DESIGN §3.1). */
enum class WeatherNowLayout {
    /** `(30,30)` — WMO icon over the temperature. Two elements only. */
    COMPACT,
    /** `(60,30)` — icon | temperature | wind, side by side. */
    WIDE,
    /** `(30,60)` — icon, temperature, feels-like, wind, precipitation probability. */
    TALL,
    /** `(60,60)` — the wide row plus an hourly outlook strip. */
    FULL;

    companion object {
        fun of(gridSize: Pair<Int, Int>): WeatherNowLayout {
            val wide = gridSize.first >= 60
            val tall = gridSize.second >= 60
            return when {
                wide && tall -> FULL
                wide -> WIDE
                tall -> TALL
                else -> COMPACT
            }
        }
    }
}

/**
 * `weather-now`, Glance-composed: text plus resource icons (which cross the Binder as ids, not
 * pixels) and exactly one rotated arrow bitmap.
 *
 * @param sample conditions at the rider's own position, interpolated to now.
 * @param outlook hourly series for the `(60,60)` strip; ignored at other grid sizes.
 * @param bearing GPS bearing, degrees true. Null keeps the arrow in the meteorological frame.
 * @param stale bundle older than three hours: the temperature is muted with a leading `~`.
 */
@Composable
fun WeatherNowView(
    context: Context,
    config: ViewConfig,
    sample: WeatherSample,
    outlook: List<WeatherSample>,
    units: Units,
    bearing: Double?,
    night: Boolean,
    arrows: ArrowBitmaps,
    stale: Boolean,
) {
    val layout = WeatherNowLayout.of(config.gridSize)
    val density = context.resources.displayMetrics.density
    val iconDp = FieldChrome.iconBoxDp(config, density)
    val arrowDp = windArrowDp(config, density)
    val tempStr = temperatureText(sample, units, stale)
    val windStr = units.wind(sample.windSpeed).roundToInt().toString()
    val unitStr = context.getString(FieldChrome.windUnitLabel(units.wind))
    // The wind unit is the row's last and least load-bearing run: 11 without a suffix still reads
    // as a speed, `km/` above `h` reads as a bug. Drop it when the row cannot hold it.
    val showUnit =
        when (layout) {
            WeatherNowLayout.WIDE ->
                unitFits(
                    config,
                    density,
                    iconDp,
                    arrowDp,
                    leadingTextPx =
                        FieldText.widthPx(
                            tempStr,
                            FieldChrome.primarySp(config),
                            density,
                            bold = true,
                        ),
                    windStr = windStr,
                    unitStr = unitStr,
                )
            WeatherNowLayout.FULL ->
                unitFits(
                    config,
                    density,
                    iconDp,
                    arrowDp,
                    leadingTextPx = leftColumnPx(context, config, density, sample, units, tempStr),
                    windStr = windStr,
                    unitStr = unitStr,
                )
            // The stacked layouts give the wind cell the whole width; nothing competes with it.
            WeatherNowLayout.COMPACT,
            WeatherNowLayout.TALL -> true
        }
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(FieldChrome.paddingDp(config).dp),
        contentAlignment =
            Alignment(GlanceChrome.horizontal(config.alignment), Alignment.CenterVertically),
    ) {
        when (layout) {
            WeatherNowLayout.COMPACT ->
                Column(horizontalAlignment = GlanceChrome.horizontal(config.alignment)) {
                    ConditionIcon(sample, iconDp)
                    Temperature(tempStr, sample, config, stale)
                }
            WeatherNowLayout.WIDE ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConditionIcon(sample, iconDp)
                    Spacer(GlanceModifier.width(WIDE_ICON_GAP_DP.dp))
                    Temperature(tempStr, sample, config, stale)
                    Spacer(GlanceModifier.width(WIDE_CELL_GAP_DP.dp))
                    WindCell(
                        context,
                        sample,
                        config,
                        bearing,
                        night,
                        arrows,
                        arrowDp,
                        windStr,
                        unitStr,
                        showUnit,
                    )
                }
            WeatherNowLayout.TALL ->
                Column(horizontalAlignment = GlanceChrome.horizontal(config.alignment)) {
                    ConditionIcon(sample, iconDp)
                    Temperature(tempStr, sample, config, stale)
                    FeelsLike(context, sample, units, config)
                    WindCell(
                        context,
                        sample,
                        config,
                        bearing,
                        night,
                        arrows,
                        arrowDp,
                        windStr,
                        unitStr,
                        showUnit,
                    )
                    PrecipProbability(sample, config)
                }
            WeatherNowLayout.FULL ->
                Column(
                    modifier = GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = GlanceChrome.horizontal(config.alignment),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConditionIcon(sample, iconDp)
                        Spacer(GlanceModifier.width(WIDE_ICON_GAP_DP.dp))
                        Column {
                            Temperature(tempStr, sample, config, stale)
                            FeelsLike(context, sample, units, config)
                        }
                        Spacer(GlanceModifier.width(WIDE_CELL_GAP_DP.dp))
                        Column {
                            WindCell(
                                context,
                                sample,
                                config,
                                bearing,
                                night,
                                arrows,
                                arrowDp,
                                windStr,
                                unitStr,
                                showUnit,
                            )
                            Gust(context, sample, units, config)
                        }
                    }
                    Divider()
                    OutlookStrip(config, outlook, units)
                }
        }
    }
}

@Composable
private fun ConditionIcon(sample: WeatherSample, iconDp: Int) {
    Image(
        provider = ImageProvider(WmoIcons.fieldForCode(sample.wmoCode, sample.isDay)),
        contentDescription = null,
        modifier = GlanceModifier.size(iconDp.dp),
        colorFilter = ColorFilter.tint(GlanceChrome.provider(Wx.fg)),
    )
}

/** The rendered temperature, built once so the row budget can measure the string it will draw. */
private fun temperatureText(sample: WeatherSample, units: Units, stale: Boolean): String {
    val prefix = if (stale) "~" else ""
    return "$prefix${units.temp(sample.temp).roundToInt()}°"
}

/**
 * Arrow box for the inline wind cell, in dp. The cell borrows 60 % of the standalone `wind` field's
 * arrow so it reads as an annotation of the temperature rather than a second headline.
 */
private fun windArrowDp(config: ViewConfig, density: Float): Int {
    val sizePx = (FieldChrome.arrowSizePx(config) * INLINE_ARROW_SCALE).roundToInt()
    val safeDensity = if (density > 0f) density else 1f
    return (sizePx.coerceAtLeast(24) / safeDensity).roundToInt().coerceAtLeast(12)
}

/** Width in px of the temperature / feels-like column at [WeatherNowLayout.FULL]. */
private fun leftColumnPx(
    context: Context,
    config: ViewConfig,
    density: Float,
    sample: WeatherSample,
    units: Units,
    tempStr: String,
): Int {
    val tempPx = FieldText.widthPx(tempStr, FieldChrome.primarySp(config), density, bold = true)
    val feels = sample.apparentTemp ?: return tempPx
    val feelsStr =
        "${context.getString(R.string.label_feels_short)} ${units.temp(feels).roundToInt()}°"
    val feelsPx = FieldText.widthPx(feelsStr, FieldChrome.secondarySp(config), density)
    return maxOf(tempPx, feelsPx)
}

/**
 * Whether the wind cell can still afford its unit suffix.
 *
 * @param leadingTextPx text already committed to the row left of the wind value.
 */
private fun unitFits(
    config: ViewConfig,
    density: Float,
    iconDp: Int,
    arrowDp: Int,
    leadingTextPx: Int,
    windStr: String,
    unitStr: String,
): Boolean {
    val content =
        FieldChrome.rowWidthPx(
            density,
            iconDp + WIDE_ICON_GAP_DP + WIDE_CELL_GAP_DP + arrowDp + ARROW_GAP_DP + UNIT_GAP_DP,
            leadingTextPx,
            FieldText.widthPx(windStr, FieldChrome.secondarySp(config), density),
            FieldText.widthPx(unitStr, FieldChrome.unitSp(config), density),
        )
    return FieldChrome.rowFits(config, density, content)
}

@Composable
private fun Temperature(
    text: String,
    sample: WeatherSample,
    config: ViewConfig,
    stale: Boolean,
) {
    val colour = if (stale) Wx.fgMuted else Wx.forTemp(sample.temp)
    Text(
        text = text,
        maxLines = 1,
        style =
            TextStyle(
                color = GlanceChrome.provider(colour),
                fontSize = GlanceChrome.sp(FieldChrome.primarySp(config)),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            ),
    )
}

@Composable
private fun FeelsLike(
    context: Context,
    sample: WeatherSample,
    units: Units,
    config: ViewConfig,
) {
    val feels = sample.apparentTemp ?: return
    Text(
        text =
            "${context.getString(R.string.label_feels_short)} " +
                "${units.temp(feels).roundToInt()}°",
        maxLines = 1,
        style =
            TextStyle(
                color = GlanceChrome.provider(Wx.fgMuted),
                fontSize = GlanceChrome.sp(FieldChrome.secondarySp(config)),
                fontFamily = FontFamily.Monospace,
            ),
    )
}

@Composable
private fun WindCell(
    context: Context,
    sample: WeatherSample,
    config: ViewConfig,
    bearing: Double?,
    night: Boolean,
    arrows: ArrowBitmaps,
    arrowDp: Int,
    windStr: String,
    unitStr: String,
    showUnit: Boolean,
) {
    val relative =
        bearing?.let { RelativeWind.relativeAngle(it, sample.windDir) } ?: sample.windToDir
    // Rotating the arrow into the meteorological frame when there is no bearing is intentional;
    // COLOURING it from that frame is not — `headwindComponent` reads its angle as relative to
    // travel, so an absolute bearing would paint a stationary rider's arrow tailwind green purely
    // because the wind blows south. No travel direction, no headwind claim (as `clockColumns`).
    val headwind =
        bearing?.let { RelativeWind.headwindComponent(relative, sample.windSpeed) } ?: 0.0
    val tint = Wx.forHeadwind(headwind).pick(night)
    val sizePx =
        (FieldChrome.arrowSizePx(config) * INLINE_ARROW_SCALE).roundToInt().coerceAtLeast(24)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider =
                ImageProvider(
                    arrows.rotated(context, R.drawable.ic_wind_arrow, relative, sizePx, tint)
                ),
            contentDescription = null,
            modifier = GlanceModifier.size(arrowDp.dp),
        )
        Spacer(GlanceModifier.width(ARROW_GAP_DP.dp))
        Text(
            text = windStr,
            maxLines = 1,
            style =
                TextStyle(
                    color = GlanceChrome.provider(Wx.fg),
                    fontSize = GlanceChrome.sp(FieldChrome.secondarySp(config)),
                    fontFamily = FontFamily.Monospace,
                ),
        )
        if (showUnit) {
            Spacer(GlanceModifier.width(UNIT_GAP_DP.dp))
            Text(
                text = unitStr,
                maxLines = 1,
                style =
                    TextStyle(
                        color = GlanceChrome.provider(Wx.fgMuted),
                        fontSize = GlanceChrome.sp(FieldChrome.unitSp(config)),
                        fontFamily = FontFamily.Monospace,
                    ),
            )
        }
    }
}

@Composable
private fun Gust(context: Context, sample: WeatherSample, units: Units, config: ViewConfig) {
    Text(
        text =
            "${context.getString(R.string.label_gust_short)} " +
                "${units.wind(sample.windGusts).roundToInt()}",
        maxLines = 1,
        style =
            TextStyle(
                color = GlanceChrome.provider(Wx.fgMuted),
                fontSize = GlanceChrome.sp(FieldChrome.secondarySp(config)),
                fontFamily = FontFamily.Monospace,
            ),
    )
}

@Composable
private fun PrecipProbability(sample: WeatherSample, config: ViewConfig) {
    val probability = sample.precipProb ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(R.drawable.ic_umbrella),
            contentDescription = null,
            modifier = GlanceModifier.size(FieldChrome.MIN_ICON_DP.dp),
            colorFilter = ColorFilter.tint(GlanceChrome.provider(Wx.fgMuted)),
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(
            text = "$probability%",
            maxLines = 1,
            style =
                TextStyle(
                    color = GlanceChrome.provider(Wx.fgMuted),
                    fontSize = GlanceChrome.sp(FieldChrome.secondarySp(config)),
                    fontFamily = FontFamily.Monospace,
                ),
        )
    }
}

@Composable
private fun ColumnScope.Divider() {
    Spacer(GlanceModifier.fillMaxWidth().height(1.dp).background(GlanceChrome.provider(Wx.divider)))
}

@Composable
private fun OutlookStrip(config: ViewConfig, outlook: List<WeatherSample>, units: Units) {
    if (outlook.isEmpty()) return
    val columns = FieldChrome.columnsFor(config.viewSize, OUTLOOK_MAX_COLUMNS)
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        outlook.take(columns).forEach { entry ->
            Column(
                modifier = GlanceModifier.defaultWeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = GlanceChrome.hourLabel(entry.time),
                    maxLines = 1,
                    style =
                        TextStyle(
                            color = GlanceChrome.provider(Wx.fgMuted),
                            fontSize = GlanceChrome.sp(FieldChrome.labelSp(config)),
                            fontFamily = FontFamily.Monospace,
                        ),
                )
                Image(
                    provider = ImageProvider(WmoIcons.fieldForCode(entry.wmoCode, entry.isDay)),
                    contentDescription = null,
                    modifier = GlanceModifier.size(FieldChrome.MIN_ICON_DP.dp),
                    colorFilter = ColorFilter.tint(GlanceChrome.provider(Wx.fg)),
                )
                Text(
                    text = "${units.temp(entry.temp).roundToInt()}°",
                    maxLines = 1,
                    style =
                        TextStyle(
                            color = GlanceChrome.provider(Wx.forTemp(entry.temp)),
                            fontSize = GlanceChrome.sp(FieldChrome.secondarySp(config)),
                            fontFamily = FontFamily.Monospace,
                        ),
                )
            }
        }
    }
}

private const val OUTLOOK_MAX_COLUMNS = 6

/** Gap between the WMO icon and the temperature on the side-by-side layouts, dp. */
private const val WIDE_ICON_GAP_DP = 8

/** Gap between the temperature and the wind cell on the side-by-side layouts, dp. */
private const val WIDE_CELL_GAP_DP = 12

/** Gap between the wind arrow and its value, dp. */
private const val ARROW_GAP_DP = 4

/** Gap between the wind value and its unit suffix, dp. */
private const val UNIT_GAP_DP = 2

/** The inline wind arrow relative to the standalone `wind` field's arrow. */
private const val INLINE_ARROW_SCALE = 0.6f
