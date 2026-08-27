package io.github.glandais.karoo.weather.datatypes.views

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
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
import io.hammerhead.karooext.models.ViewConfig
import kotlin.math.roundToInt

/** Which rows the `wind` field draws at a given `gridSize` (DESIGN §3.2). */
enum class WindLayout {
    /** `(60,15)` — one row: arrow, speed, gust, origin. */
    STRIP,
    /** `(30,30)` — arrow over speed over unit. */
    COMPACT,
    /** `(60,30)` — arrow | speed | gust. */
    WIDE,
    /** `(·,60)` — the compact stack plus gust and the meteorological origin. */
    TALL;

    companion object {
        fun of(gridSize: Pair<Int, Int>): WindLayout {
            val wide = gridSize.first >= 60
            return when {
                gridSize.second >= 60 -> TALL
                wide && gridSize.second <= 15 -> STRIP
                wide -> WIDE
                else -> COMPACT
            }
        }
    }
}

/**
 * `wind`, Glance-composed. The arrow is the field: it points where the wind pushes the rider, in
 * the rider's own frame, so straight up is a pure tailwind. Its colour follows the headwind ramp
 * and is the only green on the device (DESIGN §3.2).
 *
 * @param bearing GPS bearing, degrees true. Null falls back to the meteorological frame, where "up"
 *   means the wind is blowing north.
 */
@Composable
fun WindView(
    context: Context,
    config: ViewConfig,
    sample: WeatherSample,
    units: Units,
    bearing: Double?,
    night: Boolean,
    arrows: ArrowBitmaps,
) {
    val layout = WindLayout.of(config.gridSize)
    val relative =
        bearing?.let { RelativeWind.relativeAngle(it, sample.windDir) } ?: sample.windToDir
    val headwind = RelativeWind.headwindComponent(relative, sample.windSpeed)
    val tint = Wx.forHeadwind(headwind).pick(night)
    val sizePx = FieldChrome.arrowSizePx(config)
    val sizeDp = (sizePx / context.resources.displayMetrics.density).roundToInt().coerceAtLeast(16)

    Box(
        modifier = GlanceModifier.fillMaxSize().padding(FieldChrome.paddingDp(config).dp),
        contentAlignment =
            Alignment(GlanceChrome.horizontal(config.alignment), Alignment.CenterVertically),
    ) {
        when (layout) {
            WindLayout.STRIP ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArrowInRing(context, relative, sizePx, sizeDp, tint, arrows)
                    Spacer(GlanceModifier.width(8.dp))
                    Speed(context, sample, units, config)
                    Spacer(GlanceModifier.width(8.dp))
                    Gust(context, sample, units, config)
                    Spacer(GlanceModifier.width(8.dp))
                    Origin(context, sample, config)
                }
            WindLayout.COMPACT ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ArrowInRing(context, relative, sizePx, sizeDp, tint, arrows)
                    SpeedValue(sample, units, config)
                    UnitLabel(context, units, config)
                }
            WindLayout.WIDE ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArrowInRing(context, relative, sizePx, sizeDp, tint, arrows)
                    Spacer(GlanceModifier.width(10.dp))
                    Speed(context, sample, units, config)
                    Spacer(GlanceModifier.width(10.dp))
                    Gust(context, sample, units, config)
                }
            WindLayout.TALL ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ArrowInRing(context, relative, sizePx, sizeDp, tint, arrows)
                    SpeedValue(sample, units, config)
                    UnitLabel(context, units, config)
                    Gust(context, sample, units, config)
                    Origin(context, sample, config)
                }
        }
    }
}

@Composable
private fun ArrowInRing(
    context: Context,
    relativeAngle: Double,
    sizePx: Int,
    sizeDp: Int,
    tint: Int,
    arrows: ArrowBitmaps,
) {
    Box(modifier = GlanceModifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(R.drawable.ic_wind_ring),
            contentDescription = null,
            modifier = GlanceModifier.size(sizeDp.dp),
            colorFilter = ColorFilter.tint(GlanceChrome.provider(Wx.fgMuted)),
        )
        Image(
            provider =
                ImageProvider(
                    arrows.rotated(
                        context,
                        R.drawable.ic_wind_arrow,
                        relativeAngle,
                        sizePx,
                        tint,
                    )
                ),
            contentDescription = null,
            modifier = GlanceModifier.size((sizeDp * 0.7f).roundToInt().dp),
        )
    }
}

@Composable
private fun Speed(context: Context, sample: WeatherSample, units: Units, config: ViewConfig) {
    Row(verticalAlignment = Alignment.Bottom) {
        SpeedValue(sample, units, config)
        Spacer(GlanceModifier.width(3.dp))
        UnitLabel(context, units, config)
    }
}

@Composable
private fun SpeedValue(sample: WeatherSample, units: Units, config: ViewConfig) {
    Text(
        text = units.wind(sample.windSpeed).roundToInt().toString(),
        style =
            TextStyle(
                color = GlanceChrome.provider(Wx.fg),
                fontSize = GlanceChrome.sp(FieldChrome.primarySp(config)),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            ),
    )
}

@Composable
private fun UnitLabel(context: Context, units: Units, config: ViewConfig) {
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
private fun Origin(context: Context, sample: WeatherSample, config: ViewConfig) {
    val index = RelativeWind.compassIndex(sample.windDir)
    Text(
        text =
            "${context.getString(R.string.label_from)} " +
                context.getString(FieldChrome.compassLabel(index)),
        style =
            TextStyle(
                color = GlanceChrome.provider(Wx.fgMuted),
                fontSize = GlanceChrome.sp(FieldChrome.unitSp(config)),
                fontFamily = FontFamily.Monospace,
            ),
    )
}
