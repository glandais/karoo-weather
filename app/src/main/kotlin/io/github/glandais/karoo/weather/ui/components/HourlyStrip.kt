package io.github.glandais.karoo.weather.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.glandais.karoo.weather.domain.Units
import io.github.glandais.karoo.weather.domain.WeatherSample
import io.github.glandais.karoo.weather.ui.asColor
import io.github.glandais.karoo.weather.ui.theme.Wx
import io.github.glandais.karoo.weather.util.Numbers
import io.github.glandais.karoo.weather.util.TimeFormat
import io.github.glandais.karoo.weather.weather.WmoIcons
import kotlin.math.max

/**
 * The next hours as a horizontally scrolled strip (DESIGN §5): hour, condition icon, temperature,
 * rain bar.
 *
 * The bars are drawn with a Compose `Canvas` rather than assembled from `Box` heights: the bar,
 * its baseline and the "no rain at all" case are one drawing decision, and a `Box` whose height is
 * a fraction of a parent that itself has no intrinsic height is exactly the layout that collapses
 * to zero on a narrow screen.
 *
 * Scrolling a list is not the drag-gesture DESIGN §7 forbids — that rule is about a drag being the
 * only path to an *action*. Everything actionable in this app is a tap.
 */
@Composable
fun HourlyStrip(
    hourly: List<WeatherSample>,
    units: Units,
    nowSec: Long,
    modifier: Modifier = Modifier,
    maxColumns: Int = 12,
) {
    val columns =
        hourly.asSequence().filter { it.time >= nowSec - HOUR_SEC }.take(maxColumns).toList()
    if (columns.isEmpty()) return

    // One shared scale, so bar heights are comparable across the strip. The 0.5 mm floor keeps a
    // drizzle from rendering as a full-height bar just because it is the wettest hour on screen.
    val scaleMm = max(MIN_SCALE_MM, columns.maxOf { it.precip })

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items = columns, key = { it.time }) { sample ->
            HourColumn(sample = sample, units = units, scaleMm = scaleMm)
        }
    }
}

@Composable
private fun HourColumn(sample: WeatherSample, units: Units, scaleMm: Double) {
    // `Wx.forRain` is calibrated in mm per quarter hour (DESIGN §1.1) and these are hourly
    // accumulations, so the intensity is converted before the ramp is applied.
    val barColor = Wx.forRain(sample.precip / 4.0).asColor()
    val baselineColor = Wx.fgMuted.asColor()

    Column(
        modifier = Modifier.width(44.dp).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = TimeFormat.hour(sample.time),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Icon(
            painter = painterResource(WmoIcons.fieldForCode(sample.wmoCode, sample.isDay)),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = Numbers.temp(sample.temp, units),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = Wx.forTemp(sample.temp).asColor(),
        )
        Spacer(Modifier.height(4.dp))
        Canvas(modifier = Modifier.width(24.dp).height(32.dp)) {
            val baselineY = size.height - 1f
            val fraction = (sample.precip / scaleMm).coerceIn(0.0, 1.0).toFloat()
            val barHeight = fraction * (size.height - 2f)
            if (barHeight > 0f) {
                drawRect(
                    color = barColor,
                    topLeft = Offset(0f, baselineY - barHeight),
                    size = Size(size.width, barHeight),
                )
            }
            drawLine(
                color = baselineColor,
                start = Offset(0f, baselineY),
                end = Offset(size.width, baselineY),
                strokeWidth = 1f,
            )
        }
    }
}

private const val HOUR_SEC = 3_600L
private const val MIN_SCALE_MM = 0.5
