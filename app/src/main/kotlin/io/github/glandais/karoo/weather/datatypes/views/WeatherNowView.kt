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
    val iconDp = FieldChrome.iconBoxDp(config, context.resources.displayMetrics.density)
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(FieldChrome.paddingDp(config).dp),
        contentAlignment =
            Alignment(GlanceChrome.horizontal(config.alignment), Alignment.CenterVertically),
    ) {
        when (layout) {
            WeatherNowLayout.COMPACT ->
                Column(horizontalAlignment = GlanceChrome.horizontal(config.alignment)) {
                    ConditionIcon(sample, iconDp)
                    Temperature(sample, units, config, stale)
                }
            WeatherNowLayout.WIDE ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConditionIcon(sample, iconDp)
                    Spacer(GlanceModifier.width(8.dp))
                    Temperature(sample, units, config, stale)
                    Spacer(GlanceModifier.width(12.dp))
                    WindCell(context, sample, units, config, bearing, night, arrows)
                }
            WeatherNowLayout.TALL ->
                Column(horizontalAlignment = GlanceChrome.horizontal(config.alignment)) {
                    ConditionIcon(sample, iconDp)
                    Temperature(sample, units, config, stale)
                    FeelsLike(context, sample, units, config)
                    WindCell(context, sample, units, config, bearing, night, arrows)
                    PrecipProbability(sample, config)
                }
            WeatherNowLayout.FULL ->
                Column(
                    modifier = GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = GlanceChrome.horizontal(config.alignment),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConditionIcon(sample, iconDp)
                        Spacer(GlanceModifier.width(8.dp))
                        Column {
                            Temperature(sample, units, config, stale)
                            FeelsLike(context, sample, units, config)
                        }
                        Spacer(GlanceModifier.width(12.dp))
                        Column {
                            WindCell(context, sample, units, config, bearing, night, arrows)
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

@Composable
private fun Temperature(
    sample: WeatherSample,
    units: Units,
    config: ViewConfig,
    stale: Boolean,
) {
    val prefix = if (stale) "~" else ""
    val colour = if (stale) Wx.fgMuted else Wx.forTemp(sample.temp)
    Text(
        text = "$prefix${units.temp(sample.temp).roundToInt()}°",
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
    units: Units,
    config: ViewConfig,
    bearing: Double?,
    night: Boolean,
    arrows: ArrowBitmaps,
) {
    val relative =
        bearing?.let { RelativeWind.relativeAngle(it, sample.windDir) } ?: sample.windToDir
    val headwind = RelativeWind.headwindComponent(relative, sample.windSpeed)
    val tint = Wx.forHeadwind(headwind).pick(night)
    val sizePx = (FieldChrome.arrowSizePx(config) * 0.6f).roundToInt().coerceAtLeast(24)
    val sizeDp = (sizePx / context.resources.displayMetrics.density).roundToInt().coerceAtLeast(12)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider =
                ImageProvider(
                    arrows.rotated(context, R.drawable.ic_wind_arrow, relative, sizePx, tint)
                ),
            contentDescription = null,
            modifier = GlanceModifier.size(sizeDp.dp),
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(
            text = units.wind(sample.windSpeed).roundToInt().toString(),
            style =
                TextStyle(
                    color = GlanceChrome.provider(Wx.fg),
                    fontSize = GlanceChrome.sp(FieldChrome.secondarySp(config)),
                    fontFamily = FontFamily.Monospace,
                ),
        )
        Spacer(GlanceModifier.width(2.dp))
        Text(
            text = context.getString(FieldChrome.windUnitLabel(units.wind)),
            style =
                TextStyle(
                    color = GlanceChrome.provider(Wx.fgMuted),
                    fontSize = GlanceChrome.sp(FieldChrome.unitSp(config)),
                    fontFamily = FontFamily.Monospace,
                ),
        )
    }
}

@Composable
private fun Gust(context: Context, sample: WeatherSample, units: Units, config: ViewConfig) {
    Text(
        text =
            "${context.getString(R.string.label_gust_short)} " +
                "${units.wind(sample.windGusts).roundToInt()}",
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
