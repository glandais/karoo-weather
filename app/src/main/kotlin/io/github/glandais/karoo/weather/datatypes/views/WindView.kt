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
    // Rotating the arrow into the meteorological frame when there is no bearing is intentional;
    // COLOURING it from that frame is not — `headwindComponent` reads its angle as relative to
    // travel, so an absolute bearing would paint a stationary rider's arrow tailwind green purely
    // because the wind blows south. No travel direction, no headwind claim (as `clockColumns`).
    val headwind =
        bearing?.let { RelativeWind.headwindComponent(relative, sample.windSpeed) } ?: 0.0
    val tint = Wx.forHeadwind(headwind).pick(night)
    val density = context.resources.displayMetrics.density
    val sizePx = FieldChrome.arrowSizePx(config)
    val sizeDp = (sizePx / density).roundToInt().coerceAtLeast(16)

    val speedStr = units.wind(sample.windSpeed).roundToInt().toString()
    val unitStr = context.getString(FieldChrome.windUnitLabel(units.wind))
    val gustStr =
        "${context.getString(R.string.label_gust_short)} " +
            "${units.wind(sample.windGusts).roundToInt()}"
    val originStr =
        "${context.getString(R.string.label_from)} " +
            context.getString(FieldChrome.compassLabel(RelativeWind.compassIndex(sample.windDir)))

    // The arrow, the speed and its unit are the field; gust and origin are the annotations, and a
    // Glance Row that overruns pushes its last run off the panel edge rather than dropping it
    // (DESIGN 1.3). Shed the origin first, then the gust, until the row measures inside the field.
    val speedPx = FieldText.widthPx(speedStr, FieldChrome.primarySp(config), density, bold = true)
    val unitPx = FieldText.widthPx(unitStr, FieldChrome.unitSp(config), density)
    val gustPx = FieldText.widthPx(gustStr, FieldChrome.secondarySp(config), density)
    val originPx = FieldText.widthPx(originStr, FieldChrome.unitSp(config), density)

    fun rowFits(withGust: Boolean, withOrigin: Boolean): Boolean {
        val runs = buildList {
            add(speedPx)
            add(unitPx)
            if (withGust) add(gustPx)
            if (withOrigin) add(originPx)
        }
        val fixedDp =
            sizeDp +
                ROW_GAP_DP +
                UNIT_GAP_DP +
                (if (withGust) ROW_GAP_DP else 0) +
                (if (withOrigin) ROW_GAP_DP else 0)
        return FieldChrome.rowFits(
            config,
            density,
            FieldChrome.rowWidthPx(density, fixedDp, *runs.toIntArray()),
        )
    }

    val showOrigin = rowFits(withGust = true, withOrigin = true)
    val showGust = showOrigin || rowFits(withGust = true, withOrigin = false)

    Box(
        modifier = GlanceModifier.fillMaxSize().padding(FieldChrome.paddingDp(config).dp),
        contentAlignment =
            Alignment(GlanceChrome.horizontal(config.alignment), Alignment.CenterVertically),
    ) {
        when (layout) {
            WindLayout.STRIP ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArrowInRing(context, relative, sizePx, sizeDp, tint, arrows)
                    Spacer(GlanceModifier.width(ROW_GAP_DP.dp))
                    Speed(speedStr, unitStr, config)
                    if (showGust) {
                        Spacer(GlanceModifier.width(ROW_GAP_DP.dp))
                        Gust(gustStr, config)
                    }
                    if (showOrigin) {
                        Spacer(GlanceModifier.width(ROW_GAP_DP.dp))
                        Origin(originStr, config)
                    }
                }
            WindLayout.COMPACT ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ArrowInRing(context, relative, sizePx, sizeDp, tint, arrows)
                    SpeedValue(speedStr, config)
                    UnitLabel(unitStr, config)
                }
            WindLayout.WIDE ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArrowInRing(context, relative, sizePx, sizeDp, tint, arrows)
                    Spacer(GlanceModifier.width(ROW_GAP_DP.dp))
                    Speed(speedStr, unitStr, config)
                    if (showGust) {
                        Spacer(GlanceModifier.width(ROW_GAP_DP.dp))
                        Gust(gustStr, config)
                    }
                }
            WindLayout.TALL ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ArrowInRing(context, relative, sizePx, sizeDp, tint, arrows)
                    SpeedValue(speedStr, config)
                    UnitLabel(unitStr, config)
                    Gust(gustStr, config)
                    Origin(originStr, config)
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
private fun Speed(speedStr: String, unitStr: String, config: ViewConfig) {
    Row(verticalAlignment = Alignment.Bottom) {
        SpeedValue(speedStr, config)
        Spacer(GlanceModifier.width(UNIT_GAP_DP.dp))
        UnitLabel(unitStr, config)
    }
}

@Composable
private fun SpeedValue(speedStr: String, config: ViewConfig) {
    Text(
        text = speedStr,
        maxLines = 1,
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
private fun UnitLabel(unitStr: String, config: ViewConfig) {
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

@Composable
private fun Gust(gustStr: String, config: ViewConfig) {
    Text(
        text = gustStr,
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
private fun Origin(originStr: String, config: ViewConfig) {
    Text(
        text = originStr,
        maxLines = 1,
        style =
            TextStyle(
                color = GlanceChrome.provider(Wx.fgMuted),
                fontSize = GlanceChrome.sp(FieldChrome.unitSp(config)),
                fontFamily = FontFamily.Monospace,
            ),
    )
}

/** Gap between the elements of a one-row wind layout, dp. */
private const val ROW_GAP_DP = 8

/** Gap between the wind value and its unit suffix, dp. */
private const val UNIT_GAP_DP = 3
