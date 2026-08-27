package io.github.glandais.karoo.weather.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.glandais.karoo.weather.R
import io.github.glandais.karoo.weather.domain.RoutePointForecast
import io.github.glandais.karoo.weather.domain.Units
import io.github.glandais.karoo.weather.ui.asColor
import io.github.glandais.karoo.weather.ui.theme.Wx
import io.github.glandais.karoo.weather.util.Distance
import io.github.glandais.karoo.weather.util.Numbers
import io.github.glandais.karoo.weather.util.TimeFormat
import io.github.glandais.karoo.weather.util.distanceAheadLabel
import io.github.glandais.karoo.weather.weather.WmoIcons

/**
 * One sampled point of the loaded route (DESIGN §5, Route tab).
 *
 * `distanceAlong` is measured from the route start, so the "+18 km" the rider reads is
 * `distanceAlong - progress`. Index 0 of a `RouteForecast` is always the rider's own position and
 * therefore renders as "+0".
 *
 * The wind arrow is rotated by the *relative* angle, not the compass bearing: on this screen the
 * frame of reference is the direction of travel, so straight up is "with you" and straight down is
 * "against you". That is the only reading a rider can act on, and it matches the colour scale —
 * green up, red down (DESIGN §1.2, green reserved for tailwind).
 */
@Composable
fun RouteRow(
    point: RoutePointForecast,
    progress: Double,
    units: Units,
    modifier: Modifier = Modifier,
) {
    val ahead = (point.distanceAlong - progress).coerceAtLeast(0.0)
    val windColor = Wx.forHeadwind(point.headwindSpeed).asColor()
    val tempColor = Wx.forTemp(point.sample.temp).asColor()
    val wet = point.sample.precip >= 0.2
    val dimmed = point.beyondHorizon

    val rowBackground = if (wet) Wx.rainMed.asColor().copy(alpha = 0.12f) else Color.Transparent

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(rowBackground)
                .padding(horizontal = RouteRowMetrics.PADDING_DP.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RouteRowMetrics.GAP_DP.dp),
    ) {
        val alpha = if (dimmed) 0.5f else 1f

        // Distance and ETA SHARE the leftover width instead of each carrying a fixed one: on the
        // Karoo's 256 dp panel seven fixed children plus their gaps came to 312 dp, and Compose
        // laid the last two — the arrow and the headwind figure, the only actionable content on
        // the row — past the right edge, where they were clipped and never seen.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RouteRowMetrics.GAP_DP.dp),
        ) {
            Text(
                text =
                    stringResource(
                        distanceAheadLabel(units.distance),
                        Distance.format(ahead, units.distance),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text =
                    if (dimmed) stringResource(R.string.horizon_beyond)
                    else TimeFormat.clock(point.eta),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Icon(
            painter =
                painterResource(WmoIcons.fieldForCode(point.sample.wmoCode, point.sample.isDay)),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            modifier = Modifier.size(RouteRowMetrics.ICON_DP.dp),
        )

        Text(
            text = Numbers.temp(point.sample.temp, units),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = tempColor.copy(alpha = alpha),
            maxLines = 1,
            modifier = Modifier.width(RouteRowMetrics.TEMP_DP.dp),
        )

        WindArrow(
            angleDeg = point.relativeWindAngle.toFloat(),
            color = windColor.copy(alpha = alpha),
            modifier = Modifier.size(RouteRowMetrics.ARROW_DP.dp),
        )

        Text(
            text = Numbers.signedWind(point.headwindSpeed, units),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = windColor.copy(alpha = alpha),
            maxLines = 1,
            modifier = Modifier.width(RouteRowMetrics.HEADWIND_DP.dp),
        )
    }
}

/**
 * The row's fixed geometry, in dp, kept as plain numbers so the arithmetic can be unit tested.
 *
 * The Karoo panel measures 480 x 800 px at 300 dpi = **256 x 427 dp**, and the companion app runs
 * on the device itself: [fixedWidthDp] must stay under [CONTENT_WIDTH_DP] or content is laid out
 * past the right edge, with no ellipsis and no scroll to recover it.
 */
object RouteRowMetrics {
    const val SCREEN_WIDTH_DP = 256
    const val PADDING_DP = 12
    const val GAP_DP = 4
    const val ICON_DP = 20
    const val TEMP_DP = 38
    const val ARROW_DP = 20
    const val HEADWIND_DP = 46

    /** Width the row's children share. */
    const val CONTENT_WIDTH_DP = SCREEN_WIDTH_DP - 2 * PADDING_DP

    /** Everything that cannot shrink: four fixed children plus the four gaps between five. */
    const val fixedWidthDp = ICON_DP + TEMP_DP + ARROW_DP + HEADWIND_DP + 4 * GAP_DP
}

/**
 * An arrow drawn with Compose `Canvas` rather than a rotated drawable.
 *
 * A drawable would need `Modifier.rotate` around a painter whose own geometry we do not control,
 * and the app must not depend on WP4's bitmap machinery (PLAN WP5: "no dependency on WP4"). Twelve
 * lines of `Path` are cheaper than either.
 *
 * [angleDeg] is clockwise from "up". 0 draws an arrow pointing up.
 */
@Composable
fun WindArrow(angleDeg: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val half = minOf(w, h) / 2f
            val headHalfWidth = half * 0.55f
            val headY = -half * 0.15f

            rotate(degrees = angleDeg, pivot = Offset(cx, cy)) {
                drawLine(
                    color = color,
                    start = Offset(cx, cy + half * 0.85f),
                    end = Offset(cx, cy + headY),
                    strokeWidth = half * 0.28f,
                    cap = StrokeCap.Round,
                )
                val head =
                    Path().apply {
                        moveTo(cx, cy - half * 0.9f)
                        lineTo(cx - headHalfWidth, cy + headY + half * 0.18f)
                        lineTo(cx + headHalfWidth, cy + headY + half * 0.18f)
                        close()
                    }
                drawPath(path = head, color = color)
            }
        }
    }
}
